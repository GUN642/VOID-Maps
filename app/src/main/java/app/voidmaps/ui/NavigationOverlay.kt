package app.voidmaps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import app.voidmaps.map.MapIcons
import app.voidmaps.ui.theme.mapPalette
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.voidmaps.data.AppSettings
import app.voidmaps.data.Format
import app.voidmaps.data.HazardType
import app.voidmaps.data.LaneDir
import app.voidmaps.data.LaneSection
import app.voidmaps.data.ManeuverKind
import app.voidmaps.data.NavView
import app.voidmaps.nav.NavState
import app.voidmaps.nav.Navigator
import app.voidmaps.ui.theme.VoidTheme
import app.voidmaps.ui.theme.dotNumber
import app.voidmaps.ui.theme.headingStyle

@Composable
fun NavigationOverlay(vm: MainViewModel, settings: AppSettings, s: NavState, onTopMeasured: (Int) -> Unit = {}) {
    if (isLandscape()) {
        NavigationOverlayLandscape(vm, settings, s, onTopMeasured)
        return
    }
    val c = VoidTheme.colors
    val density = LocalDensity.current
    var topBottom by remember { mutableFloatStateOf(0f) }
    var bottomTop by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).onGloballyPositioned {
                topBottom = it.boundsInRoot().bottom
                onTopMeasured(topBottom.toInt())
            },
        ) {
            ManeuverCard(s)
            s.lanes?.let { lanes ->
                Spacer(Modifier.height(6.dp))
                LaneStrip(lanes)
            }
            s.faster?.let { f ->
                Spacer(Modifier.height(8.dp))
                FasterRouteCard(f.savedSeconds, onAccept = { Navigator.acceptFaster() }, onDecline = { Navigator.declineFaster() })
            }
            s.warning?.let { w ->
                Spacer(Modifier.height(8.dp))
                WarningBanner(w.hazard.type, w.hazard.description, w.distance)
            }
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 160.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VoiceButton(vm, settings.voiceMode)
            SatelliteButton(vm, settings.satellite)
            val (icon, label) = when (settings.navView) {
                NavView.HEADING -> Icons.Outlined.Navigation to "Route oben"
                NavView.NORTH -> Icons.Outlined.Explore to "Norden oben"
                NavView.OVERVIEW -> Icons.Outlined.Map to "Übersicht"
            }
            MapButton(icon, "Ausrichtung: $label", onClick = { vm.cycleNavView() }, active = settings.navView != NavView.HEADING)
            MapButton(Icons.Outlined.Search, "Zwischenziel oder Sonderziel suchen", onClick = { vm.openSearch(Pick.STOP) })
        }

        // Route progress on the left edge: cameras, jams and incidents ahead.
        if (topBottom > 0 && bottomTop - topBottom > with(density) { 160.dp.toPx() }) {
            val gap = with(density) { 12.dp.toPx() }
            RouteProgressBar(
                s, settings,
                Modifier
                    .offset { IntOffset(with(density) { 6.dp.roundToPx() }, (topBottom + gap).toInt()) }
                    .width(56.dp)
                    .height(with(density) { (bottomTop - topBottom - 2 * gap).toDp() }),
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)
                .onGloballyPositioned { bottomTop = it.boundsInRoot().top },
        ) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                SpeedBubble(s.speedKmh)
                Spacer(Modifier.weight(1f))
                if (!vm.following || settings.navView == NavView.OVERVIEW) {
                    MapButton(Icons.Outlined.MyLocation, "Zentrieren", onClick = {
                        if (settings.navView == NavView.OVERVIEW) vm.cycleNavView()
                        vm.following = true
                    })
                }
            }
            Spacer(Modifier.height(10.dp))
            val nextStop = s.stops.firstOrNull()
            if (nextStop != null && s.nextStopDistance != null && !s.arrived) {
                StopBar(nextStop.name, s.nextStopDistance, s.nextStopTime ?: 0.0, s.stops.size) { Navigator.removeStop(nextStop) }
                Spacer(Modifier.height(8.dp))
            }
            if (s.arrived) ArrivedBar(s) { vm.stopNavigation() } else BottomBar(s) { vm.stopNavigation() }
        }
    }
}

/** Width of the side panel in landscape; the map uses the rest. */
val LANDSCAPE_PANEL = 340.dp

@Composable
fun isLandscape(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.screenWidthDp > cfg.screenHeightDp
}

/**
 * Landscape: everything in a slim panel on the left (turn, lanes, warnings, stop, arrival), the map
 * keeps the whole rest of the screen. Buttons sit in a column on the right edge.
 */
@Composable
private fun NavigationOverlayLandscape(vm: MainViewModel, settings: AppSettings, s: NavState, onTopMeasured: (Int) -> Unit) {
    val density = LocalDensity.current
    // North arrow simply at the top right of the map.
    LaunchedEffect(Unit) { onTopMeasured(with(density) { 24.dp.roundToPx() }) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxHeight().width(LANDSCAPE_PANEL)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ManeuverCard(s, compact = true)
            s.lanes?.let { LaneStrip(it) }
            s.faster?.let { f ->
                FasterRouteCard(f.savedSeconds, onAccept = { Navigator.acceptFaster() }, onDecline = { Navigator.declineFaster() })
            }
            s.warning?.let { w -> WarningBanner(w.hazard.type, w.hazard.description, w.distance) }
            Spacer(Modifier.weight(1f))
            val nextStop = s.stops.firstOrNull()
            if (nextStop != null && s.nextStopDistance != null && !s.arrived) {
                StopBar(nextStop.name, s.nextStopDistance, s.nextStopTime ?: 0.0, s.stops.size) { Navigator.removeStop(nextStop) }
            }
            if (s.arrived) ArrivedBar(s) { vm.stopNavigation() } else BottomBar(s) { vm.stopNavigation() }
        }

        // Progress bar along the left edge of the map, right next to the panel.
        RouteProgressBar(
            s, settings,
            Modifier.padding(start = LANDSCAPE_PANEL + 4.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                .padding(vertical = 70.dp).width(52.dp).fillMaxHeight(),
        )

        Column(
            Modifier.align(Alignment.CenterEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VoiceButton(vm, settings.voiceMode)
            SatelliteButton(vm, settings.satellite)
            val icon = when (settings.navView) {
                NavView.HEADING -> Icons.Outlined.Navigation
                NavView.NORTH -> Icons.Outlined.Explore
                NavView.OVERVIEW -> Icons.Outlined.Map
            }
            MapButton(icon, "Ausrichtung", onClick = { vm.cycleNavView() }, active = settings.navView != NavView.HEADING)
            MapButton(Icons.Outlined.Search, "Zwischenziel oder Sonderziel suchen", onClick = { vm.openSearch(Pick.STOP) })
        }

        Row(
            Modifier.align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                .padding(10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!vm.following || settings.navView == NavView.OVERVIEW) {
                MapButton(Icons.Outlined.MyLocation, "Zentrieren", onClick = {
                    if (settings.navView == NavView.OVERVIEW) vm.cycleNavView()
                    vm.following = true
                })
            }
            SpeedBubble(s.speedKmh)
        }
    }
}

@Composable
private fun FasterRouteCard(saved: Double, onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = VoidTheme.colors
    MapCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Schnellere Route", color = c.accent)
                Text("${(saved / 60).toInt().coerceAtLeast(1)} MIN SCHNELLER", style = dotNumber(20), color = c.text)
            }
            Pill("Ablehnen", selected = false, onClick = onDecline)
            Spacer(Modifier.width(8.dp))
            Pill("Annehmen", selected = true, onClick = onAccept, filledColor = c.accent)
        }
    }
}

@Composable
private fun StopBar(name: String, distance: Double, seconds: Double, count: Int, onSkip: () -> Unit) {
    val c = VoidTheme.colors
    MapCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).border(2.dp, c.accent, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Label(if (count > 1) "Zwischenziel 1 von $count" else "Zwischenziel")
                Text(name, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.clock(System.currentTimeMillis() + (seconds * 1000).toLong()), style = dotNumber(18), color = c.accent)
                Label("${Format.hhmm(seconds)} · ${Format.distance(distance)}")
            }
            IconButton(onClick = onSkip) { Icon(Icons.Outlined.Close, "Zwischenziel entfernen", tint = c.textMuted, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun ManeuverCard(s: NavState, compact: Boolean = false) {
    val c = VoidTheme.colors
    val m = s.route.maneuvers.getOrNull(s.next)
    val dark = c.isDark
    // VOID: black card, white text, red arrow. Light designs: accent card for contrast on the bright map.
    val bg = if (dark) c.surface else c.accent
    val fg = if (dark) c.text else c.onAccent
    val arrow = if (dark) c.accent else c.onAccent
    Column(
        Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
            .background(bg).border(1.dp, c.divider, RoundedCornerShape(28.dp)).padding(18.dp),
    ) {
        if (s.rerouting) {
            Label("Route wird neu berechnet", color = fg)
            Spacer(Modifier.height(12.dp))
            DotLoader(Modifier.fillMaxWidth().height(12.dp))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m != null) ManeuverIcon(m.kind, arrow, Modifier.size(if (compact) 54.dp else 72.dp), exit = m.exit)
            Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
            Column(Modifier.weight(1f)) {
                Text(Format.distance(s.distanceToNext).uppercase(), style = dotNumber(if (compact) 32 else 44), color = fg)
                val street = m?.street
                Text(
                    street ?: m?.text?.trimEnd('.') ?: s.destination.name,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (street != null && m != null && m.text.isNotBlank()) {
                    Text(m.text.trimEnd('.'), style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Next-but-one manoeuvre when it follows closely.
        val after = s.route.maneuvers.getOrNull(s.next + 1)
        if (after != null && s.next + 1 < s.route.maneuverAlong.size &&
            s.route.maneuverAlong[s.next + 1] - s.route.maneuverAlong[s.next] < 300
        ) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Danach", color = fg.copy(alpha = 0.7f))
                Spacer(Modifier.width(8.dp))
                ManeuverIcon(after.kind, fg, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(after.street ?: after.text, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun WarningBanner(type: HazardType, description: String, distance: Double) {
    val c = VoidTheme.colors
    val color = if (type.isCamera) Color(0xFF111111) else c.warn
    Row(
        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
            .background(color).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (type.isCamera) c.warn else Color.White))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(type.label.uppercase(), style = headingStyle(20), color = Color.White)
            val detail = description.lineSequence().firstOrNull().orEmpty()
            if (detail.isNotBlank() && detail != type.label) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(Format.distance(distance).uppercase(), style = dotNumber(24), color = Color.White)
    }
}

@Composable
private fun SpeedBubble(kmh: Int) {
    val c = VoidTheme.colors
    Box(
        Modifier.size(72.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(c.surface).border(1.dp, c.divider, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$kmh", style = dotNumber(26), color = c.text)
            Label("km/h")
        }
    }
}

@Composable
private fun BottomBar(s: NavState, onStop: () -> Unit) {
    val c = VoidTheme.colors
    MapCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 22.dp, end = 10.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Stat(Format.clock(s.eta), "Ankunft", Modifier.weight(1f), highlight = true)
            Stat(Format.hhmm(s.remainingTime), "Restzeit", Modifier.weight(1f))
            Stat(Format.distance(s.remainingDistance), "Strecke", Modifier.weight(1f))
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(c.surfaceHigh).clickable(onClick = onStop),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, "Navigation beenden", tint = c.text) }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, highlight: Boolean = false) {
    val c = VoidTheme.colors
    Column(modifier) {
        Text(value.uppercase(), style = dotNumber(22), color = if (highlight) c.accent else c.text, maxLines = 1)
        Label(label)
    }
}

@Composable
private fun ArrivedBar(s: NavState, onStop: () -> Unit) {
    val c = VoidTheme.colors
    MapCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Text("ZIEL ERREICHT", style = headingStyle(30), color = c.text)
            Text(s.destination.name, style = MaterialTheme.typography.bodyLarge, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Pill("Beenden", selected = true, onClick = onStop, filledColor = c.accent, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RouteProgressBar(s: NavState, settings: AppSettings, modifier: Modifier) {
    val c = VoidTheme.colors
    val context = LocalContext.current
    val design = VoidTheme.design
    val palette = mapPalette(design)
    val icons = remember(design) { MapIcons.all(context, palette).mapValues { it.value.asImageBitmap() } }
    val along = s.snap?.along ?: 0.0
    // The bar shows the next 30 km, or the rest of the route when that is shorter.
    val horizon = s.remainingDistance.coerceIn(1_000.0, 30_000.0)
    val events = s.routeHazards.filter { e ->
        (settings.speedCameras || !e.hazard.type.isCamera) &&
            (e.endAlong ?: e.along) >= along - 10 && e.along - along <= horizon
    }
    val next = events.filter { it.along >= along - 10 }.minByOrNull { it.along }
    val stopsAhead = s.route.waypointAlong.filter { it > along && it - along <= horizon }
    BoxWithConstraints(modifier) {
        val dens = LocalDensity.current
        val h = constraints.maxHeight.toFloat()
        val top = with(dens) { 22.dp.toPx() }
        val bottom = h - with(dens) { 54.dp.toPx() }
        val cx = constraints.maxWidth / 2f
        fun y(d: Double): Float = bottom - ((d / horizon).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()
        val heavy = Color(android.graphics.Color.parseColor(palette.trafficHeavy))
        val amber = Color(0xFFFFB800)
        Canvas(Modifier.fillMaxSize()) {
            val w = 6.dp.toPx()
            drawLine(c.textMuted.copy(alpha = 0.35f), Offset(cx, top), Offset(cx, bottom), strokeWidth = w, cap = StrokeCap.Round)
            events.forEach { e ->
                val end = e.endAlong ?: return@forEach
                val color = when (e.hazard.type) {
                    HazardType.JAM -> heavy
                    HazardType.ROADWORKS, HazardType.LANE_CLOSED -> amber
                    else -> c.warn
                }
                drawLine(color, Offset(cx, y((e.along - along).coerceAtLeast(0.0))), Offset(cx, y(end - along)), strokeWidth = w, cap = StrokeCap.Round)
            }
            s.route.traffic.forEach { t ->
                if (t.endAlong <= along || t.startAlong - along > horizon) return@forEach
                val color = when {
                    t.works -> amber
                    t.level >= 3 -> c.warn
                    t.level == 2 -> heavy
                    else -> amber
                }
                drawLine(color, Offset(cx, y((t.startAlong - along).coerceAtLeast(0.0))), Offset(cx, y(t.endAlong - along)), strokeWidth = w, cap = StrokeCap.Round)
            }
            stopsAhead.forEach { a ->
                drawCircle(c.surface, 7.dp.toPx(), Offset(cx, y(a - along)))
                drawCircle(c.accent, 7.dp.toPx(), Offset(cx, y(a - along)), style = Stroke(3.dp.toPx()))
            }
            if (s.remainingDistance <= horizon) {
                val yd = y(s.remainingDistance)
                drawCircle(c.accent, 8.dp.toPx(), Offset(cx, yd))
                drawCircle(c.surface, 3.dp.toPx(), Offset(cx, yd))
            }
        }
        val iconPx = with(dens) { 24.dp.roundToPx() }
        events.forEach { e ->
            val bmp = icons[e.hazard.type.icon] ?: return@forEach
            val yy = y((e.along - along).coerceAtLeast(0.0))
            Image(
                bmp, e.hazard.type.label,
                Modifier.offset { IntOffset((cx - iconPx / 2f).toInt(), (yy - iconPx / 2f).toInt()) }.size(24.dp),
            )
        }
        Text(
            Format.distance(horizon).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.textMuted,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Navigation, null, tint = c.accent, modifier = Modifier.size(22.dp))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.surface.copy(alpha = 0.9f)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                Text(
                    Format.distance(if (next != null) (next.along - along).coerceAtLeast(0.0) else s.remainingDistance).uppercase(),
                    style = MaterialTheme.typography.labelSmall, color = c.text, maxLines = 1,
                )
            }
        }
    }
}

private fun laneKind(d: LaneDir): ManeuverKind = when (d) {
    LaneDir.STRAIGHT -> ManeuverKind.STRAIGHT
    LaneDir.SLIGHT_RIGHT -> ManeuverKind.SLIGHT_RIGHT
    LaneDir.RIGHT -> ManeuverKind.RIGHT
    LaneDir.SHARP_RIGHT -> ManeuverKind.SHARP_RIGHT
    LaneDir.SLIGHT_LEFT -> ManeuverKind.SLIGHT_LEFT
    LaneDir.LEFT -> ManeuverKind.LEFT
    LaneDir.SHARP_LEFT -> ManeuverKind.SHARP_LEFT
    LaneDir.UTURN -> ManeuverKind.UTURN
}

/** Lane assistant: one box per lane, the lanes to use lit up. */
@Composable
private fun LaneStrip(section: LaneSection) {
    val c = VoidTheme.colors
    Row(
        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
            .background(if (c.isDark) c.surface else Color(0xFF1A1A1A)).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        section.lanes.forEachIndexed { i, lane ->
            if (i > 0) {
                Canvas(Modifier.width(6.dp).height(40.dp)) {
                    drawLine(
                        Color.White.copy(alpha = 0.35f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height),
                        strokeWidth = 2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                    )
                }
            }
            Box(
                Modifier.width(44.dp).height(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (lane.recommended) c.accent.copy(alpha = 0.28f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                // Directions not to take first, the one to follow on top.
                lane.directions.sortedBy { lane.recommended && (lane.follow == null || it == lane.follow) }.forEach { d ->
                    val active = lane.recommended && (lane.follow == null || d == lane.follow)
                    ManeuverIcon(laneKind(d), if (active) Color.White else Color.White.copy(alpha = 0.3f), Modifier.size(34.dp))
                }
            }
        }
    }
}
