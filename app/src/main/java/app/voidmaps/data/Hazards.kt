package app.voidmaps.data

import org.json.JSONArray
import org.json.JSONObject

enum class HazardType(val label: String, val icon: String, val spoken: String) {
    ACCIDENT("Unfall", "hz-accident", "ein Unfall"),
    JAM("Stauende", "hz-jam", "ein Stauende"),
    ROADWORKS("Baustelle", "hz-works", "eine Baustelle"),
    CLOSURE("Sperrung", "hz-closed", "eine Sperrung"),
    LANE_CLOSED("Fahrstreifen gesperrt", "hz-lane", "eine Fahrstreifensperrung"),
    DANGER("Gefahrenstelle", "hz-danger", "eine Gefahrenstelle"),
    WEATHER("Wetter", "hz-weather", "schlechtes Wetter"),
    BROKEN_DOWN("Pannenfahrzeug", "hz-danger", "ein Pannenfahrzeug"),
    SPEED_CAMERA("Blitzer", "hz-camera", "ein Blitzer"),
}

data class Hazard(
    val id: String,
    val type: HazardType,
    val point: GeoPoint,
    /** Direction of travel the hazard applies to, if known. */
    val bearing: Double?,
    val description: String,
    /** Extra seconds of delay (traffic incidents). */
    val delay: Int = 0,
    /** Speed limit at a speed camera, if mapped. */
    val maxSpeed: Int? = null,
    /** Full geometry of a traffic incident, for drawing it on the map. */
    val line: List<GeoPoint> = emptyList(),
)

/** TomTom Traffic Incidents API (accidents, jams, road works, closures). */
object Incidents {
    private val FIELDS = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay," +
        "events{description,code,iconCategory},from,to,length,delay,roadNumbers}}}"

    fun fetch(key: String, boxes: List<BBox>): List<Hazard> {
        val out = LinkedHashMap<String, Hazard>()
        for (b in boxes) {
            val url = "https://api.tomtom.com/traffic/services/5/incidentDetails?key=${Http.enc(key)}" +
                "&bbox=${b.minLon},${b.minLat},${b.maxLon},${b.maxLat}" +
                "&fields=${Http.enc(FIELDS)}&language=de-DE&timeValidityFilter=present"
            val json = JSONObject(Http.get(url))
            val arr = json.optJSONArray("incidents") ?: continue
            for (i in 0 until arr.length()) parse(arr.getJSONObject(i))?.let { out[it.id] = it }
        }
        return out.values.toList()
    }

    private fun parse(o: JSONObject): Hazard? {
        val props = o.optJSONObject("properties") ?: return null
        val geom = o.optJSONObject("geometry") ?: return null
        val coords = geom.optJSONArray("coordinates") ?: return null
        val line = ArrayList<GeoPoint>()
        if (geom.optString("type") == "Point") {
            line += GeoPoint(coords.getDouble(1), coords.getDouble(0))
        } else {
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                line += GeoPoint(c.getDouble(1), c.getDouble(0))
            }
        }
        if (line.isEmpty()) return null
        val type = when (props.optInt("iconCategory")) {
            1 -> HazardType.ACCIDENT
            6 -> HazardType.JAM
            9 -> HazardType.ROADWORKS
            8 -> HazardType.CLOSURE
            7 -> HazardType.LANE_CLOSED
            2, 4, 5, 10, 11 -> HazardType.WEATHER
            14 -> HazardType.BROKEN_DOWN
            else -> HazardType.DANGER
        }
        val events = props.optJSONArray("events") ?: JSONArray()
        val desc = (0 until events.length()).map { events.getJSONObject(it).optString("description") }
            .filter { it.isNotBlank() }.distinct().joinToString(" · ")
        val roads = props.optJSONArray("roadNumbers")?.let { r -> (0 until r.length()).joinToString(", ") { r.getString(it) } }.orEmpty()
        val from = props.optString("from")
        val to = props.optString("to")
        val where = listOf(roads, if (from.isNotBlank() && to.isNotBlank()) "$from → $to" else "").filter { it.isNotBlank() }.joinToString(" · ")
        return Hazard(
            id = "tt:" + props.optString("id", line.first().toString()),
            type = type,
            // TomTom geometries run in driving direction, so the first point is where you reach it (e.g. the end of a jam).
            point = line.first(),
            bearing = if (line.size >= 2) Geo.bearing(line[0], line[1]) else null,
            description = listOf(desc.ifBlank { type.label }, where).filter { it.isNotBlank() }.joinToString("\n"),
            delay = props.optInt("delay", 0),
            line = line,
        )
    }
}

/** Fixed speed cameras mapped in OpenStreetMap (highway=speed_camera), via Overpass. */
object SpeedCameras {
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"

    fun fetch(boxes: List<BBox>): List<Hazard> {
        if (boxes.isEmpty()) return emptyList()
        val q = StringBuilder("[out:json][timeout:25];(")
        for (b in boxes) q.append("node[\"highway\"=\"speed_camera\"](${b.minLat},${b.minLon},${b.maxLat},${b.maxLon});")
        q.append(");out body;")
        val json = JSONObject(Http.post(OVERPASS, "data=" + Http.enc(q.toString()), "application/x-www-form-urlencoded", 30_000))
        val arr = json.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Hazard>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val max = tags.optString("maxspeed").filter { it.isDigit() }.toIntOrNull()
            val dir = tags.optString("direction").toDoubleOrNull()
            out += Hazard(
                id = "osm:" + e.optLong("id"),
                type = HazardType.SPEED_CAMERA,
                point = GeoPoint(e.getDouble("lat"), e.getDouble("lon")),
                bearing = dir,
                description = if (max != null) "Blitzer · $max km/h" else "Blitzer",
                maxSpeed = max,
            )
        }
        return out
    }
}
