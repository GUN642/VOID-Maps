package app.voidmaps.data

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Turns text shared from other apps ("Teilen" → VOID Maps) into a destination.
 *
 * Google Maps shares something like
 * ```
 * Marktplatz Heilbronn
 * Marktpl., 74072 Heilbronn
 * https://maps.app.goo.gl/AbC123
 * ```
 * Short links are expanded without a browser and the coordinates are read from the target URL
 * (or the page behind it). Without coordinates, the name/address is looked up with the normal search.
 * Also understands links from Apple Maps, OpenStreetMap, Waze, HERE, geo: URIs and plain or
 * degree/minute/second coordinates.
 */
object SharedLocation {
    /** What could be read from the text without any network access. */
    data class Parsed(
        val point: GeoPoint?,
        /** First line that is not a link, e.g. the place name. */
        val name: String?,
        /** The other non-link lines, e.g. the address. */
        val detail: String?,
        /** Text to search for when there are no coordinates. */
        val query: String?,
        val links: List<String>,
    )

    private val URL_RE = Regex("""(?:https?://|geo:|google\.navigation:)[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private const val LAT = """[-+]?\d{1,2}\.\d+"""
    private const val LON = """[-+]?\d{1,3}\.\d+"""
    private val PAIR_RE = Regex("""($LAT)\s*,\s*\+?($LON)""")
    private val DMS_RE = Regex(
        """(\d{1,2})°\s*(\d{1,2})['′]\s*([\d.]+)["″]?\s*([NS])[\s,]+(\d{1,3})°\s*(\d{1,2})['′]\s*([\d.]+)["″]?\s*([EOW])""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): Parsed {
        val links = URL_RE.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }.toList()
        val plain = URL_RE.replace(text, " ")
        val lines = plain.lines().map { it.trim().trim(',', '-', '–', ':').trim() }.filter { it.isNotBlank() }
        val point = links.firstNotNullOfOrNull { pointFromUrl(it) } ?: pointFromText(plain)
        // A line that is just the coordinates is not a name.
        val named = lines.filter { pointFromText(it) == null }
        val urlName = links.firstNotNullOfOrNull { nameFromUrl(it) }
        // "Name, Straße 1, 12345 Ort" on a single line: the part before the first comma is the name.
        val single = named.singleOrNull()?.takeIf { it.contains(',') }
        val name = single?.substringBefore(',')?.trim() ?: named.firstOrNull() ?: urlName
        val detail = single?.substringAfter(',')?.trim()?.ifBlank { null } ?: named.drop(1).joinToString(", ").ifBlank { null }
        val query = when {
            named.isNotEmpty() -> named.joinToString(", ")
            else -> urlName
        }
        return Parsed(point, name, detail, query, links)
    }

    /** Plain "48.1234, 9.1234" or 48°08'33.0"N 9°12'48.0"E inside running text. */
    fun pointFromText(text: String): GeoPoint? {
        DMS_RE.find(text)?.let { m ->
            val g = m.groupValues
            var lat = g[1].toDouble() + g[2].toDouble() / 60 + g[3].toDouble() / 3600
            var lon = g[5].toDouble() + g[6].toDouble() / 60 + g[7].toDouble() / 3600
            if (g[4].equals("S", true)) lat = -lat
            if (g[8].equals("W", true)) lon = -lon
            return valid(lat, lon)
        }
        val m = PAIR_RE.find(text) ?: return null
        return valid(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
    }

    /** Coordinates contained in a maps link, most precise source first. */
    fun pointFromUrl(raw: String): GeoPoint? {
        val url = decode(raw)
        // geo:48.1,9.2?q=… and geo:0,0?q=48.1,9.2(Name)
        if (url.startsWith("geo:", true)) {
            val q = param(url, "q")?.let { PAIR_RE.find(it) }
            q?.let { return valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            PAIR_RE.find(url.substringBefore('?'))?.let { return valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            return null
        }
        // Google: the place itself (!3d lat !4d lon), preferred over the map centre.
        Regex("""!3d($LAT)!4d($LON)""").findAll(url).lastOrNull()?.let {
            valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())?.let { p -> return p }
        }
        // Query parameters of Google, Apple, Waze, OSM, WhatsApp and others.
        for (key in listOf("q", "query", "destination", "daddr", "ll", "coordinate", "sll", "center", "markers", "location", "to")) {
            val v = param(url, key) ?: continue
            PAIR_RE.find(v)?.let { m ->
                // Only when the value starts with the coordinates ("q=Pizza near 48.1,9.2" is a search).
                if (v.trimStart().startsWith(m.value) || v.substringAfter('|', v).trimStart().startsWith(m.value)) {
                    valid(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())?.let { return it }
                }
            }
        }
        val mlat = param(url, "mlat")?.toDoubleOrNull()
        val mlon = param(url, "mlon")?.toDoubleOrNull()
        if (mlat != null && mlon != null) valid(mlat, mlon)?.let { return it }
        // Google route link /dir/start/destination: the last stop is the destination.
        if (url.contains("/dir/")) {
            url.substringAfter("/dir/").substringBefore('?').split('/')
                .filter { it.isNotBlank() && !it.startsWith("@") && !it.startsWith("data=") }
                .lastOrNull()?.let { seg -> PAIR_RE.find(seg)?.let { m -> if (seg.trimStart().startsWith(m.value)) valid(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())?.let { p -> return p } } }
        }
        // HERE: share.here.com/l/48.1,9.2,Name ; Google: /maps/search/48.1,+9.2 or /place/48.1,9.2
        Regex("""/(?:l|search|place)/($LAT)\s*,\s*\+?($LON)""").find(url)?.let {
            valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())?.let { p -> return p }
        }
        // Map centre: Google /@48.1,9.2,17z and OSM #map=17/48.1/9.2
        Regex("""@($LAT),($LON)""").find(url)?.let {
            valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())?.let { p -> return p }
        }
        Regex("""map=\d+(?:\.\d+)?/($LAT)/($LON)""").find(url)?.let {
            valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())?.let { p -> return p }
        }
        return null
    }

    /** Place name from /maps/place/Name/… or a non-coordinate q= parameter. */
    fun nameFromUrl(raw: String): String? {
        val url = decode(raw)
        Regex("""/maps/place/([^/@?#]+)""").find(url)?.let { m ->
            val n = m.groupValues[1].replace('+', ' ').trim()
            if (n.isNotBlank() && PAIR_RE.find(n) == null) return n
        }
        for (key in listOf("q", "query", "daddr", "destination", "address", "name")) {
            val v = param(url, key)?.replace('+', ' ')?.trim() ?: continue
            if (v.isNotBlank() && pointFromText(v) == null) return v.substringBefore('(').trim().ifBlank { null }
        }
        return null
    }

    fun isShortLink(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        return host == "maps.app.goo.gl" || host == "goo.gl" || host == "g.co" || host == "maps.apple" ||
            host == "osm.org" || host.endsWith("page.link") || host == "share.google"
    }

    /**
     * Follows redirects of a short link by hand and returns the first URL with coordinates,
     * or the last URL reached. Looks into the page as a last resort (Google puts the place
     * position into the preview image URL).
     */
    fun expand(start: String): Pair<String, GeoPoint?> {
        var url = start
        repeat(6) {
            pointFromUrl(url)?.let { return url to it }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", Http.USER_AGENT)
                conn.setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
                // Skips Google's cookie consent page in the EU.
                conn.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI")
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    url = URL(URL(url), location).toString()
                    // consent.google.com/…?continue=<the real maps URL>
                    if (url.contains("consent.google.", true)) param(url, "continue")?.let { url = decodeOnce(it) }
                    return@repeat
                }
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(400_000)
                        val n = r.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    return url to pointFromPage(body)
                }
                return url to null
            } finally {
                conn.disconnect()
            }
        }
        return url to pointFromUrl(url)
    }

    /** Coordinates in an HTML page behind a maps link (og:image static map, canonical link, …). */
    fun pointFromPage(html: String): GeoPoint? {
        val text = html.replace("&amp;", "&").replace("\\u003d", "=").replace("\\u0026", "&").replace("%2C", ",")
        Regex("""!3d($LAT)!4d($LON)""").find(text)?.let {
            valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())?.let { p -> return p }
        }
        Regex("""(?:markers|center)=($LAT),\s*($LON)""").find(text)?.let {
            valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())?.let { p -> return p }
        }
        Regex("""https://[^"'\s]*(?:google|apple|openstreetmap)[^"'\s]*""").findAll(text).forEach { m ->
            pointFromUrl(m.value)?.let { return it }
        }
        return null
    }

    sealed class Result {
        data class Found(val place: Place) : Result()
        /** Nothing exact found; the text should go into the search field. */
        data class Search(val query: String) : Result()
        object None : Result()
    }

    /** Full resolution including network access (short links, reverse geocoding, search). Blocking. */
    fun resolve(text: String, near: GeoPoint?, tomtomKey: String): Result {
        val parsed = parse(text)
        var point = parsed.point
        var urlName: String? = null
        if (point == null) {
            for (link in parsed.links.filter { it.startsWith("http", true) }) {
                val (finalUrl, p) = runCatching { expand(link) }.getOrNull() ?: continue
                urlName = nameFromUrl(finalUrl)
                if (p != null) {
                    point = p
                    break
                }
            }
        }
        if (point != null) {
            val name = parsed.name ?: urlName
            if (name != null) {
                val coords = "%.5f, %.5f".format(java.util.Locale.US, point.lat, point.lon)
                return Result.Found(Place(name, parsed.detail ?: coords, point))
            }
            val rev = app.voidmaps.data.Search.reverse(point)
            return Result.Found(rev ?: Place("Geteilter Ort", "%.5f, %.5f".format(java.util.Locale.US, point.lat, point.lon), point))
        }
        val query = parsed.query ?: urlName ?: return Result.None
        val hit = runCatching { app.voidmaps.data.Search.search(query, near, tomtomKey) }.getOrNull()?.firstOrNull()
            // Name and address together may be too much for the search; try the address alone.
            ?: parsed.detail?.let { d -> runCatching { app.voidmaps.data.Search.search(d, near, tomtomKey) }.getOrNull()?.firstOrNull() }
        return if (hit != null) {
            Result.Found(if (parsed.name != null && parsed.detail != null) Place(parsed.name, parsed.detail, hit.point) else hit)
        } else {
            Result.Search(query)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun valid(lat: Double, lon: Double): GeoPoint? {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return GeoPoint(lat, lon)
    }

    private fun decodeOnce(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** Fully decodes (links are sometimes encoded twice inside redirect parameters). */
    private fun decode(s: String): String {
        var cur = s
        repeat(3) {
            val next = decodeOnce(cur)
            if (next == cur) return cur
            cur = next
        }
        return cur
    }

    private fun param(url: String, key: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        val fragment = url.substringAfter('#', "")
        for (part in (query.split('&') + fragment.split('&'))) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            if (part.substring(0, i).equals(key, true)) return part.substring(i + 1)
        }
        return null
    }
}
