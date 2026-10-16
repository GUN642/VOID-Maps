package app.voidmaps.nav

import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.content.ContextCompat
import app.voidmaps.VoidMapsApp
import app.voidmaps.data.AppSettings
import app.voidmaps.data.BBox
import app.voidmaps.data.Format
import app.voidmaps.data.Geo
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Hazard
import app.voidmaps.data.HazardType
import app.voidmaps.data.Maneuver
import app.voidmaps.data.ManeuverKind
import app.voidmaps.data.Place
import app.voidmaps.data.Route
import app.voidmaps.data.RouteOptions
import app.voidmaps.data.Routing
import app.voidmaps.data.RoutingEngine
import app.voidmaps.data.Snap
import app.voidmaps.data.VoiceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A hazard that lies on the current route; [endAlong] is where a jam or roadworks end. */
data class RouteHazard(val hazard: Hazard, val along: Double, val endAlong: Double? = null)

data class Warning(val hazard: Hazard, val distance: Double)

/** A clearly faster route found in traffic, waiting for the driver to accept or decline it. */
data class FasterRoute(val route: Route, val savedSeconds: Double, val offeredAt: Long)

data class NavState(
    val destination: Place,
    /** Intermediate stops still ahead, in driving order. */
    val stops: List<Place>,
    val route: Route,
    /** Bumps whenever the route was replaced (reroute, faster route, new stop). */
    val routeVersion: Int,
    val snap: Snap?,
    /** Index into route.maneuvers of the next manoeuvre. */
    val next: Int,
    val distanceToNext: Double,
    val remainingDistance: Double,
    val remainingTime: Double,
    val speedKmh: Int,
    val offRoute: Boolean = false,
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
    val warning: Warning? = null,
    val routeHazards: List<RouteHazard> = emptyList(),
    val faster: FasterRoute? = null,
    /** Metres to the next intermediate stop, if there is one. */
    val nextStopDistance: Double? = null,
    /** Seconds to the next intermediate stop. */
    val nextStopTime: Double? = null,
    /** Lanes to use at the next junction, when the route has lane data. */
    val lanes: app.voidmaps.data.LaneSection? = null,
) {
    val eta: Long get() = System.currentTimeMillis() + (remainingTime * 1000).toLong()
}

/**
 * Turn-by-turn guidance: follows the GPS position along the route, announces manoeuvres and
 * hazards, handles intermediate stops, recalculates when the driver leaves the route and offers
 * faster routes in traffic.
 */
object Navigator {
    private enum class Say { GUIDE, WARN, INFO }

    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state.asStateFlow()

    private var speaker: Speaker? = null
    private var locationJob: Job? = null
    private var hazardJob: Job? = null
    private var appContext: Context? = null

    /** Stages already spoken per manoeuvre index (1 = far, 2 = mid, 4 = now). */
    private val spoken = HashMap<Int, Int>()
    private val spokenHazards = HashSet<String>()
    private var offRouteCount = 0
    private var lastReroute = 0L
    private var lastFasterCheck = 0L
    private var fasterDeclinedUntil = 0L
    private var arrivedAt = 0L

    /** Stops the current route was calculated with, and how many of them are already behind us. */
    private var routeStops: List<Place> = emptyList()
    private var passedStops = 0

    private val settings: AppSettings get() = VoidMapsApp.settings.value
    private val short: Boolean get() = settings.shortAnnouncements

    fun isActive() = _state.value != null

    fun start(context: Context, destination: Place, stops: List<Place>, route: Route) {
        val app = context.applicationContext
        appContext = app
        if (speaker == null) speaker = Speaker(app)
        reset()
        routeStops = stops
        passedStops = 0
        val first = firstRealManeuver(route)
        _state.value = NavState(
            destination = destination,
            stops = stops,
            route = route,
            routeVersion = 0,
            snap = null,
            next = first,
            distanceToNext = route.maneuverAlong.getOrElse(first) { route.distance },
            remainingDistance = route.distance,
            remainingTime = route.duration,
            speedKmh = 0,
        )
        lastFasterCheck = System.currentTimeMillis()
        // May be refused when started from Android Auto with the phone app in the background;
        // guidance then still runs while the car session keeps the app alive.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, NavigationService::class.java)) }
        val arrival = Format.clock(System.currentTimeMillis() + (route.duration * 1000).toLong())
        say(
            if (short) "Route gestartet. Ankunft $arrival."
            else "Die Route wird gestartet. ${spokenDuration(route.duration)}, Ankunft um $arrival Uhr.",
            Say.INFO, interrupt = true,
        )
        locationJob?.cancel()
        locationJob = VoidMapsApp.scope.launch {
            LocationTracker.location.collect { loc -> if (loc != null) onLocation(loc) }
        }
        hazardJob?.cancel()
        hazardJob = VoidMapsApp.scope.launch {
            launch {
                // Recompute which hazards lie on the route whenever new data arrives.
                combine(HazardRepo.incidents, HazardRepo.cameras) { a, b -> a to b }.collect { (inc, cam) ->
                    val s = _state.value ?: return@collect
                    val list = withContext(Dispatchers.Default) {
                        projectHazards(s.route, inc + if (settings.speedCameras) cam else emptyList())
                    }
                    _state.value = _state.value?.copy(routeHazards = list)
                }
            }
            while (isActive) {
                refreshHazards()
                delay(5 * 60_000L)
            }
        }
    }

    fun stop() {
        locationJob?.cancel()
        hazardJob?.cancel()
        locationJob = null
        hazardJob = null
        speaker?.stop()
        _state.value = null
        appContext?.let { it.stopService(Intent(it, NavigationService::class.java)) }
    }

    /** Applies a new voice mode immediately (silences running speech when muted). */
    fun onVoiceModeChanged(mode: VoiceMode) {
        if (mode != VoiceMode.ALL) speaker?.stop()
    }

    /** Adds an intermediate stop before the destination and recalculates from the current position. */
    fun addStop(place: Place) {
        val s = _state.value ?: return
        val loc = LocationTracker.location.value ?: return
        val stops = s.stops + place
        recalculate(GeoPoint(loc.latitude, loc.longitude), heading(loc), stops, announce = false)
        say(if (short) "Zwischenziel hinzugefügt." else "Zwischenziel ${place.name} wurde hinzugefügt.", Say.INFO)
    }

    /** Removes an intermediate stop and recalculates. */
    fun removeStop(place: Place) {
        val s = _state.value ?: return
        val loc = LocationTracker.location.value ?: return
        recalculate(GeoPoint(loc.latitude, loc.longitude), heading(loc), s.stops - place, announce = false)
    }

    fun acceptFaster() {
        val s = _state.value ?: return
        val f = s.faster ?: return
        replaceRoute(s.copy(faster = null), f.route, s.stops)
        say(if (short) "Neue Route." else "Die schnellere Route wird verwendet.", Say.INFO, interrupt = true)
    }

    fun declineFaster() {
        fasterDeclinedUntil = System.currentTimeMillis() + 10 * 60_000L
        _state.value = _state.value?.copy(faster = null)
    }

    private fun reset() {
        spoken.clear()
        spokenHazards.clear()
        offRouteCount = 0
        lastReroute = 0L
        arrivedAt = 0L
        fasterDeclinedUntil = 0L
    }

    private fun firstRealManeuver(route: Route): Int {
        val i = route.maneuvers.indexOfFirst { it.kind != ManeuverKind.DEPART }
        return if (i < 0) 0 else i
    }

    private fun say(text: String, kind: Say, interrupt: Boolean = false) {
        val allowed = when (settings.voiceMode) {
            VoiceMode.OFF -> false
            VoiceMode.WARNINGS -> kind == Say.WARN
            VoiceMode.ALL -> true
        }
        if (allowed) speaker?.let {
            it.configure(settings.voiceName, settings.speechRate)
            it.speak(text, interrupt)
        }
    }

    private fun heading(loc: Location): Double? = if (loc.hasBearing() && loc.speed > 2) loc.bearing.toDouble() else null

    // ------------------------------------------------------------------ position updates

    private fun onLocation(loc: Location) {
        val s = _state.value ?: return
        if (s.arrived) {
            if (System.currentTimeMillis() - arrivedAt > 120_000) stop()
            return
        }
        val p = GeoPoint(loc.latitude, loc.longitude)
        val route = s.route
        val snap = route.line.snap(p, s.snap?.segment ?: -1) ?: return
        val speed = loc.speed.toDouble().coerceAtLeast(0.0)
        val tolerance = maxOf(45.0, (if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0) * 1.5)

        // Off route: three fixes in a row clearly away from the line.
        if (snap.offset > tolerance) offRouteCount++ else offRouteCount = 0
        val offRoute = offRouteCount >= 3
        if (offRoute && !s.rerouting && System.currentTimeMillis() - lastReroute > 12_000) {
            recalculate(p, heading(loc), s.stops, announce = true)
        }

        val along = snap.along

        // Intermediate stops passed?
        var stopsLeft = s.stops
        val wpAlong = route.waypointAlong
        while (passedStops < wpAlong.size && passedStops < routeStops.size && along >= wpAlong[passedStops] - 30) {
            val reached = routeStops[passedStops]
            passedStops++
            stopsLeft = routeStops.drop(passedStops)
            say(if (short) "Zwischenziel erreicht." else "Sie haben Ihr Zwischenziel ${reached.name} erreicht.", Say.INFO, interrupt = true)
        }
        val nextStopDistance = wpAlong.getOrNull(passedStops)?.let { (it - along).coerceAtLeast(0.0) }
        val nextStopTime = wpAlong.getOrNull(passedStops)?.let { (route.remainingTime(along) - route.remainingTime(it)).coerceAtLeast(0.0) }

        var next = s.next
        while (next < route.maneuvers.size - 1 && route.maneuverAlong[next] < along + 3) next++
        // Never fall back to a departure manoeuvre.
        if (route.maneuvers.getOrNull(next)?.kind == ManeuverKind.DEPART && next + 1 < route.maneuvers.size) next++
        val distToNext = (route.maneuverAlong.getOrElse(next) { route.distance } - along).coerceAtLeast(0.0)
        val remaining = (route.distance - along).coerceAtLeast(0.0)
        val destDist = Geo.distance(p, s.destination.point)
        val arrived = stopsLeft.isEmpty() && (remaining < 25 || (destDist < 30 && remaining < 150))

        val warning = currentWarning(s.routeHazards, along)
        // An unanswered offer disappears after half a minute.
        val faster = s.faster?.takeIf { System.currentTimeMillis() - it.offeredAt < 30_000 }

        val updated = s.copy(
            stops = stopsLeft,
            snap = snap,
            next = next,
            distanceToNext = distToNext,
            remainingDistance = remaining,
            remainingTime = route.remainingTime(along),
            speedKmh = (speed * 3.6).roundToInt(),
            offRoute = offRoute,
            arrived = arrived,
            warning = warning,
            faster = faster,
            nextStopDistance = nextStopDistance,
            nextStopTime = nextStopTime,
            lanes = route.lanesAt(along, if (speed > 22) 900.0 else 450.0),
        )
        _state.value = updated

        if (arrived) {
            arrivedAt = System.currentTimeMillis()
            say(if (short) "Ziel erreicht." else "Sie haben Ihr Ziel erreicht.", Say.INFO, interrupt = true)
            return
        }
        if (!offRoute) {
            announceManeuver(updated, speed)
            announceWarning(warning, speed)
        }
        maybeLookForFasterRoute(p, loc)
    }

    private fun announceManeuver(s: NavState, speed: Double) {
        val route = s.route
        val m = route.maneuvers.getOrNull(s.next) ?: return
        // "Continue on …" only marks a name change – not worth interrupting the music for.
        if (m.kind == ManeuverKind.STRAIGHT) return
        // Intermediate stops are announced when reached.
        val isLast = s.next == route.maneuvers.size - 1
        if (m.kind == ManeuverKind.ARRIVE && !isLast) return
        val d = s.distanceToNext
        val done = spoken[s.next] ?: 0
        val fast = speed > 22 // ~80 km/h
        val nowDist = maxOf(35.0, speed * 4.5)
        val midDist = when {
            fast -> 1000.0
            speed > 12 -> 500.0
            else -> 250.0
        }
        val farDist = if (fast && !short) 2000.0 else Double.MAX_VALUE

        fun mark(bits: Int) {
            spoken[s.next] = done or bits
        }

        when {
            done and 4 == 0 && d <= nowDist -> {
                mark(7)
                if (m.kind == ManeuverKind.ARRIVE) return
                var text = "Jetzt ${phrase(m)}"
                // Manoeuvres close behind each other are announced together.
                val after = route.maneuvers.getOrNull(s.next + 1)
                if (after != null && after.kind != ManeuverKind.ARRIVE && after.kind != ManeuverKind.STRAIGHT &&
                    route.maneuverAlong[s.next + 1] - route.maneuverAlong[s.next] < 150
                ) {
                    text += if (short) ", dann ${phrase(after)}" else ", danach ${phrase(after)}"
                }
                say("$text.", Say.GUIDE)
            }
            done and 2 == 0 && d <= midDist && d > nowDist * 1.6 -> {
                mark(3)
                say(
                    if (m.kind == ManeuverKind.ARRIVE) {
                        if (short) "Ziel in ${Format.spokenDistance(d)}." else "In ${Format.spokenDistance(d)} erreichen Sie Ihr Ziel."
                    } else {
                        "In ${Format.spokenDistance(d)} ${phrase(m)}."
                    },
                    Say.GUIDE,
                )
            }
            done and 1 == 0 && d <= farDist && d > midDist * 1.4 && fast && d > 1500 -> {
                mark(1)
                if (m.kind != ManeuverKind.ARRIVE) say("In ${Format.spokenDistance(d)} ${phrase(m)}.", Say.GUIDE)
            }
        }
    }

    /** The manoeuvre in the form that follows "In 300 Metern …" or "Jetzt …". */
    private fun phrase(m: Maneuver): String = if (short) shortPhrase(m) else lowerFirst(m.spoken)

    private fun shortPhrase(m: Maneuver): String = when (m.kind) {
        ManeuverKind.RIGHT -> "rechts abbiegen"
        ManeuverKind.LEFT -> "links abbiegen"
        ManeuverKind.SLIGHT_RIGHT -> "leicht rechts"
        ManeuverKind.SLIGHT_LEFT -> "leicht links"
        ManeuverKind.SHARP_RIGHT -> "scharf rechts"
        ManeuverKind.SHARP_LEFT -> "scharf links"
        ManeuverKind.KEEP_RIGHT -> "rechts halten"
        ManeuverKind.KEEP_LEFT -> "links halten"
        ManeuverKind.EXIT_RIGHT -> "Ausfahrt rechts nehmen"
        ManeuverKind.EXIT_LEFT -> "Ausfahrt links nehmen"
        ManeuverKind.UTURN -> "wenden"
        ManeuverKind.MERGE -> "auffahren"
        ManeuverKind.ROUNDABOUT -> m.exit?.let { "im Kreisverkehr die ${ordinal(it)} Ausfahrt" } ?: "in den Kreisverkehr"
        ManeuverKind.FERRY -> "auf die Fähre"
        ManeuverKind.ARRIVE -> "Ziel erreichen"
        ManeuverKind.DEPART -> "losfahren"
        ManeuverKind.STRAIGHT -> "geradeaus"
    }

    private fun ordinal(n: Int) = when (n) {
        1 -> "erste"
        2 -> "zweite"
        3 -> "dritte"
        4 -> "vierte"
        5 -> "fünfte"
        6 -> "sechste"
        7 -> "siebte"
        8 -> "achte"
        else -> "$n."
    }

    private fun currentWarning(hazards: List<RouteHazard>, along: Double): Warning? {
        val cams = settings.speedCameras
        return hazards.asSequence()
            .filter { cams || !it.hazard.type.isCamera }
            .map { it to it.along - along }
            .filter { (h, d) -> d > -15 && d < if (h.hazard.type.isCamera) 1500.0 else 3000.0 }
            .minByOrNull { it.second }
            ?.let { (h, d) -> Warning(h.hazard, d.coerceAtLeast(0.0)) }
    }

    private fun announceWarning(w: Warning?, speed: Double) {
        if (w == null || w.hazard.id in spokenHazards) return
        val camera = w.hazard.type.isCamera
        if (camera && !settings.speedCameras) return
        val limit = when {
            camera -> if (speed > 22) 1000.0 else 500.0
            speed > 22 -> 2000.0
            else -> 800.0
        }
        if (w.distance > limit) return
        spokenHazards += w.hazard.id
        val dist = Format.spokenDistance(w.distance)
        if (short) {
            val extra = if (camera && w.hazard.maxSpeed != null) ", ${w.hazard.maxSpeed}" else ""
            say("${w.hazard.type.label} in $dist$extra.", Say.WARN)
        } else {
            val extra = if (camera && w.hazard.maxSpeed != null) ", ${w.hazard.maxSpeed} Kilometer pro Stunde" else ""
            say("Achtung, in $dist ${w.hazard.type.spoken}$extra.", Say.WARN)
        }
    }

    // ------------------------------------------------------------------ routing

    private fun options(heading: Double?) = RouteOptions(settings.avoidTolls, settings.avoidMotorways, heading)

    private fun recalculate(from: GeoPoint, heading: Double?, stops: List<Place>, announce: Boolean) {
        val s = _state.value ?: return
        lastReroute = System.currentTimeMillis()
        _state.value = s.copy(rerouting = true)
        if (announce) say(if (short) "Neue Route." else "Route wird neu berechnet.", Say.GUIDE, interrupt = true)
        VoidMapsApp.scope.launch {
            val st = settings
            val points = listOf(from) + stops.map { it.point } + s.destination.point
            val result = withContext(Dispatchers.IO) {
                runCatching { Routing.route(points, st.routing, st.tomtomKey, options(heading)) }
            }
            val cur = _state.value ?: return@launch
            result.onSuccess { r -> replaceRoute(cur, r, stops) }
                .onFailure { _state.value = cur.copy(rerouting = false) }
        }
    }

    private fun replaceRoute(cur: NavState, r: Route, stops: List<Place>) {
        spoken.clear()
        offRouteCount = 0
        routeStops = stops
        passedStops = 0
        val first = firstRealManeuver(r)
        _state.value = cur.copy(
            stops = stops,
            route = r,
            routeVersion = cur.routeVersion + 1,
            snap = null,
            next = first,
            distanceToNext = r.maneuverAlong.getOrElse(first) { r.distance },
            remainingDistance = r.distance,
            remainingTime = r.duration,
            offRoute = false,
            rerouting = false,
            faster = null,
            routeHazards = projectHazards(r, HazardRepo.incidents.value + if (settings.speedCameras) HazardRepo.cameras.value else emptyList()),
        )
        VoidMapsApp.scope.launch { refreshHazards() }
    }

    /** With live traffic (TomTom) check every 3 minutes whether a clearly faster route exists, then offer it. */
    private fun maybeLookForFasterRoute(p: GeoPoint, loc: Location) {
        val st = settings
        val s = _state.value ?: return
        if (st.tomtomKey.isBlank() || st.routing == RoutingEngine.VALHALLA || s.rerouting || s.faster != null) return
        val now = System.currentTimeMillis()
        if (now - lastFasterCheck < 3 * 60_000L || now < fasterDeclinedUntil || s.remainingDistance < 3000) return
        lastFasterCheck = now
        val points = listOf(p) + s.stops.map { it.point } + s.destination.point
        VoidMapsApp.scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Routing.tomtom(points, st.tomtomKey, options(heading(loc))).firstOrNull() }.getOrNull()
            } ?: return@launch
            val cur = _state.value ?: return@launch
            val saved = cur.remainingTime - r.duration
            if (saved > maxOf(120.0, cur.remainingTime * 0.05) && !r.similarTo(cur.route)) {
                _state.value = cur.copy(faster = FasterRoute(r, saved, System.currentTimeMillis()))
                val min = (saved / 60).roundToInt()
                say(
                    if (short) "Schnellere Route, $min Minuten." else "Eine schnellere Route ist verfügbar. Sie sparen $min Minuten.",
                    Say.INFO,
                )
            }
        }
    }

    // ------------------------------------------------------------------ hazards

    private suspend fun refreshHazards() {
        val s = _state.value ?: return
        val st = settings
        val rest = s.snap?.let { s.route.line.remainder(it.segment, it.point) } ?: s.route.points
        if (st.tomtomKey.isNotBlank()) {
            val boxes = withContext(Dispatchers.Default) { BBox.chunks(rest, 9_000.0, 500.0, limit = 20) }
            HazardRepo.loadIncidents(st.tomtomKey, boxes, replace = false)
        }
        // Mobile controls first and in parallel: they matter most and change often.
        if (st.speedCameras && st.blitzerDe && st.mobileCameras) {
            VoidMapsApp.scope.launch {
                // Mobile controls along the next ~100 km, in areas of about 30 × 30 km, nearest first.
                val ahead = withContext(Dispatchers.Default) {
                    var acc = 0.0
                    val out = ArrayList<GeoPoint>()
                    for (k in rest.indices) {
                        if (k > 0) acc += Geo.distance(rest[k - 1], rest[k])
                        out += rest[k]
                        if (acc > 100_000) break
                    }
                    BBox.chunks(out, 900.0, 1_000.0, limit = 10)
                }
                HazardRepo.ensureMobile(ahead)
            }
        }
        if (st.speedCameras || st.chargers) {
            val cells = withContext(Dispatchers.Default) { HazardRepo.cellsFor(rest) }
            if (st.speedCameras) HazardRepo.ensureCameras(cells, st.blitzerDe, st.mobileCameras)
            if (st.chargers) HazardRepo.ensureChargers(cells, st.chargerOperators)
        }
    }

    private fun projectHazards(route: Route, hazards: List<Hazard>): List<RouteHazard> {
        val out = ArrayList<RouteHazard>()
        for (h in hazards) {
            val snap = route.line.snap(h.point) ?: continue
            val max = if (h.type.isCamera) 35.0 else 60.0
            if (snap.offset > max) continue
            // Incidents on the opposite carriageway share the road but not the direction.
            if (h.bearing != null && !h.type.isCamera &&
                Geo.angleDiff(h.bearing, route.line.segmentBearing(snap.segment)) > 70
            ) {
                continue
            }
            val end = if (h.line.size >= 2) {
                route.line.snap(h.line.last(), snap.segment, window = 4000)
                    ?.takeIf { it.offset < 80 && it.along > snap.along }?.along
            } else {
                null
            }
            out += RouteHazard(h, snap.along, end)
        }
        return out.sortedBy { it.along }
    }

    // ------------------------------------------------------------------ text helpers

    private fun lowerFirst(s: String): String =
        if (s.isEmpty()) s else s[0].lowercaseChar() + s.substring(1)

    fun spokenDuration(seconds: Double): String {
        val min = (seconds / 60).roundToInt().coerceAtLeast(1)
        val h = min / 60
        val m = min % 60
        val hs = when (h) {
            0 -> ""
            1 -> "Eine Stunde"
            else -> "$h Stunden"
        }
        val ms = when (m) {
            0 -> ""
            1 -> "eine Minute"
            else -> "$m Minuten"
        }
        return listOf(hs, ms).filter { it.isNotEmpty() }.joinToString(" ").replaceFirstChar { it.uppercase() }
    }
}
