package app.voidmaps.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import app.voidmaps.data.Format
import app.voidmaps.data.HazardType
import app.voidmaps.map.MapPalette
import app.voidmaps.nav.NavState

/** Route progress bar drawn on the Android Auto map: cameras, jams, incidents, stops and destination ahead. */
class CarProgressView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private var state: NavState? = null
    private var cameras = false
    private var icons: Map<String, Bitmap> = emptyMap()
    private var palette: MapPalette? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun update(s: NavState?, showCameras: Boolean, p: MapPalette, iconSet: Map<String, Bitmap>) {
        state = s
        cameras = showCameras
        palette = p
        icons = iconSet
        visibility = if (s == null || s.arrived) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        val p = palette ?: return
        val along = s.snap?.along ?: 0.0
        val horizon = s.remainingDistance.coerceIn(1_000.0, 30_000.0)
        val cx = width / 2f
        val top = 26 * d
        val bottom = height - 50 * d
        fun y(dist: Double) = bottom - ((dist / horizon).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()

        // Backing plate so the bar reads on any map.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRoundRect(RectF(cx - 18 * d, 0f, cx + 18 * d, height.toFloat()), 18 * d, 18 * d, paint)

        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6 * d
        paint.color = Color.argb(110, 255, 255, 255)
        canvas.drawLine(cx, top, cx, bottom, paint)

        val amber = Color.parseColor("#FFB800")
        val heavy = Color.parseColor(p.trafficHeavy)
        val warn = Color.parseColor(p.warn)
        s.route.traffic.forEach { t ->
            if (t.endAlong <= along || t.startAlong - along > horizon) return@forEach
            paint.color = when {
                t.works -> amber
                t.level >= 3 -> warn
                t.level == 2 -> heavy
                else -> amber
            }
            canvas.drawLine(cx, y((t.startAlong - along).coerceAtLeast(0.0)), cx, y(t.endAlong - along), paint)
        }
        val events = s.routeHazards.filter { e ->
            (cameras || !e.hazard.type.isCamera) && (e.endAlong ?: e.along) >= along - 10 && e.along - along <= horizon
        }
        events.forEach { e ->
            val end = e.endAlong ?: return@forEach
            paint.color = when (e.hazard.type) {
                HazardType.JAM -> heavy
                HazardType.ROADWORKS, HazardType.LANE_CLOSED -> amber
                else -> warn
            }
            canvas.drawLine(cx, y((e.along - along).coerceAtLeast(0.0)), cx, y(end - along), paint)
        }

        val route = Color.parseColor(p.route)
        paint.style = Paint.Style.FILL
        s.route.waypointAlong.filter { it > along && it - along <= horizon }.forEach { a ->
            paint.color = Color.WHITE
            canvas.drawCircle(cx, y(a - along), 7 * d, paint)
            paint.color = route
            canvas.drawCircle(cx, y(a - along), 4.5f * d, paint)
        }
        if (s.remainingDistance <= horizon) {
            paint.color = route
            canvas.drawCircle(cx, y(s.remainingDistance), 8 * d, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(cx, y(s.remainingDistance), 3 * d, paint)
        }
        val size = (24 * d).toInt()
        events.forEach { e ->
            val bmp = icons[e.hazard.type.icon] ?: return@forEach
            val yy = y((e.along - along).coerceAtLeast(0.0))
            canvas.drawBitmap(bmp, null, RectF(cx - size / 2f, yy - size / 2f, cx + size / 2f, yy + size / 2f), null)
        }

        // Horizon on top, own arrow and distance to the next event at the bottom.
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = 10 * d
        canvas.drawText(Format.distance(horizon).uppercase(), cx, 14 * d, paint)
        paint.color = route
        val arrow = android.graphics.Path().apply {
            moveTo(cx, bottom + 4 * d)
            lineTo(cx + 9 * d, bottom + 24 * d)
            lineTo(cx, bottom + 19 * d)
            lineTo(cx - 9 * d, bottom + 24 * d)
            close()
        }
        canvas.drawPath(arrow, paint)
        val next = events.filter { it.along >= along - 10 }.minByOrNull { it.along }
        val dist = if (next != null) (next.along - along).coerceAtLeast(0.0) else s.remainingDistance
        paint.color = Color.WHITE
        paint.textSize = 10 * d
        canvas.drawText(Format.distance(dist).uppercase(), cx, bottom + 40 * d, paint)
    }
}
