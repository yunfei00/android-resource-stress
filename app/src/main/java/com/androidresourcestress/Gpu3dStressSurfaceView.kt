package com.androidresourcestress

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Choreographer

class Gpu3dStressSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), Choreographer.FrameCallback {
    private val waterRenderer = Gpu3dWaterRenderer()
    private var testRunning = false
    private var framePacer = Gpu3dFramePacer(Gpu3dFpsLimit.FPS_60)

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        setRenderer(waterRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = false
        contentDescription = context.getString(R.string.gpu3d_scene_description)
    }

    fun startTest(configuration: Gpu3dStressConfiguration) {
        stopFrameCallbacks()
        framePacer = Gpu3dFramePacer(configuration.fpsLimit)
        testRunning = true
        queueEvent { waterRenderer.start(configuration) }
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopTest() {
        if (!testRunning && !waterRenderer.snapshot().running) return
        testRunning = false
        stopFrameCallbacks()
        waterRenderer.markStopped()
        queueEvent { waterRenderer.stopAndRelease() }
        requestRender()
    }

    fun snapshot(): Gpu3dStressMetrics = waterRenderer.snapshot()

    override fun doFrame(frameTimeNanos: Long) {
        if (!testRunning) return
        if (framePacer.shouldRender(frameTimeNanos)) requestRender()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        stopTest()
        super.onDetachedFromWindow()
    }

    private fun stopFrameCallbacks() {
        Choreographer.getInstance().removeFrameCallback(this)
        framePacer.reset()
    }
}
