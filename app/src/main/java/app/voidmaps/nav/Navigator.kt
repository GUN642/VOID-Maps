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
import app.voidmaps.data.ManeuverKind
import app.voidmaps.data.Place
import app.voidmaps.data.Route
import app.voidmaps.data.RouteOptions
import app.voidmaps.data.Routing
import app.voidmaps.data.RoutingEngine
import app.voidmaps.data.Snap
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

/** A hazard that lies on the current route. */
data class RouteHazard(val hazard: Hazard, val along: Double)

data class Warning(val hazard: Hazard, val distance: Double)

data class NavState(
    val destination: Place,
    val route: Route,
    /** Bumps whenever the route was replaced (reroute, faster route). */
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
) {
    val eta: Long get() = System.currentTimeMillis() + (remainingTime * 1000).toLong()
}

/**
 * Turn-by-turn guidance: follows the GPS position along the route, announces manoeuvres and
 * hazards, recalculates when the driver leaves the route and looks for faster routes in traffic.
 */
object Navigator {
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
    private var arrivedAt = 0L

    private val settings: AppSettings get() = VoidMapsApp.settings.value

    fun isActive() = _state.value != null

    fun start(context: Context, destination: Place, route: Route) {
        val app = context.applicationContext
        appContext = app
        if (speaker == null) speaker = Speaker(app)
        reset()
        _state.value = NavState(
            destination = destination,
            route = route,
            routeVersion = 0,
            snap = null,
            next = firstRealManeuver(route),
            distanceToNext = route.maneuverAlong.getOrElse(firstRealManeuver(route)) { route.distance },
            remainingDistance = route.distance,
            remainingTime = route.duration,
            speedKmh = 0,
        )
        lastFasterCheck = System.currentTimeMillis()
        ContextCompat.startForegroundService(app, Intent(app, NavigationService::class.java))
        say(
            "Die Route wird gestartet. ${spokenDuration(route.duration)}, Ankunft um ${Format.clock(System.currentTimeMillis() + (route.duration * 1000).toLong())} Uhr.",
            interrupt = true,
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

    /** Mutes or re-enables voice immediately. */
    fun onVoiceChanged(enabled: Boolean) {
        if (!enabled) speaker?.stop()
    }

    private fun reset() {
        spoken.clear()
        spokenHazards.clear()
        offRouteCount = 0
        lastReroute = 0L
        arrivedAt = 0L
    }

    private fun firstRealManeuver(route: Route): Int {
        val i = route.maneuvers.indexOfFirst { it.kind != ManeuverKind.DEPART }
        return if (i < 0) 0 else i
    }

    private fun say(text: String, interrupt: Boolean = false) {
        if (settings.voice) speaker?.speak(text, interrupt)
    }

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
            reroute(p, if (loc.hasBearing() && speed > 2) loc.bearing.toDouble() else null, announce = true)
        }

        val along = snap.along
        var next = s.next
        while (next < route.maneuvers.size - 1 && route.maneuverAlong[next] < along + 3) next++
        // Never fall back to the departure manoeuvre.
        if (route.maneuvers.getOrNull(next)?.kind == ManeuverKind.DEPART && next + 1 < route.maneuvers.size) next++
        val distToNext = (route.maneuverAlong.getOrElse(next) { route.distance } - along).coerceAtLeast(0.0)
        val remaining = (route.distance - along).coerceAtLeast(0.0)
        val destDist = Geo.distance(p, s.destination.point)
        val arrived = remaining < 25 || (destDist < 30 && remaining < 150)

        val warning = currentWarning(s.routeHazards, along)

        val updated = s.copy(
            snap = snap,
            next = next,
            distanceToNext = distToNext,
            remainingDistance = remaining,
            remainingTime = route.remainingTime(along),
            speedKmh = (speed * 3.6).roundToInt(),
            offRoute = offRoute,
            arrived = arrived,
            warning = warning,
        )
        _state.value = updated

        if (arrived) {
            arrivedAt = System.currentTimeMillis()
            say("Sie haben Ihr Ziel erreicht.", interrupt = true)
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
        val d = s.distanceToNext
        val done = spoken[s.next] ?: 0
        val fast = speed > 22 // ~80 km/h
        val nowDist = maxOf(35.0, speed * 4.5)
        val midDist = when {
            fast -> 1000.0
            speed > 12 -> 500.0
            else -> 250.0
        }
        val farDist = if (fast) 2000.0 else Double.MAX_VALUE

        fun mark(bits: Int) {
            spoken[s.next] = done or bits
        }

        val phrase = if (m.kind == ManeuverKind.ARRIVE) "erreichen Sie Ihr Ziel" else lowerFirst(m.spoken)
        when {
            done and 4 == 0 && d <= nowDist -> {
                var text = if (m.kind == ManeuverKind.ARRIVE) "Sie erreichen jetzt Ihr Ziel" else "Jetzt ${lowerFirst(m.spoken)}"
                // Manoeuvres close behind each other are announced together.
                val after = route.maneuvers.getOrNull(s.next + 1)
                if (after != null && after.kind != ManeuverKind.ARRIVE &&
                    route.maneuverAlong[s.next + 1] - route.maneuverAlong[s.next] < 150
                ) {
                    text += ", danach ${lowerFirst(after.spoken)}"
                }
                mark(7)
                say("$text.")
            }
            done and 2 == 0 && d <= midDist && d > nowDist * 1.6 -> {
                mark(3)
                say("In ${Format.spokenDistance(d)} $phrase.")
            }
            done and 1 == 0 && d <= farDist && d > midDist * 1.4 && fast && d > 1500 -> {
                mark(1)
                say("In ${Format.spokenDistance(d)} $phrase.")
            }
        }
    }

    private fun currentWarning(hazards: List<RouteHazard>, along: Double): Warning? {
        val cams = settings.speedCameras
        return hazards.asSequence()
            .filter { cams || it.hazard.type != HazardType.SPEED_CAMERA }
            .map { it to it.along - along }
            .filter { (h, d) -> d > -15 && d < if (h.hazard.type == HazardType.SPEED_CAMERA) 1500.0 else 3000.0 }
            .minByOrNull { it.second }
            ?.let { (h, d) -> Warning(h.hazard, d.coerceAtLeast(0.0)) }
    }

    private fun announceWarning(w: Warning?, speed: Double) {
        if (w == null || w.hazard.id in spokenHazards) return
        val camera = w.hazard.type == HazardType.SPEED_CAMERA
        if (camera && !settings.speedCameras) return
        val limit = when {
            camera -> if (speed > 22) 1000.0 else 500.0
            speed > 22 -> 2000.0
            else -> 800.0
        }
        if (w.distance > limit) return
        spokenHazards += w.hazard.id
        val extra = if (camera && w.hazard.maxSpeed != null) ", ${w.hazard.maxSpeed} Kilometer pro Stunde" else ""
        say("Achtung, in ${Format.spokenDistance(w.distance)} ${w.hazard.type.spoken}$extra.")
    }

    // ------------------------------------------------------------------ routing

    private fun options(heading: Double?) = RouteOptions(settings.avoidTolls, settings.avoidMotorways, heading)

    private fun reroute(from: GeoPoint, heading: Double?, announce: Boolean) {
        val s = _state.value ?: return
        lastReroute = System.currentTimeMillis()
        _state.value = s.copy(rerouting = true)
        if (announce) say("Route wird neu berechnet.", interrupt = true)
        VoidMapsApp.scope.launch {
            val st = settings
            val result = withContext(Dispatchers.IO) {
                runCatching { Routing.route(from, s.destination.point, st.routing, st.tomtomKey, options(heading)) }
            }
            val cur = _state.value ?: return@launch
            result.onSuccess { r -> replaceRoute(cur, r) }
                .onFailure { _state.value = cur.copy(rerouting = false) }
        }
    }

    private fun replaceRoute(cur: NavState, r: Route) {
        spoken.clear()
        offRouteCount = 0
        val first = firstRealManeuver(r)
        _state.value = cur.copy(
            route = r,
            routeVersion = cur.routeVersion + 1,
            snap = null,
            next = first,
            distanceToNext = r.maneuverAlong.getOrElse(first) { r.distance },
            remainingDistance = r.distance,
            remainingTime = r.duration,
            offRoute = false,
            rerouting = false,
            routeHazards = projectHazards(r, HazardRepo.incidents.value + if (settings.speedCameras) HazardRepo.cameras.value else emptyList()),
        )
        VoidMapsApp.scope.launch { refreshHazards() }
    }

    /** With live traffic (TomTom) check every 5 minutes whether a clearly faster route exists. */
    private fun maybeLookForFasterRoute(p: GeoPoint, loc: Location) {
        val st = settings
        val s = _state.value ?: return
        if (st.tomtomKey.isBlank() || st.routing == RoutingEngine.VALHALLA || s.rerouting) return
        val now = System.currentTimeMillis()
        if (now - lastFasterCheck < 5 * 60_000L || s.remainingDistance < 3000) return
        lastFasterCheck = now
        val heading = if (loc.hasBearing()) loc.bearing.toDouble() else null
        VoidMapsApp.scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Routing.tomtom(p, s.destination.point, st.tomtomKey, options(heading)) }.getOrNull()
            } ?: return@launch
            val cur = _state.value ?: return@launch
            val saved = cur.remainingTime - r.duration
            if (saved > maxOf(180.0, cur.remainingTime * 0.08)) {
                replaceRoute(cur, r)
                say("Schnellere Route gefunden. Sie sparen ${(saved / 60).roundToInt()} Minuten.")
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
        if (st.speedCameras) {
            val boxes = withContext(Dispatchers.Default) { BBox.chunks(rest, 2_500.0, 300.0, limit = 40) }
            HazardRepo.loadCameras(boxes, replace = false)
        }
    }

    private fun projectHazards(route: Route, hazards: List<Hazard>): List<RouteHazard> {
        val out = ArrayList<RouteHazard>()
        for (h in hazards) {
            val snap = route.line.snap(h.point) ?: continue
            val max = if (h.type == HazardType.SPEED_CAMERA) 35.0 else 60.0
            if (snap.offset > max) continue
            // Incidents on the opposite carriageway share the road but not the direction.
            if (h.bearing != null && h.type != HazardType.SPEED_CAMERA &&
                Geo.angleDiff(h.bearing, route.line.segmentBearing(snap.segment)) > 70
            ) {
                continue
            }
            out += RouteHazard(h, snap.along)
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
