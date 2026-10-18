package app.voidmaps.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Hazard
import app.voidmaps.data.NavView
import app.voidmaps.data.Place
import app.voidmaps.data.Poi
import app.voidmaps.data.PoiCategory
import app.voidmaps.data.Pois
import app.voidmaps.data.Release
import app.voidmaps.data.Route
import app.voidmaps.data.RouteOptions
import app.voidmaps.data.Routing
import app.voidmaps.data.Search
import app.voidmaps.data.SharedLocation
import app.voidmaps.data.SettingsRepository
import app.voidmaps.data.Updater
import app.voidmaps.data.VoiceMode
import app.voidmaps.map.OfflineMaps
import app.voidmaps.nav.HazardRepo
import app.voidmaps.nav.LocationTracker
import app.voidmaps.nav.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Mode { BROWSE, SEARCH, PREVIEW, NAVIGATE, OFFLINE_SELECT }

/** What a place chosen in the search is used for. */
enum class Pick { DESTINATION, HOME, WORK, STOP }

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
    var category by mutableStateOf<PoiCategory?>(null)
        private set
    private var searchJob: Job? = null

    var pick by mutableStateOf(Pick.DESTINATION)

    /** Place chosen while navigating – asks "Zwischenziel or new destination". */
    var pendingChoice by mutableStateOf<Place?>(null)

    // ------------------------------------------------------------------ destination / routes
    var destination by mutableStateOf<Place?>(null)
        private set
    var stops by mutableStateOf<List<Place>>(emptyList())
        private set
    var routes by mutableStateOf<List<Route>>(emptyList())
        private set
    var selectedRoute by mutableStateOf(0)
        private set
    val preview: Route? get() = routes.getOrNull(selectedRoute)
    var routing by mutableStateOf(false)
        private set
    var routeError by mutableStateOf<String?>(null)
        private set
    private var routeJob: Job? = null

    /** Time when the selected route starts by itself (5 s without any input). 0 = no countdown. */
    var autoStartAt by mutableLongStateOf(0L)
        private set

    var tappedHazard by mutableStateOf<Hazard?>(null)
    var tappedPoi by mutableStateOf<Poi?>(null)

    // ------------------------------------------------------------------ offline selection
    var offlineBounds by mutableStateOf<BBox?>(null)

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
                    stops = emptyList()
                    routes = emptyList()
                    following = true
                } else if (st != null && mode != Mode.NAVIGATE && mode != Mode.SEARCH) {
                    mode = Mode.NAVIGATE
                    destination = st.destination
                }
            }
        }
        // Auto start: the selected route begins after 5 seconds without input.
        viewModelScope.launch {
            while (true) {
                delay(250)
                val at = autoStartAt
                if (at > 0 && mode == Mode.PREVIEW && !routing && preview != null && System.currentTimeMillis() >= at) {
                    startNavigation()
                }
            }
        }
        // Traffic lights around the position while driving (areas already loaded are skipped).
        viewModelScope.launch {
            LocationTracker.location.collect { loc ->
                if (loc != null && settings.value.trafficSignals && (mode == Mode.NAVIGATE || following)) {
                    HazardRepo.loadSignals(GeoPoint(loc.latitude, loc.longitude))
                }
            }
        }
        OfflineMaps.refresh(app)
    }

    fun toast(text: String) {
        events.trySend(UiEvent.Toast(text))
    }

    fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repo.block() }
    }

    private fun myPosition(): GeoPoint? = LocationTracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }

    /** Any touch in the route preview restarts the 5 second countdown. */
    fun onUserInput() {
        if (autoStartAt > 0) autoStartAt = System.currentTimeMillis() + AUTO_START_MS
    }

    // ------------------------------------------------------------------ search

    fun openSearch(purpose: Pick = Pick.DESTINATION) {
        pick = purpose
        autoStartAt = 0
        mode = Mode.SEARCH
    }

    fun closeSearch() {
        searchJob?.cancel()
        pick = Pick.DESTINATION
        query = ""
        results = emptyList()
        searchError = null
        category = null
        searching = false
        mode = when {
            Navigator.isActive() -> Mode.NAVIGATE
            destination != null -> Mode.PREVIEW
            else -> Mode.BROWSE
        }
        if (mode == Mode.PREVIEW && routes.isNotEmpty()) autoStartAt = System.currentTimeMillis() + AUTO_START_MS
    }

    fun onQuery(q: String) {
        query = q
        category = null
        searchJob?.cancel()
        searchError = null
        if (q.trim().length < 2) {
            results = emptyList()
            searching = false
            return
        }
        // Instant hits from favourites and recent places while the online search runs.
        val s = settings.value
        val local = (listOfNotNull(s.home, s.work) + s.recents).filter {
            it.name.contains(q.trim(), ignoreCase = true) || it.detail.contains(q.trim(), ignoreCase = true)
        }.distinct()
        results = local
        searchJob = viewModelScope.launch {
            delay(220)
            searching = true
            val near = myPosition() ?: lastView?.second
            val r = withContext(Dispatchers.IO) { runCatching { Search.search(q, near, s.tomtomKey) } }
            searching = false
            r.onSuccess { list ->
                val online = if (near == null) list else list.sortedBy { p -> if (Geo.distance(near, p.point) < 30_000) 0 else 1 }
                results = (local + online).distinctBy { it.name + "|" + it.detail }
                if (results.isEmpty()) searchError = "Nichts gefunden"
            }.onFailure { if (local.isEmpty()) searchError = "Suche nicht erreichbar – Internetverbindung prüfen" }
        }
    }

    /** Sonderziele: places of a category nearby, or along the route while navigating. */
    fun searchCategory(c: PoiCategory) {
        searchJob?.cancel()
        query = ""
        category = c
        searchError = null
        results = emptyList()
        val nav = Navigator.state.value
        val center = myPosition() ?: lastView?.second ?: return run { searchError = "Kein Standort" }
        searchJob = viewModelScope.launch {
            searching = true
            val path = nav?.let { n ->
                val rest = n.snap?.let { n.route.line.remainder(it.segment, it.point) } ?: n.route.points
                // Next ~50 km of the route, one point per kilometre.
                val out = ArrayList<GeoPoint>()
                var acc = 0.0
                var last: GeoPoint? = null
                for (p in rest) {
                    val l = last
                    if (l != null) acc += Geo.distance(l, p)
                    if (l == null || out.isEmpty() || Geo.distance(out.last(), p) > 1000) out += p
                    last = p
                    if (acc > 50_000) break
                }
                out.takeIf { it.size >= 2 }
            }
            val key = settings.value.tomtomKey
            val r = withContext(Dispatchers.IO) { runCatching { Pois.category(c, center, 10_000, path, key) } }
            searching = false
            r.onSuccess { list ->
                results = list.map { it.toPlace() }
                if (list.isEmpty()) searchError = if (path != null) "Nichts entlang der Route gefunden" else "Nichts in der Nähe gefunden"
            }.onFailure { searchError = "Sonderziele nicht erreichbar – Internetverbindung prüfen" }
        }
    }

    fun choose(place: Place) {
        when (pick) {
            Pick.HOME, Pick.WORK -> {
                val home = pick == Pick.HOME
                update { if (home) setHome(place) else setWork(place) }
                toast(if (home) "Zuhause gespeichert" else "Arbeit gespeichert")
                closeSearch()
                return
            }
            Pick.STOP -> {
                update { addRecent(place) }
                if (Navigator.isActive()) {
                    Navigator.addStop(place)
                    closeSearch()
                } else {
                    stops = stops + place
                    closeSearch()
                    computeRoute()
                }
                return
            }
            Pick.DESTINATION -> {}
        }
        searchJob?.cancel()
        update { addRecent(place) }
        if (Navigator.isActive()) {
            pendingChoice = place
            return
        }
        query = ""
        results = emptyList()
        category = null
        goTo(place)
    }

    /** Answer to the "Zwischenziel or new destination" question while navigating. */
    fun resolvePending(asStop: Boolean) {
        val place = pendingChoice ?: return
        pendingChoice = null
        if (asStop) {
            Navigator.addStop(place)
            closeSearch()
        } else {
            Navigator.stop()
            closeSearch()
            goTo(place)
        }
    }

    fun goTo(place: Place) {
        destination = place
        stops = emptyList()
        routes = emptyList()
        routeError = null
        mode = Mode.PREVIEW
        following = false
        computeRoute()
    }

    fun removeStop(place: Place) {
        stops = stops - place
        computeRoute()
    }

    fun onMapLongPress(p: GeoPoint) {
        if (mode == Mode.NAVIGATE || mode == Mode.OFFLINE_SELECT) return
        viewModelScope.launch {
            val place = withContext(Dispatchers.IO) { Search.reverse(p) }
                ?: Place("Markierter Ort", "%.5f, %.5f".format(java.util.Locale.US, p.lat, p.lon), p)
            goTo(place.copy(point = p))
        }
    }

    fun computeRoute() {
        val dest = destination ?: return
        routeJob?.cancel()
        autoStartAt = 0
        routing = true
        routeError = null
        routeJob = viewModelScope.launch {
            // Right after the app was opened (e.g. from "Teilen") the first GPS fix may still be missing.
            val from = myPosition() ?: kotlinx.coroutines.withTimeoutOrNull(20_000) {
                LocationTracker.location.filterNotNull().first()
            }?.let { GeoPoint(it.latitude, it.longitude) }
            if (from == null) {
                routeError = "Kein GPS-Signal – Standort aktivieren und kurz warten"
                routing = false
                return@launch
            }
            val s = settings.value
            val heading = LocationTracker.location.value?.takeIf { it.hasBearing() && it.speed > 2 }?.bearing?.toDouble()
            val points = listOf(from) + stops.map { it.point } + dest.point
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    Routing.routes(points, s.routing, s.tomtomKey, RouteOptions(s.avoidTolls, s.avoidMotorways, heading), alternatives = 2)
                }
            }
            routing = false
            r.onSuccess {
                routes = it.take(3)
                selectedRoute = 0
                if (routes.isNotEmpty() && mode == Mode.PREVIEW) autoStartAt = System.currentTimeMillis() + AUTO_START_MS
                alignPreview(routes)
            }.onFailure { routeError = it.message ?: "Route konnte nicht berechnet werden" }
            // Warnings along the route are shown already in the preview.
            preview?.let { route ->
                if (s.tomtomKey.isNotBlank()) HazardRepo.loadIncidents(s.tomtomKey, BBox.chunks(route.points, 9_000.0, 500.0, 20), replace = false)
                if (s.speedCameras) HazardRepo.ensureCameras(HazardRepo.cellsFor(route.points), s.blitzerDe, s.mobileCameras)
            }
        }
    }

    private var alignJob: Job? = null

    /** Aligns the preview routes to the roads of the map (fastest first) – see RouteMatcher. */
    private fun alignPreview(list: List<Route>) {
        alignJob?.cancel()
        alignJob = viewModelScope.launch {
            for (original in list) {
                val aligned = withContext(Dispatchers.IO) { app.voidmaps.data.RouteMatcher.match(original) }
                if (aligned === original) continue
                val i = routes.indexOfFirst { it === original }
                if (i < 0) return@launch
                routes = routes.toMutableList().also { it[i] = aligned }
            }
        }
    }

    fun selectRoute(i: Int) {
        if (i in routes.indices) selectedRoute = i
        onUserInput()
    }

    fun cancelPreview() {
        routeJob?.cancel()
        destination = null
        stops = emptyList()
        routes = emptyList()
        routeError = null
        routing = false
        autoStartAt = 0
        mode = Mode.BROWSE
        following = true
    }

    fun startNavigation() {
        val dest = destination ?: return
        val route = preview ?: return
        autoStartAt = 0
        Navigator.start(getApplication(), dest, stops, route)
        navStartView()
        mode = Mode.NAVIGATE
        following = true
    }

    /** Every navigation starts with "Route oben" in the tilted 3D view. */
    private fun navStartView() {
        val s = settings.value
        if (s.navView != NavView.HEADING || !s.tilt3d) {
            update {
                setNavView(NavView.HEADING)
                setTilt(true)
            }
        }
    }

    /** Demo drive on the built-in route Stuttgart → Heilbronn. */
    fun startDemo() {
        showSettings = false
        toast("Demo-Route wird berechnet …")
        viewModelScope.launch {
            app.voidmaps.nav.DemoDrive.startDefault(getApplication())
                .onSuccess {
                    destination = app.voidmaps.nav.DemoDrive.DEMO_DEST
                    navStartView()
                    mode = Mode.NAVIGATE
                    following = true
                }
                .onFailure { toast("Demo nicht möglich: ${it.message}") }
        }
    }

    /** Demo drive along the route selected in the preview. */
    fun startDemoForPreview() {
        val dest = destination ?: return
        val route = preview ?: return
        autoStartAt = 0
        app.voidmaps.nav.DemoDrive.start(getApplication(), dest, stops, route)
        navStartView()
        mode = Mode.NAVIGATE
        following = true
    }

    /** Settings button: one request to Blitzer.de around the position or map centre. */
    fun testBlitzerDe() {
        val c = myPosition() ?: lastView?.second ?: return toast("Kein Standort")
        viewModelScope.launch { toast(HazardRepo.testBlitzerDe(c)) }
    }

    fun stopNavigation() {
        Navigator.stop()
        destination = null
        stops = emptyList()
        routes = emptyList()
        mode = Mode.BROWSE
        following = true
    }

    // ------------------------------------------------------------------ voice / orientation

    // ------------------------------------------------------------------ voices

    private var voiceSpeaker: app.voidmaps.nav.Speaker? = null

    /** Own speech engine for the voice list and previews in the settings. */
    fun voiceSpeaker(): app.voidmaps.nav.Speaker =
        voiceSpeaker ?: app.voidmaps.nav.Speaker(getApplication()).also { voiceSpeaker = it }

    fun chooseVoice(name: String) {
        update { setVoiceName(name) }
        previewVoice(name, settings.value.speechRate)
    }

    fun chooseSpeechRate(rate: Float) {
        update { setSpeechRate(rate) }
        previewVoice(settings.value.voiceName, rate)
    }

    private fun previewVoice(name: String, rate: Float) {
        val sp = voiceSpeaker()
        sp.configure(name, rate)
        sp.speak("In 300 Metern rechts abbiegen. Danach links halten auf die A 81 Richtung Heilbronn.", interrupt = true)
    }

    override fun onCleared() {
        voiceSpeaker?.shutdown()
        voiceSpeaker = null
        super.onCleared()
    }

    fun cycleVoice() {
        val next = when (settings.value.voiceMode) {
            VoiceMode.ALL -> VoiceMode.WARNINGS
            VoiceMode.WARNINGS -> VoiceMode.OFF
            VoiceMode.OFF -> VoiceMode.ALL
        }
        update { setVoiceMode(next) }
        Navigator.onVoiceModeChanged(next)
        toast(next.label)
    }

    fun cycleNavView() {
        val next = when (settings.value.navView) {
            NavView.HEADING -> NavView.NORTH
            NavView.NORTH -> NavView.OVERVIEW
            NavView.OVERVIEW -> NavView.HEADING
        }
        update { setNavView(next) }
        following = next != NavView.OVERVIEW
    }

    // ------------------------------------------------------------------ map / hazards / POIs

    fun onCameraIdle(box: BBox, center: GeoPoint, zoom: Double) {
        lastView = Triple(box, center, zoom)
        if (mode == Mode.OFFLINE_SELECT) offlineBounds = box
        val s = settings.value
        if (zoom < 8.5) return
        if (s.hasTraffic && zoom >= 9.5) {
            viewModelScope.launch { HazardRepo.loadIncidentsForView(box, center, s.tomtomKey) }
        }
        if (s.speedCameras) {
            viewModelScope.launch {
                HazardRepo.ensureCameras(HazardRepo.cellsFor(box), s.blitzerDe, s.mobileCameras)
            }
            if (s.blitzerDe && s.mobileCameras) {
                viewModelScope.launch { HazardRepo.ensureMobile(listOf(HazardRepo.mobileArea(box))) }
            }
        }
        if (s.trafficSignals && zoom >= 14) {
            viewModelScope.launch { HazardRepo.loadSignals(center) }
        }
        if (s.chargers) {
            viewModelScope.launch { HazardRepo.ensureChargers(HazardRepo.cellsFor(box), s.chargerOperators) }
        }
    }

    fun saveCamera(lat: Double, lon: Double, zoom: Double) = update { this.saveCamera(lat, lon, zoom) }

    fun setSpeedCameras(on: Boolean) {
        update { this.setSpeedCameras(on) }
        if (!on) {
            HazardRepo.clearCameras()
        } else {
            reloadCameras()
        }
    }

    /** Loads the cameras for the current view and route again (after a source was switched). */
    fun reloadCameras() {
        val v = lastView
        viewModelScope.launch {
            delay(150)
            val s = settings.value
            if (!s.speedCameras) return@launch
            val route = Navigator.state.value?.route ?: preview
            if (route != null) HazardRepo.ensureCameras(HazardRepo.cellsFor(route.points), s.blitzerDe, s.mobileCameras)
            if (v != null && v.third >= 8.5) HazardRepo.ensureCameras(HazardRepo.cellsFor(v.first), s.blitzerDe, s.mobileCameras)
            if (v != null && s.blitzerDe && s.mobileCameras) HazardRepo.ensureMobile(listOf(HazardRepo.mobileArea(v.first)))
        }
    }

    fun setBlitzerDe(on: Boolean) {
        update { this.setBlitzerDe(on) }
        HazardRepo.clearCameras()
        reloadCameras()
    }

    fun setMobileCameras(on: Boolean) {
        update { this.setMobileCameras(on) }
        HazardRepo.clearCameras()
        reloadCameras()
    }

    fun setChargers(on: Boolean) {
        update { this.setChargers(on) }
        if (!on) HazardRepo.clearChargers() else reloadChargers()
    }

    fun toggleChargerOperator(op: app.voidmaps.data.ChargeOperator) {
        val cur = settings.value.chargerOperators
        val next = if (op in cur) cur - op else cur + op
        update { setChargerOperators(next) }
        // All networks are loaded anyway; the map only filters.
    }

    private fun reloadChargers() {
        val v = lastView ?: return
        viewModelScope.launch {
            delay(100)
            val route = Navigator.state.value?.route ?: preview
            if (route != null) HazardRepo.ensureChargers(HazardRepo.cellsFor(route.points), settings.value.chargerOperators)
            if (v.third >= 8.5) HazardRepo.ensureChargers(HazardRepo.cellsFor(v.first), settings.value.chargerOperators)
        }
    }

    fun toggleSatellite() {
        val on = !settings.value.satellite
        update { setSatellite(on) }
    }

    fun setTrafficSignals(on: Boolean) {
        update { this.setTrafficSignals(on) }
        if (!on) HazardRepo.clearSignals()
    }

    fun setTomTomKey(key: String) {
        update { this.setTomTomKey(key) }
        if (key.isBlank()) HazardRepo.clearIncidents()
    }

    // ------------------------------------------------------------------ offline maps

    fun startOfflineSelection() {
        showSettings = false
        following = false
        mode = Mode.OFFLINE_SELECT
        offlineBounds = lastView?.first
    }

    fun cancelOfflineSelection() {
        mode = Mode.BROWSE
        showSettings = true
    }

    fun downloadOffline(name: String) {
        val box = offlineBounds ?: return toast("Bereich nicht erkannt – Karte kurz bewegen")
        OfflineMaps.download(getApplication(), name.ifBlank { "Bereich" }, box) { err -> toast("Download fehlgeschlagen: $err") }
        toast("Download gestartet – Fortschritt unter Einstellungen → Offline-Karten")
        mode = Mode.BROWSE
        showSettings = true
    }

    // ------------------------------------------------------------------ external intents

    /**
     * Destinations from other apps: "Teilen" → VOID Maps (text with a maps link, name and address),
     * maps links opened with VOID Maps, and geo: / google.navigation: links.
     */
    fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            // Some apps put the place name into the subject only.
            val all = if (subject.isNotBlank() && !text.contains(subject)) "$subject\n$text" else text
            if (all.isNotBlank()) receiveShared(all)
            return
        }
        val data: Uri = intent.data ?: return
        val raw = data.toString()
        val q = when (data.scheme) {
            "geo" -> data.getQueryParameter("q") ?: raw.removePrefix("geo:").substringBefore('?')
            "google.navigation" -> data.getQueryParameter("q") ?: raw.substringAfter("q=", "")
            "http", "https" -> return receiveShared(raw)
            else -> return
        }.trim()
        val coords = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""").find(q)
        val label = Regex("""\((.*)\)""").find(q)?.groupValues?.get(1)
        if (coords != null) {
            val p = GeoPoint(coords.groupValues[1].toDouble(), coords.groupValues[2].toDouble())
            if (p.lat != 0.0 || p.lon != 0.0) {
                receivePlace(Place(label ?: "Ziel", "%.5f, %.5f".format(java.util.Locale.US, p.lat, p.lon), p))
                return
            }
        }
        if (q.isBlank()) return
        receiveShared(q)
    }

    private var sharedJob: Job? = null

    /** Shared text or link: find the place (expanding short links if needed), then route to it. */
    private fun receiveShared(text: String) {
        sharedJob?.cancel()
        toast("Geteilter Ort wird gesucht …")
        sharedJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SharedLocation.resolve(text, myPosition(), settings.value.tomtomKey) }.getOrDefault(SharedLocation.Result.None)
            }
            when (result) {
                is SharedLocation.Result.Found -> receivePlace(result.place)
                is SharedLocation.Result.Search -> {
                    if (Navigator.isActive()) {
                        toast("Ort nicht gefunden: ${result.query}")
                    } else {
                        showSettings = false
                        openSearch()
                        onQuery(result.query)
                    }
                }
                SharedLocation.Result.None -> toast("Im geteilten Text wurde kein Ort gefunden")
            }
        }
    }

    /** Route preview (with the 5 s auto start), or the "Zwischenziel / neues Ziel" question while navigating. */
    private fun receivePlace(place: Place) {
        update { addRecent(place) }
        showSettings = false
        if (Navigator.isActive()) {
            pendingChoice = place
            return
        }
        searchJob?.cancel()
        pick = Pick.DESTINATION
        query = ""
        results = emptyList()
        category = null
        goTo(place)
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

    companion object {
        const val AUTO_START_MS = 5_000L
    }
}
