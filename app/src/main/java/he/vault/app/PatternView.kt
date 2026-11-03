package he.vault.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

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
        parent?.requestDisallowInterceptTouchEvent(false)
        path.reset()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val heightSize = if (heightMode == MeasureSpec.UNSPECIFIED) widthSize else MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        val requestedHeight = (min(widthSize, heightSize) * 0.85f).toInt().coerceAtLeast(1)
        val measuredWidth = if (widthMode == MeasureSpec.UNSPECIFIED) requestedHeight else widthSize
        setMeasuredDimension(measuredWidth, resolveSize(requestedHeight, heightMeasureSpec))
    }

    private fun updateCenters() {
        val side = min(width.toFloat(), height.toFloat())
        if (side <= 0f) return
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val gap = side / 4f
        for (row in 0..2) for (column in 0..2) {
            val index = row * 3 + column
            centers[index] = Pair(left + gap * (column + 1), top + gap * (row + 1))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCenters()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateCenters()
        val side = min(width.toFloat(), height.toFloat())
        val gap = side / 4f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(3f, gap * 0.045f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFFF1744.toInt()
        if (points.size > 1) {
            path.reset()
            points.forEachIndexed { position, index ->
                val point = centers[index]
                if (position == 0) path.moveTo(point.first, point.second)
                else path.lineTo(point.first, point.second)
            }
            canvas.drawPath(path, paint)
        }

        points.forEach { index ->
            val point = centers[index]
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFF1744.toInt()
            canvas.drawCircle(point.first, point.second, gap * 0.18f, paint)
        }

        for (index in 0..8) {
            val point = centers[index]
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(2f, gap * 0.035f)
            paint.color = if (points.contains(index)) 0xFFFF1744.toInt() else 0xFFA7B0A5.toInt()
            canvas.drawCircle(point.first, point.second, gap * 0.16f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPattern()
                updateCenters()
                active = true
                // Keep the parent ScrollView from taking vertical drags away from the pattern.
                parent?.requestDisallowInterceptTouchEvent(true)
                addPointAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active) {
                    for (pointer in 0 until event.pointerCount) {
                        addPointAt(event.getX(pointer), event.getY(pointer))
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val completed = points.size >= 4
                val pattern = if (completed) points.joinToString("") else null
                active = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (pattern != null) onPatternComplete?.invoke(pattern)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                clearPattern()
                return true
            }
            else -> return true
        }
    }

    private fun addPointAt(x: Float, y: Float) {
        updateCenters()
        val side = min(width.toFloat(), height.toFloat())
        if (side <= 0f) return
        val gap = side / 4f
        // A 60% inter-dot spacing hit radius is forgiving on small or less sensitive screens.
        val hitRadius = gap * 0.60f
        val hitRadiusSquared = hitRadius * hitRadius
        val nearest = centers.indices.minByOrNull { index ->
            val point = centers[index]
            val dx = point.first - x
            val dy = point.second - y
            dx * dx + dy * dy
        } ?: return
        val point = centers[nearest]
        val dx = point.first - x
        val dy = point.second - y
        if (dx * dx + dy * dy <= hitRadiusSquared && !points.contains(nearest)) {
            addPointWithSkippedIntermediates(nearest)
        }
    }

    /**
     * Adds lattice points lying between the previously selected point and the new one.
     * Grid indexes are kept exactly as before (0..8), so saved pattern strings remain compatible.
     */
    private fun addPointWithSkippedIntermediates(target: Int) {
        val previous = points.lastOrNull()
        if (previous != null) {
            val fromRow = previous / 3
            val fromColumn = previous % 3
            val toRow = target / 3
            val toColumn = target % 3
            val rowDelta = toRow - fromRow
            val columnDelta = toColumn - fromColumn
            val divisor = gcd(abs(rowDelta), abs(columnDelta))

            if (divisor > 1) {
                val rowStep = rowDelta / divisor
                val columnStep = columnDelta / divisor
                for (step in 1 until divisor) {
                    val row = fromRow + rowStep * step
                    val column = fromColumn + columnStep * step
                    val intermediate = row * 3 + column
                    if (!points.contains(intermediate)) points.add(intermediate)
                }
            }
        }
        if (!points.contains(target)) points.add(target)
    }

    private fun gcd(first: Int, second: Int): Int {
        var a = first
        var b = second
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return abs(a)
    }
}
