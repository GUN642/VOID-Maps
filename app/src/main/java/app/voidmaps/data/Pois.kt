package app.voidmaps.data

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

/** Categories for the "Sonderziele" search. */
enum class PoiCategory(val label: String, val filters: List<String>) {
    FUEL("Tankstelle", listOf("[\"amenity\"=\"fuel\"]")),
    CHARGING("Ladestation", listOf("[\"amenity\"=\"charging_station\"]")),
    PARKING("Parkplatz", listOf("[\"amenity\"=\"parking\"][\"access\"!~\"private|no\"]")),
    REST("Rastplatz", listOf("[\"highway\"=\"services\"]", "[\"highway\"=\"rest_area\"]")),
    SUPERMARKET("Supermarkt", listOf("[\"shop\"=\"supermarket\"]")),
    RESTAURANT("Restaurant", listOf("[\"amenity\"=\"restaurant\"]")),
    FASTFOOD("Fast Food", listOf("[\"amenity\"=\"fast_food\"]")),
    CAFE("Café", listOf("[\"amenity\"=\"cafe\"]")),
    PHARMACY("Apotheke", listOf("[\"amenity\"=\"pharmacy\"]")),
    HOSPITAL("Krankenhaus", listOf("[\"amenity\"=\"hospital\"]")),
    HOTEL("Hotel", listOf("[\"tourism\"~\"hotel|motel\"]")),
    WORKSHOP("Werkstatt", listOf("[\"shop\"=\"car_repair\"]")),
    TOILETS("Toiletten", listOf("[\"amenity\"=\"toilets\"]")),
    ATM("Geldautomat", listOf("[\"amenity\"=\"atm\"]")),
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
     * Places of a category around [center] (radius in metres), or along [path] when navigating.
     * Results are sorted by distance from [center].
     */
    fun category(category: PoiCategory, center: GeoPoint, radius: Int, path: List<GeoPoint>?): List<Poi> {
        val area = if (path != null && path.size >= 2) {
            "(around:800," + path.joinToString(",") { "${"%.5f".format(java.util.Locale.US, it.lat)},${"%.5f".format(java.util.Locale.US, it.lon)}" } + ")"
        } else {
            "(around:$radius,${center.lat},${center.lon})"
        }
        val q = StringBuilder("[out:json][timeout:25];(")
        for (f in category.filters) q.append("nwr$f$area;")
        q.append(");out center tags 80;")
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
