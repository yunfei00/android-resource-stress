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
    val averageFps: Double = 0.0,
    val minimumFps: Double = 0.0,
    val frameTimeMs: Double = 0.0,
    val maximumFrameTimeMs: Double = 0.0,
    val runtimeMs: Long = 0L,
    val renderedFrames: Long = 0L,
    val resolution: Gpu3dResolution = Gpu3dResolution.P720,
    val fpsLimit: Gpu3dFpsLimit = Gpu3dFpsLimit.FPS_60,
    val lastError: String = "",
)

data class Gpu3dFrameStatisticsSnapshot(
    val currentFps: Double,
    val averageFps: Double,
    val minimumFps: Double,
    val frameTimeMs: Double,
    val maximumFrameTimeMs: Double,
    val renderedFrames: Long,
)

/**
 * Collects frame statistics without depending on Android or OpenGL. A full
 * one-second window is required before minimum FPS is reported so renderer
 * startup does not become a false minimum.
 */
class Gpu3dFrameStatistics(
    private val windowNanos: Long = 1_000_000_000L,
) {
    private var firstFrameNanos = 0L
    private var lastFrameNanos = 0L
    private var windowStartNanos = 0L
    private var windowFrames = 0L
    private var renderedFrames = 0L
    private var currentFps = 0.0
    private var minimumFps = 0.0
    private var smoothedFrameTimeMs = 0.0
    private var maximumFrameTimeMs = 0.0

    fun reset() {
        firstFrameNanos = 0L
        lastFrameNanos = 0L
        windowStartNanos = 0L
        windowFrames = 0L
        renderedFrames = 0L
        currentFps = 0.0
        minimumFps = 0.0
        smoothedFrameTimeMs = 0.0
        maximumFrameTimeMs = 0.0
    }

    fun recordFrame(nowNanos: Long): Gpu3dFrameStatisticsSnapshot {
        if (renderedFrames == 0L || nowNanos < lastFrameNanos) {
            reset()
            firstFrameNanos = nowNanos
            lastFrameNanos = nowNanos
            windowStartNanos = nowNanos
            renderedFrames = 1L
            return snapshot()
        }

        val frameTimeMs = (nowNanos - lastFrameNanos) / 1_000_000.0
        lastFrameNanos = nowNanos
        smoothedFrameTimeMs = if (smoothedFrameTimeMs == 0.0) {
            frameTimeMs
        } else {
            smoothedFrameTimeMs * 0.85 + frameTimeMs * 0.15
        }
        maximumFrameTimeMs = maxOf(maximumFrameTimeMs, frameTimeMs)
        renderedFrames += 1L
        windowFrames += 1L

        val windowDuration = nowNanos - windowStartNanos
        if (windowDuration >= windowNanos) {
            currentFps = windowFrames * 1_000_000_000.0 / windowDuration
            minimumFps = if (minimumFps == 0.0) currentFps else minOf(minimumFps, currentFps)
            windowFrames = 0L
            windowStartNanos = nowNanos
        }
        return snapshot()
    }

    private fun snapshot(): Gpu3dFrameStatisticsSnapshot {
        val measuredDuration = lastFrameNanos - firstFrameNanos
        val averageFps = if (renderedFrames > 1L && measuredDuration > 0L) {
            (renderedFrames - 1L) * 1_000_000_000.0 / measuredDuration
        } else {
            0.0
        }
        return Gpu3dFrameStatisticsSnapshot(
            currentFps = currentFps,
            averageFps = averageFps,
            minimumFps = minimumFps,
            frameTimeMs = smoothedFrameTimeMs,
            maximumFrameTimeMs = maximumFrameTimeMs,
            renderedFrames = renderedFrames,
        )
    }
}

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
