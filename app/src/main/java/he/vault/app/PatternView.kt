package he.vault.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.pow

class PatternView(context: Context) : View(context) {
    private val points = mutableListOf<Int>()
    private val centers = Array(9) { Pair(0f, 0f) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var active = false
    var onPatternComplete: ((String) -> Unit)? = null

    init {
        isFocusable = true
        contentDescription = "לוח קוד קווים בן תשע נקודות"
    }

    fun clearPattern() {
        points.clear()
        active = false
        path.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width.toFloat(), height.toFloat())
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val gap = side / 4f
        for (r in 0..2) for (c in 0..2) {
            val idx = r * 3 + c
            centers[idx] = Pair(left + gap * (c + 1), top + gap * (r + 1))
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFF55D6BE.toInt()
        if (points.size > 1) {
            path.reset()
            points.forEachIndexed { position, idx ->
                val p = centers[idx]
                if (position == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second)
            }
            canvas.drawPath(path, paint)
        }
        points.forEach { idx ->
            val p = centers[idx]
            paint.style = Paint.Style.FILL
            paint.color = 0xFF55D6BE.toInt()
            canvas.drawCircle(p.first, p.second, side / 34f, paint)
        }
        for (i in 0..8) {
            val p = centers[i]
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = if (points.contains(i)) 0xFF55D6BE.toInt() else 0xFF728096.toInt()
            canvas.drawCircle(p.first, p.second, side / 21f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                clearPattern()
                active = true
                addPointAt(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active) {
                    addPointAt(event.x, event.y)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active) {
                    active = false
                    invalidate()
                    if (points.size >= 4) onPatternComplete?.invoke(points.joinToString(""))
                }
                return true
            }
        }
        return true
    }

    private fun addPointAt(x: Float, y: Float) {
        val side = min(width.toFloat(), height.toFloat())
        val radius = side / 10f
        val nearest = centers.indices.minByOrNull { idx ->
            val p = centers[idx]
            (p.first - x).pow(2) + (p.second - y).pow(2)
        } ?: return
        val p = centers[nearest]
        if ((p.first - x).pow(2) + (p.second - y).pow(2) <= radius * radius && !points.contains(nearest)) {
            points.add(nearest)
        }
    }
}
