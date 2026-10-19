package app.voidmaps.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GPS position for the whole app. Uses the platform LocationManager (no Google services needed).
 * Several owners (map screen, navigation service) can hold it; it runs while at least one does.
 */
object LocationTracker {
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val owners = HashSet<String>()
    private var manager: LocationManager? = null
    private var running = false
    private var lastGpsAt = 0L
    private var lastBearing = 0f

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun acquire(context: Context, owner: String) {
        owners += owner
        start(context.applicationContext)
    }

    @Synchronized
    fun release(owner: String) {
        owners -= owner
        if (owners.isEmpty()) stop()
    }

    /** Call after the permission was granted while owners were already waiting. */
    @Synchronized
    fun restart(context: Context) {
        if (owners.isNotEmpty()) {
            stop()
            start(context.applicationContext)
        }
    }

    @SuppressLint("MissingPermission")
    private fun start(context: Context) {
        if (running || !hasPermission(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        val providers = lm.allProviders
        runCatching {
            if (LocationManager.GPS_PROVIDER in providers) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
            }
        }
        val secondary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in providers) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.NETWORK_PROVIDER
        }
        runCatching {
            if (secondary in providers) {
                lm.requestLocationUpdates(secondary, if (secondary == LocationManager.FUSED_PROVIDER) 500L else 2000L, 0f, listener, Looper.getMainLooper())
            }
        }
        running = true
        if (_location.value == null) {
            val last = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null) _location.value = last
        }
    }

    private fun stop() {
        runCatching { manager?.removeUpdates(listener) }
        running = false
    }

    /** While the demo drive runs, real GPS fixes are ignored and the simulated ones are used. */
    @Volatile
    var simulating = false

    fun pushSimulated(loc: Location) {
        if (simulating) _location.value = loc
    }

    private fun onLocation(loc: Location) {
        if (simulating) return
        val now = System.currentTimeMillis()
        if (loc.provider == LocationManager.GPS_PROVIDER) {
            lastGpsAt = now
        } else if (now - lastGpsAt < 5000 && (!loc.hasAccuracy() || loc.accuracy > 12f)) {
            return // GPS is delivering – ignore coarser fixes (precise fused ones, e.g. from the car, are fine).
        }
        val fixed = if (loc.hasBearing() && loc.speed > 1.5f) {
            lastBearing = loc.bearing
            loc
        } else {
            Location(loc).apply { bearing = lastBearing }
        }
        _location.value = fixed
    }
}
