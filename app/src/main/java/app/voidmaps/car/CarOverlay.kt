package app.voidmaps.car

import android.content.Context
import android.graphics.Rect
import androidx.car.app.CarContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.voidmaps.VoidMapsApp
import app.voidmaps.nav.Navigator
import app.voidmaps.ui.CAR_PANEL
import app.voidmaps.ui.CarNavOverlay
import app.voidmaps.ui.theme.VoidMapsTheme
import app.voidmaps.ui.theme.resolveDesign

/**
 * Compose layer over the car map showing the phone's navigation view. A Presentation is no
 * activity, so the view gets its own lifecycle and saved-state owner.
 */
class CarOverlay(context: Context, private val carContext: CarContext) {
    private val owner = Owner()
    private val area = mutableStateOf<Rect?>(null)
    private val size = mutableStateOf(0 to 0)

    val view = ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setContent {
            val nav by Navigator.state.collectAsState()
            val settings by VoidMapsApp.settings.collectAsState()
            val s = nav
            val base = LocalDensity.current
            val a = area.value
            val (w, h) = size.value
            val scale = scale(base.density)
            val design = resolveDesign(settings.theme, carContext.isDarkMode)
            VoidMapsTheme(design, settings.dotHeadings) {
                CompositionLocalProvider(LocalDensity provides Density(base.density * scale, 1f)) {
                    val d = base.density * scale
                    Box(
                        Modifier.fillMaxSize().padding(
                            start = ((a?.left ?: 0) / d).dp,
                            top = ((a?.top ?: 0) / d).dp,
                            end = (((if (w > 0 && a != null) w - a.right else 0)) / d).dp,
                            bottom = (((if (h > 0 && a != null) h - a.bottom else 0)) / d).dp,
                        ),
                    ) {
                        if (s != null) CarNavOverlay(s, settings)
                    }
                }
            }
        }
    }

    private val density = context.resources.displayMetrics.density

    /** Size factor for the car display: a big screen gets proportionally bigger cards. */
    private fun scale(density: Float): Float {
        val a = area.value
        val (w, h) = size.value
        val visW = (a?.width() ?: w).coerceAtLeast(1) / density
        val visH = (a?.height() ?: h).coerceAtLeast(1) / density
        return minOf(visH / 470f, visW * 0.42f / CAR_PANEL.value).coerceIn(0.75f, 2.2f)
    }

    /** Width of the turn card column in surface pixels. */
    fun panelWidthPx(): Int = (CAR_PANEL.value * density * scale(density)).toInt()

    /**
     * Compose looks for the lifecycle on the window's root view, not on the ComposeView itself –
     * a Presentation has none, so the owners go onto every root that can hold the view.
     */
    fun attachOwners(root: android.view.View?) {
        root ?: return
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
    }

    /** Free map area (without Android Auto's own buttons and cards) in surface pixels. */
    fun setArea(visible: Rect, width: Int, height: Int) {
        area.value = Rect(visible)
        size.value = width to height
    }

    fun dispose() {
        owner.destroy()
    }

    private class Owner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        init {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
