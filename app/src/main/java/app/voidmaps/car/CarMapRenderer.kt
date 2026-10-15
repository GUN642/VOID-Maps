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
    /** False after the driver panned the map; the view button then recentres. */
    var following = true
        private set(v) {
            if (field != v) {
                field = v
                onFollowingChanged()
            }
        }
    var onFollowingChanged: () -> Unit = {}
    private var progress: CarProgressView? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var lastOverview = 0L
    private val smoother = app.voidmaps.nav.PuckSmoother()

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
        val bar = CarProgressView(pres.context)
        val root = android.widget.FrameLayout(pres.context)
        root.addView(mv, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(bar, android.widget.FrameLayout.LayoutParams(0, 0))
        progress = bar
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        pres.setContentView(root)
        visibleArea?.let { area -> root.post { onVisibleAreaChanged(area) } }
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

    private var icons: Map<String, android.graphics.Bitmap>? = null

    private fun observe(ctrl: MapController) {
        val pres = presentation ?: return
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
                    if (loc != null) smoother.onFix(loc, nav)
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
                Navigator.state.collect { st ->
                    applyFollow()
                    val st2 = VoidMapsApp.settings.value
                    val design = resolveDesign(st2.theme, carContext.isDarkMode)
                    val pal = mapPalette(design)
                    if (icons == null) icons = app.voidmaps.map.MapIcons.all(pres.context, pal)
                    progress?.update(st, st2.speedCameras, pal, icons.orEmpty())
                    // Route overview refreshes now and then as the route shrinks.
                    if (st != null && st2.navView == NavView.OVERVIEW && following &&
                        System.currentTimeMillis() - lastOverview > 30_000
                    ) {
                        showOverview()
                    }
                }
            }
            launch {
                // Smooth puck and camera: ~20 position updates per second.
                while (true) {
                    smoother.frame()?.let { ctrl.updateLocation(it) }
                    kotlinx.coroutines.delay(50)
                }
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
        val overview = navigating && s.navView == NavView.OVERVIEW
        ctrl.setFollow(
            when {
                !following || overview -> Follow.NONE
                navigating -> Follow.NAVIGATE
                else -> Follow.BROWSE
            },
            headingUp, s.tilt3d, top,
        )
    }

    private fun showOverview() {
        val ctrl = controller ?: return
        val n = Navigator.state.value ?: return
        val sn = n.snap
        val pts = if (sn != null) n.route.line.remainder(sn.segment, sn.point) else n.route.points
        val area = visibleArea ?: android.graphics.Rect(0, 0, surfaceWidth, surfaceHeight)
        lastOverview = System.currentTimeMillis()
        ctrl.showOverview(pts, area.left + 60, area.top + 40, (surfaceWidth - area.right) + 100, (surfaceHeight - area.bottom) + 40)
    }

    /** View button: recentre after panning, otherwise Route oben → Norden oben → Übersicht. */
    fun cycleView() {
        if (!following) {
            recenter()
            return
        }
        val cur = VoidMapsApp.settings.value.navView
        val next = when (cur) {
            NavView.HEADING -> NavView.NORTH
            NavView.NORTH -> NavView.OVERVIEW
            NavView.OVERVIEW -> NavView.HEADING
        }
        lifecycle.coroutineScope.launch {
            VoidMapsApp.repo.setNavView(next)
            kotlinx.coroutines.delay(100)
            if (next == NavView.OVERVIEW && Navigator.state.value != null) {
                applyFollow()
                showOverview()
            } else {
                recenter()
            }
        }
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
        // Progress bar at the right edge of the free map area.
        progress?.let { bar ->
            val d = bar.resources.displayMetrics.density
            val lp = android.widget.FrameLayout.LayoutParams((44 * d).toInt(), (visibleArea.height() - 60 * d).toInt().coerceAtLeast(0))
            lp.leftMargin = visibleArea.right - (52 * d).toInt()
            lp.topMargin = visibleArea.top + (30 * d).toInt()
            bar.layoutParams = lp
        }
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
        progress = null
        icons = null
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
