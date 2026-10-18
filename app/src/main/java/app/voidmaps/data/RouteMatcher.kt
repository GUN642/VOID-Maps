package app.voidmaps.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Aligns a route to the OpenStreetMap roads drawn on the map.
 *
 * TomTom computes routes on its own map, whose road lines can lie a few metres beside the
 * OpenStreetMap roads and has fewer points in curves – the red line (and the position snapped
 * to it) then ran next to the road and cut corners. Valhalla's map matching (trace_attributes,
 * same OSM data as the map) moves every route point onto the OSM road and adds the road's own
 * curve points in between. Manoeuvres, lanes, traffic and times keep their place via the index
 * map of [Route.withGeometry]. Pieces that cannot be matched reliably keep TomTom's geometry.
 */
object RouteMatcher {
    private const val URL = "https://valhalla1.openstreetmap.de/trace_attributes"
    private const val CHUNK_POINTS = 400
    private const val CHUNK_METRES = 25_000.0

    /** Max. distance between a TomTom point and its matched OSM position. */
    private const val MAX_SHIFT = 22.0

    fun needsMatching(r: Route) = !r.matched && r.engine != "Valhalla" && r.points.size >= 2

    /**
     * Matches [route] chunk by chunk. [onPartial] receives an intermediate result (first part matched,
     * rest unchanged) once [partialAfterMetres] are done, so the area around the car improves quickly.
     * Returns the route unchanged when nothing could be matched.
     */
    fun match(route: Route, partialAfterMetres: Double = 30_000.0, onPartial: ((Route) -> Unit)? = null): Route {
        if (!needsMatching(route)) return route
        val pts = route.points
        val cum = route.line.cumulative
        val out = ArrayList<GeoPoint>(pts.size * 2)
        val map = IntArray(pts.size)
        out += pts[0]
        map[0] = 0
        var start = 0
        var anyMatched = false
        var partialSent = false
        while (start < pts.size - 1) {
            var end = start + 1
            while (end < pts.size - 1 && end - start < CHUNK_POINTS && cum[end] - cum[start] < CHUNK_METRES) end++
            val piece = runCatching { matchChunk(pts.subList(start, end + 1)) }.getOrNull()
            if (piece != null) {
                anyMatched = true
                if (start == 0) out[0] = piece.points[0]
                // piece.points[0] belongs to the shared point `start`, already in `out`.
                val base = out.size - 1
                for (j in 1 until piece.points.size) out += piece.points[j]
                for (k in 1..end - start) map[start + k] = base + piece.index[k]
            } else {
                for (k in start + 1..end) {
                    out += pts[k]
                    map[k] = out.size - 1
                }
            }
            start = end
            if (onPartial != null && !partialSent && anyMatched && cum[start] >= partialAfterMetres && start < pts.size - 1) {
                partialSent = true
                onPartial(assemble(route, out, map, start))
            }
        }
        if (!anyMatched) return route
        return runCatching { route.withGeometry(out, map) }.getOrDefault(route)
    }

    /** Matched part up to old index [upTo], the rest of the route unchanged. */
    private fun assemble(route: Route, out: List<GeoPoint>, map: IntArray, upTo: Int): Route {
        val pts = ArrayList(out)
        val m = map.copyOf()
        for (k in upTo + 1 until route.points.size) {
            pts += route.points[k]
            m[k] = pts.size - 1
        }
        return runCatching { route.withGeometry(pts, m) }.getOrDefault(route)
    }

    internal class Piece(val points: List<GeoPoint>, val index: IntArray)

    /** One request; null when the result does not look trustworthy. */
    internal fun matchChunk(chunk: List<GeoPoint>): Piece? {
        val shape = JSONArray()
        chunk.forEach { shape.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        val body = JSONObject()
            .put("shape", shape)
            .put("costing", "auto")
            .put("shape_match", "map_snap")
            .put("trace_options", JSONObject().put("search_radius", 30))
            .put(
                "filters",
                JSONObject().put("action", "include").put(
                    "attributes",
                    JSONArray(
                        listOf(
                            "shape", "matched.point", "matched.type", "matched.edge_index",
                            "matched.distance_along_edge", "matched.distance_from_trace_point",
                            "edge.begin_shape_index", "edge.end_shape_index",
                        ),
                    ),
                ),
            )
        val json = JSONObject(Http.post(URL, body.toString(), "application/json", timeoutMs = 12_000))
        return pieceFrom(chunk, json)
    }

    /** Builds the aligned piece from a trace_attributes answer (separate for tests). */
    internal fun pieceFrom(chunk: List<GeoPoint>, json: JSONObject): Piece? {
        val shapePts = Geo.decodePolyline(json.optString("shape"), 6)
        if (shapePts.size < 2) return null
        val shapeLine = Polyline(shapePts)
        val sc = shapeLine.cumulative
        val edges = json.optJSONArray("edges") ?: return null
        val matched = json.optJSONArray("matched_points") ?: return null
        if (matched.length() != chunk.size) return null

        // Position of every matched point along the matched shape.
        val along = DoubleArray(chunk.size)
        val origLine = Polyline(chunk)
        for (i in chunk.indices) {
            val mp = matched.getJSONObject(i)
            if (mp.optString("type") == "unmatched") return null
            if (mp.optDouble("distance_from_trace_point", 0.0) > MAX_SHIFT) return null
            val edge = edges.optJSONObject(mp.optInt("edge_index", -1)) ?: return null
            val b = edge.optInt("begin_shape_index").coerceIn(0, shapePts.size - 1)
            val e = edge.optInt("end_shape_index").coerceIn(0, shapePts.size - 1)
            val f = mp.optDouble("distance_along_edge", 0.0).coerceIn(0.0, 1.0)
            along[i] = sc[b] + (sc[e] - sc[b]) * f
            if (i > 0) {
                val step = along[i] - along[i - 1]
                val orig = origLine.cumulative[i] - origLine.cumulative[i - 1]
                // Backwards or a detour the original does not make: not reliable.
                if (step < -2.0 || step > orig * 1.5 + 30) return null
                if (step < 0) along[i] = along[i - 1]
            }
        }

        // New points: each matched point, plus the OSM shape points between it and the next one.
        val out = ArrayList<GeoPoint>(shapePts.size + chunk.size)
        val index = IntArray(chunk.size)
        var s = 0
        for (i in chunk.indices) {
            index[i] = out.size
            out += shapeLine.pointAt(along[i])
            if (i < chunk.size - 1) {
                while (s < shapePts.size && sc[s] <= along[i] + 0.3) s++
                while (s < shapePts.size && sc[s] < along[i + 1] - 0.3) {
                    out += shapePts[s]
                    s++
                }
            }
        }
        return Piece(out, index)
    }
}
