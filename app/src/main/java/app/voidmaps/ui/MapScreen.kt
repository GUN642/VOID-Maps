package app.voidmaps.ui

import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Traffic
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.toArgb
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
import app.voidmaps.map.Follow
import app.voidmaps.map.MapController
import app.voidmaps.map.StyleConfig
import app.voidmaps.nav.HazardRepo
import app.voidmaps.nav.LocationTracker
import app.voidmaps.nav.Navigator
import app.voidmaps.ui.theme.Design
import app.voidmaps.ui.theme.VoidTheme
import app.voidmaps.ui.theme.dotNumber
import app.voidmaps.ui.theme.headingStyle
import app.voidmaps.ui.theme.mapPalette
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
            mapReady = true
        }
    }

    // Map design and traffic layer.
    val palette = mapPalette(design)
    LaunchedEffect(mapReady, design, settings.tomtomKey, settings.trafficLayer, settings.trafficOriginalColors) {
        if (!mapReady) return@LaunchedEffect
        controller.applyStyle(
            StyleConfig(palette, settings.tomtomKey, settings.trafficLayer, settings.trafficOriginalColors),
            accent = c.accent.toArgb(),
            background = if (c.isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
        )
    }
    LaunchedEffect(mapReady, hasPermission) {
        if (mapReady && hasPermission) controller.onPermissionGranted(c.accent.toArgb(), if (c.isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }

    val location by LocationTracker.location.collectAsState()
    val nav by Navigator.state.collectAsState()

    // Position puck: during navigation snapped onto the route so it does not wobble beside the road.
    LaunchedEffect(location, nav?.snap, mapReady) {
        val loc = location ?: return@LaunchedEffect
        val n = nav
        val snap = n?.snap
        val shown = if (n != null && snap != null && !n.offRoute && snap.offset < 30) {
            Location(loc).apply {
                latitude = snap.point.lat
                longitude = snap.point.lon
                if (loc.speed > 1.5f) bearing = n.route.line.segmentBearing(snap.segment).toFloat()
            }
        } else {
            loc
        }
        controller.updateLocation(shown)
    }

    // Route line: full preview, or the part still ahead while navigating.
    val preview = vm.preview
    LaunchedEffect(mapReady, vm.mode, preview, nav?.routeVersion, nav?.snap?.segment) {
        if (!mapReady) return@LaunchedEffect
        val n = nav
        val snap = n?.snap
        when {
            vm.mode == Mode.NAVIGATE && n != null ->
                controller.setRoute(if (snap != null) n.route.line.remainder(snap.segment, snap.point) else n.route.points)
            vm.mode == Mode.PREVIEW || vm.mode == Mode.SEARCH -> controller.setRoute(preview?.points)
            else -> controller.setRoute(null)
        }
    }
    LaunchedEffect(mapReady, vm.destination) { if (mapReady) controller.setDestination(vm.destination) }

    val incidents by HazardRepo.incidents.collectAsState()
    val cameras by HazardRepo.cameras.collectAsState()
    LaunchedEffect(mapReady, incidents, cameras, settings.speedCameras) {
        if (mapReady) controller.setHazards(incidents + if (settings.speedCameras) cameras else emptyList())
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.background)) {
        val heightPx = with(density) { maxHeight.toPx() }
        val follow = when {
            !vm.following -> Follow.NONE
            vm.mode == Mode.NAVIGATE -> Follow.NAVIGATE
            vm.mode == Mode.BROWSE -> Follow.BROWSE
            else -> Follow.NONE
        }
        LaunchedEffect(mapReady, follow, settings.headingUp, settings.tilt3d, heightPx) {
            if (mapReady) controller.setFollow(follow, settings.headingUp, settings.tilt3d, heightPx * 0.42)
        }
        LaunchedEffect(mapReady, preview, vm.mode) {
            if (mapReady && preview != null && vm.mode == Mode.PREVIEW) {
                with(density) {
                    controller.showOverview(
                        preview.points, 40.dp.roundToPx(), 150.dp.roundToPx(), 40.dp.roundToPx(), 300.dp.roundToPx(),
                    )
                }
            }
        }

        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        when (vm.mode) {
            Mode.BROWSE -> BrowseOverlay(vm, settings, hasPermission, onRequestPermission)
            Mode.PREVIEW -> PreviewOverlay(vm, settings)
            Mode.NAVIGATE -> nav?.let { NavigationOverlay(vm, settings, it) }
            Mode.SEARCH -> {}
        }
        AnimatedVisibility(vm.mode == Mode.SEARCH, enter = fadeIn(), exit = fadeOut()) {
            SearchPanel(vm, settings)
        }
    }

    BackHandler(enabled = vm.mode == Mode.SEARCH || vm.mode == Mode.PREVIEW) {
        when (vm.mode) {
            Mode.SEARCH -> vm.closeSearch()
            Mode.PREVIEW -> vm.cancelPreview()
            else -> {}
        }
    }

    vm.tappedHazard?.let { h -> HazardDialog(h) { vm.tappedHazard = null } }
}

@Composable
private fun BrowseOverlay(vm: MainViewModel, settings: AppSettings, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val c = VoidTheme.colors
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
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
                        Icon(Icons.Outlined.Settings, "Einstellungen", tint = c.text)
                        if (vm.availableUpdate != null) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent).align(Alignment.TopEnd))
                        }
                    }
                }
            }
            if (settings.home != null || settings.work != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.home?.let { home ->
                        QuickChip("Zuhause", Icons.Outlined.Home) { vm.setDestination(home) }
                    }
                    settings.work?.let { work ->
                        QuickChip("Arbeit", Icons.Outlined.Work) { vm.setDestination(work) }
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MapButton(Icons.Outlined.Traffic, "Verkehr", onClick = {
                if (!settings.hasTraffic) vm.toast("Für Verkehrsdaten den TomTom-Schlüssel in den Einstellungen eintragen")
                else {
                    val on = !settings.trafficLayer
                    vm.update { setTraffic(on) }
                }
            }, active = settings.hasTraffic && settings.trafficLayer)
            SpeedCameraButton(vm, settings)
        }

        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
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
fun SpeedCameraButton(vm: MainViewModel, settings: AppSettings) {
    MapButton(
        Icons.Outlined.PhotoCamera, "Blitzer anzeigen",
        onClick = { vm.setSpeedCameras(!settings.speedCameras) },
        active = settings.speedCameras,
    )
}

@Composable
private fun QuickChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
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
private fun PreviewOverlay(vm: MainViewModel, settings: AppSettings) {
    val c = VoidTheme.colors
    val dest = vm.destination ?: return
    val route = vm.preview
    val incidents by HazardRepo.incidents.collectAsState()
    val cameras by HazardRepo.cameras.collectAsState()
    Box(Modifier.fillMaxSize()) {
        MapCard(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()) {
            Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Ziel")
                    Text(dest.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (dest.detail.isNotBlank()) {
                        Text(dest.detail, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { vm.openSearch() }) { Icon(Icons.Outlined.Search, "Suchen", tint = c.text) }
                IconButton(onClick = { vm.cancelPreview() }) { Icon(Icons.Outlined.Close, "Schließen", tint = c.text) }
            }
        }

        MapCard(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth()) {
            Column(Modifier.padding(22.dp)) {
                when {
                    vm.routing -> {
                        Label("Route wird berechnet")
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
                    route != null -> {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(Format.duration(route.duration).uppercase(), style = dotNumber(40), color = c.text)
                            Spacer(Modifier.width(14.dp))
                            Text(Format.distance(route.distance), style = MaterialTheme.typography.titleMedium, color = c.textMuted, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        val eta = System.currentTimeMillis() + (route.duration * 1000).toLong()
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Label("Ankunft ${Format.clock(eta)}", color = c.text)
                            if (route.trafficDelay >= 60) Label("+${Format.duration(route.trafficDelay)} Verkehr", color = c.warn)
                            Label(route.engine)
                        }
                        val onRoute = remember(route, incidents, cameras, settings.speedCameras) {
                            val list = incidents + if (settings.speedCameras) cameras else emptyList()
                            list.count { h ->
                                val s = route.line.snap(h.point)
                                s != null && s.offset < if (h.type == HazardType.SPEED_CAMERA) 35 else 60
                            }
                        }
                        if (onRoute > 0) {
                            Spacer(Modifier.height(8.dp))
                            Label("$onRoute ${if (onRoute == 1) "Meldung" else "Meldungen"} auf der Strecke", color = c.warn)
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill("Start", selected = true, onClick = { vm.startNavigation() }, filledColor = c.accent, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Pill(if (settings.speedCameras) "Blitzer an" else "Blitzer aus", selected = false, onClick = { vm.setSpeedCameras(!settings.speedCameras) })
                        }
                    }
                }
            }
        }
    }
}
