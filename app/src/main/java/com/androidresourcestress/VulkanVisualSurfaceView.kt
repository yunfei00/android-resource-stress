package com.androidresourcestress

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

data class OnscreenVisualSnapshot(
    val running: Boolean,
    val framesPerSecond: Double,
    val frameTimeNanos: Double,
    val frameCount: Long,
    val lastError: String,
)

class VulkanVisualSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var requested = false
    private var targetPercent = 100
    private var surfaceAvailable = false
    private var nativeStartAttempted = false

    init {
        holder.addCallback(this)
        holder.setKeepScreenOn(false)
        contentDescription = context.getString(R.string.gpu_visual_content_description)
    }

    fun start(targetLoadPercent: Int) {
        targetPercent = targetLoadPercent.coerceIn(25, 100)
        requested = true
        if (surfaceAvailable) startNative()
    }

    fun stop() {
        requested = false
        nativeStartAttempted = false
        NativeStress.stopOnscreenVisual()
    }

    fun snapshot(): OnscreenVisualSnapshot = OnscreenVisualSnapshot(
        running = NativeStress.isOnscreenVisualRunning(),
        framesPerSecond = NativeStress.getOnscreenVisualFps(),
        frameTimeNanos = NativeStress.getOnscreenVisualFrameTimeNanos().toDouble(),
        frameCount = NativeStress.getOnscreenVisualFrameCount(),
        lastError = NativeStress.getOnscreenVisualLastError(),
    )

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        if (requested) startNative()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (requested && !NativeStress.isOnscreenVisualRunning()) startNative()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        nativeStartAttempted = false
        NativeStress.stopOnscreenVisual()
    }

    private fun startNative() {
        if (!holder.surface.isValid || NativeStress.isOnscreenVisualRunning() || nativeStartAttempted) return
        nativeStartAttempted = true
        NativeStress.startOnscreenVisual(holder.surface, targetPercent)
    }
}
