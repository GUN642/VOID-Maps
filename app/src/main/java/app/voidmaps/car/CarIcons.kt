package app.voidmaps.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import app.voidmaps.data.ManeuverKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Turn arrows as bitmaps for the Android Auto navigation card (same shapes as the phone UI). */
object CarIcons {
    private const val SIZE = 128

    fun maneuver(kind: ManeuverKind): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = SIZE * 0.12f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        when (kind) {
            ManeuverKind.ARRIVE -> {
                c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE * 0.3f, stroke)
                c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE * 0.12f, fill)
            }
            ManeuverKind.ROUNDABOUT -> {
                c.drawCircle(SIZE / 2f, SIZE * 0.42f, SIZE * 0.2f, stroke)
                c.drawLine(SIZE / 2f, SIZE * 0.94f, SIZE / 2f, SIZE * 0.62f, stroke)
            }
            else -> {
                val (pts, mirror) = shape(kind)
                arrow(c, if (mirror) pts.map { (x, y) -> 1f - x to y } else pts, stroke, fill)
            }
        }
        return bmp
    }

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

    private fun arrow(c: Canvas, points: List<Pair<Float, Float>>, stroke: Paint, fill: Paint) {
        val s = SIZE.toFloat()
        val head = s * 0.2f
        val ex = points.last().first * s
        val ey = points.last().second * s
        val bx = points[points.size - 2].first * s
        val by = points[points.size - 2].second * s
        val a = atan2(ey - by, ex - bx)
        val path = Path()
        points.dropLast(1).forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s) }
        path.lineTo(ex - cos(a) * head * 0.6f, ey - sin(a) * head * 0.6f)
        c.drawPath(path, stroke)
        val tip = Path().apply {
            moveTo(ex + cos(a) * head * 0.35f, ey + sin(a) * head * 0.35f)
            lineTo(ex + cos(a + 2.3f) * head, ey + sin(a + 2.3f) * head)
            lineTo(ex + cos(a - 2.3f) * head, ey + sin(a - 2.3f) * head)
            close()
        }
        c.drawPath(tip, fill)
    }
}
