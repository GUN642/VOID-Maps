package app.voidmaps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.voidmaps.BuildConfig
import app.voidmaps.data.AppSettings
import app.voidmaps.data.ChargeOperator
import app.voidmaps.data.VoiceMode
import app.voidmaps.map.OfflineMaps
import app.voidmaps.nav.Navigator
import app.voidmaps.data.RoutingEngine
import app.voidmaps.data.SatelliteSource
import app.voidmaps.data.ThemeChoice
import app.voidmaps.data.Updater
import app.voidmaps.ui.theme.Design
import app.voidmaps.ui.theme.VoidTheme
import app.voidmaps.ui.theme.headingStyle
import app.voidmaps.ui.theme.mapPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, s: AppSettings, onBack: () -> Unit) {
    val c = VoidTheme.colors
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", tint = c.text) }
        }
        Text("EINSTELLUNGEN", style = headingStyle(40), color = c.text, modifier = Modifier.padding(horizontal = 20.dp))
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp).navigationBarsPadding(),
        ) {
            Group("Design")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeChoice.entries.forEach { t ->
                    ThemeTile(t, t == s.theme) { vm.update { setTheme(t) } }
                }
            }
            if (s.theme == ThemeChoice.AUTO) {
                Label("Auto folgt dem dunklen Modus des Systems: nachts Void, tagsüber Hell.", Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(6.dp))
            Toggle("Dot-Matrix-Überschriften", "Pixelschrift im Nothing-Stil", s.dotHeadings) { v -> vm.update { setDotHeadings(v) } }

            Group("Karte")
            Toggle("Fahrtrichtung oben", "Karte dreht sich mit, sonst Norden oben", s.headingUp) { v -> vm.update { setHeadingUp(v) } }
            Toggle("3D-Ansicht bei Navigation", "Geneigte Karte während der Zielführung", s.tilt3d) { v -> vm.update { setTilt(v) } }
            Toggle("Satellitenbild", "Luftbild statt gezeichneter Karte (auch per Knopf auf der Karte)", s.satellite) { v -> vm.update { setSatellite(v) } }
            run {
                Label("Luftbild-Quelle")
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SatelliteSource.entries.forEach { src ->
                        Pill(src.label, selected = src == s.satelliteSource, onClick = { vm.update { setSatelliteSource(src) } })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Toggle("Amtliche Luftbilder BW & Bayern", "20-cm-Orthofotos der Landesvermessung ab Straßenzoom, aktueller und schärfer", s.officialImagery) { v ->
                    vm.update { setOfficialImagery(v) }
                }
            }
            Toggle("3D-Gebäude", "Gebäude mit Höhe, sichtbar beim Neigen der Karte", s.buildings3d) { v -> vm.update { setBuildings3d(v) } }
            Toggle("Gelände-Relief", "Berge und Täler plastisch schattiert", s.terrain) { v -> vm.update { setTerrain(v) } }
            Toggle("Ampeln", "Ampeln aus OpenStreetMap ab Straßenzoom", s.trafficSignals) { v -> vm.setTrafficSignals(v) }

            Group("Navigation")
            Label("Sprachansagen")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceMode.entries.forEach { m ->
                    Pill(m.label, selected = m == s.voiceMode, onClick = {
                        vm.update { setVoiceMode(m) }
                        Navigator.onVoiceModeChanged(m)
                    })
                }
            }
            Spacer(Modifier.height(6.dp))
            Toggle("Kurze Ansagen", "„In 300 Metern rechts abbiegen“ statt ausführlicher Sätze", s.shortAnnouncements) { v ->
                vm.update { setShortAnnouncements(v) }
            }
            VoiceSection(vm, s)
            Toggle("Mautstraßen vermeiden", null, s.avoidTolls) { v -> vm.update { setAvoidTolls(v) } }
            Toggle("Autobahnen vermeiden", null, s.avoidMotorways) { v -> vm.update { setAvoidMotorways(v) } }
            Spacer(Modifier.height(8.dp))
            Label("Routing")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutingEngine.entries.forEach { e ->
                    Pill(e.label, selected = e == s.routing, onClick = { vm.update { setRouting(e) } })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Automatisch: TomTom mit Live-Verkehr, wenn ein Schlüssel hinterlegt ist, sonst Valhalla auf OpenStreetMap-Daten.",
                color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
            )

            Group("Verkehr · TomTom")
            KeyField(s.tomtomKeyInput) { vm.setTomTomKey(it) }
            Spacer(Modifier.height(6.dp))
            Text(
                if (s.tomtomKeyInput.isBlank() && app.voidmaps.BuildConfig.TOMTOM_KEY.isNotBlank()) "Fest eingebauter Schlüssel aktiv. Ein eigener Schlüssel hier hat Vorrang."
                else if (s.hasTraffic) "Schlüssel aktiv. Verkehrslage, Unfälle, Baustellen, Stauenden und Sperrungen werden geladen."
                else "Kostenlosen Schlüssel auf developer.tomtom.com anlegen (Freemium reicht privat) und hier einfügen.",
                color = if (s.hasTraffic) c.text else c.textMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Toggle("Verkehrslage anzeigen", "Staus als Punktreihen auf der Karte", s.trafficLayer) { v -> vm.update { setTraffic(v) } }
            Toggle("TomTom-Originalfarben", "Grün/Gelb/Rot statt der Farben des Designs", s.trafficOriginalColors) { v ->
                vm.update { setTrafficOriginal(v) }
            }

            Group("Ladesäulen")
            Toggle("Ladesäulen anzeigen", "Schnelllader aus OpenStreetMap auf der Karte", s.chargers) { v -> vm.setChargers(v) }
            if (s.chargers) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChargeOperator.entries.forEach { op ->
                        Pill(op.label, selected = op in s.chargerOperators, onClick = { vm.toggleChargerOperator(op) },
                            filledColor = Color(android.graphics.Color.parseColor(op.color)))
                    }
                }
            }

            Group("Offline-Karten")
            OfflineSection(vm)

            Group("Blitzer")
            Toggle("Blitzer anzeigen", "Feste Anlagen aus OpenStreetMap, mit Ansage", s.speedCameras) { v -> vm.setSpeedCameras(v) }
            if (s.speedCameras) {
                Toggle("Blitzer.de-Daten", "Zusätzliche feste Blitzer aus der Blitzer.de-Karte (inoffizielle Schnittstelle)", s.blitzerDe) { v -> vm.setBlitzerDe(v) }
                if (s.blitzerDe) {
                    Toggle("Mobile Blitzer", "Gemeldete mobile Kontrollen und Blitzeranhänger, alle 10 Minuten aktualisiert", s.mobileCameras) { v -> vm.setMobileCameras(v) }
                    Pill("Blitzer.de jetzt testen", selected = false, onClick = { vm.testBlitzerDe() })
                    Spacer(Modifier.height(6.dp))
                    val bz by app.voidmaps.nav.HazardRepo.bzStatus.collectAsState()
                    if (bz.isNotBlank()) Label(bz, color = if (bz.startsWith("Blitzer.de OK")) c.textMuted else c.warn)
                }
            }
            Text(
                "Hinweis: In Deutschland verbietet § 23 Abs. 1c StVO dem Fahrer, während der Fahrt Blitzerwarner zu nutzen " +
                    "(75 € und ein Punkt). Ist der Schalter aus, werden keine Blitzerdaten geladen, angezeigt oder angesagt.",
                color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
            )

            Group("Demo")
            Text(
                "Simulierte Fahrt Stuttgart → Heilbronn (Stadt, Landstraße, A81) mit Ansagen, Spuren, Warnungen und Android Auto. " +
                    "Der echte Standort wird dabei ignoriert. Eine eigene Route lässt sich in der Routenvorschau mit „Demo“ abfahren.",
                color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Pill("Demo-Fahrt starten", selected = true, onClick = { vm.startDemo() }, filledColor = c.accent)

            Group("Updates")
            UpdateSection(vm, s)

            Group("Über")
            Text("VOID Maps ${BuildConfig.VERSION_NAME}", color = c.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Kartendaten © OpenStreetMap-Mitwirkende (ODbL). Kacheln: OpenFreeMap, Schema OpenMapTiles. " +
                    "Darstellung: MapLibre Native. Routing: Valhalla (FOSSGIS-Server). Suche: Photon (komoot). " +
                    "Satellit: Esri World Imagery bzw. TomTom; Orthofotos © LGL-BW (dl-de/by-2-0) und © Bayerische Vermessungsverwaltung (CC BY 4.0). " +
                    "Höhendaten: Terrain Tiles (Mapzen/AWS). " +
                    "Blitzer: Overpass API. Verkehr: © TomTom. Schriften: Doto & Space Mono (SIL Open Font License).",
                color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Voice of the announcements: the German voices of the phone's speech engine, plus speaking rate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSection(vm: MainViewModel, s: AppSettings) {
    val c = VoidTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val speaker = remember { vm.voiceSpeaker() }
    val voices by speaker.voices.collectAsState()
    Spacer(Modifier.height(8.dp))
    Label("Stimme")
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VoiceRow("Standard", "Voreinstellung der Sprachausgabe", s.voiceName.isBlank()) { vm.chooseVoice("") }
        voices.forEach { v -> VoiceRow(v.label, v.detail, v.name == s.voiceName) { vm.chooseVoice(v.name) } }
    }
    if (voices.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("Stimmen werden geladen …", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(10.dp))
    Label("Sprechtempo")
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Ruhig" to 0.9f, "Normal" to 1.0f, "Zügig" to 1.12f).forEach { (label, rate) ->
            Pill(label, selected = kotlin.math.abs(s.speechRate - rate) < 0.01f, onClick = { vm.chooseSpeechRate(rate) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Antippen spielt eine Probeansage. Am natürlichsten klingen die Online-Stimmen der „Sprachausgabe von Google“ " +
            "(ohne Netz wird automatisch die Offline-Stimme genutzt). " +
            (if (speaker.engineLabel.isNotBlank()) "Aktuelle Sprachausgabe: ${speaker.engineLabel}." else ""),
        color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Stimmen herunterladen", selected = false, onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { vm.toast("Nicht verfügbar") }
        })
        Pill("Sprachausgabe-Einstellungen", selected = false, onClick = {
            runCatching {
                context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { vm.toast("Nicht verfügbar") }
        })
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun VoiceRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val c = VoidTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (selected) c.accent else c.divider, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
        }
        if (selected) Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.accent))
    }
}

@Composable
private fun KeyField(current: String, onSave: (String) -> Unit) {
    val c = VoidTheme.colors
    var text by remember(current) { mutableStateOf(current) }
    var visible by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.surfaceHigh).padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("API-Schlüssel", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = text,
                onValueChange = { text = it.trim() },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(onClick = { visible = !visible }) {
            Text(if (visible) "VERBERGEN" else "ZEIGEN", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
    }
    if (text != current) {
        Spacer(Modifier.height(8.dp))
        Pill("Speichern", selected = true, onClick = { onSave(text) }, filledColor = c.accent)
    }
}

@Composable
private fun UpdateSection(vm: MainViewModel, s: AppSettings) {
    val c = VoidTheme.colors
    LaunchedEffect(Unit) { if (vm.releases.isEmpty()) vm.checkForUpdates(manual = false) }
    val update = vm.availableUpdate
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Installiert: ${BuildConfig.VERSION_NAME}", color = c.text, style = MaterialTheme.typography.bodyLarge)
            vm.updateStatus?.let {
                Text(it, color = if (update != null) c.accent else c.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (vm.checkingUpdate) CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp)
    }
    Spacer(Modifier.height(12.dp))
    val progress = vm.downloadProgress
    if (progress != null) {
        val (done, total) = progress
        val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        DotBar(f, Modifier.fillMaxWidth().height(10.dp), dots = 32)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("${formatSize(done)} / ${formatSize(total)}", Modifier.weight(1f))
            TextButton(onClick = { vm.cancelDownload() }) { Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.accent) }
        }
    } else {
        FlowRow8 {
            if (update != null) {
                Pill("Auf ${update.version} aktualisieren", selected = true, onClick = { vm.downloadUpdate(update) }, filledColor = c.accent)
            }
            Pill("Nach Updates suchen", selected = false, onClick = { vm.checkForUpdates(manual = true) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Toggle("Automatisch prüfen", "Beim Start der App nach neuen Versionen suchen", s.autoUpdateCheck) { v ->
        vm.update { setAutoUpdate(v) }
    }

    if (vm.releases.isNotEmpty()) {
        Group("Changelog")
        var expanded by remember { mutableStateOf(false) }
        val shown = if (expanded) vm.releases else vm.releases.take(5)
        shown.forEach { r ->
            val installed = r.version == BuildConfig.VERSION_NAME
            val newer = Updater.isNewer(r.version, BuildConfig.VERSION_NAME)
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.version, style = headingStyle(22), color = c.text)
                    Spacer(Modifier.width(10.dp))
                    Label(r.publishedAt.take(10).split('-').reversed().joinToString("."), Modifier.weight(1f))
                    when {
                        installed -> Label("Installiert", color = c.text)
                        newer -> Label("Neu", color = c.accent)
                    }
                }
                if (r.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(r.notes, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (vm.releases.size > 5) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "WENIGER" else "ALLE ${vm.releases.size} VERSIONEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
            }
        }
    }
}

@Composable
private fun OfflineSection(vm: MainViewModel) {
    val c = VoidTheme.colors
    val areas by OfflineMaps.areas.collectAsState()
    Text(
        "Kartenbereiche für Funklöcher speichern. Suche und Routenberechnung brauchen weiterhin Internet.",
        color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(10.dp))
    areas.forEach { a ->
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, color = c.text, style = MaterialTheme.typography.bodyLarge)
                    Label(
                        when {
                            a.error != null -> "Fehler: ${a.error}"
                            a.complete -> "Fertig · ${formatSize(a.bytes)}"
                            a.downloading -> "${(a.progress * 100).toInt()} % · ${formatSize(a.bytes)}"
                            else -> "Pausiert · ${(a.progress * 100).toInt()} %"
                        },
                        color = if (a.error != null) c.warn else c.textMuted,
                    )
                }
                if (!a.complete) {
                    TextButton(onClick = { if (a.downloading) OfflineMaps.pause(a.id) else OfflineMaps.resume(a.id) }) {
                        Text(if (a.downloading) "PAUSE" else "WEITER", style = MaterialTheme.typography.labelMedium, color = c.accent)
                    }
                }
                TextButton(onClick = { OfflineMaps.delete(a.id) }) {
                    Text("LÖSCHEN", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                }
            }
            if (!a.complete) {
                Spacer(Modifier.height(4.dp))
                DotBar(a.progress, Modifier.fillMaxWidth().height(8.dp), dots = 32)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Pill("Bereich herunterladen", selected = true, onClick = { vm.startOfflineSelection() }, filledColor = c.accent)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow8(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(28.dp))
    Label(title)
    Spacer(Modifier.height(10.dp))
}

/** Mini map preview of a design: background, a road and the route in the design's colours. */
@Composable
private fun ThemeTile(choice: ThemeChoice, selected: Boolean, onClick: () -> Unit) {
    val c = VoidTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            Modifier.size(68.dp).clip(RoundedCornerShape(20.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.divider, RoundedCornerShape(20.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val designs = if (choice == ThemeChoice.AUTO) listOf(Design.VOID, Design.HELL) else listOf(
                if (choice == ThemeChoice.HELL) Design.HELL else Design.VOID,
            )
            Canvas(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))) {
                designs.forEachIndexed { i, d ->
                    val p = mapPalette(d)
                    val w = size.width / designs.size
                    val x0 = i * w
                    drawRect(Color(android.graphics.Color.parseColor(p.background)), Offset(x0, 0f), androidx.compose.ui.geometry.Size(w, size.height))
                    drawRect(Color(android.graphics.Color.parseColor(p.water)), Offset(x0, size.height * 0.72f), androidx.compose.ui.geometry.Size(w, size.height * 0.28f))
                    drawLine(Color(android.graphics.Color.parseColor(p.motorway)), Offset(x0, size.height * 0.3f), Offset(x0 + w, size.height * 0.55f), strokeWidth = 5f, cap = StrokeCap.Round)
                    drawLine(Color(android.graphics.Color.parseColor(p.route)), Offset(x0 + w * 0.3f, size.height), Offset(x0 + w * 0.6f, size.height * 0.15f), strokeWidth = 7f, cap = StrokeCap.Round)
                }
            }
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).clip(CircleShape).background(c.accent))
        }
        Spacer(Modifier.height(4.dp))
        Label(choice.label, color = if (selected) c.text else c.textMuted)
    }
}
