package he.vault.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.min

class RadarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val startedAt = SystemClock.uptimeMillis()
    private var active = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.43f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0x6655D6BE
        for (ring in 1..3) canvas.drawCircle(cx, cy, radius * ring / 3f, paint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)
        val phase = ((SystemClock.uptimeMillis() - startedAt) % 1600L) / 1600f
        paint.color = 0x9955D6BE.toInt()
        paint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, radius * phase, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF55D6BE.toInt()
        canvas.drawCircle(cx, cy, 7f, paint)
        paint.color = 0xFFFF647C.toInt()
        canvas.drawCircle(cx + radius * 0.55f, cy - radius * 0.3f, 6f, paint)
        if (active) postInvalidateDelayed(45)
    }

    fun stop() { active = false }
    override fun onDetachedFromWindow() { active = false; super.onDetachedFromWindow() }
}
