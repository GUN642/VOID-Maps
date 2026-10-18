package app.voidmaps.nav

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.voidmaps.MainActivity
import app.voidmaps.R
import app.voidmaps.VoidMapsApp
import app.voidmaps.data.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps GPS and voice guidance running while the screen is off or another app is in front. */
class NavigationService : LifecycleService() {
    private var lastText = ""

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build("Navigation läuft", "VOID Maps"), type)
        }.onFailure {
            stopSelf()
            return
        }
        LocationTracker.acquire(this, "nav")
        lifecycleScope.launch {
            combine(Navigator.state, carSession) { s, car -> s to car }.collect { (s, car) ->
                if (s == null) {
                    if (!car) {
                        stopSelf()
                    } else if (lastText != CAR_TEXT) {
                        // Android Auto open without a route: keeps the position on the car map up to date
                        // even with the phone locked.
                        lastText = CAR_TEXT
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build("Android Auto", "Karte aktiv"))
                    }
                    return@collect
                }
                val m = s.route.maneuvers.getOrNull(s.next)
                val title = when {
                    s.arrived -> "Ziel erreicht"
                    s.rerouting -> "Route wird neu berechnet …"
                    m != null -> "${Format.distance(s.distanceToNext)} · ${m.text.trimEnd('.')}"
                    else -> s.destination.name
                }
                val text = "Ankunft ${Format.clock(s.eta)} · ${Format.distance(s.remainingDistance)} · ${s.destination.name}"
                if (title + text != lastText) {
                    lastText = title + text
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(title, text))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            Navigator.stop()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LocationTracker.release("nav")
        super.onDestroy()
    }

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, VoidMapsApp.NAV_CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_small)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(0, "Beenden", stop)
            .build()
    }

    companion object {
        private const val CAR_TEXT = "car"

        /** True while the Android Auto map is open; the service then also runs without a route. */
        val carSession = MutableStateFlow(false)

        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "app.voidmaps.STOP_NAVIGATION"
    }
}
