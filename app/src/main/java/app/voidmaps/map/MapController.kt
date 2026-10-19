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
import app.voidmaps.data.Polyline
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
import org.maplibre.android.location.OnLocationCameraTransitionListener
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
    private var routeTrafficJson: String = EMPTY
    private var hazardById: Map<String, Hazard> = emptyMap()
    private var poiById: Map<String, Poi> = emptyMap()

    private var follow = Follow.NONE
    private var headingUp = true
    private var tilt = true
    private var topPadding = 0.0
    private var leftPadding = 0.0
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
            // No animation of its own: we feed an already smoothed position every frame. With the
            // built-in animation each new position restarted the animation before it could move,
            // and the puck trailed behind the route by a second or more.
            .trackingAnimationDurationMultiplier(0f)
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
            lc.setMaxAnimationFps(60)
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

    private var speedBand = 0

    /** Closer in town, a bit further out at motorway speed; closer still in the tilted 3D view. */
    private fun navZoom(): Double {
        val base = when (speedBand) {
            2 -> 16.2
            1 -> 16.9
            else -> 17.6
        }
        return if (tilt && headingUp) base + 0.3 else base - 0.4
    }

    fun updateLocation(loc: Location) {
        // Speed bands with a little hysteresis, so the zoom does not pump.
        val kmh = loc.speed * 3.6f
        val band = when {
            kmh > 105 || (speedBand == 2 && kmh > 90) -> 2
            kmh > 65 || (speedBand >= 1 && kmh > 52) -> 1
            else -> 0
        }
        if (band != speedBand) {
            speedBand = band
            val m = map
            if (follow == Follow.NAVIGATE && m != null && m.locationComponent.isLocationComponentActivated) {
                runCatching { m.locationComponent.zoomWhileTracking(navZoom(), 1500) }
            }
        }
        lastLocation = loc
        val m = map ?: return
        if (style == null) return
        val lc = m.locationComponent
        if (lc.isLocationComponentActivated) runCatching { lc.forceLocationUpdate(loc) }
    }

    // ------------------------------------------------------------------ road snapping

    private val roadLayers = arrayOf("road-minor", "road-tertiary", "road-secondary", "road-primary", "road-trunk", "road-motorway")

    /**
     * Moves a GPS fix onto the nearest road drawn on the map (within [maxMetres]), so the position
     * does not float beside the street when no route is active. Unchanged when no road is near.
     */
    fun snapToRoad(loc: Location, maxMetres: Double = 14.0): Location {
        val m = map ?: return loc
        if (style == null) return loc
        return runCatching {
            val pt = m.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude))
            val r = 70f * context.resources.displayMetrics.density
            val features = m.queryRenderedFeatures(android.graphics.RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r), *roadLayers)
            val p = GeoPoint(loc.latitude, loc.longitude)
            var best: GeoPoint? = null
            var bestOffset = Double.MAX_VALUE
            for (f in features) {
                val g = f.geometry()
                val lines: List<List<org.maplibre.geojson.Point>> = when (g) {
                    is org.maplibre.geojson.LineString -> listOf(g.coordinates())
                    is org.maplibre.geojson.MultiLineString -> g.coordinates()
                    else -> emptyList()
                }
                for (coords in lines) {
                    if (coords.size < 2) continue
                    val snap = Polyline(coords.map { GeoPoint(it.latitude(), it.longitude()) }).snap(p) ?: continue
                    if (snap.offset < bestOffset) {
                        bestOffset = snap.offset
                        best = snap.point
                    }
                }
            }
            val b = best
            if (b == null || bestOffset > maxMetres) loc else Location(loc).apply {
                latitude = b.lat
                longitude = b.lon
            }
        }.getOrDefault(loc)
    }

    // ------------------------------------------------------------------ camera

    fun setFollow(mode: Follow, headingUp: Boolean, tilt: Boolean, topPadding: Double, leftPadding: Double = 0.0) {
        val changed = mode != follow || headingUp != this.headingUp || tilt != this.tilt || topPadding != this.topPadding ||
            leftPadding != this.leftPadding
        this.leftPadding = leftPadding
        follow = mode
        this.headingUp = headingUp
        this.tilt = tilt
        this.topPadding = topPadding
        if (changed) applyFollow()
    }

    /** Bumped on every camera change, so late animation callbacks do not undo a newer choice. */
    private var followGen = 0

    private fun applyFollow() {
        val m = map ?: return
        if (style == null) return
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) return
        val gen = ++followGen
        runCatching {
            when (follow) {
                Follow.NONE -> lc.cameraMode = CameraMode.NONE
                Follow.BROWSE -> track(
                    m, gen,
                    if (headingUp) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH,
                    zoom = if (m.cameraPosition.zoom < 13) 15.5 else null,
                    tilt = 0.0,
                    padding = doubleArrayOf(leftPadding, 0.0, 0.0, 0.0),
                )
                Follow.NAVIGATE -> track(
                    m, gen,
                    if (headingUp) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH,
                    zoom = navZoom(),
                    tilt = if (tilt && headingUp) NAV_TILT else 0.0,
                    padding = doubleArrayOf(leftPadding, topPadding, 0.0, 0.0),
                )
            }
        }
    }

    /**
     * Starts following the position with the given zoom, tilt and padding.
     *
     * MapLibre ignores zoom/tilt/padding changes while a tracking transition is running, which left
     * the navigation start far zoomed out and flat. So from a free camera we first fly to the
     * position with everything set in one animation and only then switch tracking on; when already
     * tracking, the values are applied after the (possibly instant) mode transition.
     */
    private fun track(m: MapLibreMap, gen: Int, mode: Int, zoom: Double?, tilt: Double, padding: DoubleArray) {
        val lc = m.locationComponent
        val adjust = object : OnLocationCameraTransitionListener {
            override fun onLocationCameraTransitionFinished(cameraMode: Int) {
                if (gen != followGen) return
                runCatching {
                    lc.paddingWhileTracking(padding, 500)
                    zoom?.let { lc.zoomWhileTracking(it, 500) }
                    lc.tiltWhileTracking(tilt, 500)
                }
            }
            override fun onLocationCameraTransitionCanceled(cameraMode: Int) {}
        }
        val loc = lastLocation ?: lc.lastKnownLocation
        if (lc.cameraMode != CameraMode.NONE || loc == null) {
            lc.setCameraMode(mode, 600L, zoom, null, tilt, adjust)
            return
        }
        val cam = m.cameraPosition
        val bearing = when {
            mode == CameraMode.TRACKING_GPS_NORTH -> 0.0
            loc.hasBearing() -> loc.bearing.toDouble()
            else -> cam.bearing
        }
        val target = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(zoom ?: cam.zoom)
            .bearing(bearing)
            .tilt(tilt)
            .padding(padding)
            .build()
        val engage = {
            if (gen == followGen) runCatching { lc.setCameraMode(mode, 300L, zoom, null, tilt, adjust) }
        }
        m.animateCamera(CameraUpdateFactory.newCameraPosition(target), 900, object : MapLibreMap.CancelableCallback {
            override fun onFinish() { engage() }
            override fun onCancel() { engage() }
        })
    }

    /** Shows the whole route between the given screen paddings (pixels). */
    fun showOverview(points: List<GeoPoint>, left: Int, top: Int, right: Int, bottom: Int) {
        val m = map ?: return
        if (points.size < 2) return
        follow = Follow.NONE
        followGen++
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
        followGen++
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

    /** Route being driven; its passed part is cut off at the puck by [setRouteProgress]. */
    var activeLine: Polyline? = null
        private set
    private var restStart = -1
    private var headJson: String = EMPTY

    /** Shows the route being driven from [along] metres on. */
    fun setActiveRoute(line: Polyline, along: Double) {
        if (line !== activeLine) {
            activeLine = line
            restStart = -1
        }
        setRouteProgress(along)
    }

    /**
     * Lets the active route start exactly at the puck. The long rest of the line is only replaced
     * every few vertices; the short piece up to it is redrawn with every frame.
     */
    fun setRouteProgress(along: Double) {
        val l = activeLine ?: return
        val pts = l.points
        if (pts.size < 2) return
        val a = along.coerceIn(0.0, l.length)
        val cum = l.cumulative
        // First vertex ahead of the position.
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cum[mid] > a) hi = mid else lo = mid + 1
        }
        val next = lo
        if (restStart < next || restStart - next > 40) {
            restStart = (next + 12).coerceAtMost(pts.size - 1)
            routeJson = if (pts.size - restStart >= 2) {
                featureCollection(listOf(lineFeature(pts.subList(restStart, pts.size), JSONObject().put("sel", 1).put("idx", 0))))
            } else {
                EMPTY
            }
            push(MapStyle.ROUTE, routeJson)
        }
        val head = ArrayList<GeoPoint>(restStart - next + 2)
        head += l.pointAt(a)
        for (i in next..restStart) head += pts[i]
        headJson = featureCollection(listOf(lineFeature(head, JSONObject())))
        push(MapStyle.ROUTE_HEAD, headJson)
    }

    /** Draws several routes; [selected] in the route colour, the others as grey alternatives. */
    fun setRoutes(routes: List<List<GeoPoint>>, selected: Int) {
        if (activeLine != null || headJson != EMPTY) {
            activeLine = null
            restStart = -1
            headJson = EMPTY
            push(MapStyle.ROUTE_HEAD, headJson)
        }
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

    /** Coloured jam / roadworks stretches drawn on top of the route. */
    fun setRouteTraffic(spans: List<Pair<List<GeoPoint>, app.voidmaps.data.TrafficSpan>>) {
        val features = spans.filter { it.first.size >= 2 }.map { (pts, t) ->
            lineFeature(pts, JSONObject().put("level", if (t.works) "works" else t.level.toString()))
        }
        routeTrafficJson = if (features.isEmpty()) EMPTY else featureCollection(features)
        push(MapStyle.ROUTE_TRAFFIC, routeTrafficJson)
    }

    fun setSignals(points: List<GeoPoint>) {
        signalJson = featureCollection(points.map { pointFeature(it, JSONObject()) })
        push(MapStyle.SIGNALS, signalJson)
    }

    /** Puts the north arrow at the right edge, [topPx] below the top of the map (under the search bar). */
    fun setCompassTop(topPx: Int, rightPx: Int) {
        val ui = map?.uiSettings ?: return
        ui.setCompassGravity(Gravity.TOP or Gravity.END)
        ui.setCompassMargins(0, topPx, rightPx, 0)
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
        followGen++
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
                    .put("rank", if (h.type.isCamera) 1 else 0),
            )
        }
        val lines = list.filter { it.line.size >= 2 && !it.type.isCamera }.map { lineFeature(it.line, JSONObject()) }
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
        push(MapStyle.ROUTE_TRAFFIC, routeTrafficJson)
        push(MapStyle.ROUTE_HEAD, headJson)
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
        private const val NAV_TILT = 60.0
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    }
}
