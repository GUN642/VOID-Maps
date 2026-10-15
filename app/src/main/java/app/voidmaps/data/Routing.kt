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

/** Direction a lane leads to (TomTom lane guidance). */
enum class LaneDir { STRAIGHT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, UTURN }

/** One lane: where it leads and whether the route continues on it. */
data class Lane(val directions: List<LaneDir>, val recommended: Boolean, val follow: LaneDir?)

/** Slow traffic on the route: level 1 = slow, 2 = queuing, 3 = standing / closed; works = roadworks. */
data class TrafficSpan(val startAlong: Double, val endAlong: Double, val level: Int, val works: Boolean = false, val delay: Int = 0)

/** Lanes shown before a junction, between two points of the route. */
data class LaneSection(val startIndex: Int, val endIndex: Int, val lanes: List<Lane>)

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
    /** Intermediate stops (Zwischenziele) in driving order. */
    val waypoints: List<GeoPoint> = emptyList(),
    /** Lane guidance (only from TomTom routing). */
    val laneSections: List<LaneSection> = emptyList(),
    /** Traffic sections as point indices (only from TomTom routing): start, end, level, works, delay. */
    trafficIndices: List<IntArray> = emptyList(),
) {
    val line = Polyline(points)
    val distance: Double get() = line.length
    val maneuverAlong: DoubleArray = DoubleArray(maneuvers.size) { line.cumulative[maneuvers[it].pointIndex.coerceIn(0, points.size - 1)] }

    /** Jams and roadworks along the route (TomTom). */
    val traffic: List<TrafficSpan> = trafficIndices.map { t ->
        TrafficSpan(
            line.cumulative[t[0].coerceIn(0, points.size - 1)], line.cumulative[t[1].coerceIn(0, points.size - 1)],
            t[2], t[3] == 1, t[4],
        )
    }

    /** Start and end (metres along the route) of each lane section. */
    val laneAlong: List<Pair<Double, Double>> = laneSections.map {
        line.cumulative[it.startIndex.coerceIn(0, points.size - 1)] to line.cumulative[it.endIndex.coerceIn(0, points.size - 1)]
    }

    /** Lane section to show at [along]: the one we are in, or one starting within [ahead] metres. */
    fun lanesAt(along: Double, ahead: Double): LaneSection? {
        for (i in laneSections.indices) {
            val (a, b) = laneAlong[i]
            if (along <= b && along >= a - ahead) return laneSections[i]
        }
        return null
    }

    /** Metres from the start to each intermediate stop. */
    val waypointAlong: DoubleArray = run {
        var hint = 0
        DoubleArray(waypoints.size) { i ->
            val s = line.snap(waypoints[i], hint, window = points.size)
            if (s != null) hint = s.segment
            s?.along ?: 0.0
        }
    }

    /** Main roads of the route, e.g. "A 7, B 27" – helps telling alternatives apart. */
    val via: String = run {
        val lengths = HashMap<String, Double>()
        for (i in maneuvers.indices) {
            val street = maneuvers[i].street ?: continue
            val len = (maneuverAlong.getOrElse(i + 1) { distance } - maneuverAlong[i]).coerceAtLeast(0.0)
            val key = street.split(" / ").firstOrNull { Regex("""^[ABLKE] ?\d""").containsMatchIn(it) } ?: street.split(" / ").first()
            lengths[key] = (lengths[key] ?: 0.0) + len
        }
        lengths.entries.sortedByDescending { it.value }.take(2).joinToString(", ") { it.key }
    }

    /** True when this route runs (almost) the same way as [other]. */
    fun similarTo(other: Route): Boolean =
        kotlin.math.abs(distance - other.distance) < 300 && kotlin.math.abs(duration - other.duration) < 60

    val totalTime: Double get() = cumulativeTime.lastOrNull() ?: 0.0

    /** Metres along the route after [seconds] of driving (used by the demo drive). */
    fun alongAtTime(seconds: Double): Double {
        val ct = cumulativeTime
        val cum = line.cumulative
        if (ct.size < 2) return 0.0
        if (seconds <= ct[0]) return 0.0
        if (seconds >= ct.last()) return cum.last()
        var lo = 0
        var hi = ct.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (ct[mid] <= seconds) lo = mid else hi = mid
        }
        val span = ct[hi] - ct[lo]
        val f = if (span <= 0) 1.0 else (seconds - ct[lo]) / span
        return cum[lo] + (cum[hi] - cum[lo]) * f
    }

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
    /**
     * Routes through [stops] (start, intermediate stops…, destination). Picks TomTom when a key is available
     * (traffic-aware), Valhalla otherwise. With [alternatives] > 0 and no intermediate stops up to that many
     * extra routes are returned; the first route is always the fastest.
     */
    fun routes(stops: List<GeoPoint>, engine: RoutingEngine, tomtomKey: String, options: RouteOptions, alternatives: Int = 0): List<Route> {
        require(stops.size >= 2)
        val alt = if (stops.size == 2) alternatives else 0
        val useTomTom = tomtomKey.isNotBlank() && engine != RoutingEngine.VALHALLA
        val list = if (useTomTom) {
            try {
                tomtom(stops, tomtomKey, options, alt)
            } catch (e: Exception) {
                if (engine == RoutingEngine.TOMTOM) throw e
                valhalla(stops, options, alt)
            }
        } else {
            valhalla(stops, options, alt)
        }
        // Fastest first, drop near-duplicates.
        val sorted = list.sortedBy { it.duration }
        val out = ArrayList<Route>()
        for (r in sorted) if (out.none { it.similarTo(r) }) out += r
        return out
    }

    fun route(stops: List<GeoPoint>, engine: RoutingEngine, tomtomKey: String, options: RouteOptions): Route =
        routes(stops, engine, tomtomKey, options, 0).first()

    // ------------------------------------------------------------------ Valhalla

    private const val VALHALLA = "https://valhalla1.openstreetmap.de/route"

    fun valhalla(stops: List<GeoPoint>, options: RouteOptions, alternatives: Int): List<Route> {
        val locations = JSONArray()
        stops.forEachIndexed { i, p ->
            val loc = JSONObject().put("lat", p.lat).put("lon", p.lon).put("type", "break")
            if (i == 0) options.heading?.let { loc.put("heading", it.toInt()).put("heading_tolerance", 60) }
            locations.put(loc)
        }
        val body = JSONObject()
            .put("locations", locations)
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
        if (alternatives > 0) body.put("alternates", alternatives)
        val text = try {
            Http.post(VALHALLA, body.toString(), "application/json")
        } catch (e: HttpException) {
            val msg = runCatching { JSONObject(e.body).optString("error") }.getOrNull()
            throw RoutingException(if (msg.isNullOrBlank()) "Keine Route gefunden (${e.code})" else "Keine Route: $msg")
        }
        val json = JSONObject(text)
        val waypoints = stops.subList(1, stops.size - 1)
        val out = arrayListOf(valhallaTrip(json.getJSONObject("trip"), waypoints))
        val alts = json.optJSONArray("alternates")
        if (alts != null) {
            for (i in 0 until alts.length()) {
                runCatching { out += valhallaTrip(alts.getJSONObject(i).getJSONObject("trip"), waypoints) }
            }
        }
        return out
    }

    private fun valhallaTrip(trip: JSONObject, waypoints: List<GeoPoint>): Route {
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
        return Route(points, maneuvers, duration, 0.0, "Valhalla", Route.timeProfile(line, marks, times), waypoints)
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

    fun tomtom(stops: List<GeoPoint>, key: String, options: RouteOptions, alternatives: Int = 0): List<Route> {
        val sb = StringBuilder("https://api.tomtom.com/routing/1/calculateRoute/")
            .append(stops.joinToString(":") { "${it.lat},${it.lon}" }).append("/json")
            .append("?key=").append(Http.enc(key))
            .append("&traffic=true&travelMode=car&routeType=fastest&instructionsType=text&language=de-DE")
        if (alternatives > 0) sb.append("&maxAlternatives=").append(alternatives)
        sb.append("&sectionType=lanes&sectionType=traffic")
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
        val routes = JSONObject(text).getJSONArray("routes")
        val waypoints = stops.subList(1, stops.size - 1)
        return (0 until routes.length()).map { tomtomRoute(routes.getJSONObject(it), waypoints) }
    }

    private fun tomtomRoute(route: JSONObject, waypoints: List<GeoPoint>): Route {
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
            waypoints,
            parseLanes(route.optJSONArray("sections"), points.size),
            parseTraffic(route.optJSONArray("sections"), points.size),
        )
    }

    private fun parseTraffic(sections: JSONArray?, size: Int): List<IntArray> {
        if (sections == null) return emptyList()
        val out = ArrayList<IntArray>()
        for (i in 0 until sections.length()) {
            val sec = sections.getJSONObject(i)
            if (!sec.optString("sectionType").equals("TRAFFIC", ignoreCase = true)) continue
            val cat = sec.optString("simpleCategory").uppercase()
            val magnitude = sec.optInt("magnitudeOfDelay", 0)
            val works = cat == "ROAD_WORK"
            val level = when {
                cat == "ROAD_CLOSURE" -> 3
                works -> 1
                magnitude >= 3 -> 3
                magnitude == 2 -> 2
                else -> 1
            }
            out += intArrayOf(
                sec.optInt("startPointIndex").coerceIn(0, size - 1),
                sec.optInt("endPointIndex").coerceIn(0, size - 1),
                level, if (works) 1 else 0, sec.optInt("delayInSeconds", 0),
            )
        }
        return out
    }

    private fun laneDir(s: String): LaneDir? = when (s.uppercase()) {
        "STRAIGHT" -> LaneDir.STRAIGHT
        "SLIGHT_RIGHT" -> LaneDir.SLIGHT_RIGHT
        "RIGHT" -> LaneDir.RIGHT
        "SHARP_RIGHT" -> LaneDir.SHARP_RIGHT
        "SLIGHT_LEFT" -> LaneDir.SLIGHT_LEFT
        "LEFT" -> LaneDir.LEFT
        "SHARP_LEFT" -> LaneDir.SHARP_LEFT
        "U_TURN", "U_TURN_LEFT", "U_TURN_RIGHT", "UTURN" -> LaneDir.UTURN
        else -> null
    }

    private fun parseLanes(sections: JSONArray?, size: Int): List<LaneSection> {
        if (sections == null) return emptyList()
        val out = ArrayList<LaneSection>()
        for (i in 0 until sections.length()) {
            val sec = sections.getJSONObject(i)
            if (!sec.optString("sectionType").equals("LANES", ignoreCase = true)) continue
            val lanesJson = sec.optJSONArray("lanes") ?: continue
            val lanes = (0 until lanesJson.length()).map { j ->
                val l = lanesJson.getJSONObject(j)
                val dirs = l.optJSONArray("directions")?.let { d -> (0 until d.length()).mapNotNull { laneDir(d.getString(it)) } }.orEmpty()
                val follow = l.optString("follow").takeIf { it.isNotBlank() }?.let { laneDir(it) }
                Lane(dirs.ifEmpty { listOfNotNull(follow) }, follow != null, follow)
            }
            // Only useful when some lanes are recommended and others are not.
            if (lanes.size < 2 || lanes.none { it.recommended } || lanes.all { it.recommended }) continue
            out += LaneSection(
                sec.optInt("startPointIndex").coerceIn(0, size - 1),
                sec.optInt("endPointIndex").coerceIn(0, size - 1),
                lanes,
            )
        }
        return out
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
