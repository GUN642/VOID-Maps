package app.voidmaps.data

import org.json.JSONObject

data class Place(val name: String, val detail: String, val point: GeoPoint) {
    fun encode() = listOf(name, detail, point.lat.toString(), point.lon.toString()).joinToString(SEP)

    companion object {
        private const val SEP = "\u0001"

        fun decode(s: String): Place? {
            val p = s.split(SEP)
            if (p.size != 4) return null
            val lat = p[2].toDoubleOrNull() ?: return null
            val lon = p[3].toDoubleOrNull() ?: return null
            return Place(p[0], p[1], GeoPoint(lat, lon))
        }
    }
}

/**
 * Address and place search. With a TomTom key the fast TomTom fuzzy search is used (addresses and POIs),
 * otherwise Photon (OpenStreetMap data, no key).
 */
object Search {
    private const val BASE = "https://photon.komoot.io"

    fun search(query: String, near: GeoPoint?, tomtomKey: String = ""): List<Place> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        coordinates(q)?.let { return listOf(Place("Koordinaten", "%.5f, %.5f".format(java.util.Locale.US, it.lat, it.lon), it)) }
        if (tomtomKey.isNotBlank()) {
            runCatching { return tomtom(q, near, tomtomKey) }
        }
        var url = "$BASE/api/?q=${Http.enc(q)}&lang=de&limit=10"
        if (near != null) url += "&lat=${near.lat}&lon=${near.lon}&location_bias_scale=0.4"
        return parse(Http.get(url, timeoutMs = 8_000))
    }

    private fun tomtom(q: String, near: GeoPoint?, key: String): List<Place> {
        var url = "https://api.tomtom.com/search/2/search/${Http.enc(q).replace("+", "%20")}.json" +
            "?key=${Http.enc(key)}&language=de-DE&limit=10&typeahead=true"
        if (near != null) url += "&lat=${near.lat}&lon=${near.lon}"
        val results = JSONObject(Http.get(url, timeoutMs = 6_000)).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val pos = r.optJSONObject("position") ?: continue
            val addr = r.optJSONObject("address") ?: JSONObject()
            val poi = r.optJSONObject("poi")
            val street = listOf(addr.optString("streetName"), addr.optString("streetNumber")).filter { it.isNotBlank() }.joinToString(" ")
            val city = addr.optString("municipality")
            val title = poi?.optString("name")?.takeIf { it.isNotBlank() }
                ?: street.ifBlank { city.ifBlank { addr.optString("freeformAddress") } }
            if (title.isBlank()) continue
            val detail = listOf(
                if (poi != null) street else "",
                listOf(addr.optString("postalCode"), city).filter { it.isNotBlank() }.joinToString(" "),
                addr.optString("country").takeIf { it.isNotBlank() && addr.optString("countryCode") != "DE" }.orEmpty(),
            ).filter { it.isNotBlank() && it != title }.joinToString(", ")
            val place = Place(title, detail, GeoPoint(pos.getDouble("lat"), pos.getDouble("lon")))
            if (out.none { it.name == place.name && it.detail == place.detail }) out += place
        }
        return out
    }

    fun reverse(p: GeoPoint): Place? = runCatching {
        parse(Http.get("$BASE/reverse?lat=${p.lat}&lon=${p.lon}&lang=de&limit=1", timeoutMs = 6_000)).firstOrNull()?.copy(point = p)
    }.getOrNull()

    /** Accepts "52.52, 13.40" style input. */
    fun coordinates(q: String): GeoPoint? {
        val m = Regex("""^\s*(-?\d{1,2}(?:[.,]\d+)?)\s*[,; ]\s*(-?\d{1,3}(?:[.,]\d+)?)\s*$""").find(q) ?: return null
        val lat = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        // "52,5 13,4" would be ambiguous with a comma separator; only plain decimals reach here.
        return GeoPoint(lat, lon)
    }

    private fun parse(json: String): List<Place> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val p = f.optJSONObject("properties") ?: continue
            val point = GeoPoint(c.getDouble(1), c.getDouble(0))
            val street = p.optString("street")
            val number = p.optString("housenumber")
            val name = p.optString("name")
            val city = p.optString("city").ifBlank { p.optString("locality").ifBlank { p.optString("district") } }
            val postcode = p.optString("postcode")
            val streetLine = listOf(street, number).filter { it.isNotBlank() }.joinToString(" ")
            val title = when {
                name.isNotBlank() -> name
                streetLine.isNotBlank() -> streetLine
                city.isNotBlank() -> city
                else -> continue
            }
            val detail = listOf(
                if (name.isNotBlank() && streetLine.isNotBlank()) streetLine else "",
                listOf(postcode, city).filter { it.isNotBlank() }.joinToString(" ").takeIf { it != title }.orEmpty(),
                p.optString("country").takeIf { it.isNotBlank() && it != "Deutschland" }.orEmpty(),
            ).filter { it.isNotBlank() }.joinToString(", ")
            val place = Place(title, detail, point)
            if (out.none { it.name == place.name && it.detail == place.detail }) out += place
        }
        return out
    }
}
