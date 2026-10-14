package app.voidmaps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.voidmaps.data.AppSettings
import app.voidmaps.data.Format
import app.voidmaps.data.Geo
import app.voidmaps.data.GeoPoint
import app.voidmaps.data.Place
import app.voidmaps.nav.LocationTracker
import app.voidmaps.ui.theme.VoidTheme
import app.voidmaps.ui.theme.headingStyle

@Composable
fun SearchPanel(vm: MainViewModel, settings: AppSettings) {
    val c = VoidTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val here = LocationTracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.closeSearch() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", tint = c.text) }
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(c.surfaceHigh).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (vm.query.isEmpty()) {
                    Text(
                        when (vm.pickingFavourite) {
                            "home" -> "Adresse für Zuhause"
                            "work" -> "Adresse für Arbeit"
                            else -> "Adresse, Ort oder Koordinaten"
                        },
                        color = c.textMuted, style = MaterialTheme.typography.bodyLarge,
                    )
                }
                BasicTextField(
                    value = vm.query,
                    onValueChange = { vm.onQuery(it) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.results.firstOrNull()?.let { vm.choose(it) } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (vm.query.isNotEmpty()) {
                IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Outlined.Close, "Leeren", tint = c.textMuted) }
            }
        }
        if (vm.searching) DotLoader(Modifier.fillMaxWidth().height(10.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            if (vm.query.isBlank() && vm.pickingFavourite == null) {
                item {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        FavouriteTile("Zuhause", Icons.Outlined.Home, settings.home, Modifier.weight(1f),
                            onGo = { settings.home?.let { vm.choose(it) } },
                            onSet = { vm.pickingFavourite = "home" })
                        Spacer(Modifier.width(12.dp))
                        FavouriteTile("Arbeit", Icons.Outlined.Work, settings.work, Modifier.weight(1f),
                            onGo = { settings.work?.let { vm.choose(it) } },
                            onSet = { vm.pickingFavourite = "work" })
                    }
                }
                if (settings.recents.isNotEmpty()) {
                    item { Label("Zuletzt", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp)) }
                    items(settings.recents) { p ->
                        PlaceRow(p, Icons.Outlined.History, here, onClick = { vm.choose(p) }, onRemove = { vm.update { removeRecent(p) } })
                    }
                } else {
                    item {
                        Column(Modifier.padding(20.dp)) {
                            Text("LEER", style = headingStyle(30), color = c.textMuted)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Tipp: Lange auf die Karte drücken, um einen Punkt als Ziel zu wählen.",
                                color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            vm.searchError?.let { err ->
                item { Text(err, color = c.textMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp)) }
            }
            items(vm.results) { p -> PlaceRow(p, Icons.Outlined.Place, here, onClick = { vm.choose(p) }, onRemove = null) }
        }
    }
}

@Composable
private fun FavouriteTile(title: String, icon: ImageVector, place: Place?, modifier: Modifier, onGo: () -> Unit, onSet: () -> Unit) {
    val c = VoidTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(c.surface)
            .clickable { if (place != null) onGo() else onSet() }.padding(16.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent2, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, color = c.text, style = MaterialTheme.typography.titleMedium)
        Text(
            place?.name ?: "Festlegen",
            color = if (place != null) c.textMuted else c.accent,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (place != null) {
            Text("Ändern", color = c.textMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable(onClick = onSet).padding(top = 6.dp))
        }
    }
}

@Composable
private fun PlaceRow(p: Place, icon: ImageVector, here: GeoPoint?, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    val c = VoidTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.detail.isNotBlank()) {
                Text(p.detail, color = c.textMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (here != null) {
            Spacer(Modifier.width(8.dp))
            Label(Format.distance(Geo.distance(here, p.point)))
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Entfernen", tint = c.textMuted, modifier = Modifier.size(18.dp)) }
        }
    }
}
