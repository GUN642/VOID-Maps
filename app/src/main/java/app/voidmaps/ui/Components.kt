package app.voidmaps.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voidmaps.ui.theme.VoidTheme
import kotlin.math.min

/** Nothing-style dot grid backdrop. */
@Composable
fun DotGrid(modifier: Modifier = Modifier, spacing: Dp = 18.dp, radius: Dp = 1.2.dp) {
    val color = VoidTheme.colors.dot
    Canvas(modifier) {
        val step = spacing.toPx()
        val r = radius.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(color, r, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

/** Segmented dot bar (progress). */
@Composable
fun DotBar(fraction: Float, modifier: Modifier = Modifier, dots: Int = 28, color: Color = VoidTheme.colors.accent) {
    val c = VoidTheme.colors
    Canvas(modifier) {
        val gap = size.width / dots
        val r = (gap * 0.32f).coerceAtMost(size.height / 2)
        val filled = (fraction.coerceIn(0f, 1f) * dots).toInt()
        for (i in 0 until dots) {
            drawCircle(
                if (i < filled) color else c.textMuted.copy(alpha = 0.28f),
                r, Offset(gap * i + gap / 2, size.height / 2),
            )
        }
    }
}

/** Five pulsing dots, used as a loading indicator. */
@Composable
fun DotLoader(modifier: Modifier = Modifier, dots: Int = 5) {
    val c = VoidTheme.colors
    val t = rememberInfiniteTransition(label = "loader")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        val gap = 10.dp.toPx()
        val r = min(size.height / 2, 2.4.dp.toPx())
        val total = gap * (dots - 1)
        val start = size.width / 2 - total / 2
        for (i in 0 until dots) {
            val local = ((phase * dots - i) % dots + dots) % dots
            val a = if (local < 1f) 1f - local else 0.25f
            drawCircle(c.accent.copy(alpha = a.coerceIn(0.25f, 1f)), r, Offset(start + gap * i, size.height / 2))
        }
    }
}

/** Small uppercase mono label, Nothing style. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = VoidTheme.colors.textMuted) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = color)
}

/** Pill-shaped chip used for toggles and actions. */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filledColor: Color? = null,
    icon: ImageVector? = null,
) {
    val c = VoidTheme.colors
    val fill = filledColor ?: c.text
    val content = if (filledColor != null) c.onAccent else c.background
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) fill else Color.Transparent)
            .border(1.dp, if (selected) fill else c.divider, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) content else c.text, modifier = Modifier.size(16.dp).padding(end = 0.dp))
            Box(Modifier.size(6.dp))
        }
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
            color = if (selected) content else c.text,
        )
    }
}

/** Round floating button over the map. [active] marks an enabled layer with the accent dot. */
@Composable
fun MapButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = VoidTheme.colors.accent,
) {
    val c = VoidTheme.colors
    Box(
        modifier
            .size(52.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, if (active) activeColor else c.divider, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (active) activeColor else c.text, modifier = Modifier.size(24.dp))
    }
}

/** Card surface floating over the map. */
@Composable
fun MapCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = VoidTheme.colors
    Column(
        modifier
            .shadow(10.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(c.surface)
            .border(1.dp, c.divider, RoundedCornerShape(26.dp)),
    ) { content() }
}

@Composable
fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = VoidTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                uncheckedThumbColor = c.textMuted, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.divider,
            ),
        )
    }
}
