package app.voidmaps.nav

import android.location.Location
import app.voidmaps.VoidMapsApp
import app.voidmaps.data.Geo
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Speed limit where no route provides one (no navigation, or a gap in the route's data):
 * TomTom reverse geocoding with the driving direction, at most every 10 s or 200 m while moving.
 */
object SpeedLimits {
    private val _lookup = MutableStateFlow<Int?>(null)
    val lookup: StateFlow<Int?> = _lookup.asStateFlow()

    private var started = false
    private var lastAt = 0L
    private var lastPoint: GeoPoint? = null
    private var job: Job? = null

    fun start() {
        if (started) return
        started = true
        VoidMapsApp.scope.launch {
            LocationTracker.location.collect { loc -> if (loc != null) onLocation(loc) }
        }
    }

    /** Limit to show: from the route while navigating, otherwise the looked-up one. */
    fun current(nav: NavState?): Int? = nav?.speedLimit ?: _lookup.value

    private fun onLocation(loc: Location) {
        val key = VoidMapsApp.settings.value.tomtomKey
        if (key.isBlank() || Navigator.state.value?.speedLimit != null) return
        val p = GeoPoint(loc.latitude, loc.longitude)
        val moved = lastPoint?.let { Geo.distance(it, p) } ?: Double.MAX_VALUE
        if (moved > 1_500) _lookup.value = null // far away from the last lookup: no longer valid
        if (loc.speed < 2f && lastPoint != null) return
        val now = System.currentTimeMillis()
        if (job?.isActive == true || (now - lastAt < 10_000 && moved < 200)) return
        lastAt = now
        lastPoint = p
        val heading = if (loc.hasBearing()) loc.bearing.toInt() else null
        job = VoidMapsApp.scope.launch {
            val v = withContext(Dispatchers.IO) { runCatching { fetch(p, heading, key) }.getOrNull() }
            _lookup.value = v
        }
    }

    private fun fetch(p: GeoPoint, heading: Int?, key: String): Int? {
        val url = StringBuilder("https://api.tomtom.com/search/2/reverseGeocode/${p.lat},${p.lon}.json")
            .append("?key=").append(Http.enc(key))
            .append("&returnSpeedLimit=true&radius=30&language=de-DE")
        heading?.let { url.append("&heading=").append(it.coerceIn(0, 359)) }
        val addresses = JSONObject(Http.get(url.toString(), timeoutMs = 6_000)).optJSONArray("addresses") ?: return null
        val raw = addresses.optJSONObject(0)?.optJSONObject("address")?.optString("speedLimit").orEmpty() // "50.00KPH"
        val value = Regex("""([\d.]+)""").find(raw)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val kmh = if (raw.contains("MPH", ignoreCase = true)) value * 1.609 else value
        return kmh.toInt().takeIf { it in 5..200 }
    }
}
