package app.voidmaps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.voidmaps.BuildConfig
import app.voidmaps.data.Hazard
import app.voidmaps.data.Release
import app.voidmaps.ui.theme.VoidTheme
import app.voidmaps.ui.theme.headingStyle

@Composable
fun VoidDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String?,
    onConfirm: () -> Unit,
    dismissText: String = "Abbrechen",
    content: @Composable () -> Unit,
) {
    val c = VoidTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title.uppercase(), style = headingStyle(24), color = c.text) },
        text = { content() },
        confirmButton = {
            if (confirmText != null) {
                TextButton(onClick = onConfirm) {
                    Text(confirmText.uppercase(), style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText.uppercase(), style = MaterialTheme.typography.labelLarge, color = c.textMuted)
            }
        },
    )
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb < 1) "%.0f KB".format(bytes / 1024.0) else "%.1f MB".format(mb)
}

@Composable
fun UpdateDialog(release: Release, onDismiss: () -> Unit, onUpdate: () -> Unit) {
    val c = VoidTheme.colors
    VoidDialog("Update", onDismiss, "Aktualisieren", onUpdate, dismissText = "Später") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Version ${release.version} ist verfügbar (installiert: ${BuildConfig.VERSION_NAME}).", color = c.text)
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Label("Neu in dieser Version")
                Spacer(Modifier.height(6.dp))
                Text(release.notes, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
            if (release.apkSize > 0) {
                Spacer(Modifier.height(10.dp))
                Label("Download: ${formatSize(release.apkSize)}")
            }
        }
    }
}

@Composable
fun CrashDialog(report: String, onShare: () -> Unit, onDismiss: () -> Unit) {
    val c = VoidTheme.colors
    VoidDialog("Absturz", onDismiss, "Bericht teilen", onShare, dismissText = "Schließen") {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "VOID Maps ist beim letzten Mal abgestürzt. Teile den Bericht, damit der Fehler behoben werden kann. " +
                    "Er enthält nur Gerät, Android-Version und die Fehlerstelle.",
                color = c.text,
            )
            Text(report.lines().take(14).joinToString("\n"), color = c.textMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun HazardDialog(h: Hazard, onDismiss: () -> Unit) {
    val c = VoidTheme.colors
    VoidDialog(h.type.label, onDismiss, null, {}, dismissText = "Schließen") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(h.description, color = c.text)
            if (h.delay > 0) Label("Verzögerung ca. ${(h.delay + 30) / 60} min", color = c.warn)
            Label(if (h.id.startsWith("osm:")) "Quelle: OpenStreetMap" else "Quelle: TomTom")
        }
    }
}
