package app.voidmaps.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.voidmaps.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The app designs. AUTO follows the system: VOID at night, HELL by day. */
enum class ThemeChoice(val label: String) {
    VOID("Void"),
    HELL("Hell"),
    AUTO("Auto"),
}

/** Voice output: silent, only hazard warnings, or full guidance. */
enum class VoiceMode(val label: String) {
    OFF("Stumm"),
    WARNINGS("Nur Warnungen"),
    ALL("Laut"),
}

/** Map orientation during navigation. */
enum class NavView(val label: String) {
    HEADING("Route oben"),
    NORTH("Norden oben"),
    OVERVIEW("Übersicht"),
}

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.VOID,
    val dotHeadings: Boolean = true,
    val voiceMode: VoiceMode = VoiceMode.ALL,
    /** Compact announcements ("In 300 Metern rechts abbiegen"). On by default. */
    val shortAnnouncements: Boolean = true,
    val tilt3d: Boolean = true,
    val headingUp: Boolean = true,
    val navView: NavView = NavView.HEADING,
    val routing: RoutingEngine = RoutingEngine.AUTO,
    val avoidTolls: Boolean = false,
    val avoidMotorways: Boolean = false,
    val trafficLayer: Boolean = true,
    val trafficOriginalColors: Boolean = false,
    /** Speed camera layer. Off by default – see § 23 Abs. 1c StVO. */
    val speedCameras: Boolean = false,
    val chargers: Boolean = false,
    val satellite: Boolean = false,
    val buildings3d: Boolean = true,
    /** Relief shading from elevation data (MapLibre Native has no true 3D terrain yet). */
    val terrain: Boolean = true,
    val trafficSignals: Boolean = true,
    val chargerOperators: Set<ChargeOperator> = ChargeOperator.entries.toSet(),
    val tomtomKeyInput: String = "",
    val autoUpdateCheck: Boolean = true,
    val recents: List<Place> = emptyList(),
    val home: Place? = null,
    val work: Place? = null,
    val lastLat: Double = 51.1657,
    val lastLon: Double = 10.4515,
    val lastZoom: Double = 5.5,
    val firstStart: Boolean = true,
) {
    /** Key typed into the app, otherwise the one from local.properties of a local build. */
    val tomtomKey: String get() = tomtomKeyInput.ifBlank { BuildConfig.TOMTOM_KEY }
    val hasTraffic: Boolean get() = tomtomKey.isNotBlank()
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dotHeadings = booleanPreferencesKey("dot_headings")
        val voice = booleanPreferencesKey("voice")
        val voiceMode = stringPreferencesKey("voice_mode")
        val shortAnnouncements = booleanPreferencesKey("short_announcements")
        val tilt = booleanPreferencesKey("tilt3d")
        val headingUp = booleanPreferencesKey("heading_up")
        val navView = stringPreferencesKey("nav_view")
        val routing = stringPreferencesKey("routing")
        val avoidTolls = booleanPreferencesKey("avoid_tolls")
        val avoidMotorways = booleanPreferencesKey("avoid_motorways")
        val traffic = booleanPreferencesKey("traffic")
        val trafficOriginal = booleanPreferencesKey("traffic_original")
        val cameras = booleanPreferencesKey("speed_cameras")
        val chargers = booleanPreferencesKey("chargers")
        val satellite = booleanPreferencesKey("satellite")
        val buildings3d = booleanPreferencesKey("buildings3d")
        val terrain = booleanPreferencesKey("terrain")
        val signals = booleanPreferencesKey("traffic_signals")
        val chargerOperators = stringPreferencesKey("charger_operators")
        val tomtomKey = stringPreferencesKey("tomtom_key")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val recents = stringPreferencesKey("recents")
        val home = stringPreferencesKey("home")
        val work = stringPreferencesKey("work")
        val lastLat = stringPreferencesKey("last_lat")
        val lastLon = stringPreferencesKey("last_lon")
        val lastZoom = stringPreferencesKey("last_zoom")
        val started = longPreferencesKey("started")
    }

    private inline fun <reified T : Enum<T>> Preferences.enum(key: Preferences.Key<String>, default: T): T =
        this[key]?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: default

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            theme = p.enum(K.theme, d.theme),
            dotHeadings = p[K.dotHeadings] ?: d.dotHeadings,
            // Older versions stored a plain on/off switch.
            voiceMode = p.enum(K.voiceMode, if (p[K.voice] == false) VoiceMode.OFF else d.voiceMode),
            shortAnnouncements = p[K.shortAnnouncements] ?: d.shortAnnouncements,
            tilt3d = p[K.tilt] ?: d.tilt3d,
            headingUp = p[K.headingUp] ?: d.headingUp,
            navView = p.enum(K.navView, d.navView),
            routing = p.enum(K.routing, d.routing),
            avoidTolls = p[K.avoidTolls] ?: d.avoidTolls,
            avoidMotorways = p[K.avoidMotorways] ?: d.avoidMotorways,
            trafficLayer = p[K.traffic] ?: d.trafficLayer,
            trafficOriginalColors = p[K.trafficOriginal] ?: d.trafficOriginalColors,
            speedCameras = p[K.cameras] ?: d.speedCameras,
            chargers = p[K.chargers] ?: d.chargers,
            satellite = p[K.satellite] ?: d.satellite,
            buildings3d = p[K.buildings3d] ?: d.buildings3d,
            terrain = p[K.terrain] ?: d.terrain,
            trafficSignals = p[K.signals] ?: d.trafficSignals,
            chargerOperators = p[K.chargerOperators]?.split(',')
                ?.mapNotNull { v -> ChargeOperator.entries.firstOrNull { it.name == v } }?.toSet()
                ?: d.chargerOperators,
            tomtomKeyInput = p[K.tomtomKey] ?: d.tomtomKeyInput,
            autoUpdateCheck = p[K.autoUpdate] ?: d.autoUpdateCheck,
            recents = p[K.recents]?.split('\n')?.mapNotNull { Place.decode(it) } ?: emptyList(),
            home = p[K.home]?.let { Place.decode(it) },
            work = p[K.work]?.let { Place.decode(it) },
            lastLat = p[K.lastLat]?.toDoubleOrNull() ?: d.lastLat,
            lastLon = p[K.lastLon]?.toDoubleOrNull() ?: d.lastLon,
            lastZoom = p[K.lastZoom]?.toDoubleOrNull() ?: d.lastZoom,
            firstStart = p[K.started] == null,
        )
    }

    suspend fun setTheme(v: ThemeChoice) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setDotHeadings(v: Boolean) = context.dataStore.edit { it[K.dotHeadings] = v }
    suspend fun setVoiceMode(v: VoiceMode) = context.dataStore.edit { it[K.voiceMode] = v.name }
    suspend fun setShortAnnouncements(v: Boolean) = context.dataStore.edit { it[K.shortAnnouncements] = v }
    suspend fun setTilt(v: Boolean) = context.dataStore.edit { it[K.tilt] = v }
    suspend fun setHeadingUp(v: Boolean) = context.dataStore.edit { it[K.headingUp] = v }
    suspend fun setNavView(v: NavView) = context.dataStore.edit { it[K.navView] = v.name }
    suspend fun setRouting(v: RoutingEngine) = context.dataStore.edit { it[K.routing] = v.name }
    suspend fun setAvoidTolls(v: Boolean) = context.dataStore.edit { it[K.avoidTolls] = v }
    suspend fun setAvoidMotorways(v: Boolean) = context.dataStore.edit { it[K.avoidMotorways] = v }
    suspend fun setTraffic(v: Boolean) = context.dataStore.edit { it[K.traffic] = v }
    suspend fun setTrafficOriginal(v: Boolean) = context.dataStore.edit { it[K.trafficOriginal] = v }
    suspend fun setSpeedCameras(v: Boolean) = context.dataStore.edit { it[K.cameras] = v }
    suspend fun setChargers(v: Boolean) = context.dataStore.edit { it[K.chargers] = v }
    suspend fun setSatellite(v: Boolean) = context.dataStore.edit { it[K.satellite] = v }
    suspend fun setBuildings3d(v: Boolean) = context.dataStore.edit { it[K.buildings3d] = v }
    suspend fun setTerrain(v: Boolean) = context.dataStore.edit { it[K.terrain] = v }
    suspend fun setTrafficSignals(v: Boolean) = context.dataStore.edit { it[K.signals] = v }
    suspend fun setChargerOperators(v: Set<ChargeOperator>) =
        context.dataStore.edit { it[K.chargerOperators] = v.joinToString(",") { o -> o.name } }
    suspend fun setTomTomKey(v: String) = context.dataStore.edit { it[K.tomtomKey] = v.trim() }
    suspend fun setAutoUpdate(v: Boolean) = context.dataStore.edit { it[K.autoUpdate] = v }
    suspend fun setHome(p: Place?) = context.dataStore.edit { if (p == null) it.remove(K.home) else it[K.home] = p.encode() }
    suspend fun setWork(p: Place?) = context.dataStore.edit { if (p == null) it.remove(K.work) else it[K.work] = p.encode() }
    suspend fun markStarted() = context.dataStore.edit { it[K.started] = System.currentTimeMillis() }

    suspend fun saveCamera(lat: Double, lon: Double, zoom: Double) = context.dataStore.edit {
        it[K.lastLat] = lat.toString()
        it[K.lastLon] = lon.toString()
        it[K.lastZoom] = zoom.toString()
    }

    /** Remembers a searched place; the search shows the five most recent ones. */
    suspend fun addRecent(place: Place) = context.dataStore.edit {
        val cur = it[K.recents]?.split('\n')?.mapNotNull { s -> Place.decode(s) } ?: emptyList()
        val next = listOf(place) + cur.filterNot { r -> r.name == place.name && Geo.distance(r.point, place.point) < 50 }
        it[K.recents] = next.take(5).joinToString("\n") { r -> r.encode() }
    }

    suspend fun removeRecent(place: Place) = context.dataStore.edit {
        val cur = it[K.recents]?.split('\n')?.mapNotNull { s -> Place.decode(s) } ?: emptyList()
        it[K.recents] = cur.filterNot { r -> r == place }.joinToString("\n") { r -> r.encode() }
    }
}
