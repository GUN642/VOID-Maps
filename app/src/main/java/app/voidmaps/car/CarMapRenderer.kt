package app.voidmaps.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import app.voidmaps.VoidMapsApp
import app.voidmaps.data.NavView
import app.voidmaps.map.Follow
import app.voidmaps.map.MapController
import app.voidmaps.map.styleConfigFor
import app.voidmaps.nav.HazardRepo
import app.voidmaps.nav.LocationTracker
import app.voidmaps.nav.Navigator
import app.voidmaps.ui.theme.colorsFor
import app.voidmaps.ui.theme.mapPalette
import app.voidmaps.ui.theme.resolveDesign
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/**
 * Draws the MapLibre map onto the Android Auto surface. The map view lives in a Presentation on a
 * private virtual display whose output is the car's surface.
 */
class CarMapRenderer(private val carContext: CarContext, private val lifecycle: Lifecycle) : SurfaceCallback, DefaultLifecycleObserver {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var controller: MapController? = null
    private var stateJob: Job? = null
    private var visibleArea: Rect? = null
    private var following = true

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        LocationTracker.acquire(carContext, "car")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
        LocationTracker.release("car")
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        release()
        val surface = surfaceContainer.surface ?: return
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "VOIDMapsCar", surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        display = vd
        val pres = Presentation(carContext, vd.display)
        val mv = MapView(pres.context)
        pres.setContentView(mv)
        pres.show()
        mv.onCreate(null)
        mv.onStart()
        mv.onResume()
        presentation = pres
        mapView = mv
        val ctrl = MapController(pres.context)
        controller = ctrl
        mv.getMapAsync { map ->
            val loc = LocationTracker.location.value
            val s = VoidMapsApp.settings.value
            ctrl.attach(
                map,
                CameraPosition.Builder()
                    .target(if (loc != null) LatLng(loc.latitude, loc.longitude) else LatLng(s.lastLat, s.lastLon))
                    .zoom(15.0).build(),
            )
            ctrl.onTrackingDismissed = { following = false }
            observe(ctrl)
        }
    }

    private fun observe(ctrl: MapController) {
        stateJob?.cancel()
        stateJob = lifecycle.coroutineScope.launch {
            launch {
                VoidMapsApp.settings.collect { s ->
                    val design = resolveDesign(s.theme, carContext.isDarkMode)
                    val colors = colorsFor(design)
                    ctrl.applyStyle(
                        styleConfigFor(s, mapPalette(design)),
                        colors.accent.toArgb(),
                        if (colors.isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                    )
                    applyFollow()
                }
            }
            launch {
                combine(LocationTracker.location, Navigator.state) { l, n -> l to n }.collect { (loc, nav) ->
                    if (loc != null) {
                        val snap = nav?.snap
                        val shown = if (nav != null && snap != null && !nav.offRoute && snap.offset < 30) {
                            Location(loc).apply {
                                latitude = snap.point.lat
                                longitude = snap.point.lon
                                if (loc.speed > 1.5f) bearing = nav.route.line.segmentBearing(snap.segment).toFloat()
                            }
                        } else {
                            loc
                        }
                        ctrl.updateLocation(shown)
                    }
                    if (nav != null) {
                        val sn = nav.snap
                        ctrl.setRoute(if (sn != null) nav.route.line.remainder(sn.segment, sn.point) else nav.route.points)
                        ctrl.setDestination(nav.destination, nav.stops)
                    } else {
                        ctrl.setRoute(null)
                        ctrl.setDestination(null)
                    }
                }
            }
            launch {
                Navigator.state.collect { applyFollow() }
            }
            launch {
                combine(HazardRepo.incidents, HazardRepo.cameras, VoidMapsApp.settings) { a, b, s -> a + if (s.speedCameras) b else emptyList() }
                    .collect { ctrl.setHazards(it) }
            }
        }
    }

    private fun applyFollow() {
        val ctrl = controller ?: return
        val s = VoidMapsApp.settings.value
        val navigating = Navigator.state.value != null
        val area = visibleArea
        val height = mapView?.height ?: 0
        // Keep the position in the lower part of the visible area while navigating.
        val top = if (navigating && area != null) (area.top + area.height() * 0.35).toDouble() else if (navigating) height * 0.35 else 0.0
        val headingUp = if (navigating) s.navView != NavView.NORTH else s.headingUp
        ctrl.setFollow(
            when {
                !following -> Follow.NONE
                navigating -> Follow.NAVIGATE
                else -> Follow.BROWSE
            },
            headingUp, s.tilt3d, top,
        )
    }

    fun recenter() {
        following = true
        // Force the camera mode to be re-applied.
        controller?.setFollow(Follow.NONE, true, false, 0.0)
        applyFollow()
    }

    fun zoomIn() = controller?.zoomBy(1.0)

    fun zoomOut() = controller?.zoomBy(-1.0)

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        applyFollow()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onScroll(distanceX: Float, distanceY: Float) {
        following = false
        controller?.scrollBy(distanceX, distanceY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        controller?.zoomBy(if (scaleFactor > 1f) 0.3 else -0.3)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = release()

    private fun release() {
        stateJob?.cancel()
        stateJob = null
        controller?.detach()
        controller = null
        mapView?.let {
            runCatching {
                it.onPause()
                it.onStop()
                it.onDestroy()
            }
        }
        mapView = null
        runCatching { presentation?.dismiss() }
        presentation = null
        display?.release()
        display = null
    }
}
