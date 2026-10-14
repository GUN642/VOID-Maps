package app.voidmaps.data

import org.json.JSONArray
import org.json.JSONObject

/** Charging networks the user can show on the map. */
enum class ChargeOperator(val label: String, val pattern: Regex, val color: String, val letter: String) {
    TESLA("Tesla", Regex("tesla", RegexOption.IGNORE_CASE), "#E82127", "T"),
    ENBW("EnBW", Regex("enbw", RegexOption.IGNORE_CASE), "#0A5EB0", "E"),
    IONITY("Ionity", Regex("ionity", RegexOption.IGNORE_CASE), "#5A2D91", "I"),
    EWE_GO("EWE Go", Regex("ewe[ _-]?go", RegexOption.IGNORE_CASE), "#F28C00", "G"),
    ;

    val icon: String get() = "ch-${name.lowercase()}"
}

/** A point of interest from OpenStreetMap (charging station, petrol station, …). */
data class Poi(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val detail: String,
    val operator: ChargeOperator? = null,
) {
    fun toPlace() = Place(name, detail, point)
}

/**
 * Categories for the "Sonderziele" search: OSM filters for Overpass and the TomTom category
 * ([tomtom] = categorySet ids, otherwise the label is used as search text).
 */
enum class PoiCategory(val label: String, val filters: List<String>, val tomtom: String? = null) {
    FUEL("Tankstelle", listOf("[\"amenity\"=\"fuel\"]"), "7311"),
    CHARGING("Ladestation", listOf("[\"amenity\"=\"charging_station\"]"), "7309"),
    PARKING("Parkplatz", listOf("[\"amenity\"=\"parking\"][\"access\"!~\"private|no\"]"), "7369,7313"),
    REST("Rastplatz", listOf("[\"highway\"=\"services\"]", "[\"highway\"=\"rest_area\"]"), "7395"),
    SUPERMARKET("Supermarkt", listOf("[\"shop\"=\"supermarket\"]")),
    RESTAURANT("Restaurant", listOf("[\"amenity\"=\"restaurant\"]"), "7315"),
    FASTFOOD("Fast Food", listOf("[\"amenity\"=\"fast_food\"]")),
    CAFE("Café", listOf("[\"amenity\"=\"cafe\"]"), "9376"),
    PHARMACY("Apotheke", listOf("[\"amenity\"=\"pharmacy\"]"), "7326"),
    HOSPITAL("Krankenhaus", listOf("[\"amenity\"=\"hospital\"]"), "7321"),
    HOTEL("Hotel", listOf("[\"tourism\"~\"hotel|motel\"]"), "7314"),
    WORKSHOP("Werkstatt", listOf("[\"shop\"=\"car_repair\"]"), "7310"),
    TOILETS("Toiletten", listOf("[\"amenity\"=\"toilets\"]")),
    ATM("Geldautomat", listOf("[\"amenity\"=\"atm\"]"), "7397"),
}

object Pois {
    private fun query(q: String): JSONObject = Overpass.query(q)

    /** Charging stations of the selected networks inside the boxes. */
    fun chargers(boxes: List<BBox>, operators: Set<ChargeOperator>): List<Poi> {
        if (boxes.isEmpty() || operators.isEmpty()) return emptyList()
        val re = "tesla|enbw|ionity|ewe[ _-]?go"
        val q = StringBuilder("[out:json][timeout:25];(")
        for (b in boxes) {
            val bb = "(${b.minLat},${b.minLon},${b.maxLat},${b.maxLon})"
            for (key in listOf("operator", "brand", "network", "name")) {
                q.append("nwr[\"amenity\"=\"charging_station\"][\"$key\"~\"$re\",i]$bb;")
            }
        }
        q.append(");out center tags;")
        val arr = query(q.toString()).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Poi>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val point = point(e) ?: continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val text = listOf("operator", "brand", "network", "name").joinToString(" ") { tags.optString(it) }
            val op = ChargeOperator.entries.firstOrNull { it.pattern.containsMatchIn(text) } ?: continue
            if (op !in operators) continue
            val kw = maxPower(tags)
            val capacity = tags.optString("capacity").toIntOrNull()
            val detail = listOfNotNull(
                kw?.let { "bis $it kW" },
                capacity?.let { "$it Ladepunkte" },
                tags.optString("opening_hours").takeIf { it == "24/7" }?.let { "rund um die Uhr" },
            ).joinToString(" · ").ifBlank { "Ladestation" }
            out += Poi(
                id = "${e.optString("type")}/${e.optLong("id")}",
                name = tags.optString("name").ifBlank { "${op.label} Ladestation" },
                point = point,
                detail = detail,
                operator = op,
            )
        }
        return out.distinctBy { it.id }
    }

    /**
     * Places of a category: with a TomTom key through the fast TomTom search (around [center], or
     * along [path] when navigating), otherwise – or when TomTom finds nothing – through Overpass.
     */
    fun category(category: PoiCategory, center: GeoPoint, radius: Int, path: List<GeoPoint>?, tomtomKey: String): List<Poi> {
        if (tomtomKey.isNotBlank()) {
            val tt = runCatching {
                if (path != null && path.size >= 2) tomtomAlongRoute(category, path, tomtomKey) else tomtomNearby(category, center, radius, tomtomKey)
            }.getOrNull()
            if (!tt.isNullOrEmpty()) return tt
        }
        return overpassCategory(category, center, minOf(radius, 5_000), path)
    }

    private fun tomtomNearby(category: PoiCategory, center: GeoPoint, radius: Int, key: String): List<Poi> {
        val common = "key=${Http.enc(key)}&lat=${center.lat}&lon=${center.lon}&radius=$radius&limit=40&language=de-DE"
        val url = if (category.tomtom != null) {
            "https://api.tomtom.com/search/2/nearbySearch/.json?$common&categorySet=${category.tomtom}"
        } else {
            "https://api.tomtom.com/search/2/categorySearch/${Http.enc(category.label).replace("+", "%20")}.json?$common"
        }
        return parseTomTom(Http.get(url, timeoutMs = 6_000), category).sortedBy { Geo.distance(center, it.point) }
    }

    private fun tomtomAlongRoute(category: PoiCategory, path: List<GeoPoint>, key: String): List<Poi> {
        var url = "https://api.tomtom.com/search/2/searchAlongRoute/${Http.enc(category.label).replace("+", "%20")}.json" +
            "?key=${Http.enc(key)}&maxDetourTime=600&limit=20&language=de-DE&sortBy=detourTime"
        if (category.tomtom != null) url += "&categorySet=${category.tomtom}"
        val pts = JSONArray()
        path.take(150).forEach { pts.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        val body = JSONObject().put("route", JSONObject().put("points", pts)).toString()
        return parseTomTom(Http.post(url, body, "application/json", 8_000), category)
    }

    private fun parseTomTom(json: String, category: PoiCategory): List<Poi> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Poi>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val pos = r.optJSONObject("position") ?: continue
            val poi = r.optJSONObject("poi")
            val addr = r.optJSONObject("address")
            val name = poi?.optString("name")?.takeIf { it.isNotBlank() } ?: category.label
            val detour = r.optInt("detourTime", -1).takeIf { it >= 0 }?.let { "+${(it + 30) / 60} min Umweg" }
            val detail = listOfNotNull(addr?.optString("freeformAddress")?.takeIf { it.isNotBlank() }, detour).joinToString(" · ")
            out += Poi("tt:" + r.optString("id", "$i"), name, GeoPoint(pos.getDouble("lat"), pos.getDouble("lon")), detail.ifBlank { category.label })
        }
        return out
    }

    private fun overpassCategory(category: PoiCategory, center: GeoPoint, radius: Int, path: List<GeoPoint>?): List<Poi> {
        val area = if (path != null && path.size >= 2) {
            "(around:800," + path.joinToString(",") { "${"%.5f".format(java.util.Locale.US, it.lat)},${"%.5f".format(java.util.Locale.US, it.lon)}" } + ")"
        } else {
            "(around:$radius,${center.lat},${center.lon})"
        }
        val q = StringBuilder("[out:json][timeout:12];(")
        for (f in category.filters) {
            q.append("node$f$area;")
            q.append("way$f$area;")
        }
        q.append(");out center tags 60;")
        val arr = query(q.toString()).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Poi>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val point = point(e) ?: continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val name = tags.optString("name").ifBlank { tags.optString("brand").ifBlank { tags.optString("operator").ifBlank { category.label } } }
            val street = listOf(tags.optString("addr:street"), tags.optString("addr:housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val city = tags.optString("addr:city")
            val detail = listOf(
                if (name != tags.optString("brand") && tags.optString("brand").isNotBlank()) tags.optString("brand") else "",
                street, city,
                if (category == PoiCategory.CHARGING) maxPower(tags)?.let { "bis $it kW" }.orEmpty() else "",
            ).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { category.label }
            out += Poi("${e.optString("type")}/${e.optLong("id")}", name, point, detail)
        }
        return out.distinctBy { it.id }.sortedBy { Geo.distance(center, it.point) }.take(40)
    }

    /** Traffic lights (highway=traffic_signals) inside [box]. */
    fun trafficSignals(box: BBox): List<GeoPoint> {
        val q = "[out:json][timeout:20];node[\"highway\"=\"traffic_signals\"](${box.minLat},${box.minLon},${box.maxLat},${box.maxLon});out skel;"
        val arr = query(q).optJSONArray("elements") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> point(arr.getJSONObject(i)) }
    }

    private fun point(e: JSONObject): GeoPoint? {
        if (e.has("lat")) return GeoPoint(e.getDouble("lat"), e.getDouble("lon"))
        val c = e.optJSONObject("center") ?: return null
        return GeoPoint(c.getDouble("lat"), c.getDouble("lon"))
    }

    /** Highest charging power from socket:*:output / charging_station:output tags, in kW. */
    private fun maxPower(tags: JSONObject): Int? {
        var best = 0.0
        val keys = tags.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.endsWith(":output") && k != "maxpower") continue
            val v = tags.optString(k).lowercase()
            val num = Regex("""(\d+(?:[.,]\d+)?)""").find(v)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: continue
            val kw = if ("mw" in v) num * 1000 else if ("kw" in v || num < 1000) num else num / 1000
            if (kw > best) best = kw
        }
        return if (best > 0) best.toInt() else null
    }
}
