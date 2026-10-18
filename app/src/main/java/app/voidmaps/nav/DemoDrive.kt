package app.voidmaps.nav

import android.content.Context
import android.location.Location
import android.os.SystemClock
import app.voidmaps.VoidMapsApp
import app.voidmaps.data.Geo
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Place
import app.voidmaps.data.Route
import app.voidmaps.data.RouteOptions
import app.voidmaps.data.Routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Demo drive: navigation along a route with a simulated position, at the speed the routing expects
 * (optionally faster). The real GPS position is ignored meanwhile. Everything else – announcements,
 * warnings, lanes, progress bar, Android Auto – runs exactly as on a real drive.
 */
object DemoDrive {
    /** City, country road and motorway with junctions: Stuttgart → Heilbronn via A81. */
    val DEMO_START = GeoPoint(48.7838, 9.1817)
    val DEMO_DEST = Place("Heilbronn Marktplatz", "Demo-Fahrt", GeoPoint(49.1424, 9.2188))

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val speed = MutableStateFlow(1)
    val paused = MutableStateFlow(false)
    private var job: Job? = null

    /** Demo on the built-in demo route. */
    suspend fun startDefault(context: Context): Result<Unit> {
        val s = VoidMapsApp.settings.value
        val r = withContext(Dispatchers.IO) {
            runCatching {
                Routing.route(listOf(DEMO_START, DEMO_DEST.point), s.routing, s.tomtomKey, RouteOptions(s.avoidTolls, s.avoidMotorways))
            }
        }
        return r.map { start(context, DEMO_DEST, emptyList(), it) }
    }

    /** Demo along an already calculated route (e.g. the one chosen in the preview). */
    fun start(context: Context, destination: Place, stops: List<Place>, route: Route) {
        stop()
        paused.value = false
        LocationTracker.simulating = true
        push(route, 0.0, 0.0)
        Navigator.start(context, destination, stops, route)
        _active.value = true
        job = VoidMapsApp.scope.launch {
            var t = 0.0
            var version = Navigator.state.value?.routeVersion ?: 0
            try {
                while (true) {
                    val st = Navigator.state.value ?: break
                    if (st.routeVersion != version) {
                        // New route (faster route, new stop) starts where the car is now; a route that was
                        // only aligned to the map continues from the same place.
                        version = st.routeVersion
                        val a = st.snap?.along ?: 0.0
                        t = (st.route.totalTime - st.route.remainingTime(a)).coerceAtLeast(0.0)
                    }
                    if (!paused.value) t += 0.2 * speed.value
                    val r = st.route
                    val along = r.alongAtTime(t)
                    val v = if (paused.value) 0.0 else (r.alongAtTime(t + 1) - along).coerceAtLeast(0.0)
                    push(r, along, v)
                    delay(200)
                }
            } finally {
                LocationTracker.simulating = false
                _active.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        LocationTracker.simulating = false
        _active.value = false
    }

    fun cycleSpeed() {
        speed.value = when (speed.value) {
            1 -> 2
            2 -> 4
            4 -> 8
            else -> 1
        }
    }

    private fun push(r: Route, along: Double, metresPerSecond: Double) {
        val p = r.line.pointAt(along)
        val ahead = r.line.pointAt(along + 15)
        val bearing = if (Geo.distance(p, ahead) > 0.5) Geo.bearing(p, ahead) else 0.0
        val loc = Location("demo").apply {
            latitude = p.lat
            longitude = p.lon
            this.bearing = bearing.toFloat()
            speed = metresPerSecond.toFloat()
            accuracy = 4f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        LocationTracker.pushSimulated(loc)
    }
}
