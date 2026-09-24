package com.androidresourcestress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Gpu3dPhase4Test {
    @Test
    fun fixedAndCustomDurationsResolveDeterministically() {
        assertEquals(10, Gpu3dAutoDuration.SECONDS_10.resolveSeconds(null))
        assertEquals(20, Gpu3dAutoDuration.SECONDS_20.resolveSeconds(null))
        assertEquals(30, Gpu3dAutoDuration.SECONDS_30.resolveSeconds(null))
        assertEquals(60, Gpu3dAutoDuration.SECONDS_60.resolveSeconds(null))
        assertEquals(17, Gpu3dAutoDuration.CUSTOM.resolveSeconds(17))
        assertThrows(IllegalArgumentException::class.java) {
            Gpu3dAutoDuration.CUSTOM.resolveSeconds(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Gpu3dAutoDuration.CUSTOM.resolveSeconds(3_601)
        }
    }

    @Test
    fun levelTraversalUsesEveryLevelInStableOrderAndKeepsScene() {
        val plan = Gpu3dAutoTestPlan.create(
            traversalMode = Gpu3dTraversalMode.LEVELS,
            selected = Gpu3dStressConfiguration(
                Gpu3dStressLevel.HIGH,
                Gpu3dStressScene.SHADER_STRESS,
            ),
            stepDurationSeconds = 20,
        )
        assertEquals(Gpu3dStressLevel.entries, plan.steps.map { it.configuration.level })
        assertTrue(plan.steps.all { it.configuration.scene == Gpu3dStressScene.SHADER_STRESS })
        assertEquals(20_000L, plan.stepDurationMs)
    }

    @Test
    fun sceneTraversalUsesEverySceneInStableOrderAndKeepsLevel() {
        val plan = Gpu3dAutoTestPlan.create(
            traversalMode = Gpu3dTraversalMode.SCENES,
            selected = Gpu3dStressConfiguration(
                Gpu3dStressLevel.EXTREME,
                Gpu3dStressScene.WATER_RACE,
            ),
            stepDurationSeconds = 30,
        )
        assertEquals(Gpu3dStressScene.entries, plan.steps.map { it.configuration.scene })
        assertTrue(plan.steps.all { it.configuration.level == Gpu3dStressLevel.EXTREME })
        assertEquals(plan.steps.indices.toList(), plan.steps.map { it.index })
    }

    @Test
    fun resultRecordsRequiredTimingAndFrameStatistics() {
        val result = Gpu3dAutoTestStepResult.fromMetrics(
            metrics = Gpu3dStressMetrics(
                scene = Gpu3dStressScene.PARTICLE_STORM,
                level = Gpu3dStressLevel.HIGH,
                runtimeMs = 19_950L,
                averageFps = 50.0,
                minimumFps = 43.5,
                maximumFrameTimeMs = 37.2,
            ),
            startTimeMillis = 1_000L,
            endTimeMillis = 21_000L,
        )
        assertEquals(Gpu3dStressScene.PARTICLE_STORM, result.scene)
        assertEquals(Gpu3dStressLevel.HIGH, result.level)
        assertEquals(1_000L, result.startTimeMillis)
        assertEquals(21_000L, result.endTimeMillis)
        assertEquals(19_950L, result.durationMs)
        assertEquals(50.0, result.averageFps, 0.0)
        assertEquals(43.5, result.minimumFps, 0.0)
        assertEquals(37.2, result.maximumFrameTimeMs, 0.0)
        assertEquals(20.0, result.averageFrameTimeMs, 0.0001)
    }
}
