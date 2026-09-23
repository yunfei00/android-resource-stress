package com.androidresourcestress

enum class Gpu3dResolution(
    val width: Int,
    val height: Int,
) {
    P720(1280, 720),
    P1080(1920, 1080),
}

enum class Gpu3dFpsLimit(val framesPerSecond: Int) {
    FPS_30(30),
    FPS_60(60),
}

data class Gpu3dStressConfiguration(
    val resolution: Gpu3dResolution = Gpu3dResolution.P720,
    val fpsLimit: Gpu3dFpsLimit = Gpu3dFpsLimit.FPS_60,
)

data class Gpu3dStressMetrics(
    val running: Boolean = false,
    val currentFps: Double = 0.0,
    val frameTimeMs: Double = 0.0,
    val runtimeMs: Long = 0L,
    val renderedFrames: Long = 0L,
    val resolution: Gpu3dResolution = Gpu3dResolution.P720,
    val fpsLimit: Gpu3dFpsLimit = Gpu3dFpsLimit.FPS_60,
    val lastError: String = "",
)

/**
 * Choreographer calls may be a little early relative to an exact 30/60 Hz
 * interval. A ten-percent tolerance prevents a 60 Hz display from
 * accidentally pacing a requested 30 FPS at 20 FPS because of rounding.
 */
class Gpu3dFramePacer(limit: Gpu3dFpsLimit) {
    private var intervalNanos = intervalFor(limit)
    private var lastCallbackNanos = 0L
    private var accumulatedNanos = 0L

    fun updateLimit(limit: Gpu3dFpsLimit) {
        intervalNanos = intervalFor(limit)
        reset()
    }

    fun reset() {
        lastCallbackNanos = 0L
        accumulatedNanos = 0L
    }

    fun shouldRender(frameTimeNanos: Long): Boolean {
        if (lastCallbackNanos == 0L || frameTimeNanos < lastCallbackNanos) {
            lastCallbackNanos = frameTimeNanos
            accumulatedNanos = 0L
            return true
        }
        accumulatedNanos += frameTimeNanos - lastCallbackNanos
        lastCallbackNanos = frameTimeNanos
        if (accumulatedNanos < intervalNanos * 9L / 10L) return false
        accumulatedNanos = (accumulatedNanos - intervalNanos).coerceAtLeast(0L)
        return true
    }

    private fun intervalFor(limit: Gpu3dFpsLimit): Long =
        1_000_000_000L / limit.framesPerSecond
}
