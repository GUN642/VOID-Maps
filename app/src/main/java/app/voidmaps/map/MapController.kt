package app.voidmaps.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.Location
import android.view.Gravity
import app.voidmaps.data.BBox
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Hazard
import app.voidmaps.data.HazardType
import app.voidmaps.data.Place
import app.voidmaps.data.Poi
import app.voidmaps.nav.LocationTracker
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

enum class Follow { NONE, BROWSE, NAVIGATE }

/**
 * Glue between Compose state and the MapLibre map: style switching, route/hazard layers,
 * location puck and the camera modes (free, follow, navigation).
 */
class MapController(private val context: Context) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleConfig: StyleConfig? = null

    var onLongPress: (GeoPoint) -> Unit = {}
    var onTap: (GeoPoint, Hazard?) -> Unit = { _, _ -> }
    var onRouteTap: (Int) -> Unit = {}
    var onPoiTap: (Poi) -> Unit = {}
    /** The user touched the map (pan, zoom, rotate). */
    var onGesture: () -> Unit = {}
    var onCameraIdle: (BBox, GeoPoint, Double) -> Unit = { _, _, _ -> }
    var onTrackingDismissed: () -> Unit = {}

    private var routeJson: String = EMPTY
    private var hazardJson: String = EMPTY
    private var hazardLineJson: String = EMPTY
    private var destJson: String = EMPTY
    private var poiJson: String = EMPTY
    private var signalJson: String = EMPTY
    private var hazardById: Map<String, Hazard> = emptyMap()
    private var poiById: Map<String, Poi> = emptyMap()

    private var follow = Follow.NONE
    private var headingUp = true
    private var tilt = true
    private var topPadding = 0.0
    private var lastLocation: Location? = null

    val isReady: Boolean get() = style != null

    fun attach(m: MapLibreMap, start: CameraPosition) {
        map = m
        m.cameraPosition = start
        m.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = true
            setAttributionGravity(Gravity.BOTTOM or Gravity.START)
            isCompassEnabled = true
            setCompassFadeFacingNorth(true)
            setTiltGesturesEnabled(true)
        }
        m.addOnMapLongClickListener { ll ->
            onLongPress(GeoPoint(ll.latitude, ll.longitude))
            true
        }
        m.addOnMapClickListener { ll ->
            val screen = m.projection.toScreenLocation(ll)
            val box = android.graphics.RectF(screen.x - 24, screen.y - 24, screen.x + 24, screen.y + 24)
            val hit = runCatching { m.queryRenderedFeatures(box, "hazard-icons") }.getOrNull()
                ?.firstOrNull()?.getStringProperty("id")?.let { hazardById[it] }
            if (hit != null) {
                onTap(GeoPoint(ll.latitude, ll.longitude), hit)
                return@addOnMapClickListener true
            }
            val poi = runCatching { m.queryRenderedFeatures(box, "poi-icons") }.getOrNull()
                ?.firstOrNull()?.getStringProperty("id")?.let { poiById[it] }
            if (poi != null) {
                onPoiTap(poi)
                return@addOnMapClickListener true
            }
            val route = runCatching { m.queryRenderedFeatures(box, "route-alt") }.getOrNull()
                ?.firstOrNull()?.getNumberProperty("idx")?.toInt()
            if (route != null) {
                onRouteTap(route)
                return@addOnMapClickListener true
            }
            onTap(GeoPoint(ll.latitude, ll.longitude), null)
            false
        }
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onGesture()
        }
        m.addOnCameraIdleListener {
            val region = m.projection.visibleRegion.latLngBounds
            val target = m.cameraPosition.target ?: return@addOnCameraIdleListener
            onCameraIdle(
                BBox(region.getLatSouth(), region.getLonWest(), region.getLatNorth(), region.getLonEast()),
                GeoPoint(target.latitude, target.longitude),
                m.cameraPosition.zoom,
            )
        }
    }

    fun detach() {
        map = null
        style = null
        styleConfig = null
    }

    /** Switches the map design / traffic layer. Cheap when nothing changed. */
    fun applyStyle(config: StyleConfig, accent: Int, background: Int) {
        val m = map ?: return
        if (config == styleConfig) return
        styleConfig = config
        style = null
        val builder = Style.Builder().fromJson(MapStyle.build(config))
        MapIcons.all(context, config.palette).forEach { (name, bmp) -> builder.withImage(name, bmp) }
        m.setStyle(builder) { s ->
            if (styleConfig != config) return@setStyle
            style = s
            m.uiSettings.setAttributionTintColor(if (config.palette.dark) Color.parseColor("#8C8C8C") else Color.parseColor("#6B6B6B"))
            enableLocation(m, s, accent, background)
            pushAll()
            applyFollow()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(m: MapLibreMap, s: Style, accent: Int, background: Int) {
        if (!LocationTracker.hasPermission(context)) return
        val opts = LocationComponentOptions.builder(context)
            .foregroundTintColor(accent)
            .bearingTintColor(accent)
            .backgroundTintColor(background)
            .foregroundStaleTintColor(accent)
            .backgroundStaleTintColor(background)
            .accuracyColor(accent)
            .accuracyAlpha(0.12f)
            .pulseEnabled(false)
            .elevation(0f)
            .trackingGesturesManagement(true)
            .build()
        val lc = m.locationComponent
        runCatching {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.Builder(context, s)
                    .locationComponentOptions(opts)
                    .useDefaultLocationEngine(false)
                    .build(),
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.GPS
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() = onTrackingDismissed()
                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
            (lastLocation ?: LocationTracker.location.value)?.let { lc.forceLocationUpdate(it) }
        }
    }

    /** Call when the location permission was granted after the map was created. */
    fun onPermissionGranted(accent: Int, background: Int) {
        val m = map ?: return
        val s = style ?: return
        if (!m.locationComponent.isLocationComponentActivated) {
            enableLocation(m, s, accent, background)
            applyFollow()
        }
    }

    fun updateLocation(loc: Location) {
        lastLocation = loc
        val m = map ?: return
        if (style == null) return
        val lc = m.locationComponent
        if (lc.isLocationComponentActivated) runCatching { lc.forceLocationUpdate(loc) }
    }

    // ------------------------------------------------------------------ camera

    fun setFollow(mode: Follow, headingUp: Boolean, tilt: Boolean, topPadding: Double) {
        val changed = mode != follow || headingUp != this.headingUp || tilt != this.tilt || topPadding != this.topPadding
        follow = mode
        this.headingUp = headingUp
        this.tilt = tilt
        this.topPadding = topPadding
        if (changed) applyFollow()
    }

    private fun applyFollow() {
        val m = map ?: return
        if (style == null) return
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) return
        runCatching {
            when (follow) {
                Follow.NONE -> lc.cameraMode = CameraMode.NONE
                Follow.BROWSE -> {
                    lc.cameraMode = if (headingUp) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH
                    lc.paddingWhileTracking(doubleArrayOf(0.0, 0.0, 0.0, 0.0), 600)
                    lc.tiltWhileTracking(0.0, 600)
                    if (m.cameraPosition.zoom < 13) lc.zoomWhileTracking(15.5, 800)
                }
                Follow.NAVIGATE -> {
                    lc.cameraMode = if (headingUp) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH
                    lc.paddingWhileTracking(doubleArrayOf(0.0, topPadding, 0.0, 0.0), 600)
                    lc.zoomWhileTracking(16.5, 800)
                    lc.tiltWhileTracking(if (tilt && headingUp) 55.0 else 0.0, 800)
                }
            }
        }
    }

    /** Shows the whole route between the given screen paddings (pixels). */
    fun showOverview(points: List<GeoPoint>, left: Int, top: Int, right: Int, bottom: Int) {
        val m = map ?: return
        if (points.size < 2) return
        follow = Follow.NONE
        runCatching { if (m.locationComponent.isLocationComponentActivated) m.locationComponent.cameraMode = CameraMode.NONE }
        val step = (points.size / 400).coerceAtLeast(1)
        val b = LatLngBounds.Builder()
        points.forEachIndexed { i, p -> if (i % step == 0) b.include(LatLng(p.lat, p.lon)) }
        b.include(LatLng(points.last().lat, points.last().lon))
        runCatching {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), left, top, right, bottom), 900)
        }
    }

    fun moveTo(p: GeoPoint, zoom: Double) {
        val m = map ?: return
        follow = Follow.NONE
        runCatching { if (m.locationComponent.isLocationComponentActivated) m.locationComponent.cameraMode = CameraMode.NONE }
        m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(zoom).tilt(0.0).build()), 800)
    }

    fun camera(): Triple<Double, Double, Double>? {
        val c = map?.cameraPosition ?: return null
        val t = c.target ?: return null
        return Triple(t.latitude, t.longitude, c.zoom)
    }

    // ------------------------------------------------------------------ data layers

    fun setRoute(points: List<GeoPoint>?) = setRoutes(if (points == null) emptyList() else listOf(points), 0)

    /** Draws several routes; [selected] in the route colour, the others as grey alternatives. */
    fun setRoutes(routes: List<List<GeoPoint>>, selected: Int) {
        val features = routes.mapIndexedNotNull { i, pts ->
            if (pts.size < 2) null else lineFeature(pts, JSONObject().put("sel", if (i == selected) 1 else 0).put("idx", i))
        }.sortedBy { it.getJSONObject("properties").getInt("sel") }
        routeJson = if (features.isEmpty()) EMPTY else featureCollection(features)
        push(MapStyle.ROUTE, routeJson)
    }

    fun setDestination(place: Place?, stops: List<Place> = emptyList()) {
        val features = stops.map { pointFeature(it.point, JSONObject().put("icon", "stop")) } +
            listOfNotNull(place?.let { pointFeature(it.point, JSONObject().put("icon", "dest")) })
        destJson = if (features.isEmpty()) EMPTY else featureCollection(features)
        push(MapStyle.DEST, destJson)
    }

    fun setPois(list: List<Poi>) {
        poiById = list.associateBy { it.id }
        poiJson = featureCollection(list.mapNotNull { p ->
            val icon = p.operator?.icon ?: return@mapNotNull null
            pointFeature(p.point, JSONObject().put("icon", icon).put("id", p.id))
        })
        push(MapStyle.POIS, poiJson)
    }

    fun setSignals(points: List<GeoPoint>) {
        signalJson = featureCollection(points.map { pointFeature(it, JSONObject()) })
        push(MapStyle.SIGNALS, signalJson)
    }

    /** Visible area of the map (for offline downloads). */
    fun visibleBounds(): BBox? {
        val m = map ?: return null
        val r = m.projection.visibleRegion.latLngBounds
        return BBox(r.getLatSouth(), r.getLonWest(), r.getLatNorth(), r.getLonEast())
    }

    fun zoom(): Double = map?.cameraPosition?.zoom ?: 0.0

    // Gestures forwarded from Android Auto.
    fun scrollBy(dx: Float, dy: Float) {
        val m = map ?: return
        follow = Follow.NONE
        runCatching { if (m.locationComponent.isLocationComponentActivated) m.locationComponent.cameraMode = CameraMode.NONE }
        m.scrollBy(-dx, -dy, 0)
    }

    fun zoomBy(delta: Double) {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.zoomBy(delta), 300)
    }

    fun setHazards(list: List<Hazard>) {
        hazardById = list.associateBy { it.id }
        val points = list.map { h ->
            pointFeature(
                h.point,
                JSONObject().put("icon", h.type.icon).put("id", h.id)
                    .put("rank", if (h.type == HazardType.SPEED_CAMERA) 1 else 0),
            )
        }
        val lines = list.filter { it.line.size >= 2 && it.type != HazardType.SPEED_CAMERA }.map { lineFeature(it.line, JSONObject()) }
        hazardJson = featureCollection(points)
        hazardLineJson = featureCollection(lines)
        push(MapStyle.HAZARDS, hazardJson)
        push(MapStyle.HAZARD_LINES, hazardLineJson)
    }

    private fun pushAll() {
        push(MapStyle.ROUTE, routeJson)
        push(MapStyle.HAZARDS, hazardJson)
        push(MapStyle.HAZARD_LINES, hazardLineJson)
        push(MapStyle.DEST, destJson)
        push(MapStyle.POIS, poiJson)
        push(MapStyle.SIGNALS, signalJson)
    }

    private fun push(source: String, json: String) {
        val s = style ?: return
        runCatching { s.getSourceAs<GeoJsonSource>(source)?.setGeoJson(json) }
    }

    private fun featureCollection(features: List<JSONObject>): String =
        JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features)).toString()

    private fun pointFeature(p: GeoPoint, props: JSONObject) = JSONObject()
        .put("type", "Feature")
        .put("properties", props)
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))

    private fun lineFeature(points: List<GeoPoint>, props: JSONObject): JSONObject {
        val coords = JSONArray()
        for (p in points) coords.put(JSONArray().put(p.lon).put(p.lat))
        return JSONObject()
            .put("type", "Feature")
            .put("properties", props)
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
    }

    companion object {
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    }
}
