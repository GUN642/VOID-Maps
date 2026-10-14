package app.voidmaps.ui

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
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Traffic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.voidmaps.nav.NavState
import app.voidmaps.nav.Navigator
import app.voidmaps.ui.theme.VoidTheme
import app.voidmaps.ui.theme.dotNumber
import app.voidmaps.ui.theme.headingStyle

@Composable
fun NavigationOverlay(vm: MainViewModel, settings: AppSettings, s: NavState) {
    val c = VoidTheme.colors
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            ManeuverCard(s)
            s.warning?.let { w ->
                Spacer(Modifier.height(8.dp))
                WarningBanner(w.hazard.type, w.hazard.description, w.distance)
            }
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MapButton(
                if (settings.voice) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                "Sprachansagen",
                onClick = {
                    val on = !settings.voice
                    vm.update { setVoice(on) }
                    Navigator.onVoiceChanged(on)
                },
                active = settings.voice,
            )
            if (settings.hasTraffic) {
                MapButton(Icons.Outlined.Traffic, "Verkehr", onClick = {
                    val on = !settings.trafficLayer
                    vm.update { setTraffic(on) }
                }, active = settings.trafficLayer)
            }
            SpeedCameraButton(vm, settings)
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                SpeedBubble(s.speedKmh)
                Spacer(Modifier.weight(1f))
                if (!vm.following) {
                    MapButton(Icons.Outlined.MyLocation, "Zentrieren", onClick = { vm.following = true })
                }
            }
            Spacer(Modifier.height(10.dp))
            if (s.arrived) ArrivedBar(s) { vm.stopNavigation() } else BottomBar(s) { vm.stopNavigation() }
        }
    }
}

@Composable
private fun ManeuverCard(s: NavState) {
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
            if (m != null) ManeuverIcon(m.kind, arrow, Modifier.size(72.dp), exit = m.exit)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(Format.distance(s.distanceToNext).uppercase(), style = dotNumber(44), color = fg)
                val street = m?.street
                Text(
                    street ?: m?.text?.trimEnd('.') ?: s.destination.name,
                    style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
    val color = if (type == HazardType.SPEED_CAMERA) Color(0xFF111111) else c.warn
    Row(
        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
            .background(color).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (type == HazardType.SPEED_CAMERA) c.warn else Color.White))
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
            Stat(Format.duration(s.remainingTime), "Restzeit", Modifier.weight(1f))
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
