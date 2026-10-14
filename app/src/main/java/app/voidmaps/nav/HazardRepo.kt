package app.voidmaps.nav

import app.voidmaps.data.BBox
import app.voidmaps.data.ChargeOperator
import app.voidmaps.data.Geo
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Hazard
import app.voidmaps.data.Incidents
import app.voidmaps.data.Poi
import app.voidmaps.data.Pois
import app.voidmaps.data.SpeedCameras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.floor

/**
 * Traffic incidents (with a TomTom key), speed cameras (only while the switch is on), charging
 * stations and traffic lights.
 *
 * Cameras and chargers are loaded in grid cells of about 20 × 20 km: every cell that is visible or
 * touched by the route is fetched once and kept for a while, so panning or zooming never leaves
 * gaps and nothing is fetched twice.
 */
object HazardRepo {
    private val _incidents = MutableStateFlow<List<Hazard>>(emptyList())
    val incidents: StateFlow<List<Hazard>> = _incidents.asStateFlow()

    private val _cameras = MutableStateFlow<List<Hazard>>(emptyList())
    val cameras: StateFlow<List<Hazard>> = _cameras.asStateFlow()

    private val _chargers = MutableStateFlow<List<Poi>>(emptyList())
    val chargers: StateFlow<List<Poi>> = _chargers.asStateFlow()

    private val _signals = MutableStateFlow<List<GeoPoint>>(emptyList())
    val signals: StateFlow<List<GeoPoint>> = _signals.asStateFlow()
    private val signalAreas = ArrayList<BBox>()

    private var lastViewFetch = 0L
    private var lastViewCenter: GeoPoint? = null

    // ------------------------------------------------------------------ grid cells

    data class Cell(val x: Int, val y: Int) {
        fun box() = BBox(y * LAT_STEP, x * LON_STEP, (y + 1) * LAT_STEP, (x + 1) * LON_STEP)
    }

    private const val LAT_STEP = 0.18
    private const val LON_STEP = 0.28

    private fun cellOf(p: GeoPoint) = Cell(floor(p.lon / LON_STEP).toInt(), floor(p.lat / LAT_STEP).toInt())

    fun cellsFor(box: BBox, max: Int = 30): List<Cell> {
        val a = cellOf(GeoPoint(box.minLat, box.minLon))
        val b = cellOf(GeoPoint(box.maxLat, box.maxLon))
        val out = ArrayList<Cell>()
        for (y in a.y..b.y) for (x in a.x..b.x) out += Cell(x, y)
        // Too large (zoomed far out): only the cells around the centre.
        if (out.size <= max) return out
        val c = cellOf(GeoPoint((box.minLat + box.maxLat) / 2, (box.minLon + box.maxLon) / 2))
        return out.sortedBy { (it.x - c.x) * (it.x - c.x) + (it.y - c.y) * (it.y - c.y) }.take(max)
    }

    /** Cells along a path plus their neighbours across it (the corridor of a route). */
    fun cellsFor(points: List<GeoPoint>, max: Int = 120): List<Cell> {
        val out = LinkedHashSet<Cell>()
        var last: GeoPoint? = null
        for (p in points) {
            val l = last
            if (l != null && Geo.distance(l, p) < 1500) continue
            last = p
            val c = cellOf(p)
            out += c
            // Points close to a cell border also need the neighbour.
            val fx = p.lon / LON_STEP - c.x
            val fy = p.lat / LAT_STEP - c.y
            if (fx < 0.1) out += Cell(c.x - 1, c.y)
            if (fx > 0.9) out += Cell(c.x + 1, c.y)
            if (fy < 0.1) out += Cell(c.x, c.y - 1)
            if (fy > 0.9) out += Cell(c.x, c.y + 1)
            if (out.size >= max) break
        }
        return out.toList()
    }

    private class CellCache(val ttlMs: Long) {
        val loaded = HashMap<Cell, Long>()
        val mutex = Mutex()
        fun missing(cells: List<Cell>, now: Long) = cells.filter { (loaded[it] ?: 0L) + ttlMs < now }
    }

    private val cameraCells = CellCache(20 * 60_000L)
    private val chargerCells = CellCache(24 * 3_600_000L)

    // ------------------------------------------------------------------ cameras

    /** Makes sure the speed cameras of [cells] are loaded (OSM always, Blitzer.de when enabled). */
    suspend fun ensureCameras(cells: List<Cell>, blitzerDe: Boolean, mobile: Boolean) = cameraCells.mutex.withLock {
        val now = System.currentTimeMillis()
        val todo = cameraCells.missing(cells, now)
        if (todo.isEmpty()) return@withLock
        val found = withContext(Dispatchers.IO) {
            coroutineScope {
                val osm = todo.chunked(8).map { group -> async { runCatching { SpeedCameras.osm(group.map { it.box() }) }.getOrNull() } }
                val bz = if (blitzerDe) {
                    todo.map { cell -> async { runCatching { SpeedCameras.blitzerDe(cell.box(), mobile) }.getOrNull() } }
                } else {
                    emptyList()
                }
                val osmLists = osm.awaitAll()
                val bzLists = bz.awaitAll()
                // A cell counts as loaded only when every source answered.
                todo.forEachIndexed { i, cell ->
                    val osmOk = osmLists.getOrNull(i / 8) != null
                    val bzOk = !blitzerDe || bzLists.getOrNull(i) != null
                    if (osmOk && bzOk) cameraCells.loaded[cell] = now
                }
                osmLists.filterNotNull().flatten() + bzLists.filterNotNull().flatten()
            }
        }
        val boxes = todo.map { it.box() }
        // Replace what was known in the refreshed cells (mobile cameras come and go).
        val kept = _cameras.value.filterNot { h -> boxes.any { it.contains(h.point) } }
        _cameras.value = dedupe(kept + found).takeLast(6000)
    }

    /** OSM and Blitzer.de often list the same camera; keep one per spot (Blitzer.de wins, it knows mobile ones). */
    private fun dedupe(list: List<Hazard>): List<Hazard> {
        val out = ArrayList<Hazard>()
        for (h in list.sortedBy { if (it.id.startsWith("bz:")) 0 else 1 }) {
            if (out.none { it.id == h.id || (Geo.distance(it.point, h.point) < 40 && it.type.isCamera && h.type.isCamera) }) out += h
        }
        return out
    }

    fun clearCameras() {
        _cameras.value = emptyList()
        cameraCells.loaded.clear()
    }

    // ------------------------------------------------------------------ chargers

    suspend fun ensureChargers(cells: List<Cell>, operators: Set<ChargeOperator>) = chargerCells.mutex.withLock {
        val now = System.currentTimeMillis()
        val todo = chargerCells.missing(cells, now)
        if (todo.isEmpty() || operators.isEmpty()) return@withLock
        val found = withContext(Dispatchers.IO) {
            coroutineScope {
                todo.chunked(6).map { group ->
                    async {
                        val r = runCatching { Pois.chargers(group.map { it.box() }, ChargeOperator.entries.toSet()) }.getOrNull()
                        if (r != null) group.forEach { chargerCells.loaded[it] = now }
                        r
                    }
                }.awaitAll().filterNotNull().flatten()
            }
        }
        _chargers.value = (found + _chargers.value).distinctBy { it.id }.take(8000)
    }

    fun clearChargers() {
        _chargers.value = emptyList()
        chargerCells.loaded.clear()
    }

    // ------------------------------------------------------------------ incidents

    suspend fun loadIncidents(key: String, boxes: List<BBox>, replace: Boolean) {
        if (key.isBlank() || boxes.isEmpty()) return
        val list = withContext(Dispatchers.IO) { runCatching { Incidents.fetch(key, boxes) }.getOrNull() } ?: return
        _incidents.value = if (replace) list else (list + _incidents.value).distinctBy { it.id }.take(800)
    }

    /** Incidents around the visible map area, throttled so panning does not burn the API quota. */
    suspend fun loadIncidentsForView(box: BBox, center: GeoPoint, key: String) {
        if (key.isBlank()) return
        val now = System.currentTimeMillis()
        val clamped = if (box.areaKm2 > 9_000) BBox.around(center, 45_000.0) else box
        val last = lastViewCenter
        if (last == null || now - lastViewFetch > 120_000 || Geo.distance(last, center) > 8_000) {
            lastViewFetch = now
            lastViewCenter = center
            loadIncidents(key, listOf(clamped), replace = false)
        }
    }

    fun clearIncidents() {
        _incidents.value = emptyList()
        lastViewCenter = null
    }

    // ------------------------------------------------------------------ traffic lights

    /** Traffic lights around [center] (~3 km); areas already loaded are not fetched again. */
    suspend fun loadSignals(center: GeoPoint) {
        if (signalAreas.any { it.expand(-800.0).contains(center) }) return
        val box = BBox.around(center, 3_000.0)
        signalAreas += box
        val list = withContext(Dispatchers.IO) { runCatching { Pois.trafficSignals(box) }.getOrNull() }
        if (list == null) {
            signalAreas.remove(box)
            return
        }
        _signals.value = (_signals.value + list).distinct().takeLast(6000)
        if (signalAreas.size > 40) signalAreas.removeAt(0)
    }

    fun clearSignals() {
        _signals.value = emptyList()
        signalAreas.clear()
    }
}
