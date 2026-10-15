package app.voidmaps.nav

import android.location.Location
import app.voidmaps.data.Geo
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Polyline
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Turns the 1 Hz GPS fixes into a smooth ~20 Hz position for the map.
 *
 * While navigating, the position moves along the route with the current speed between fixes
 * (dead reckoning). A new fix does not make the puck jump: the difference to the predicted
 * position fades out over about a second. The heading follows the road a little ahead and is
 * damped, so the map no longer jerks at every bend of the route line.
 */
class PuckSmoother {
    private var fix: Location? = null
    private var fixTime = 0L
    private var line: Polyline? = null
    private var fixAlong = 0.0
    private var alongCorrection = 0.0
    private var shownAlong: Double? = null
    private var shownBearing: Float? = null
    private var latCorrection = 0.0
    private var lonCorrection = 0.0
    private var shownPoint: GeoPoint? = null

    /** Feed every GPS fix together with the current navigation state. */
    fun onFix(loc: Location, nav: NavState?) {
        val now = System.currentTimeMillis()
        val snap = nav?.snap
        val onRoute = nav != null && snap != null && !nav.offRoute && snap.offset < 35
        val previousShownAlong = shownAlong
        val previousShown = shownPoint
        fix = loc
        fixTime = now
        if (onRoute) {
            val newLine = nav!!.route.line
            fixAlong = snap!!.along
            // Keep moving on smoothly from where the puck is now (same route only).
            alongCorrection = if (newLine === line && previousShownAlong != null) {
                (previousShownAlong - fixAlong).takeIf { kotlin.math.abs(it) < 60 } ?: 0.0
            } else {
                0.0
            }
            if (newLine !== line) shownAlong = null
            line = newLine
            latCorrection = 0.0
            lonCorrection = 0.0
        } else {
            line = null
            shownAlong = null
            if (previousShown != null && Geo.distance(previousShown, GeoPoint(loc.latitude, loc.longitude)) < 60) {
                latCorrection = previousShown.lat - loc.latitude
                lonCorrection = previousShown.lon - loc.longitude
            } else {
                latCorrection = 0.0
                lonCorrection = 0.0
            }
        }
    }

    /** Distance along [l] shown in the last frame, or null when the puck is not running on that line. */
    fun alongOn(l: Polyline?): Double? = if (l != null && l === line) shownAlong else null

    /** Position to show right now, or null before the first fix. */
    fun frame(): Location? {
        val f = fix ?: return null
        val now = System.currentTimeMillis()
        val dt = ((now - fixTime) / 1000.0).coerceIn(0.0, 1.6)
        val speed = if (f.hasSpeed()) f.speed.toDouble() else 0.0
        val moving = speed > 0.8
        val fade = exp(-dt / 0.6)
        val l = line
        val point: GeoPoint
        var targetBearing: Float? = null
        if (l != null) {
            val along = (fixAlong + (if (moving) speed * dt else 0.0) + alongCorrection * fade).coerceIn(0.0, l.length)
            shownAlong = along
            point = l.pointAt(along)
            if (moving) targetBearing = Geo.bearing(point, l.pointAt(along + 20)).toFloat()
        } else {
            val dist = if (moving && f.hasBearing()) speed * dt else 0.0
            val b = Math.toRadians(f.bearing.toDouble())
            val dLat = dist * cos(b) / 111_320.0
            val dLon = dist * sin(b) / (111_320.0 * cos(Math.toRadians(f.latitude)).coerceAtLeast(0.1))
            point = GeoPoint(f.latitude + dLat + latCorrection * fade, f.longitude + dLon + lonCorrection * fade)
            if (moving && f.hasBearing()) targetBearing = f.bearing
        }
        shownPoint = point
        // Damped rotation: a fraction of the remaining angle per frame.
        val current = shownBearing
        val bearing = when {
            targetBearing == null -> current ?: f.bearing
            current == null -> targetBearing
            else -> {
                var diff = targetBearing - current
                while (diff > 180) diff -= 360
                while (diff < -180) diff += 360
                ((current + diff * 0.18f) + 360f) % 360f
            }
        }
        shownBearing = bearing
        return Location("smooth").apply {
            latitude = point.lat
            longitude = point.lon
            this.bearing = bearing
            this.speed = f.speed
            accuracy = if (f.hasAccuracy()) f.accuracy else 5f
            time = now
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
    }
}
