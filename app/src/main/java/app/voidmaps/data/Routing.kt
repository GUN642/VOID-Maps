package app.voidmaps.data

import org.json.JSONArray
import org.json.JSONObject

enum class ManeuverKind {
    DEPART, STRAIGHT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, SHARP_LEFT, LEFT, SLIGHT_LEFT,
    KEEP_RIGHT, KEEP_LEFT, EXIT_RIGHT, EXIT_LEFT, MERGE, ROUNDABOUT, FERRY, ARRIVE,
}

data class Maneuver(
    val kind: ManeuverKind,
    /** Text shown on screen, e.g. "Biegen Sie rechts ab auf die Hauptstraße." */
    val text: String,
    /** Spoken form that works after "In 300 Metern …", e.g. "Biegen Sie rechts ab auf die Hauptstraße". */
    val spoken: String,
    val street: String?,
    /** Exit number at roundabouts. */
    val exit: Int?,
    /** Index into [Route.points] where the manoeuvre happens. */
    val pointIndex: Int,
)

class Route(
    val points: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    /** Seconds for the whole route, already including traffic when the engine knows about it. */
    val duration: Double,
    /** Extra seconds caused by traffic (TomTom only). */
    val trafficDelay: Double,
    val engine: String,
    /** Cumulative travel time per point, used for the remaining time and the arrival estimate. */
    private val cumulativeTime: DoubleArray,
) {
    val line = Polyline(points)
    val distance: Double get() = line.length
    val maneuverAlong: DoubleArray = DoubleArray(maneuvers.size) { line.cumulative[maneuvers[it].pointIndex.coerceIn(0, points.size - 1)] }

    /** Seconds still to drive when [along] metres of the route are done. */
    fun remainingTime(along: Double): Double {
        val cum = line.cumulative
        if (cum.size < 2) return 0.0
        var lo = 0
        var hi = cum.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= along) lo = mid else hi = mid
        }
        val span = cum[hi] - cum[lo]
        val f = if (span <= 0) 0.0 else ((along - cum[lo]) / span).coerceIn(0.0, 1.0)
        val t = cumulativeTime[lo] + (cumulativeTime[hi] - cumulativeTime[lo]) * f
        return (cumulativeTime.last() - t).coerceAtLeast(0.0)
    }

    companion object {
        /** Spreads [times] (seconds between consecutive [marks]) over the points by distance. */
        fun timeProfile(line: Polyline, marks: List<Int>, times: List<Double>): DoubleArray {
            val cum = line.cumulative
            val out = DoubleArray(cum.size)
            if (cum.isEmpty()) return out
            var t0 = 0.0
            for (k in 0 until marks.size - 1) {
                val a = marks[k].coerceIn(0, cum.size - 1)
                val b = marks[k + 1].coerceIn(0, cum.size - 1)
                val dt = times.getOrElse(k) { 0.0 }
                val span = cum[b] - cum[a]
                for (i in a..b) {
                    out[i] = t0 + if (span <= 0) 0.0 else dt * (cum[i] - cum[a]) / span
                }
                t0 += dt
            }
            val lastMark = marks.lastOrNull()?.coerceIn(0, cum.size - 1) ?: 0
            for (i in lastMark until cum.size) out[i] = maxOf(out[i], t0)
            return out
        }
    }
}

enum class RoutingEngine(val label: String) {
    AUTO("Automatisch"),
    VALHALLA("Valhalla (OSM)"),
    TOMTOM("TomTom (mit Verkehr)"),
}

data class RouteOptions(
    val avoidTolls: Boolean,
    val avoidMotorways: Boolean,
    /** Current driving direction – avoids routes that start with a U-turn. */
    val heading: Double? = null,
)

object Routing {
    /** Picks the engine: TomTom when a key is available (traffic-aware), Valhalla otherwise. */
    fun route(from: GeoPoint, to: GeoPoint, engine: RoutingEngine, tomtomKey: String, options: RouteOptions): Route {
        val useTomTom = tomtomKey.isNotBlank() && engine != RoutingEngine.VALHALLA
        return if (useTomTom) {
            try {
                tomtom(from, to, tomtomKey, options)
            } catch (e: Exception) {
                if (engine == RoutingEngine.TOMTOM) throw e
                valhalla(from, to, options)
            }
        } else {
            valhalla(from, to, options)
        }
    }

    // ------------------------------------------------------------------ Valhalla

    private const val VALHALLA = "https://valhalla1.openstreetmap.de/route"

    fun valhalla(from: GeoPoint, to: GeoPoint, options: RouteOptions): Route {
        val start = JSONObject().put("lat", from.lat).put("lon", from.lon).put("type", "break")
        options.heading?.let { start.put("heading", it.toInt()).put("heading_tolerance", 60) }
        val body = JSONObject()
            .put("locations", JSONArray().put(start).put(JSONObject().put("lat", to.lat).put("lon", to.lon).put("type", "break")))
            .put("costing", "auto")
            .put(
                "costing_options",
                JSONObject().put(
                    "auto",
                    JSONObject()
                        .put("use_tolls", if (options.avoidTolls) 0.0 else 0.5)
                        .put("use_highways", if (options.avoidMotorways) 0.0 else 1.0),
                ),
            )
            .put("units", "kilometers")
            .put("language", "de-DE")
            .put("directions_options", JSONObject().put("units", "kilometers").put("language", "de-DE"))
        val text = try {
            Http.post(VALHALLA, body.toString(), "application/json")
        } catch (e: HttpException) {
            val msg = runCatching { JSONObject(e.body).optString("error") }.getOrNull()
            throw RoutingException(if (msg.isNullOrBlank()) "Keine Route gefunden (${e.code})" else "Keine Route: $msg")
        }
        val trip = JSONObject(text).getJSONObject("trip")
        val legs = trip.getJSONArray("legs")
        val points = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<Maneuver>()
        val marks = ArrayList<Int>()
        val times = ArrayList<Double>()
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val offset = points.size
            points += Geo.decodePolyline(leg.getString("shape"), 6)
            val ms = leg.getJSONArray("maneuvers")
            for (i in 0 until ms.length()) {
                val m = ms.getJSONObject(i)
                val type = m.optInt("type")
                val index = offset + m.optInt("begin_shape_index")
                marks += index
                times += m.optDouble("time", 0.0)
                val streets = m.optJSONArray("street_names")
                val street = streets?.let { s -> (0 until s.length()).joinToString(" / ") { s.getString(it) } }?.ifBlank { null }
                val text = m.optString("instruction")
                val spoken = m.optString("verbal_transition_alert_instruction")
                    .ifBlank { m.optString("verbal_pre_transition_instruction") }
                    .ifBlank { text }
                    .trimEnd('.')
                maneuvers += Maneuver(
                    kind = valhallaKind(type),
                    text = text,
                    spoken = spoken,
                    street = street,
                    exit = m.optInt("roundabout_exit_count", 0).takeIf { it > 0 },
                    pointIndex = index,
                )
            }
        }
        if (points.size < 2) throw RoutingException("Keine Route gefunden")
        marks += points.size - 1
        val line = Polyline(points)
        val duration = trip.optJSONObject("summary")?.optDouble("time", times.sum()) ?: times.sum()
        return Route(points, maneuvers, duration, 0.0, "Valhalla", Route.timeProfile(line, marks, times))
    }

    private fun valhallaKind(type: Int) = when (type) {
        1, 2, 3 -> ManeuverKind.DEPART
        4, 5, 6 -> ManeuverKind.ARRIVE
        9 -> ManeuverKind.SLIGHT_RIGHT
        10 -> ManeuverKind.RIGHT
        11 -> ManeuverKind.SHARP_RIGHT
        12, 13 -> ManeuverKind.UTURN
        14 -> ManeuverKind.SHARP_LEFT
        15 -> ManeuverKind.LEFT
        16 -> ManeuverKind.SLIGHT_LEFT
        18, 23 -> ManeuverKind.KEEP_RIGHT
        19, 24 -> ManeuverKind.KEEP_LEFT
        20 -> ManeuverKind.EXIT_RIGHT
        21 -> ManeuverKind.EXIT_LEFT
        25, 37, 38 -> ManeuverKind.MERGE
        26, 27 -> ManeuverKind.ROUNDABOUT
        28, 29 -> ManeuverKind.FERRY
        else -> ManeuverKind.STRAIGHT
    }

    // ------------------------------------------------------------------ TomTom

    fun tomtom(from: GeoPoint, to: GeoPoint, key: String, options: RouteOptions): Route {
        val sb = StringBuilder("https://api.tomtom.com/routing/1/calculateRoute/")
            .append("${from.lat},${from.lon}:${to.lat},${to.lon}/json")
            .append("?key=").append(Http.enc(key))
            .append("&traffic=true&travelMode=car&routeType=fastest&instructionsType=text&language=de-DE")
        options.heading?.let { sb.append("&vehicleHeading=").append(it.toInt().coerceIn(0, 359)) }
        if (options.avoidTolls) sb.append("&avoid=tollRoads")
        if (options.avoidMotorways) sb.append("&avoid=motorways")
        val text = try {
            Http.get(sb.toString())
        } catch (e: HttpException) {
            throw RoutingException(
                when (e.code) {
                    403 -> "TomTom-Schlüssel ungültig oder Kontingent erschöpft"
                    else -> "TomTom-Routing fehlgeschlagen (${e.code})"
                },
            )
        }
        val route = JSONObject(text).getJSONArray("routes").getJSONObject(0)
        val summary = route.getJSONObject("summary")
        val points = ArrayList<GeoPoint>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val pts = legs.getJSONObject(l).getJSONArray("points")
            for (i in 0 until pts.length()) {
                val p = pts.getJSONObject(i)
                points += GeoPoint(p.getDouble("latitude"), p.getDouble("longitude"))
            }
        }
        if (points.size < 2) throw RoutingException("Keine Route gefunden")
        val maneuvers = ArrayList<Maneuver>()
        val marks = ArrayList<Int>()
        val cumTimes = ArrayList<Double>()
        val instructions = route.optJSONObject("guidance")?.optJSONArray("instructions") ?: JSONArray()
        for (i in 0 until instructions.length()) {
            val ins = instructions.getJSONObject(i)
            val index = ins.optInt("pointIndex", 0).coerceIn(0, points.size - 1)
            val message = ins.optString("message").ifBlank { ins.optString("combinedMessage") }
            marks += index
            cumTimes += ins.optDouble("travelTimeInSeconds", 0.0)
            maneuvers += Maneuver(
                kind = tomtomKind(ins.optString("maneuver")),
                text = message,
                spoken = message.trimEnd('.'),
                street = ins.optString("street").ifBlank { null },
                exit = ins.optInt("roundaboutExitNumber", 0).takeIf { it > 0 },
                pointIndex = index,
            )
        }
        val duration = summary.optDouble("travelTimeInSeconds", 0.0)
        marks += points.size - 1
        cumTimes += duration
        val deltas = cumTimes.zipWithNext { a, b -> (b - a).coerceAtLeast(0.0) }
        val firstMarks = if (marks.first() != 0) listOf(0) + marks else marks
        val firstDeltas = if (marks.first() != 0) listOf(cumTimes.first()) + deltas else deltas
        val line = Polyline(points)
        return Route(
            points, maneuvers, duration, summary.optDouble("trafficDelayInSeconds", 0.0), "TomTom",
            Route.timeProfile(line, firstMarks, firstDeltas),
        )
    }

    private fun tomtomKind(m: String) = when (m) {
        "DEPART" -> ManeuverKind.DEPART
        "ARRIVE", "ARRIVE_LEFT", "ARRIVE_RIGHT", "WAYPOINT_REACHED" -> ManeuverKind.ARRIVE
        "BEAR_RIGHT" -> ManeuverKind.SLIGHT_RIGHT
        "TURN_RIGHT" -> ManeuverKind.RIGHT
        "SHARP_RIGHT" -> ManeuverKind.SHARP_RIGHT
        "MAKE_UTURN", "TRY_MAKE_UTURN" -> ManeuverKind.UTURN
        "SHARP_LEFT" -> ManeuverKind.SHARP_LEFT
        "TURN_LEFT" -> ManeuverKind.LEFT
        "BEAR_LEFT" -> ManeuverKind.SLIGHT_LEFT
        "KEEP_RIGHT", "SWITCH_PARALLEL_ROAD", "SWITCH_MAIN_ROAD" -> ManeuverKind.KEEP_RIGHT
        "KEEP_LEFT" -> ManeuverKind.KEEP_LEFT
        "MOTORWAY_EXIT_RIGHT", "TAKE_EXIT" -> ManeuverKind.EXIT_RIGHT
        "MOTORWAY_EXIT_LEFT" -> ManeuverKind.EXIT_LEFT
        "ENTER_MOTORWAY", "ENTER_FREEWAY", "ENTER_HIGHWAY", "ENTRANCE_RAMP" -> ManeuverKind.MERGE
        "ROUNDABOUT_CROSS", "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT", "ROUNDABOUT_BACK" -> ManeuverKind.ROUNDABOUT
        "TAKE_FERRY" -> ManeuverKind.FERRY
        else -> ManeuverKind.STRAIGHT
    }
}

class RoutingException(message: String) : Exception(message)
