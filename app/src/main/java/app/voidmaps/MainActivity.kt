package app.voidmaps

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.voidmaps.nav.LocationTracker
import app.voidmaps.nav.Navigator
import app.voidmaps.ui.CrashDialog
import app.voidmaps.ui.MainViewModel
import app.voidmaps.ui.MapScreen
import app.voidmaps.ui.SettingsScreen
import app.voidmaps.ui.UiEvent
import app.voidmaps.ui.UpdateDialog
import app.voidmaps.ui.theme.VoidMapsTheme
import app.voidmaps.ui.theme.resolveDesign
import app.voidmaps.ui.theme.systemDark
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var hasPermission by mutableStateOf(false)

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = LocationTracker.hasPermission(this)
        if (hasPermission) {
            LocationTracker.acquire(this, "ui")
            LocationTracker.restart(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hasPermission = LocationTracker.hasPermission(this)
        if (!hasPermission) askLocationPermission()
        if (savedInstanceState == null) vm.handleIntent(intent)

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val design = resolveDesign(settings.theme, systemDark())
            val nav by Navigator.state.collectAsState()
            val navigating = nav != null
            LaunchedEffect(navigating) {
                if (navigating) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(Unit) {
                vm.uiEvents.collect { e ->
                    when (e) {
                        is UiEvent.Toast -> Toast.makeText(this@MainActivity, e.text, Toast.LENGTH_LONG).show()
                        is UiEvent.Install -> installApk(e.apk)
                    }
                }
            }
            VoidMapsTheme(design, settings.dotHeadings) {
                Box(Modifier.fillMaxSize()) {
                    MapScreen(vm, settings, design, hasPermission, onRequestPermission = ::askLocationPermission)
                    AnimatedVisibility(
                        vm.showSettings,
                        enter = slideInHorizontally { it },
                        exit = slideOutHorizontally { it },
                    ) {
                        SettingsScreen(vm, settings) { vm.showSettings = false }
                    }
                }
                val update = vm.availableUpdate
                if (vm.showUpdateDialog && update != null) {
                    UpdateDialog(update, onDismiss = { vm.showUpdateDialog = false }) { vm.downloadUpdate(update) }
                }
                vm.crashReport?.let { report ->
                    CrashDialog(
                        report,
                        onShare = {
                            shareText(report)
                            CrashReporter.clear(this)
                            vm.crashReport = null
                        },
                        onDismiss = {
                            CrashReporter.clear(this)
                            vm.crashReport = null
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        LocationTracker.acquire(this, "ui")
    }

    override fun onStop() {
        super.onStop()
        // The navigation service keeps its own hold on GPS while guiding.
        LocationTracker.release("ui")
    }

    override fun onResume() {
        super.onResume()
        val now = LocationTracker.hasPermission(this)
        if (now && !hasPermission) LocationTracker.restart(this)
        hasPermission = now
    }

    private fun askLocationPermission() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionRequest.launch(perms.toTypedArray())
    }

    private fun installApk(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Bitte \"Unbekannte Apps installieren\" für VOID Maps erlauben und dann erneut aktualisieren", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Toast.makeText(this, "Installation konnte nicht gestartet werden", Toast.LENGTH_LONG).show() }
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, "Absturzbericht teilen")) }
    }
}
