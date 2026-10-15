package app.voidmaps.ui

import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Satellite
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.voidmaps.data.AppSettings
import app.voidmaps.data.Format
import app.voidmaps.data.HazardType
import app.voidmaps.data.NavView
import app.voidmaps.data.Route
import app.voidmaps.data.VoiceMode
import app.voidmaps.map.Follow
import app.voidmaps.map.MapController
import app.voidmaps.map.OfflineMaps
import app.voidmaps.map.styleConfigFor
import app.voidmaps.nav.HazardRepo
import app.voidmaps.nav.LocationTracker
import app.voidmaps.nav.Navigator
import app.voidmaps.ui.theme.Design
import app.voidmaps.ui.theme.VoidTheme
import app.voidmaps.ui.theme.dotNumber
import app.voidmaps.ui.theme.headingStyle
import app.voidmaps.ui.theme.mapPalette
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

@Composable
fun MapScreen(vm: MainViewModel, settings: AppSettings, design: Design, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val context = LocalContext.current
    val c = VoidTheme.colors
    val density = LocalDensity.current
    val controller = remember { MapController(context) }
    var mapReady by remember { mutableStateOf(false) }
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> {
                    controller.camera()?.let { (lat, lon, z) -> vm.saveCamera(lat, lon, z) }
                    mapView.onStop()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.detach()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            val start = LocationTracker.location.value
            val pos = CameraPosition.Builder()
                .target(if (start != null) LatLng(start.latitude, start.longitude) else LatLng(settings.lastLat, settings.lastLon))
                .zoom(if (start != null) 15.0 else settings.lastZoom)
                .build()
            controller.attach(map, pos)
            controller.onLongPress = { vm.onMapLongPress(it) }
            controller.onTrackingDismissed = { vm.following = false }
            controller.onCameraIdle = { box, center, zoom -> vm.onCameraIdle(box, center, zoom) }
            controller.onTap = { _, hazard -> if (hazard != null) vm.tappedHazard = hazard }
            controller.onPoiTap = { vm.tappedPoi = it }
            controller.onRouteTap = { vm.selectRoute(it) }
            controller.onGesture = { vm.onUserInput() }
            mapReady = true
        }
    }

    // Map design and traffic layer.
    val palette = mapPalette(design)
    val puckBg = if (c.isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    val styleConfig = styleConfigFor(settings, palette)
    LaunchedEffect(mapReady, styleConfig) {
        if (!mapReady) return@LaunchedEffect
        controller.applyStyle(styleConfig, accent = c.accent.toArgb(), background = puckBg)
    }
    LaunchedEffect(mapReady, hasPermission) {
        if (mapReady && hasPermission) controller.onPermissionGranted(c.accent.toArgb(), puckBg)
    }

    val location by LocationTracker.location.collectAsState()
    val nav by Navigator.state.collectAsState()

    // Position puck: GPS fixes are smoothed to ~20 updates per second (along the route while navigating).
    val smoother = remember { app.voidmaps.nav.PuckSmoother() }
    LaunchedEffect(location, nav?.snap) {
        val loc = location ?: return@LaunchedEffect
        smoother.onFix(loc, nav)
    }
    LaunchedEffect(mapReady) {
        if (!mapReady) return@LaunchedEffect
        while (true) {
            smoother.frame()?.let { controller.updateLocation(it) }
            delay(50)
        }
    }

    // Routes: all alternatives in the preview, the part still ahead while navigating.
    val routes = vm.routes
    LaunchedEffect(mapReady, vm.mode, routes, vm.selectedRoute, nav?.routeVersion, nav?.snap?.segment) {
        if (!mapReady) return@LaunchedEffect
        val n = nav
        val snap = n?.snap
        when {
            n != null && (vm.mode == Mode.NAVIGATE || vm.mode == Mode.SEARCH) ->
                controller.setRoute(if (snap != null) n.route.line.remainder(snap.segment, snap.point) else n.route.points)
            vm.mode == Mode.PREVIEW || vm.mode == Mode.SEARCH -> controller.setRoutes(routes.map { it.points }, vm.selectedRoute)
            else -> controller.setRoute(null)
        }
    }
    // Jams and roadworks beside the route: the route's own traffic sections plus jam incidents on it.
    LaunchedEffect(mapReady, vm.mode, routes, vm.selectedRoute, nav?.routeVersion, nav?.snap?.segment, nav?.routeHazards) {
        if (!mapReady) return@LaunchedEffect
        val n = nav
        val route = if (n != null && vm.mode != Mode.PREVIEW) n.route else vm.preview
        if (route == null || (vm.mode != Mode.PREVIEW && vm.mode != Mode.NAVIGATE && vm.mode != Mode.SEARCH)) {
            controller.setRouteTraffic(emptyList())
            return@LaunchedEffect
        }
        val from = if (n != null && route === n.route) n.snap?.along ?: 0.0 else 0.0
        val spans = route.traffic.toMutableList()
        if (n != null && route === n.route) {
            n.routeHazards.forEach { h ->
                val end = h.endAlong ?: return@forEach
                val level = when (h.hazard.type) {
                    HazardType.JAM -> 2
                    HazardType.CLOSURE -> 3
                    else -> return@forEach
                }
                spans += app.voidmaps.data.TrafficSpan(h.along, end, level)
            }
        }
        controller.setRouteTraffic(
            spans.filter { it.endAlong > from }.map { route.line.slice(maxOf(it.startAlong, from), it.endAlong) to it },
        )
    }
    LaunchedEffect(mapReady, vm.destination, vm.stops, nav?.stops) {
        if (mapReady) controller.setDestination(vm.destination ?: nav?.destination, nav?.stops ?: vm.stops)
    }

    val incidents by HazardRepo.incidents.collectAsState()
    val cameras by HazardRepo.cameras.collectAsState()
    val chargers by HazardRepo.chargers.collectAsState()
    LaunchedEffect(mapReady, incidents, cameras, settings.speedCameras) {
        if (mapReady) controller.setHazards(incidents + if (settings.speedCameras) cameras else emptyList())
    }
    val signals by HazardRepo.signals.collectAsState()
    LaunchedEffect(mapReady, signals, settings.trafficSignals) {
        if (mapReady) controller.setSignals(if (settings.trafficSignals) signals else emptyList())
    }
    LaunchedEffect(mapReady, chargers, settings.chargers, settings.chargerOperators) {
        if (mapReady) controller.setPois(if (settings.chargers) chargers.filter { it.operator in settings.chargerOperators } else emptyList())
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.background)) {
        val heightPx = with(density) { maxHeight.toPx() }
        val overview = vm.mode == Mode.NAVIGATE && settings.navView == NavView.OVERVIEW
        val follow = when {
            !vm.following || overview -> Follow.NONE
            vm.mode == Mode.NAVIGATE -> Follow.NAVIGATE
            vm.mode == Mode.BROWSE -> Follow.BROWSE
            else -> Follow.NONE
        }
        val headingUp = if (vm.mode == Mode.NAVIGATE) settings.navView == NavView.HEADING else settings.headingUp
        val landscape = isLandscape()
        val panelPx = with(density) { (LANDSCAPE_PANEL + 40.dp).toPx() }.toDouble()
        LaunchedEffect(mapReady, follow, headingUp, settings.tilt3d, heightPx, landscape, vm.mode) {
            if (mapReady) {
                // Landscape: the side panel covers the left part, so the position moves into the free map area.
                if (landscape && vm.mode == Mode.NAVIGATE) controller.setFollow(follow, headingUp, settings.tilt3d, heightPx * 0.30, panelPx)
                else controller.setFollow(follow, headingUp, settings.tilt3d, heightPx * 0.42)
            }
        }
        // Route overview while navigating: refit now and then as the route shrinks.
        LaunchedEffect(mapReady, overview, nav?.routeVersion) {
            while (mapReady && overview) {
                val n = Navigator.state.value ?: break
                val snap = n.snap
                val pts = if (snap != null) n.route.line.remainder(snap.segment, snap.point) else n.route.points
                with(density) {
                    if (landscape) controller.showOverview(pts, (LANDSCAPE_PANEL + 40.dp).roundToPx(), 40.dp.roundToPx(), 90.dp.roundToPx(), 40.dp.roundToPx())
                    else controller.showOverview(pts, 60.dp.roundToPx(), 260.dp.roundToPx(), 60.dp.roundToPx(), 200.dp.roundToPx())
                }
                delay(30_000)
            }
        }
        val preview = vm.preview
        LaunchedEffect(mapReady, routes, vm.mode) {
            if (mapReady && preview != null && vm.mode == Mode.PREVIEW) {
                val all = routes.flatMap { it.points }
                with(density) {
                    if (landscape) controller.showOverview(all, 400.dp.roundToPx(), 40.dp.roundToPx(), 420.dp.roundToPx(), 30.dp.roundToPx())
                    else controller.showOverview(all, 40.dp.roundToPx(), 170.dp.roundToPx(), 40.dp.roundToPx(), 380.dp.roundToPx())
                }
            }
        }

        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // North arrow right below whatever sits at the top (search bar, destination card, turn card).
        var topInset by remember { mutableIntStateOf(0) }
        LaunchedEffect(mapReady, topInset) {
            if (mapReady && topInset > 0) {
                with(density) { controller.setCompassTop(topInset + 10.dp.roundToPx(), 18.dp.roundToPx()) }
            }
        }
        val onTop: (Int) -> Unit = { if (it != topInset) topInset = it }
        when (vm.mode) {
            Mode.BROWSE -> BrowseOverlay(vm, settings, hasPermission, onRequestPermission, onTop)
            Mode.PREVIEW -> PreviewOverlay(vm, settings, onTop)
            Mode.NAVIGATE -> nav?.let { NavigationOverlay(vm, settings, it, onTop) }
            Mode.OFFLINE_SELECT -> OfflineSelectOverlay(vm)
            Mode.SEARCH -> {}
        }
        AnimatedVisibility(vm.mode == Mode.SEARCH, enter = fadeIn(), exit = fadeOut()) {
            SearchPanel(vm, settings)
        }
    }

    BackHandler(enabled = vm.mode == Mode.SEARCH || vm.mode == Mode.PREVIEW || vm.mode == Mode.OFFLINE_SELECT) {
        when (vm.mode) {
            Mode.SEARCH -> vm.closeSearch()
            Mode.PREVIEW -> vm.cancelPreview()
            Mode.OFFLINE_SELECT -> vm.cancelOfflineSelection()
            else -> {}
        }
    }

    vm.tappedHazard?.let { h -> HazardDialog(h) { vm.tappedHazard = null } }
    vm.tappedPoi?.let { p ->
        PoiDialog(
            p,
            navigating = nav != null,
            onDismiss = { vm.tappedPoi = null },
            onGo = {
                vm.tappedPoi = null
                if (nav != null) Navigator.stop()
                vm.goTo(p.toPlace())
            },
            onStop = {
                vm.tappedPoi = null
                Navigator.addStop(p.toPlace())
            },
        )
    }
    vm.pendingChoice?.let { p ->
        ChoiceDialog(p.name, onStop = { vm.resolvePending(true) }, onNew = { vm.resolvePending(false) }) { vm.pendingChoice = null }
    }
}

/** Switches between the drawn map and satellite imagery. */
@Composable
fun SatelliteButton(vm: MainViewModel, on: Boolean) {
    MapButton(Icons.Outlined.Satellite, if (on) "Karte" else "Satellit", onClick = { vm.toggleSatellite() }, active = on)
}

/** Mute / only warnings / loud – one button cycling through the three modes. */
@Composable
fun VoiceButton(vm: MainViewModel, mode: VoiceMode) {
    val c = VoidTheme.colors
    val icon: ImageVector = when (mode) {
        VoiceMode.ALL -> Icons.AutoMirrored.Outlined.VolumeUp
        VoiceMode.WARNINGS -> Icons.Outlined.ReportProblem
        VoiceMode.OFF -> Icons.AutoMirrored.Outlined.VolumeOff
    }
    MapButton(icon, "Ansagen: ${mode.label}", onClick = { vm.cycleVoice() }, active = mode != VoiceMode.OFF,
        activeColor = if (mode == VoiceMode.WARNINGS) c.warn else c.accent)
}

@Composable
private fun BrowseOverlay(vm: MainViewModel, settings: AppSettings, hasPermission: Boolean, onRequestPermission: () -> Unit, onTop: (Int) -> Unit) {
    val c = VoidTheme.colors
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 480.dp)
                .onGloballyPositioned { onTop(it.boundsInRoot().bottom.toInt()) },
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).shadow(8.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                    .background(c.surface).clickable { vm.openSearch() }.padding(start = 20.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = c.textMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("WOHIN?", style = headingStyle(22), color = c.text, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.showSettings = true }) {
                    Box {
                        Icon(Icons.Outlined.Settings, "Menü", tint = c.text)
                        if (vm.availableUpdate != null) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent).align(Alignment.TopEnd))
                        }
                    }
                }
            }
            if (settings.home != null || settings.work != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.home?.let { home -> QuickChip("Zuhause", Icons.Outlined.Home) { vm.goTo(home) } }
                    settings.work?.let { work -> QuickChip("Arbeit", Icons.Outlined.Work) { vm.goTo(work) } }
                }
            }
        }

        Column(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SatelliteButton(vm, settings.satellite)
            VoiceButton(vm, settings.voiceMode)
        }

        Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.End) {
            MapButton(
                Icons.Outlined.MyLocation, "Mein Standort",
                onClick = { if (!hasPermission) onRequestPermission() else vm.following = true },
                active = vm.following && hasPermission,
            )
        }
        if (!hasPermission) {
            Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 16.dp, bottom = 84.dp, end = 90.dp)) {
                MapCard {
                    Column(Modifier.padding(18.dp)) {
                        Text("Standort freigeben", style = MaterialTheme.typography.titleMedium, color = c.text)
                        Spacer(Modifier.height(4.dp))
                        Text("Ohne GPS keine Navigation.", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                        Spacer(Modifier.height(12.dp))
                        Pill("Erlauben", selected = true, onClick = onRequestPermission, filledColor = c.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    val c = VoidTheme.colors
    Row(
        Modifier.shadow(6.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(c.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accent2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Label(text, color = c.text)
    }
}

@Composable
private fun PreviewOverlay(vm: MainViewModel, settings: AppSettings, onTop: (Int) -> Unit) {
    val c = VoidTheme.colors
    val dest = vm.destination ?: return
    val incidents by HazardRepo.incidents.collectAsState()
    val cameras by HazardRepo.cameras.collectAsState()
    Box(Modifier.fillMaxSize()) {
        val landscape = isLandscape()
        MapCard(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
                .then(if (landscape) Modifier.width(360.dp) else Modifier.fillMaxWidth())
                .onGloballyPositioned { onTop(it.boundsInRoot().bottom.toInt()) },
        ) {
            Column(Modifier.padding(start = 20.dp, end = 6.dp, top = 12.dp, bottom = 10.dp)) {
                val sel = vm.preview
                vm.stops.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Label("Zwischenziel ${i + 1}")
                            Text(s.name, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val wp = sel?.waypointAlong?.getOrNull(i)
                        if (sel != null && wp != null) {
                            val secs = (sel.duration - sel.remainingTime(wp)).coerceAtLeast(0.0)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Format.clock(System.currentTimeMillis() + (secs * 1000).toLong()), style = dotNumber(16), color = c.accent)
                                Label(Format.hhmm(secs))
                            }
                        }
                        IconButton(onClick = { vm.removeStop(s) }) { Icon(Icons.Outlined.Close, "Entfernen", tint = c.textMuted, modifier = Modifier.size(18.dp)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Ziel")
                        Text(dest.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (dest.detail.isNotBlank()) {
                            Text(dest.detail, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = { vm.openSearch(Pick.STOP) }) { Icon(Icons.Outlined.Add, "Zwischenziel", tint = c.accent) }
                    IconButton(onClick = { vm.cancelPreview() }) { Icon(Icons.Outlined.Close, "Schließen", tint = c.text) }
                }
            }
        }

        MapCard(
            if (landscape) {
                Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(10.dp).width(380.dp)
            } else {
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth()
            },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(if (landscape) 14.dp else 18.dp)) {
                when {
                    vm.routing -> {
                        Label("Routen werden berechnet")
                        Spacer(Modifier.height(18.dp))
                        DotLoader(Modifier.fillMaxWidth().height(12.dp))
                        Spacer(Modifier.height(10.dp))
                    }
                    vm.routeError != null -> {
                        Label("Keine Route", color = c.warn)
                        Spacer(Modifier.height(6.dp))
                        Text(vm.routeError!!, color = c.text, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(14.dp))
                        Pill("Erneut versuchen", selected = true, onClick = { vm.computeRoute() }, filledColor = c.accent)
                    }
                    vm.routes.isNotEmpty() -> {
                        val fastest = vm.routes.minOf { it.duration }
                        vm.routes.forEachIndexed { i, r ->
                            val hazards = remember(r, incidents, cameras, settings.speedCameras) {
                                val list = incidents + if (settings.speedCameras) cameras else emptyList()
                                list.count { h ->
                                    val s = r.line.snap(h.point)
                                    s != null && s.offset < if (h.type.isCamera) 35 else 60
                                }
                            }
                            RouteOption(r, selected = i == vm.selectedRoute, fastest = fastest, hazards = hazards) { vm.selectRoute(i) }
                            if (i < vm.routes.size - 1) Spacer(Modifier.height(8.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { StartButton(vm) }
                            Spacer(Modifier.width(8.dp))
                            Pill("Demo", selected = false, onClick = { vm.startDemoForPreview() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteOption(r: Route, selected: Boolean, fastest: Double, hazards: Int, onClick: () -> Unit) {
    val c = VoidTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) c.surfaceHigh else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.divider, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.duration(r.duration).uppercase(), style = dotNumber(if (selected) 28 else 22), color = c.text)
                Spacer(Modifier.width(10.dp))
                Text(Format.distance(r.distance), style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.padding(bottom = 3.dp))
            }
            val extra = listOfNotNull(
                r.via.takeIf { it.isNotBlank() }?.let { "über $it" },
                if (r.trafficDelay >= 60) "+${Format.duration(r.trafficDelay)} Verkehr" else null,
                if (hazards > 0) "$hazards ${if (hazards == 1) "Meldung" else "Meldungen"}" else null,
            ).joinToString(" · ")
            if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (r.duration <= fastest + 1) Label("Schnellste", color = c.accent) else Label("+${Format.duration(r.duration - fastest)}")
            Label("an ${Format.clock(System.currentTimeMillis() + (r.duration * 1000).toLong())}")
        }
    }
}

/** START with a dot ring that fills during the 5 second auto-start countdown. */
@Composable
private fun StartButton(vm: MainViewModel) {
    val c = VoidTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vm.autoStartAt) {
        while (vm.autoStartAt > 0) {
            now = System.currentTimeMillis()
            delay(50)
        }
    }
    val at = vm.autoStartAt
    val progress = if (at > 0) (1f - (at - now).toFloat() / MainViewModel.AUTO_START_MS).coerceIn(0f, 1f) else null
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.accent)
            .clickable { vm.startNavigation() }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (progress != null) DotRing(progress, Modifier.size(36.dp), color = c.onAccent)
        }
        Text(
            "START", style = headingStyle(24), color = c.onAccent,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (progress != null) Text("${((at - now) / 1000 + 1).coerceIn(1, 5)}", style = dotNumber(18), color = c.onAccent)
        }
    }
}

@Composable
private fun OfflineSelectOverlay(vm: MainViewModel) {
    val c = VoidTheme.colors
    var name by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        // Dashed frame: the visible area is what gets downloaded.
        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
            drawRoundRect(
                c.accent, style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 14f), 0f), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()),
                topLeft = Offset.Zero,
            )
        }
        MapCard(Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp).fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("OFFLINE-KARTE", style = headingStyle(24), color = c.text)
                Text(
                    "Karte so verschieben und zoomen, dass der gewünschte Bereich im Rahmen liegt.",
                    style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                )
            }
        }
        MapCard(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                val box = vm.offlineBounds
                if (box != null) {
                    val tiles = OfflineMaps.estimateTiles(box)
                    val bytes = OfflineMaps.estimateBytes(box)
                    Label("ca. ${formatSize(bytes)} · ${"%,d".format(java.util.Locale.GERMANY, tiles)} Kacheln", color = if (tiles > 250_000) c.warn else c.text)
                    if (tiles > 250_000) Text("Sehr großer Bereich – der Download dauert lange.", style = MaterialTheme.typography.bodyMedium, color = c.warn)
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(c.surfaceHigh).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text("Name, z. B. Hamburg", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
                    BasicTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Herunterladen", selected = true, onClick = { vm.downloadOffline(name) }, filledColor = c.accent, modifier = Modifier.weight(1f))
                    Pill("Abbrechen", selected = false, onClick = { vm.cancelOfflineSelection() })
                }
            }
        }
    }
}

/** Horizontal row of chips (used for the POI categories). */
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
