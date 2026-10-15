package app.voidmaps.data

import org.json.JSONArray
import org.json.JSONObject

enum class HazardType(val label: String, val icon: String, val spoken: String, val isCamera: Boolean = false) {
    ACCIDENT("Unfall", "hz-accident", "ein Unfall"),
    JAM("Stauende", "hz-jam", "ein Stauende"),
    ROADWORKS("Baustelle", "hz-works", "eine Baustelle"),
    CLOSURE("Sperrung", "hz-closed", "eine Sperrung"),
    LANE_CLOSED("Fahrstreifen gesperrt", "hz-lane", "eine Fahrstreifensperrung"),
    DANGER("Gefahrenstelle", "hz-danger", "eine Gefahrenstelle"),
    WEATHER("Wetter", "hz-weather", "schlechtes Wetter"),
    BROKEN_DOWN("Pannenfahrzeug", "hz-danger", "ein Pannenfahrzeug"),
    SPEED_CAMERA("Blitzer", "hz-camera", "ein Blitzer", isCamera = true),
    MOBILE_CAMERA("Mobiler Blitzer", "hz-camera-mobile", "ein mobiler Blitzer", isCamera = true),
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

/** Overpass with fallback instances – the main server is often busy and then answers with errors. */
object Overpass {
    private val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    fun query(q: String, timeoutMs: Int = 30_000): JSONObject {
        var last: Exception? = null
        for (server in SERVERS) {
            try {
                return JSONObject(Http.post(server, "data=" + Http.enc(q), "application/x-www-form-urlencoded", timeoutMs))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("Overpass nicht erreichbar")
    }
}

/** Speed cameras: fixed ones from OpenStreetMap and fixed + mobile ones from Blitzer.de. */
object SpeedCameras {
    /** Fixed cameras mapped in OpenStreetMap (highway=speed_camera and enforcement relations). */
    fun osm(boxes: List<BBox>): List<Hazard> {
        if (boxes.isEmpty()) return emptyList()
        val q = StringBuilder("[out:json][timeout:25];(")
        for (b in boxes) {
            val bb = "(${b.minLat},${b.minLon},${b.maxLat},${b.maxLon})"
            q.append("node[\"highway\"=\"speed_camera\"]$bb;")
            q.append("node[\"enforcement\"=\"maxspeed\"]$bb;")
        }
        q.append(");out body;")
        val arr = Overpass.query(q.toString()).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Hazard>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (!e.has("lat")) continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val max = tags.optString("maxspeed").filter { it.isDigit() }.toIntOrNull()
            out += Hazard(
                id = "osm:" + e.optLong("id"),
                type = HazardType.SPEED_CAMERA,
                point = GeoPoint(e.getDouble("lat"), e.getDouble("lon")),
                bearing = null,
                description = if (max != null) "Blitzer · $max km/h" else "Blitzer",
                maxSpeed = max,
            )
        }
        return out
    }

    /** Type codes of Blitzer.de: 0–7 and "ts" are mobile controls (tripod, car, trailer), 101+ fixed ones. */
    private val MOBILE = listOf("ts", "0", "1", "2", "3", "4", "5", "6", "7", "11", "12")
    private val FIXED = listOf("101", "102", "103", "104", "105", "106", "107", "108", "109", "110", "111", "112", "113", "114", "115", "117")
    private val LABELS = mapOf(
        "1" to "Geschwindigkeitskontrolle", "2" to "Ampelblitzer", "3" to "Gewichtskontrolle",
        "4" to "Verkehrskontrolle", "5" to "Alkoholkontrolle", "6" to "Abstandskontrolle",
        "7" to "Geschwindigkeitskontrolle", "11" to "Ampelblitzer", "12" to "Section Control", "ts" to "Blitzeranhänger",
        "101" to "Abstandskontrolle", "102" to "Attrappe", "103" to "Auffahrtskontrolle", "104" to "Spurkontrolle",
        "105" to "Einfahrtskontrolle", "106" to "Fußgängerüberweg", "107" to "Blitzer", "108" to "Gewichtskontrolle",
        "109" to "Höhenkontrolle", "110" to "Ampel- & Geschwindigkeitsblitzer", "111" to "Ampelblitzer",
        "112" to "Abschnittskontrolle", "113" to "Abschnittskontrolle Ende", "114" to "Blitzer im Tunnel",
        "115" to "Überholverbot", "117" to "Polizeikontrolle",
    )

    /**
     * Blitzer.de (unofficial interface of the public map, private use only). One request per box;
     * the server sends at most ~500 entries, so boxes should stay around 20 × 20 km.
     */
    /** [mobile] = only mobile controls, otherwise only fixed cameras (separate requests: the answer is capped). */
    fun blitzerDe(box: BBox, mobile: Boolean): List<Hazard> {
        val types = if (mobile) MOBILE else FIXED
        val url = "https://www.blitzer.de/karte/api/markers?type=${types.joinToString(",")}" +
            "&box=${box.minLat},${box.minLon},${box.maxLat},${box.maxLon}&z=18"
        val pois = JSONObject(Http.get(url, timeoutMs = 12_000)).optJSONArray("pois") ?: return emptyList() // null when empty
        val out = ArrayList<Hazard>()
        for (i in 0 until pois.length()) {
            val p = pois.getJSONObject(i)
            val type = p.optString("type")
            if (type == "cluster" || (type.toIntOrNull() ?: 0) >= 1000) continue
            val lat = p.optString("lat").toDoubleOrNull() ?: continue
            val lng = p.optString("lng").toDoubleOrNull() ?: continue
            val isMobile = type in MOBILE
            val vmax = p.optString("vmax").toIntOrNull()?.takeIf { it > 0 }
            val street = p.optJSONObject("address")?.optString("street").orEmpty()
            val label = LABELS[type] ?: "Blitzer"
            out += Hazard(
                id = "bz:" + p.optString("backend").ifBlank { "$lat,$lng" },
                type = if (isMobile) HazardType.MOBILE_CAMERA else HazardType.SPEED_CAMERA,
                point = GeoPoint(lat, lng),
                bearing = null,
                description = listOfNotNull(
                    (if (isMobile) "Mobil: " else "") + label + (vmax?.let { " · $it km/h" } ?: ""),
                    street.takeIf { it.isNotBlank() },
                    "Quelle: Blitzer.de",
                ).joinToString("\n"),
                maxSpeed = vmax,
            )
        }
        return out
    }
}
