package he.vault.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.min

class WatchingEyeView(
    context: Context,
    private val animationsEnabled: Boolean
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePath = Path()
    private var closeStartedAt = -1L
    private var closeCallback: (() -> Unit)? = null
    private var closed = false

    init {
        contentDescription = "עין אדומה צופה"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val heightCap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            (width * 0.48f).toInt()
        } else MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        val wanted = min((width * 0.48f).toInt(), heightCap).coerceAtLeast(1)
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(wanted, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radiusX = width * 0.32f
        val baseHeight = min(height * 0.34f, width * 0.15f)
        val now = SystemClock.uptimeMillis()
        val closeProgress = if (closeStartedAt >= 0L) ((now - closeStartedAt) / 360f).coerceIn(0f, 1f) else 0f
        val pulse = if (animationsEnabled && closeStartedAt < 0L) {
            0.92f + 0.08f * ((now % 2400L) / 2400f * 6.28318f).let { kotlin.math.sin(it) }
        } else 1f
        val eyeHeight = baseHeight * pulse * (1f - closeProgress * 0.94f)

        paint.shader = RadialGradient(cx, cy, width * 0.40f,
            intArrayOf(0x33FF1744, 0x00FF1744), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, width * 0.40f, paint)
        paint.shader = null

        eyePath.reset()
        eyePath.moveTo(cx - radiusX, cy)
        eyePath.cubicTo(cx - radiusX * 0.52f, cy - eyeHeight * 1.75f, cx + radiusX * 0.52f, cy - eyeHeight * 1.75f, cx + radiusX, cy)
        eyePath.cubicTo(cx + radiusX * 0.52f, cy + eyeHeight * 1.75f, cx - radiusX * 0.52f, cy + eyeHeight * 1.75f, cx - radiusX, cy)
        eyePath.close()

        paint.style = Paint.Style.FILL
        paint.color = 0xFF111315.toInt()
        canvas.drawPath(eyePath, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(3f, width * 0.012f)
        paint.color = 0xFFFF1744.toInt()
        canvas.drawPath(eyePath, paint)

        if (eyeHeight > 2f) {
            paint.style = Paint.Style.FILL
            paint.color = 0x55FF1744
            canvas.drawCircle(cx, cy, eyeHeight * 0.95f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(2f, width * 0.008f)
            paint.color = 0xFFFF1744.toInt()
            canvas.drawCircle(cx, cy, eyeHeight * 0.78f, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFF1744.toInt()
            canvas.drawCircle(cx, cy, eyeHeight * 0.48f, paint)
            paint.color = 0xFF050505.toInt()
            canvas.drawCircle(cx, cy, eyeHeight * 0.19f, paint)
            paint.color = 0xFFE8ECE7.toInt()
            canvas.drawCircle(cx - eyeHeight * 0.12f, cy - eyeHeight * 0.13f, eyeHeight * 0.07f, paint)
        }

        if (animationsEnabled && !closed && closeStartedAt < 0L) postInvalidateDelayed(90)
        if (closeStartedAt >= 0L && closeProgress >= 1f && !closed) {
            closed = true
            closeCallback?.invoke()
            closeCallback = null
        } else if (closeStartedAt >= 0L && !closed) {
            postInvalidateDelayed(16)
        }
    }

    fun closeEye(onClosed: () -> Unit) {
        if (!animationsEnabled) {
            closed = true
            onClosed()
            return
        }
        closeCallback = onClosed
        closeStartedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        closeCallback = null
        super.onDetachedFromWindow()
    }
}
