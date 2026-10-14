package app.voidmaps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.voidmaps.data.ManeuverKind
import app.voidmaps.ui.theme.dotNumber
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Turn arrow drawn from simple strokes – bold, flat, readable at a glance. */
@Composable
fun ManeuverIcon(kind: ManeuverKind, color: Color, modifier: Modifier = Modifier, exit: Int? = null, muted: Color = color.copy(alpha = 0.3f)) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            when (kind) {
                ManeuverKind.ROUNDABOUT -> roundabout(color)
                ManeuverKind.ARRIVE -> {
                    val r = size.minDimension
                    drawCircle(color, r * 0.32f, center, style = Stroke(r * 0.1f))
                    drawCircle(color, r * 0.12f, center)
                }
                ManeuverKind.MERGE -> {
                    arrow(listOf(0.5f to 0.92f, 0.5f to 0.14f), color)
                    line(listOf(0.18f to 0.92f, 0.18f to 0.7f, 0.46f to 0.42f), muted)
                }
                ManeuverKind.FERRY -> {
                    for (k in 0..2) {
                        val y = 0.35f + k * 0.18f
                        line(listOf(0.15f to y, 0.32f to y - 0.06f, 0.5f to y, 0.68f to y - 0.06f, 0.85f to y), color)
                    }
                }
                else -> {
                    val (pts, mirror) = shape(kind)
                    arrow(if (mirror) pts.map { (x, y) -> 1f - x to y } else pts, color)
                }
            }
        }
        if (kind == ManeuverKind.ROUNDABOUT && exit != null) {
            Text("$exit", style = dotNumber(16), color = color)
        }
    }
}

/** Points (normalised 0..1) of the arrow shaft; second value mirrors right turns into left ones. */
private fun shape(kind: ManeuverKind): Pair<List<Pair<Float, Float>>, Boolean> = when (kind) {
    ManeuverKind.RIGHT -> listOf(0.34f to 0.92f, 0.34f to 0.42f, 0.84f to 0.42f) to false
    ManeuverKind.LEFT -> listOf(0.34f to 0.92f, 0.34f to 0.42f, 0.84f to 0.42f) to true
    ManeuverKind.SLIGHT_RIGHT, ManeuverKind.KEEP_RIGHT, ManeuverKind.EXIT_RIGHT ->
        listOf(0.4f to 0.92f, 0.4f to 0.56f, 0.8f to 0.16f) to false
    ManeuverKind.SLIGHT_LEFT, ManeuverKind.KEEP_LEFT, ManeuverKind.EXIT_LEFT ->
        listOf(0.4f to 0.92f, 0.4f to 0.56f, 0.8f to 0.16f) to true
    ManeuverKind.SHARP_RIGHT -> listOf(0.3f to 0.92f, 0.3f to 0.22f, 0.82f to 0.72f) to false
    ManeuverKind.SHARP_LEFT -> listOf(0.3f to 0.92f, 0.3f to 0.22f, 0.82f to 0.72f) to true
    ManeuverKind.UTURN -> listOf(0.7f to 0.92f, 0.7f to 0.3f, 0.6f to 0.16f, 0.4f to 0.16f, 0.3f to 0.3f, 0.3f to 0.66f) to false
    else -> listOf(0.5f to 0.92f, 0.5f to 0.12f) to false
}

private fun DrawScope.at(p: Pair<Float, Float>) = Offset(p.first * size.width, p.second * size.height)

private fun DrawScope.line(points: List<Pair<Float, Float>>, color: Color) {
    val path = Path()
    points.forEachIndexed { i, p ->
        val o = at(p)
        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    drawPath(path, color, style = Stroke(size.minDimension * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.arrow(points: List<Pair<Float, Float>>, color: Color) {
    val head = size.minDimension * 0.2f
    val end = at(points.last())
    val before = at(points[points.size - 2])
    val angle = atan2(end.y - before.y, end.x - before.x)
    // Shorten the shaft so its round cap does not poke through the arrow head.
    val shaftEnd = Offset(end.x - cos(angle) * head * 0.6f, end.y - sin(angle) * head * 0.6f)
    val shaft = points.dropLast(1).map { at(it) } + shaftEnd
    val path = Path()
    shaft.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
    drawPath(path, color, style = Stroke(size.minDimension * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val tip = Path().apply {
        moveTo(end.x + cos(angle) * head * 0.35f, end.y + sin(angle) * head * 0.35f)
        lineTo(end.x + cos(angle + 2.3f) * head, end.y + sin(angle + 2.3f) * head)
        lineTo(end.x + cos(angle - 2.3f) * head, end.y + sin(angle - 2.3f) * head)
        close()
    }
    drawPath(tip, color)
}

private fun DrawScope.roundabout(color: Color) {
    val r = size.minDimension
    val c = Offset(size.width / 2, size.height * 0.46f)
    drawCircle(color, r * 0.22f, c, style = Stroke(r * 0.1f))
    val path = Path().apply {
        moveTo(size.width / 2, size.height * 0.94f)
        lineTo(size.width / 2, c.y + r * 0.22f)
    }
    drawPath(path, color, style = Stroke(r * 0.1f, cap = StrokeCap.Round))
}
