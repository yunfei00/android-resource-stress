package com.androidresourcestress

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class VisualStressSnapshot(
    val running: Boolean,
    val framesPerSecond: Double,
    val frameTimeNanos: Double,
    val renderedFrames: Long,
)

class GpuVisualStressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(10, 15, 28)
        style = Paint.Style.FILL
    }
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(85, 205, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.4f
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 120, 75)
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 115, 90, 255)
        style = Paint.Style.FILL
    }
    private val path = Path()
    private var running = false
    private var targetPercent = 100
    private var startNanos = 0L
    private var lastFrameNanos = 0L
    private var fpsWindowStartedNanos = 0L
    private var fpsWindowFrames = 0L
    private var renderedFrames = 0L
    private var measuredFps = 0.0
    private var smoothedFrameTimeNanos = 0.0

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        contentDescription = context.getString(R.string.gpu_visual_content_description)
    }

    fun start(targetLoadPercent: Int) {
        targetPercent = targetLoadPercent.coerceIn(25, 100)
        if (running) return
        running = true
        startNanos = SystemClock.elapsedRealtimeNanos()
        lastFrameNanos = 0L
        fpsWindowStartedNanos = startNanos
        fpsWindowFrames = 0L
        renderedFrames = 0L
        measuredFps = 0.0
        smoothedFrameTimeNanos = 0.0
        visibility = VISIBLE
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        invalidate()
    }

    fun snapshot(): VisualStressSnapshot = VisualStressSnapshot(
        running = running,
        framesPerSecond = measuredFps,
        frameTimeNanos = smoothedFrameTimeNanos,
        renderedFrames = renderedFrames,
    )

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos > 0L) {
            val delta = (frameTimeNanos - lastFrameNanos).coerceAtLeast(0L).toDouble()
            smoothedFrameTimeNanos = if (smoothedFrameTimeNanos == 0.0) {
                delta
            } else {
                smoothedFrameTimeNanos * 0.85 + delta * 0.15
            }
        }
        lastFrameNanos = frameTimeNanos
        renderedFrames += 1L
        fpsWindowFrames += 1L
        val windowNanos = frameTimeNanos - fpsWindowStartedNanos
        if (windowNanos >= 1_000_000_000L) {
            measuredFps = fpsWindowFrames * 1_000_000_000.0 / windowNanos
            fpsWindowStartedNanos = frameTimeNanos
            fpsWindowFrames = 0L
        }
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        if (!running || width <= 0 || height <= 0) return

        val elapsed = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000_000.0
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) * 0.32f
        val rings = 3 + targetPercent / 25
        for (ring in 0 until rings) {
            val ringRadius = radius * (0.35f + ring * 0.14f)
            val pulse = (sin(elapsed * 1.7 + ring) * 0.08 + 1.0).toFloat()
            canvas.drawCircle(centerX, centerY, ringRadius * pulse, glowPaint)
        }

        val meshCount = 8 + targetPercent / 5
        path.reset()
        for (index in 0 until meshCount) {
            val angle = elapsed * (0.35 + index % 4 * 0.05) + 2.0 * PI * index / meshCount
            val wave = 0.58 + 0.32 * sin(elapsed * 1.3 + index * 0.73)
            val x = centerX + (cos(angle) * radius * wave).toFloat()
            val y = centerY + (sin(angle * 1.07) * radius * wave).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, meshPaint)

        val particles = 48 + targetPercent * 2
        val particleRadius = resources.displayMetrics.density * 1.8f
        for (index in 0 until particles) {
            val seed = index * 0.61803398875
            val angle = elapsed * (0.45 + (index % 9) * 0.025) + seed * 2.0 * PI
            val orbit = radius * (0.22 + ((index * 37) % 100) / 118.0)
            val x = centerX + (cos(angle) * orbit).toFloat()
            val y = centerY + (sin(angle * 1.13) * orbit).toFloat()
            particlePaint.alpha = 90 + index % 166
            canvas.drawCircle(x, y, particleRadius + index % 3, particlePaint)
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
