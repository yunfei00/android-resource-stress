package com.androidresourcestress

import kotlin.math.roundToInt

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

enum class Gpu3dStressScene {
    WATER_RACE,
    PARTICLE_STORM,
    SHADER_STRESS,
    GEOMETRY_STRESS,
    OVERDRAW_STRESS,
}

enum class Gpu3dGeometryPrimitive {
    CUBE,
    DENSE_SPHERE,
}

data class Gpu3dStressProfile(
    val resolution: Gpu3dResolution,
    val fpsLimit: Gpu3dFpsLimit,
    val renderScale: Double,
    val waterColumns: Int,
    val waterRows: Int,
    val buoyCount: Int,
    val obstacleCount: Int,
    val gateCount: Int,
    val particleCount: Int,
    val shadowMapSize: Int,
    val shadowDistance: Float,
    val lightCount: Int,
    val shaderIterations: Int,
    val msaaSamples: Int,
    val postProcessQuality: Int,
    val waterWaveLayers: Int,
    val reflectionSteps: Int,
    val overdrawLayers: Int,
) {
    val renderWidth: Int = evenDimension(resolution.width, renderScale)
    val renderHeight: Int = evenDimension(resolution.height, renderScale)
    val sceneModelCount: Int = buoyCount + obstacleCount + RACER_PARTS + gateCount * GATE_PARTS
    val triangleCount: Int = waterColumns * waterRows * 2 + sceneModelCount * CUBE_TRIANGLES

    init {
        require(renderScale > 0.0)
        require(waterColumns > 0 && waterRows > 0)
        require(buoyCount > 0 && buoyCount % 2 == 0)
        require(obstacleCount > 0 && gateCount > 0 && particleCount > 0)
        require(shadowMapSize > 0 && shadowDistance > 0f)
        require(lightCount in 1..3)
        require(shaderIterations in 1..32)
        require(msaaSamples in setOf(1, 2, 4))
        require(postProcessQuality in 0..4)
        require(waterWaveLayers in 1..6)
        require(reflectionSteps in 0..8)
        require(overdrawLayers in 1..5)
    }

    private companion object {
        const val RACER_PARTS = 8
        const val GATE_PARTS = 4
        const val CUBE_TRIANGLES = 12

        fun evenDimension(base: Int, scale: Double): Int =
            ((base * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
    }
}

enum class Gpu3dStressLevel(val profile: Gpu3dStressProfile) {
    LOW(
        Gpu3dStressProfile(
            Gpu3dResolution.P720, Gpu3dFpsLimit.FPS_30, 0.75,
            72, 48, 16, 6, 1, 96, 512, 24f,
            1, 4, 1, 0, 2, 1, 1,
        ),
    ),
    MEDIUM(
        Gpu3dStressProfile(
            Gpu3dResolution.P720, Gpu3dFpsLimit.FPS_60, 1.0,
            112, 72, 24, 10, 2, 192, 768, 26f,
            1, 8, 2, 1, 3, 2, 1,
        ),
    ),
    HIGH(
        Gpu3dStressProfile(
            Gpu3dResolution.P1080, Gpu3dFpsLimit.FPS_60, 0.8,
            160, 104, 36, 16, 3, 320, 1024, 28f,
            2, 12, 2, 2, 4, 4, 2,
        ),
    ),
    EXTREME(
        Gpu3dStressProfile(
            Gpu3dResolution.P1080, Gpu3dFpsLimit.FPS_60, 1.0,
            192, 128, 48, 24, 4, 512, 1536, 30f,
            2, 18, 4, 3, 5, 6, 3,
        ),
    ),
    MAX(
        Gpu3dStressProfile(
            Gpu3dResolution.P1080, Gpu3dFpsLimit.FPS_60, 4.0 / 3.0,
            256, 160, 72, 36, 6, 768, 2048, 34f,
            3, 26, 4, 4, 6, 8, 5,
        ),
    ),
}

data class Gpu3dSceneWorkload(
    val scene: Gpu3dStressScene,
    val profile: Gpu3dStressProfile,
    val waterColumns: Int = profile.waterColumns,
    val waterRows: Int = profile.waterRows,
    val buoyCount: Int = profile.buoyCount,
    val obstacleCount: Int = profile.obstacleCount,
    val gateCount: Int = profile.gateCount,
    val particleCount: Int = profile.particleCount,
    val particleLayers: Int = profile.overdrawLayers,
    val shaderIterations: Int = profile.shaderIterations,
    val waterWaveLayers: Int = profile.waterWaveLayers,
    val reflectionSteps: Int = profile.reflectionSteps,
    val postProcessQuality: Int = profile.postProcessQuality,
    val geometryPrimitive: Gpu3dGeometryPrimitive = Gpu3dGeometryPrimitive.CUBE,
    val geometrySegments: Int = 1,
    val geometryRings: Int = 1,
    val fullScreenOverdrawLayers: Int = 0,
) {
    val primitiveTriangleCount: Int = when (geometryPrimitive) {
        Gpu3dGeometryPrimitive.CUBE -> CUBE_TRIANGLES
        Gpu3dGeometryPrimitive.DENSE_SPHERE -> geometrySegments * geometryRings * 2
    }
    val sceneModelCount: Int = buoyCount + obstacleCount + RACER_PARTS + gateCount * GATE_PARTS
    val triangleCount: Int = waterColumns * waterRows * 2 +
        sceneModelCount * primitiveTriangleCount

    init {
        require(waterColumns > 0 && waterRows > 0)
        require(buoyCount > 0 && buoyCount % 2 == 0)
        require(obstacleCount > 0 && gateCount > 0)
        require(particleCount > 0 && particleLayers > 0)
        require(shaderIterations in 1..MAX_SHADER_ITERATIONS)
        require(waterWaveLayers in 1..6)
        require(reflectionSteps in 0..8)
        require(postProcessQuality in 0..4)
        require(geometrySegments > 0 && geometryRings > 0)
        require(fullScreenOverdrawLayers in 0..MAX_OVERDRAW_LAYERS)
    }

    companion object {
        const val RACER_PARTS = 8
        const val GATE_PARTS = 4
        const val CUBE_TRIANGLES = 12
        const val MAX_SHADER_ITERATIONS = 64
        const val MAX_OVERDRAW_LAYERS = 32

        fun create(level: Gpu3dStressLevel, scene: Gpu3dStressScene): Gpu3dSceneWorkload {
            val profile = level.profile
            val geometryMultipliers = intArrayOf(2, 3, 4, 6, 8)
            val geometrySegments = intArrayOf(12, 16, 20, 24, 32)
            val geometryRings = intArrayOf(8, 10, 14, 18, 24)
            val particleMultipliers = intArrayOf(4, 5, 6, 7, 8)
            val overdrawLayers = intArrayOf(4, 8, 14, 22, 32)
            val index = level.ordinal
            return when (scene) {
                Gpu3dStressScene.WATER_RACE -> Gpu3dSceneWorkload(scene, profile)
                Gpu3dStressScene.PARTICLE_STORM -> Gpu3dSceneWorkload(
                    scene = scene,
                    profile = profile,
                    particleCount = profile.particleCount * particleMultipliers[index],
                    particleLayers = profile.overdrawLayers + 2,
                    shaderIterations = maxOf(4, profile.shaderIterations / 2),
                    reflectionSteps = maxOf(1, profile.reflectionSteps / 2),
                )
                Gpu3dStressScene.SHADER_STRESS -> Gpu3dSceneWorkload(
                    scene = scene,
                    profile = profile,
                    particleCount = maxOf(32, profile.particleCount / 2),
                    particleLayers = 1,
                    shaderIterations = minOf(
                        MAX_SHADER_ITERATIONS,
                        profile.shaderIterations * 2 + 12,
                    ),
                    waterWaveLayers = 6,
                    reflectionSteps = minOf(8, profile.reflectionSteps + 2),
                    postProcessQuality = maxOf(2, profile.postProcessQuality),
                )
                Gpu3dStressScene.GEOMETRY_STRESS -> {
                    val multiplier = geometryMultipliers[index]
                    Gpu3dSceneWorkload(
                        scene = scene,
                        profile = profile,
                        buoyCount = profile.buoyCount * multiplier,
                        obstacleCount = profile.obstacleCount * multiplier,
                        gateCount = profile.gateCount * multiplier,
                        particleCount = maxOf(32, profile.particleCount / 2),
                        particleLayers = 1,
                        geometryPrimitive = Gpu3dGeometryPrimitive.DENSE_SPHERE,
                        geometrySegments = geometrySegments[index],
                        geometryRings = geometryRings[index],
                    )
                }
                Gpu3dStressScene.OVERDRAW_STRESS -> Gpu3dSceneWorkload(
                    scene = scene,
                    profile = profile,
                    particleCount = profile.particleCount * 2,
                    particleLayers = profile.overdrawLayers + 1,
                    fullScreenOverdrawLayers = overdrawLayers[index],
                )
            }
        }
    }
}

data class Gpu3dStressConfiguration(
    val level: Gpu3dStressLevel = Gpu3dStressLevel.MEDIUM,
    val scene: Gpu3dStressScene = Gpu3dStressScene.WATER_RACE,
) {
    val profile: Gpu3dStressProfile get() = level.profile
    val workload: Gpu3dSceneWorkload = Gpu3dSceneWorkload.create(level, scene)
    val resolution: Gpu3dResolution get() = profile.resolution
    val fpsLimit: Gpu3dFpsLimit get() = profile.fpsLimit
}

data class Gpu3dStressMetrics(
    val running: Boolean = false,
    val currentFps: Double = 0.0,
    val averageFps: Double = 0.0,
    val minimumFps: Double = 0.0,
    val frameTimeMs: Double = 0.0,
    val maximumFrameTimeMs: Double = 0.0,
    val runtimeMs: Long = 0L,
    val renderedFrames: Long = 0L,
    val level: Gpu3dStressLevel = Gpu3dStressLevel.MEDIUM,
    val scene: Gpu3dStressScene = Gpu3dStressScene.WATER_RACE,
    val resolution: Gpu3dResolution = Gpu3dResolution.P720,
    val fpsLimit: Gpu3dFpsLimit = Gpu3dFpsLimit.FPS_60,
    val renderWidth: Int = 0,
    val renderHeight: Int = 0,
    val msaaSamples: Int = 1,
    val modelCount: Int = 0,
    val triangleCount: Int = 0,
    val particleCount: Int = 0,
    val particleLayers: Int = 0,
    val shaderIterations: Int = 0,
    val overdrawLayers: Int = 0,
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
