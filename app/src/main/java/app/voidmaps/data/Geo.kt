package app.voidmaps.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

object Geo {
    private const val EARTH_RADIUS = 6_371_008.8

    private fun rad(d: Double) = Math.toRadians(d)

    /** Great-circle distance in metres. */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from [a] to [b] in degrees 0..360. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val y = sin(rad(b.lon - a.lon)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lon - a.lon))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Smallest absolute difference between two bearings (0..180). */
    fun angleDiff(a: Double, b: Double): Double {
        val d = ((a - b) % 360 + 360) % 360
        return if (d > 180) 360 - d else d
    }

    /** Decodes a Google/Valhalla encoded polyline. Valhalla uses precision 6. */
    fun decodePolyline(encoded: String, precision: Int): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            for (k in 0..1) {
                var result = 0L
                var shift = 0
                var b: Int
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20 && index < encoded.length)
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (k == 0) lat += delta else lon += delta
            }
            out += GeoPoint(lat / factor, lon / factor)
        }
        return out
    }

    /** Approximate area of a lat/lon box in km². */
    fun areaKm2(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): Double {
        val h = (maxLat - minLat) * 111.32
        val w = (maxLon - minLon) * 111.32 * cos(rad((minLat + maxLat) / 2))
        return h * w
    }
}

data class BBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    val areaKm2: Double get() = Geo.areaKm2(minLat, minLon, maxLat, maxLon)

    fun expand(meters: Double): BBox {
        val dLat = meters / 111_320.0
        val dLon = meters / (111_320.0 * cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.1))
        return BBox(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }

    fun contains(p: GeoPoint) = p.lat in minLat..maxLat && p.lon in minLon..maxLon

    companion object {
        fun around(p: GeoPoint, meters: Double) = BBox(p.lat, p.lon, p.lat, p.lon).expand(meters)

        /**
         * Splits a path into boxes no larger than [maxKm2] (e.g. TomTom allows 10 000 km² per request),
         * each widened by [margin] metres.
         */
        fun chunks(points: List<GeoPoint>, maxKm2: Double, margin: Double, limit: Int = 30): List<BBox> {
            if (points.isEmpty()) return emptyList()
            val out = ArrayList<BBox>()
            var cur: BBox? = null
            for (p in points) {
                val c = cur
                if (c == null) {
                    cur = BBox(p.lat, p.lon, p.lat, p.lon)
                    continue
                }
                val grown = BBox(minOf(c.minLat, p.lat), minOf(c.minLon, p.lon), maxOf(c.maxLat, p.lat), maxOf(c.maxLon, p.lon))
                if (grown.expand(margin).areaKm2 > maxKm2) {
                    out += c.expand(margin)
                    if (out.size >= limit) return out
                    cur = BBox(p.lat, p.lon, p.lat, p.lon)
                } else {
                    cur = grown
                }
            }
            cur?.let { out += it.expand(margin) }
            return out
        }
    }
}

/** Where a position lies on a polyline. */
data class Snap(
    val segment: Int,
    val point: GeoPoint,
    /** Metres from the start of the line to [point]. */
    val along: Double,
    /** Metres between the real position and the line. */
    val offset: Double,
)

/** A polyline with cumulative distances for fast "where am I on the route" queries. */
class Polyline(val points: List<GeoPoint>) {
    val cumulative: DoubleArray = DoubleArray(points.size).also { cum ->
        for (i in 1 until points.size) cum[i] = cum[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val length: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    /** Bearing of the segment that starts at [segment]. */
    fun segmentBearing(segment: Int): Double {
        val i = segment.coerceIn(0, (points.size - 2).coerceAtLeast(0))
        if (points.size < 2) return 0.0
        return Geo.bearing(points[i], points[i + 1])
    }

    private fun projectOnSegment(p: GeoPoint, i: Int): Snap {
        val a = points[i]
        val b = points[i + 1]
        // Local equirectangular projection around a – accurate enough for segments of a few km.
        val kx = 111_320.0 * cos(Math.toRadians(a.lat))
        val ky = 110_574.0
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx
        val py = (p.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val sx = bx * t
        val sy = by * t
        val dx = px - sx
        val dy = py - sy
        val point = GeoPoint(a.lat + sy / ky, a.lon + sx / kx)
        val along = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * t
        return Snap(i, point, along, sqrt(dx * dx + dy * dy))
    }

    /**
     * Snaps [p] to the line. Searches a window after [hint] first (where we were last time),
     * falls back to the whole line when nothing close is found there.
     */
    fun snap(p: GeoPoint, hint: Int = -1, window: Int = 400): Snap? {
        if (points.size < 2) return null
        fun search(from: Int, to: Int): Snap? {
            var best: Snap? = null
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(points.size - 1)) {
                val s = projectOnSegment(p, i)
                if (best == null || s.offset < best.offset) best = s
            }
            return best
        }
        if (hint >= 0) {
            val local = search(hint - 3, hint + window)
            if (local != null && local.offset < 50) return local
        }
        return search(0, points.size - 1)
    }

    /** Point at [along] metres from the start. */
    fun pointAt(along: Double): GeoPoint {
        if (points.isEmpty()) return GeoPoint(0.0, 0.0)
        val a = along.coerceIn(0.0, length)
        var lo = 0
        var hi = cumulative.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= a) lo = mid else hi = mid
        }
        val span = cumulative[hi] - cumulative[lo]
        val t = if (span <= 0) 0.0 else (a - cumulative[lo]) / span
        val p = points[lo]
        val q = points[hi]
        return GeoPoint(p.lat + (q.lat - p.lat) * t, p.lon + (q.lon - p.lon) * t)
    }

    /** The part of the line between two distances. */
    fun slice(from: Double, to: Double): List<GeoPoint> {
        if (points.size < 2 || to <= from) return emptyList()
        val out = arrayListOf(pointAt(from))
        for (i in points.indices) if (cumulative[i] > from && cumulative[i] < to) out += points[i]
        out += pointAt(to)
        return out
    }

    /** Points of the line from [along] metres onwards (for "rest of the route" rendering and queries). */
    fun remainder(fromSegment: Int, start: GeoPoint): List<GeoPoint> {
        if (points.isEmpty()) return emptyList()
        val i = fromSegment.coerceIn(0, points.size - 1)
        return listOf(start) + points.subList((i + 1).coerceAtMost(points.size), points.size)
    }
}

object Format {
    /** "350 m", "1,2 km", "24 km" */
    fun distance(m: Double): String = when {
        m < 0 -> "0 m"
        m < 1000 -> "${(roundTo(m, if (m < 100) 10 else 50)).roundToInt()} m"
        m < 10_000 -> "%.1f km".format(java.util.Locale.GERMANY, m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }

    /** Spoken dative form: "300 Metern", "einem Kilometer", "1,5 Kilometern". */
    fun spokenDistance(m: Double): String = when {
        m < 1000 -> "${roundTo(m, if (m < 100) 10 else 50).roundToInt()} Metern"
        m < 1250 -> "einem Kilometer"
        m < 10_000 -> {
            val km = Math.round(m / 500.0) / 2.0
            if (km % 1.0 == 0.0) "${km.toInt()} Kilometern" else "%.1f Kilometern".format(java.util.Locale.GERMANY, km)
        }
        else -> "${(m / 1000).roundToInt()} Kilometern"
    }

    /** "12 min", "1 h 05 min" */
    fun duration(seconds: Double): String {
        val total = (seconds / 60).roundToInt().coerceAtLeast(0)
        return if (total < 60) "$total min" else "${total / 60} h %02d min".format(total % 60)
    }

    /** Remaining time as hh:mm, e.g. "01:05". */
    fun hhmm(seconds: Double): String {
        val total = (seconds / 60).roundToInt().coerceAtLeast(0)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    fun clock(epochMs: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(epochMs))

    private fun roundTo(v: Double, step: Int) = (Math.round(v / step) * step).toDouble()
}
