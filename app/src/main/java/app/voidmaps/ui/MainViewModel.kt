package app.voidmaps.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.voidmaps.BuildConfig
import app.voidmaps.CrashReporter
import app.voidmaps.VoidMapsApp
import app.voidmaps.data.AppSettings
import app.voidmaps.data.BBox
import app.voidmaps.data.Geo
import app.voidmaps.data.Hazard
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Place
import app.voidmaps.data.Release
import app.voidmaps.data.Route
import app.voidmaps.data.RouteOptions
import app.voidmaps.data.Routing
import app.voidmaps.data.Search
import app.voidmaps.data.SettingsRepository
import app.voidmaps.data.Updater
import app.voidmaps.nav.HazardRepo
import app.voidmaps.nav.LocationTracker
import app.voidmaps.nav.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Mode { BROWSE, SEARCH, PREVIEW, NAVIGATE }

sealed interface UiEvent {
    data class Toast(val text: String) : UiEvent
    data class Install(val apk: File) : UiEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings: StateFlow<AppSettings> = VoidMapsApp.settings
    private val repo: SettingsRepository = VoidMapsApp.repo

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    var mode by mutableStateOf(if (Navigator.isActive()) Mode.NAVIGATE else Mode.BROWSE)
    var showSettings by mutableStateOf(false)

    /** Camera follows the GPS position (false after the user pans the map). */
    var following by mutableStateOf(true)

    // ------------------------------------------------------------------ search
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<Place>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    private var searchJob: Job? = null

    /** Which favourite slot the next chosen search result is saved to. */
    var pickingFavourite by mutableStateOf<String?>(null)

    // ------------------------------------------------------------------ destination / route
    var destination by mutableStateOf<Place?>(null)
        private set
    var preview by mutableStateOf<Route?>(null)
        private set
    var routing by mutableStateOf(false)
        private set
    var routeError by mutableStateOf<String?>(null)
        private set
    private var routeJob: Job? = null

    /** Hazard or map point tapped by the user. */
    var tappedHazard by mutableStateOf<Hazard?>(null)

    // ------------------------------------------------------------------ updates
    var releases by mutableStateOf<List<Release>>(emptyList())
        private set
    var availableUpdate by mutableStateOf<Release?>(null)
        private set
    var updateStatus by mutableStateOf<String?>(null)
        private set
    var checkingUpdate by mutableStateOf(false)
        private set
    var showUpdateDialog by mutableStateOf(false)
    var downloadProgress by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    private var downloadJob: Job? = null

    var crashReport by mutableStateOf<String?>(null)

    private var lastView: Triple<BBox, GeoPoint, Double>? = null

    init {
        crashReport = CrashReporter.pending(app)
        viewModelScope.launch {
            // The shared settings flow starts with defaults; read the stored values before deciding.
            val stored = repo.settings.first()
            if (stored.autoUpdateCheck) checkForUpdates(manual = false)
            if (stored.firstStart) repo.markStarted()
        }
        viewModelScope.launch {
            Navigator.state.collect { st ->
                if (st == null && mode == Mode.NAVIGATE) {
                    mode = Mode.BROWSE
                    destination = null
                    preview = null
                    following = true
                } else if (st != null && mode != Mode.NAVIGATE) {
                    mode = Mode.NAVIGATE
                    destination = st.destination
                }
            }
        }
    }

    fun toast(text: String) {
        events.trySend(UiEvent.Toast(text))
    }

    fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repo.block() }
    }

    private fun myPosition(): GeoPoint? = LocationTracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }

    // ------------------------------------------------------------------ search

    fun openSearch() {
        mode = Mode.SEARCH
    }

    fun closeSearch() {
        searchJob?.cancel()
        pickingFavourite = null
        query = ""
        results = emptyList()
        searchError = null
        mode = if (destination != null) Mode.PREVIEW else Mode.BROWSE
    }

    fun onQuery(q: String) {
        query = q
        searchJob?.cancel()
        searchError = null
        if (q.trim().length < 2) {
            results = emptyList()
            searching = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            searching = true
            val near = myPosition() ?: lastView?.second
            val r = withContext(Dispatchers.IO) { runCatching { Search.search(q, near) } }
            searching = false
            r.onSuccess { list ->
                val here = near
                results = if (here == null) list else list.sortedBy { p ->
                    // Keep Photon's relevance order but pull very close matches up.
                    val d = Geo.distance(here, p.point)
                    if (d < 30_000) 0 else 1
                }
                if (list.isEmpty()) searchError = "Nichts gefunden"
            }.onFailure { searchError = "Suche nicht erreichbar – Internetverbindung prüfen" }
        }
    }

    fun choose(place: Place) {
        val fav = pickingFavourite
        if (fav != null) {
            update { if (fav == "home") setHome(place) else setWork(place) }
            toast(if (fav == "home") "Zuhause gespeichert" else "Arbeit gespeichert")
            pickingFavourite = null
            closeSearch()
            return
        }
        searchJob?.cancel()
        query = ""
        results = emptyList()
        update { addRecent(place) }
        setDestination(place)
    }

    fun setDestination(place: Place) {
        destination = place
        preview = null
        routeError = null
        mode = Mode.PREVIEW
        following = false
        computeRoute()
    }

    fun onMapLongPress(p: GeoPoint) {
        if (mode == Mode.NAVIGATE) return
        viewModelScope.launch {
            val place = withContext(Dispatchers.IO) { Search.reverse(p) }
                ?: Place("Markierter Ort", "%.5f, %.5f".format(java.util.Locale.US, p.lat, p.lon), p)
            setDestination(place.copy(point = p))
        }
    }

    fun computeRoute() {
        val dest = destination ?: return
        routeJob?.cancel()
        val from = myPosition()
        if (from == null) {
            routeError = "Kein GPS-Signal – Standort aktivieren und kurz warten"
            routing = false
            return
        }
        routing = true
        routeError = null
        routeJob = viewModelScope.launch {
            val s = settings.value
            val heading = LocationTracker.location.value?.takeIf { it.hasBearing() && it.speed > 2 }?.bearing?.toDouble()
            val r = withContext(Dispatchers.IO) {
                runCatching { Routing.route(from, dest.point, s.routing, s.tomtomKey, RouteOptions(s.avoidTolls, s.avoidMotorways, heading)) }
            }
            routing = false
            r.onSuccess { preview = it }
                .onFailure { routeError = it.message ?: "Route konnte nicht berechnet werden" }
            // Warnings along the route are shown already in the preview.
            preview?.let { route ->
                if (s.tomtomKey.isNotBlank()) HazardRepo.loadIncidents(s.tomtomKey, BBox.chunks(route.points, 9_000.0, 500.0, 20), replace = false)
                if (s.speedCameras) HazardRepo.loadCameras(BBox.chunks(route.points, 2_500.0, 300.0, 40), replace = false)
            }
        }
    }

    fun cancelPreview() {
        routeJob?.cancel()
        destination = null
        preview = null
        routeError = null
        routing = false
        mode = Mode.BROWSE
        following = true
    }

    fun startNavigation() {
        val dest = destination ?: return
        val route = preview ?: return
        Navigator.start(getApplication(), dest, route)
        mode = Mode.NAVIGATE
        following = true
    }

    fun stopNavigation() {
        Navigator.stop()
        destination = null
        preview = null
        mode = Mode.BROWSE
        following = true
    }

    // ------------------------------------------------------------------ map / hazards

    fun onCameraIdle(box: BBox, center: GeoPoint, zoom: Double) {
        lastView = Triple(box, center, zoom)
        val s = settings.value
        if (zoom < 9.5) return
        if (!s.hasTraffic && !s.speedCameras) return
        viewModelScope.launch { HazardRepo.loadForView(box, center, s.tomtomKey, s.speedCameras) }
    }

    fun saveCamera(lat: Double, lon: Double, zoom: Double) = update { this.saveCamera(lat, lon, zoom) }

    fun setSpeedCameras(on: Boolean) {
        update { this.setSpeedCameras(on) }
        if (!on) {
            HazardRepo.clearCameras()
        } else {
            val v = lastView
            viewModelScope.launch {
                delay(100)
                val route = Navigator.state.value?.route ?: preview
                if (route != null) HazardRepo.loadCameras(BBox.chunks(route.points, 2_500.0, 300.0, 40), replace = false)
                if (v != null && v.third >= 9.5) HazardRepo.loadForView(v.first, v.second, "", cameras = true)
            }
        }
        toast(if (on) "Blitzer werden angezeigt" else "Blitzer ausgeblendet")
    }

    fun setTomTomKey(key: String) {
        update { this.setTomTomKey(key) }
        if (key.isBlank()) HazardRepo.clearIncidents()
    }

    // ------------------------------------------------------------------ external intents

    /** geo:52.5,13.4?q=… and google.navigation:q=… links from other apps. */
    fun handleIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        val raw = data.toString()
        val q = when (data.scheme) {
            "geo" -> data.getQueryParameter("q") ?: raw.removePrefix("geo:").substringBefore('?')
            "google.navigation" -> data.getQueryParameter("q") ?: raw.substringAfter("q=", "")
            else -> return
        }.trim()
        val coords = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""").find(q)
        val label = Regex("""\((.*)\)""").find(q)?.groupValues?.get(1)
        if (coords != null) {
            val p = GeoPoint(coords.groupValues[1].toDouble(), coords.groupValues[2].toDouble())
            if (p.lat != 0.0 || p.lon != 0.0) {
                setDestination(Place(label ?: "Ziel", "%.5f, %.5f".format(java.util.Locale.US, p.lat, p.lon), p))
                return
            }
        }
        if (q.isBlank()) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Search.search(q, myPosition()) }.getOrNull() }
            val first = r?.firstOrNull()
            if (first != null) setDestination(first) else {
                mode = Mode.SEARCH
                onQuery(q)
            }
        }
    }

    // ------------------------------------------------------------------ updates

    fun checkForUpdates(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        if (manual) updateStatus = "Suche nach Updates …"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.fetchReleases() } }
            checkingUpdate = false
            result.onSuccess { list ->
                releases = list
                val newest = list.firstOrNull()
                if (newest != null && Updater.isNewer(newest.version, BuildConfig.VERSION_NAME)) {
                    availableUpdate = newest
                    updateStatus = "Version ${newest.version} ist verfügbar"
                    // Never interrupt a running navigation with a dialog.
                    if (!manual && mode != Mode.NAVIGATE) showUpdateDialog = true
                } else {
                    availableUpdate = null
                    updateStatus = "VOID Maps ist aktuell"
                }
            }.onFailure {
                updateStatus = "Update-Prüfung fehlgeschlagen: ${it.message ?: "keine Verbindung"}"
                if (manual) toast(updateStatus!!)
            }
        }
    }

    fun downloadUpdate(release: Release) {
        showUpdateDialog = false
        val url = release.apkUrl ?: return toast("Für diese Version gibt es keine APK")
        if (downloadJob?.isActive == true) return
        downloadProgress = 0L to release.apkSize
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext.job
            val dir = File(getApplication<Application>().cacheDir, "updates")
            val file = File(dir, "VOID-Maps-${release.version}.apk")
            var last = 0L
            try {
                dir.listFiles()?.forEach { it.delete() }
                Updater.download(url, file, { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 120) {
                        last = now
                        downloadProgress = done to if (total > 0) total else release.apkSize
                    }
                }, { !self.isActive })
                downloadProgress = null
                events.trySend(UiEvent.Install(file))
            } catch (e: Exception) {
                downloadProgress = null
                toast(if (!self.isActive) "Update abgebrochen" else "Update fehlgeschlagen: ${e.message}")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadProgress = null
    }
}
