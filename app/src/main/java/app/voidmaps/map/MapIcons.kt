package app.voidmaps.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.voidmaps.data.ChargeOperator

/** Map symbols drawn in code – round, flat, Nothing style. No sprite sheet needed. */
object MapIcons {
    private enum class Glyph { BANG, DOTS, CONE, BAR, LANES, CAMERA, DROP }

    /** [scale] > 1 renders larger, sharp bitmaps (Android Auto progress bar). */
    fun all(context: Context, p: MapPalette, scale: Float = 1f): Map<String, Bitmap> {
        val d = context.resources.displayMetrics.density * scale
        val ring = if (p.dark) Color.BLACK else Color.WHITE
        val warn = Color.parseColor(p.warn)
        val amber = Color.parseColor("#FFB800")
        return mapOf(
            "hz-accident" to badge(d, warn, ring, Color.WHITE, Glyph.BANG),
            "hz-jam" to badge(d, Color.parseColor(p.trafficHeavy), ring, Color.WHITE, Glyph.DOTS),
            "hz-works" to badge(d, amber, ring, Color.BLACK, Glyph.CONE),
            "hz-closed" to badge(d, warn, ring, Color.WHITE, Glyph.BAR),
            "hz-lane" to badge(d, amber, ring, Color.BLACK, Glyph.LANES),
            "hz-danger" to badge(d, amber, ring, Color.BLACK, Glyph.BANG),
            "hz-weather" to badge(d, Color.parseColor("#3D7BFF"), ring, Color.WHITE, Glyph.DROP),
            "hz-camera" to badge(d, if (p.dark) Color.parseColor("#111111") else Color.parseColor("#1A1A1A"), warn, Color.WHITE, Glyph.CAMERA),
            "hz-camera-mobile" to badge(d, Color.parseColor("#1A1A1A"), amber, amber, Glyph.CAMERA),
            "dest" to destination(d, Color.parseColor(p.route), ring),
            "stop" to stop(d, Color.parseColor(p.route), ring),
            "signal" to signal(d, p.dark),
        ) + ChargeOperator.entries.associate { it.icon to charger(d, Color.parseColor(it.color), ring, it.letter) }
    }

    /** Charging station: operator colour, its letter and a small lightning dot. */
    private fun charger(d: Float, fill: Int, ring: Int, letter: String): Bitmap {
        val size = (28 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ring
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 8 * d, 8 * d, paint)
        paint.color = fill
        c.drawRoundRect(RectF(2 * d, 2 * d, size - 2 * d, size - 2 * d), 6.5f * d, 6.5f * d, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 15 * d
        paint.typeface = Typeface.DEFAULT_BOLD
        c.drawText(letter, r, r - (paint.descent() + paint.ascent()) / 2, paint)
        return bmp
    }

    /** Traffic light: dark pill with red, amber and green dots. */
    private fun signal(d: Float, dark: Boolean): Bitmap {
        val w = (11 * d).toInt()
        val h = (24 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (dark) Color.parseColor("#3A3A3A") else Color.WHITE
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), w / 2f, w / 2f, paint)
        paint.color = Color.parseColor("#111111")
        c.drawRoundRect(RectF(1.2f * d, 1.2f * d, w - 1.2f * d, h - 1.2f * d), w / 2f, w / 2f, paint)
        val colors = listOf("#FF3B30", "#FFB800", "#30D158")
        colors.forEachIndexed { i, col ->
            paint.color = Color.parseColor(col)
            c.drawCircle(w / 2f, h * (0.22f + i * 0.28f), 2.3f * d, paint)
        }
        return bmp
    }

    /** Intermediate stop: hollow ring in the route colour. */
    private fun stop(d: Float, color: Int, ring: Int): Bitmap {
        val size = (20 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ring
        c.drawCircle(r, r, r, paint)
        paint.color = color
        c.drawCircle(r, r, r - 2 * d, paint)
        paint.color = ring
        c.drawCircle(r, r, r - 5 * d, paint)
        return bmp
    }

    private fun badge(d: Float, fill: Int, ring: Int, fg: Int, glyph: Glyph): Bitmap {
        val size = (30 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ring
        c.drawCircle(r, r, r, paint)
        paint.color = fill
        c.drawCircle(r, r, r - 2.2f * d, paint)
        paint.color = fg
        val u = size / 30f // one unit of the 30dp grid
        when (glyph) {
            Glyph.BANG -> {
                c.drawRoundRect(RectF(r - 1.6f * u, 8f * u, r + 1.6f * u, 18f * u), 1.6f * u, 1.6f * u, paint)
                c.drawCircle(r, 21.5f * u, 1.8f * u, paint)
            }
            Glyph.DOTS -> for (i in -1..1) c.drawCircle(r + i * 5.5f * u, r, 2.2f * u, paint)
            Glyph.CONE -> {
                val path = Path().apply {
                    moveTo(r, 8f * u); lineTo(r + 6f * u, 21f * u); lineTo(r - 6f * u, 21f * u); close()
                }
                c.drawPath(path, paint)
                c.drawRect(r - 8f * u, 21f * u, r + 8f * u, 23f * u, paint)
            }
            Glyph.BAR -> c.drawRoundRect(RectF(r - 8f * u, r - 2.2f * u, r + 8f * u, r + 2.2f * u), 1.2f * u, 1.2f * u, paint)
            Glyph.LANES -> {
                c.drawRect(r - 5f * u, 8f * u, r - 2.5f * u, 22f * u, paint)
                for (k in 0..2) c.drawRect(r + 2.5f * u, (8f + k * 5f) * u, r + 5f * u, (11f + k * 5f) * u, paint)
            }
            Glyph.CAMERA -> {
                c.drawRoundRect(RectF(r - 8f * u, r - 5f * u, r + 8f * u, r + 6f * u), 2f * u, 2f * u, paint)
                c.drawRect(r - 3f * u, r - 7.5f * u, r + 3f * u, r - 4f * u, paint)
                paint.color = fill
                c.drawCircle(r, r + 0.5f * u, 3.6f * u, paint)
                paint.color = fg
                c.drawCircle(r, r + 0.5f * u, 1.6f * u, paint)
            }
            Glyph.DROP -> {
                val path = Path().apply {
                    moveTo(r, 7f * u)
                    cubicTo(r + 7f * u, 15f * u, r + 7f * u, 22f * u, r, 22f * u)
                    cubicTo(r - 7f * u, 22f * u, r - 7f * u, 15f * u, r, 7f * u)
                    close()
                }
                c.drawPath(path, paint)
            }
        }
        return bmp
    }

    private fun destination(d: Float, color: Int, ring: Int): Bitmap {
        val size = (26 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ring
        c.drawCircle(r, r, r, paint)
        paint.color = color
        c.drawCircle(r, r, r - 2.5f * d, paint)
        paint.color = ring
        c.drawCircle(r, r, 3.5f * d, paint)
        return bmp
    }
}
