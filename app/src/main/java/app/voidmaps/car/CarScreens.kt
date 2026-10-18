package app.voidmaps.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import app.voidmaps.R
import app.voidmaps.VoidMapsApp
import app.voidmaps.data.Format
import app.voidmaps.data.Geo
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Place
import app.voidmaps.data.RouteOptions
import app.voidmaps.data.Routing
import app.voidmaps.data.Search
import app.voidmaps.data.VoiceMode
import app.voidmaps.nav.LocationTracker
import app.voidmaps.nav.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Entry point for Android Auto. Sideloaded apps need "Unbekannte Quellen" in the Android Auto developer settings. */
class VoidCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = VoidCarSession()
}

class VoidCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val renderer = CarMapRenderer(carContext, lifecycle)
        return CarNavScreen(carContext, renderer)
    }
}

private fun icon(ctx: CarContext, res: Int): CarIcon = CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

/** Starts guidance to [place] from the current position; used by the car screens. */
private suspend fun startRoute(ctx: CarContext, place: Place): Boolean {
    val loc = LocationTracker.location.value ?: run {
        CarToast.makeText(ctx, "Kein GPS-Signal", CarToast.LENGTH_LONG).show()
        return false
    }
    val s = VoidMapsApp.settings.value
    val heading = if (loc.hasBearing() && loc.speed > 2) loc.bearing.toDouble() else null
    val result = withContext(Dispatchers.IO) {
        runCatching {
            Routing.route(
                listOf(GeoPoint(loc.latitude, loc.longitude), place.point),
                s.routing, s.tomtomKey, RouteOptions(s.avoidTolls, s.avoidMotorways, heading),
            )
        }
    }
    val route = result.getOrElse {
        CarToast.makeText(ctx, it.message ?: "Keine Route gefunden", CarToast.LENGTH_LONG).show()
        return false
    }
    VoidMapsApp.repo.addRecent(place)
    // Every navigation starts with "Route oben" in the tilted 3D view.
    VoidMapsApp.repo.setNavView(app.voidmaps.data.NavView.HEADING)
    VoidMapsApp.repo.setTilt(true)
    Navigator.start(ctx, place, emptyList(), route)
    return true
}

/** Main car screen: the map, and the turn card while navigating. */
class CarNavScreen(carContext: CarContext, private val renderer: CarMapRenderer) : Screen(carContext) {
    private val navManager: NavigationManager? = runCatching { carContext.getCarService(NavigationManager::class.java) }.getOrNull()
    private var navigating = false

    init {
        runCatching {
            navManager?.setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() = Navigator.stop()
                override fun onAutoDriveEnabled() {}
            })
        }
        lifecycleScope.launch {
            // Refresh the template whenever something visible changes (about once per second while driving).
            Navigator.state.map { s -> s?.let { listOf(it.next, it.distanceToNext.toInt() / 10, it.remainingTime.toInt() / 30, it.arrived, it.rerouting, it.routeVersion, it.warning?.hazard?.id) } }
                .distinctUntilChanged()
                .collect { st ->
                    val now = st != null
                    if (now != navigating) {
                        navigating = now
                        runCatching { if (now) navManager?.navigationStarted() else navManager?.navigationEnded() }
                    }
                    invalidate()
                }
        }
        lifecycleScope.launch {
            VoidMapsApp.settings.map { it.voiceMode to it.navView }.distinctUntilChanged().collect { invalidate() }
        }
        renderer.onFollowingChanged = { invalidate() }
    }

    /** Icon of the view button: recentre after panning, otherwise the current orientation. */
    private fun viewIcon(): Int = when {
        !renderer.following -> R.drawable.ic_car_center
        else -> when (VoidMapsApp.settings.value.navView) {
            app.voidmaps.data.NavView.HEADING -> R.drawable.ic_car_view_heading
            app.voidmaps.data.NavView.NORTH -> R.drawable.ic_car_view_north
            app.voidmaps.data.NavView.OVERVIEW -> R.drawable.ic_car_view_overview
        }
    }

    override fun onGetTemplate(): Template {
        val s = Navigator.state.value
        val builder = NavigationTemplate.Builder()
        val strip = ActionStrip.Builder()
        if (s == null) {
            strip.addAction(
                Action.Builder().setTitle("Ziel").setIcon(icon(carContext, R.drawable.ic_car_search))
                    .setOnClickListener { screenManager.push(CarSearchScreen(carContext)) }.build(),
            )
            strip.addAction(
                Action.Builder().setTitle("Favoriten").setIcon(icon(carContext, R.drawable.ic_car_star))
                    .setOnClickListener { screenManager.push(CarPlacesScreen(carContext)) }.build(),
            )
        } else {
            val voice = VoidMapsApp.settings.value.voiceMode
            strip.addAction(
                Action.Builder().setTitle(voice.label).setOnClickListener {
                    val next = when (voice) {
                        VoiceMode.ALL -> VoiceMode.WARNINGS
                        VoiceMode.WARNINGS -> VoiceMode.OFF
                        VoiceMode.OFF -> VoiceMode.ALL
                    }
                    lifecycleScope.launch { VoidMapsApp.repo.setVoiceMode(next) }
                    Navigator.onVoiceModeChanged(next)
                }.build(),
            )
            if (s.faster != null) {
                strip.addAction(
                    Action.Builder().setTitle("−${(s.faster.savedSeconds / 60).toInt()} min").setOnClickListener { Navigator.acceptFaster() }.build(),
                )
            }
            strip.addAction(
                Action.Builder().setTitle("Beenden").setIcon(icon(carContext, R.drawable.ic_car_stop))
                    .setOnClickListener { Navigator.stop() }.build(),
            )
        }
        builder.setActionStrip(strip.build())
        builder.setMapActionStrip(
            ActionStrip.Builder()
                .addAction(Action.PAN)
                .addAction(Action.Builder().setIcon(icon(carContext, viewIcon())).setOnClickListener { renderer.cycleView() }.build())
                .addAction(Action.Builder().setIcon(icon(carContext, R.drawable.ic_car_plus)).setOnClickListener { renderer.zoomIn() }.build())
                .addAction(Action.Builder().setIcon(icon(carContext, R.drawable.ic_car_minus)).setOnClickListener { renderer.zoomOut() }.build())
                .build(),
        )
        // Turn card, arrival time etc. are drawn by VOID Maps itself on the map (same view as on the phone).
        return builder.build()
    }
}

/** Search on the car display (keyboard only while parked, voice input otherwise). */
class CarSearchScreen(carContext: CarContext) : Screen(carContext) {
    private var results: List<Place> = emptyList()
    private var loading = false
    private var job: Job? = null

    private fun search(text: String, debounce: Boolean) {
        job?.cancel()
        if (text.trim().length < 2) {
            results = emptyList()
            invalidate()
            return
        }
        job = lifecycleScope.launch {
            if (debounce) delay(300)
            loading = true
            invalidate()
            val loc = LocationTracker.location.value
            val near = loc?.let { GeoPoint(it.latitude, it.longitude) }
            results = withContext(Dispatchers.IO) {
                runCatching { Search.search(text, near, VoidMapsApp.settings.value.tomtomKey) }.getOrDefault(emptyList())
            }.take(6)
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = search(searchText, true)
            override fun onSearchSubmitted(searchText: String) = search(searchText, false)
        })
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .setSearchHint("Adresse oder Ort")
        if (loading) {
            b.setLoading(true)
        } else {
            val loc = LocationTracker.location.value
            val here = loc?.let { GeoPoint(it.latitude, it.longitude) }
            b.setItemList(placeList(carContext, results, here, "Ziel eingeben") { place -> go(place) })
        }
        return b.build()
    }

    private fun go(place: Place) {
        loading = true
        invalidate()
        lifecycleScope.launch {
            val ok = startRoute(carContext, place)
            loading = false
            if (ok) screenManager.popToRoot() else invalidate()
        }
    }
}

/** Zuhause, Arbeit and the recent destinations. */
class CarPlacesScreen(carContext: CarContext) : Screen(carContext) {
    private var loading = false

    override fun onGetTemplate(): Template {
        val s = VoidMapsApp.settings.value
        val places = buildList {
            s.home?.let { add(it.copy(name = "Zuhause · ${it.name}")) }
            s.work?.let { add(it.copy(name = "Arbeit · ${it.name}")) }
            addAll(s.recents.take(5))
        }
        val loc = LocationTracker.location.value
        val here = loc?.let { GeoPoint(it.latitude, it.longitude) }
        val b = ListTemplate.Builder().setTitle("Ziele").setHeaderAction(Action.BACK)
        if (loading) {
            b.setLoading(true)
        } else {
            b.setSingleList(
                placeList(carContext, places, here, "Noch keine Ziele – Zuhause und Arbeit in der App festlegen") { place ->
                    loading = true
                    invalidate()
                    lifecycleScope.launch {
                        val ok = startRoute(carContext, place)
                        loading = false
                        if (ok) screenManager.popToRoot() else invalidate()
                    }
                },
            )
        }
        return b.build()
    }
}

private fun placeList(ctx: CarContext, places: List<Place>, here: GeoPoint?, empty: String, onClick: (Place) -> Unit): ItemList {
    val list = ItemList.Builder().setNoItemsMessage(empty)
    places.forEach { p ->
        val row = Row.Builder().setTitle(p.name.ifBlank { "Ziel" })
        val detail = listOfNotNull(
            here?.let { Format.distance(Geo.distance(it, p.point)) },
            p.detail.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (detail.isNotBlank()) row.addText(detail)
        row.setOnClickListener { onClick(p) }
        list.addItem(row.build())
    }
    return list.build()
}
