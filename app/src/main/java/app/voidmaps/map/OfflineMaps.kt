package app.voidmaps.map

import android.content.Context
import app.voidmaps.data.BBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

data class OfflineArea(
    val id: Long,
    val name: String,
    val bounds: BBox?,
    val bytes: Long,
    val progress: Float,
    val complete: Boolean,
    val downloading: Boolean,
    val error: String? = null,
)

/**
 * Map areas downloaded for use without internet (MapLibre offline regions).
 * Only the map is stored offline – searching and routing still need a connection.
 */
object OfflineMaps {
    /** Tiles of OpenFreeMap go up to zoom 14; closer zoom levels are drawn from those. */
    const val MAX_ZOOM = 14.0

    /** Average size of a vector tile, for the estimate shown before downloading. */
    private const val BYTES_PER_TILE = 22_000L

    /**
     * Style used as the offline definition. It is put into the map cache before the download starts,
     * and also published in the repository as a fallback.
     */
    private const val STYLE_URL = "https://raw.githubusercontent.com/GUN642/VOID-Maps/main/styles/offline.json"

    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    val areas: StateFlow<List<OfflineArea>> = _areas.asStateFlow()

    private val regions = HashMap<Long, OfflineRegion>()

    private fun update(id: Long, block: (OfflineArea) -> OfflineArea) {
        _areas.value = _areas.value.map { if (it.id == id) block(it) else it }
    }

    private fun nameOf(region: OfflineRegion): String =
        runCatching { JSONObject(String(region.metadata)).optString("name") }.getOrNull()?.ifBlank { null } ?: "Bereich ${region.id}"

    private fun boundsOf(region: OfflineRegion): BBox? {
        val def = region.definition as? OfflineTilePyramidRegionDefinition ?: return null
        val b = def.bounds ?: return null
        return BBox(b.getLatSouth(), b.getLonWest(), b.getLatNorth(), b.getLonEast())
    }

    /** Loads the list of stored areas with their status. */
    fun refresh(context: Context) {
        OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions?.toList().orEmpty()
                regions.clear()
                list.forEach { regions[it.id] = it }
                _areas.value = list.map { r ->
                    _areas.value.firstOrNull { it.id == r.id }
                        ?: OfflineArea(r.id, nameOf(r), boundsOf(r), 0, 0f, complete = false, downloading = false)
                }
                list.forEach { r ->
                    observe(r)
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status != null) applyStatus(r.id, status)
                        }

                        override fun onError(error: String?) {}
                    })
                }
            }

            override fun onError(error: String) {}
        })
    }

    private fun applyStatus(id: Long, s: OfflineRegionStatus) {
        val progress = if (s.requiredResourceCount > 0) s.completedResourceCount.toFloat() / s.requiredResourceCount else 0f
        update(id) {
            it.copy(
                bytes = s.completedResourceSize,
                progress = progress.coerceIn(0f, 1f),
                complete = s.isComplete,
                downloading = s.downloadState == OfflineRegion.STATE_ACTIVE && !s.isComplete,
            )
        }
    }

    private fun observe(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                applyStatus(region.id, status)
                if (status.isComplete) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }

            override fun onError(error: OfflineRegionError) {
                update(region.id) { it.copy(error = error.message) }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                update(region.id) { it.copy(error = "Zu viele Kacheln") }
            }
        })
    }

    /** Starts downloading [box] as a new offline area. */
    fun download(context: Context, name: String, box: BBox, onError: (String) -> Unit) {
        val manager = OfflineManager.getInstance(context)
        val nowSec = System.currentTimeMillis() / 1000
        // Seed the style so the download never depends on reaching GitHub.
        runCatching {
            manager.putResourceWithUrl(STYLE_URL, MapStyle.offlineStyle().toByteArray(), nowSec, nowSec + 10L * 365 * 24 * 3600, "void-offline-1", false)
        }
        val bounds = LatLngBounds.Builder()
            .include(org.maplibre.android.geometry.LatLng(box.minLat, box.minLon))
            .include(org.maplibre.android.geometry.LatLng(box.maxLat, box.maxLon))
            .build()
        val definition = OfflineTilePyramidRegionDefinition(
            STYLE_URL, bounds, 0.0, MAX_ZOOM, context.resources.displayMetrics.density, false,
        )
        val metadata = JSONObject().put("name", name).toString().toByteArray()
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                regions[offlineRegion.id] = offlineRegion
                _areas.value = _areas.value + OfflineArea(offlineRegion.id, name, box, 0, 0f, complete = false, downloading = true)
                observe(offlineRegion)
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) = onError(error)
        })
    }

    fun pause(id: Long) {
        regions[id]?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        update(id) { it.copy(downloading = false) }
    }

    fun resume(id: Long) {
        val r = regions[id] ?: return
        observe(r)
        r.setDownloadState(OfflineRegion.STATE_ACTIVE)
        update(id) { it.copy(downloading = true, error = null) }
    }

    fun delete(id: Long) {
        val r = regions[id] ?: return
        r.setDownloadState(OfflineRegion.STATE_INACTIVE)
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                regions.remove(id)
                _areas.value = _areas.value.filterNot { it.id == id }
            }

            override fun onError(error: String) {
                update(id) { it.copy(error = error) }
            }
        })
    }

    /** Number of tiles for [box] from zoom 0 to [MAX_ZOOM]. */
    fun estimateTiles(box: BBox): Long {
        var total = 0L
        for (z in 0..MAX_ZOOM.toInt()) {
            val n = 1 shl z
            fun tx(lon: Double) = floor((lon + 180) / 360 * n).toLong().coerceIn(0, n - 1L)
            fun ty(lat: Double): Long {
                val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
                return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n).toLong().coerceIn(0, n - 1L)
            }
            total += (tx(box.maxLon) - tx(box.minLon) + 1) * (ty(box.minLat) - ty(box.maxLat) + 1)
        }
        return total
    }

    fun estimateBytes(box: BBox): Long = estimateTiles(box) * BYTES_PER_TILE
}
