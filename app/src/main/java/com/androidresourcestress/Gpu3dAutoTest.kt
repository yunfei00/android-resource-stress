package com.androidresourcestress

enum class Gpu3dAutoDuration(val fixedSeconds: Int?) {
    SECONDS_10(10),
    SECONDS_20(20),
    SECONDS_30(30),
    SECONDS_60(60),
    CUSTOM(null),
    ;

    fun resolveSeconds(customSeconds: Int?): Int {
        val seconds = fixedSeconds ?: requireNotNull(customSeconds) {
            "Custom duration is required"
        }
        require(seconds in MIN_SECONDS..MAX_SECONDS) {
            "Duration must be between $MIN_SECONDS and $MAX_SECONDS seconds"
        }
        return seconds
    }

    companion object {
        const val MIN_SECONDS = 1
        const val MAX_SECONDS = 3_600
    }
}

enum class Gpu3dTraversalMode {
    LEVELS,
    SCENES,
}

data class Gpu3dAutoTestStep(
    val index: Int,
    val configuration: Gpu3dStressConfiguration,
)

data class Gpu3dAutoTestPlan(
    val traversalMode: Gpu3dTraversalMode,
    val stepDurationSeconds: Int,
    val steps: List<Gpu3dAutoTestStep>,
) {
    val stepDurationMs: Long = stepDurationSeconds * 1_000L

    init {
        require(stepDurationSeconds in Gpu3dAutoDuration.MIN_SECONDS..Gpu3dAutoDuration.MAX_SECONDS)
        require(steps.isNotEmpty())
        require(steps.map { it.index } == steps.indices.toList())
    }

    companion object {
        fun create(
            traversalMode: Gpu3dTraversalMode,
            selected: Gpu3dStressConfiguration,
            stepDurationSeconds: Int,
        ): Gpu3dAutoTestPlan {
            val configurations = when (traversalMode) {
                Gpu3dTraversalMode.LEVELS -> Gpu3dStressLevel.entries.map { level ->
                    Gpu3dStressConfiguration(level, selected.scene)
                }
                Gpu3dTraversalMode.SCENES -> Gpu3dStressScene.entries.map { scene ->
                    Gpu3dStressConfiguration(selected.level, scene)
                }
            }
            return Gpu3dAutoTestPlan(
                traversalMode = traversalMode,
                stepDurationSeconds = stepDurationSeconds,
                steps = configurations.mapIndexed(::Gpu3dAutoTestStep),
            )
        }
    }
}

data class Gpu3dAutoTestStepResult(
    val scene: Gpu3dStressScene,
    val level: Gpu3dStressLevel,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationMs: Long,
    val averageFps: Double,
    val minimumFps: Double,
    val maximumFrameTimeMs: Double,
    val averageFrameTimeMs: Double,
) {
    init {
        require(endTimeMillis >= startTimeMillis)
        require(durationMs >= 0L)
    }

    companion object {
        fun fromMetrics(
            metrics: Gpu3dStressMetrics,
            startTimeMillis: Long,
            endTimeMillis: Long,
        ): Gpu3dAutoTestStepResult = Gpu3dAutoTestStepResult(
            scene = metrics.scene,
            level = metrics.level,
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            durationMs = metrics.runtimeMs,
            averageFps = metrics.averageFps,
            minimumFps = metrics.minimumFps,
            maximumFrameTimeMs = metrics.maximumFrameTimeMs,
            averageFrameTimeMs = if (metrics.averageFps > 0.0) {
                1_000.0 / metrics.averageFps
            } else {
                0.0
            },
        )
    }
}

object Gpu3dIntegratedPlan {
    fun create(configuration: CombinedStressConfiguration): List<Gpu3dStressConfiguration> {
        val scene = configuration.gpuMode.gpu3dScene() ?: return emptyList()
        val level = if (configuration.gpuMode == GpuMode.MAX_GPU_STRESS) {
            Gpu3dStressLevel.MAX
        } else {
            configuration.gpu3dLevel
        }
        return when (configuration.gpu3dRunMode) {
            Gpu3dRunMode.FIXED -> listOf(Gpu3dStressConfiguration(level, scene))
            Gpu3dRunMode.TRAVERSE_LEVELS -> if (
                configuration.gpuMode == GpuMode.MAX_GPU_STRESS
            ) {
                listOf(Gpu3dStressConfiguration(Gpu3dStressLevel.MAX, scene))
            } else {
                Gpu3dStressLevel.entries.map { Gpu3dStressConfiguration(it, scene) }
            }
            Gpu3dRunMode.TRAVERSE_SCENES -> Gpu3dStressScene.entries.map {
                Gpu3dStressConfiguration(level, it)
            }
        }
    }
}
