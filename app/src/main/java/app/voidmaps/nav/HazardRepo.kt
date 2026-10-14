package app.voidmaps.nav

import app.voidmaps.data.BBox
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Hazard
import app.voidmaps.data.Incidents
import app.voidmaps.data.SpeedCameras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Traffic incidents (always available with a TomTom key) and speed cameras (only while the switch is on).
 * Keeps what was loaded for the map view and for the route.
 */
object HazardRepo {
    private val _incidents = MutableStateFlow<List<Hazard>>(emptyList())
    val incidents: StateFlow<List<Hazard>> = _incidents.asStateFlow()

    private val _cameras = MutableStateFlow<List<Hazard>>(emptyList())
    val cameras: StateFlow<List<Hazard>> = _cameras.asStateFlow()

    private var lastViewFetch = 0L
    private var lastViewCenter: GeoPoint? = null
    private var lastCameraFetch = 0L
    private var lastCameraCenter: GeoPoint? = null

    suspend fun loadIncidents(key: String, boxes: List<BBox>, replace: Boolean) {
        if (key.isBlank() || boxes.isEmpty()) return
        val list = withContext(Dispatchers.IO) { runCatching { Incidents.fetch(key, boxes) }.getOrNull() } ?: return
        _incidents.value = if (replace) list else (list + _incidents.value).distinctBy { it.id }.take(800)
    }

    suspend fun loadCameras(boxes: List<BBox>, replace: Boolean) {
        if (boxes.isEmpty()) return
        val list = withContext(Dispatchers.IO) { runCatching { SpeedCameras.fetch(boxes) }.getOrNull() } ?: return
        _cameras.value = if (replace) list else (list + _cameras.value).distinctBy { it.id }.take(3000)
    }

    /** Loads hazards around the visible map area, throttled so panning does not burn the API quota. */
    suspend fun loadForView(box: BBox, center: GeoPoint, key: String, cameras: Boolean) {
        val now = System.currentTimeMillis()
        val clamped = if (box.areaKm2 > 9_000) BBox.around(center, 45_000.0) else box
        val last = lastViewCenter
        if (key.isNotBlank() && (last == null || now - lastViewFetch > 120_000 ||
                app.voidmaps.data.Geo.distance(last, center) > 8_000)
        ) {
            lastViewFetch = now
            lastViewCenter = center
            loadIncidents(key, listOf(clamped), replace = false)
        }
        val lastCam = lastCameraCenter
        if (cameras && (lastCam == null || now - lastCameraFetch > 600_000 ||
                app.voidmaps.data.Geo.distance(lastCam, center) > 8_000)
        ) {
            lastCameraFetch = now
            lastCameraCenter = center
            loadCameras(listOf(clamped), replace = false)
        }
    }

    fun clearCameras() {
        _cameras.value = emptyList()
        lastCameraCenter = null
    }

    fun clearIncidents() {
        _incidents.value = emptyList()
        lastViewCenter = null
    }
}
