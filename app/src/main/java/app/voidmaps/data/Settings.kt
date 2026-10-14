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

/** The three app designs. AUTO follows the system: VOID at night, HELL by day. */
enum class ThemeChoice(val label: String) {
    VOID("Void"),
    HELL("Hell"),
    FARBE("Farbe"),
    AUTO("Auto"),
}

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.VOID,
    val dotHeadings: Boolean = true,
    val voice: Boolean = true,
    val tilt3d: Boolean = true,
    val headingUp: Boolean = true,
    val routing: RoutingEngine = RoutingEngine.AUTO,
    val avoidTolls: Boolean = false,
    val avoidMotorways: Boolean = false,
    val trafficLayer: Boolean = true,
    val trafficOriginalColors: Boolean = false,
    /** Speed camera layer. Off by default – see § 23 Abs. 1c StVO. */
    val speedCameras: Boolean = false,
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
        val tilt = booleanPreferencesKey("tilt3d")
        val headingUp = booleanPreferencesKey("heading_up")
        val routing = stringPreferencesKey("routing")
        val avoidTolls = booleanPreferencesKey("avoid_tolls")
        val avoidMotorways = booleanPreferencesKey("avoid_motorways")
        val traffic = booleanPreferencesKey("traffic")
        val trafficOriginal = booleanPreferencesKey("traffic_original")
        val cameras = booleanPreferencesKey("speed_cameras")
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
            voice = p[K.voice] ?: d.voice,
            tilt3d = p[K.tilt] ?: d.tilt3d,
            headingUp = p[K.headingUp] ?: d.headingUp,
            routing = p.enum(K.routing, d.routing),
            avoidTolls = p[K.avoidTolls] ?: d.avoidTolls,
            avoidMotorways = p[K.avoidMotorways] ?: d.avoidMotorways,
            trafficLayer = p[K.traffic] ?: d.trafficLayer,
            trafficOriginalColors = p[K.trafficOriginal] ?: d.trafficOriginalColors,
            speedCameras = p[K.cameras] ?: d.speedCameras,
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
    suspend fun setVoice(v: Boolean) = context.dataStore.edit { it[K.voice] = v }
    suspend fun setTilt(v: Boolean) = context.dataStore.edit { it[K.tilt] = v }
    suspend fun setHeadingUp(v: Boolean) = context.dataStore.edit { it[K.headingUp] = v }
    suspend fun setRouting(v: RoutingEngine) = context.dataStore.edit { it[K.routing] = v.name }
    suspend fun setAvoidTolls(v: Boolean) = context.dataStore.edit { it[K.avoidTolls] = v }
    suspend fun setAvoidMotorways(v: Boolean) = context.dataStore.edit { it[K.avoidMotorways] = v }
    suspend fun setTraffic(v: Boolean) = context.dataStore.edit { it[K.traffic] = v }
    suspend fun setTrafficOriginal(v: Boolean) = context.dataStore.edit { it[K.trafficOriginal] = v }
    suspend fun setSpeedCameras(v: Boolean) = context.dataStore.edit { it[K.cameras] = v }
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

    suspend fun addRecent(place: Place) = context.dataStore.edit {
        val cur = it[K.recents]?.split('\n')?.mapNotNull { s -> Place.decode(s) } ?: emptyList()
        val next = listOf(place) + cur.filterNot { r -> r.name == place.name && Geo.distance(r.point, place.point) < 50 }
        it[K.recents] = next.take(12).joinToString("\n") { r -> r.encode() }
    }

    suspend fun removeRecent(place: Place) = context.dataStore.edit {
        val cur = it[K.recents]?.split('\n')?.mapNotNull { s -> Place.decode(s) } ?: emptyList()
        it[K.recents] = cur.filterNot { r -> r == place }.joinToString("\n") { r -> r.encode() }
    }
}
