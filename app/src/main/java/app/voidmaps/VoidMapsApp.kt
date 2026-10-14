package app.voidmaps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.voidmaps.data.AppSettings
import app.voidmaps.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.maplibre.android.MapLibre
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoidMapsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        MapLibre.getInstance(this)
        repo = SettingsRepository(this)
        settings = repo.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NAV_CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Laufende Zielführung"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val NAV_CHANNEL = "navigation"

        /** App-wide scope for work that outlives a screen (navigation, hazard updates). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        lateinit var repo: SettingsRepository
            private set
        lateinit var settings: StateFlow<AppSettings>
            private set
    }
}

/** Writes uncaught crashes to a file so the next start can offer to share the report. */
object CrashReporter {
    private fun file(context: Context) = File(context.filesDir, "crash/last.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN).format(Date())
        f.writeText(
            buildString {
                appendLine("VOID Maps ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Zeit: $time")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(error.stackTraceToString())
            },
        )
    }

    fun pending(context: Context): String? = file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        file(context).delete()
    }
}
