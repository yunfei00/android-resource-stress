package com.androidresourcestress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gpu3dPhase5Test {
    @Test
    fun gpuModesRouteToTheCorrectRenderingBackend() {
        assertTrue(GpuMode.VISUAL.usesVulkanVisual)
        assertTrue(GpuMode.MIXED.usesVulkanVisual)
        assertFalse(GpuMode.WATER_RACE.usesVulkanVisual)
        assertTrue(GpuMode.WATER_RACE.usesGpu3d)
        assertTrue(GpuMode.WATER_RACE.isOnscreenOnly)
        assertFalse(GpuMode.WATER_RACE.keepsComputeWithSurface)
        assertTrue(GpuMode.MAX_GPU_STRESS.usesGpu3d)
        assertTrue(GpuMode.MAX_GPU_STRESS.keepsComputeWithSurface)
        assertFalse(GpuMode.MAX_GPU_STRESS.isOnscreenOnly)
        assertEquals(Gpu3dStressScene.WATER_RACE, GpuMode.WATER_RACE.gpu3dScene())
        assertEquals(Gpu3dStressScene.OVERDRAW_STRESS, GpuMode.MAX_GPU_STRESS.gpu3dScene())
        assertEquals(null, GpuMode.COMPUTE.gpu3dScene())
    }

    @Test
    fun phase5ConfigurationRoundTripsGpu3dSceneTraversal() {
        val original = PresetConfigurations.create(StressPreset.CUSTOM).copy(
            gpuMode = GpuMode.PARTICLE_STRESS,
            gpu3dLevel = Gpu3dStressLevel.EXTREME,
            gpu3dRunMode = Gpu3dRunMode.TRAVERSE_SCENES,
            gpu3dStepDurationSeconds = 30,
        )
        val restored = SessionJsonCodec.configurationFromJson(
            SessionJsonCodec.configurationToJson(original),
        )
        assertEquals(original, restored)
        val plan = Gpu3dIntegratedPlan.create(restored)
        assertEquals(Gpu3dStressScene.entries, plan.map { it.scene })
        assertTrue(plan.all { it.level == Gpu3dStressLevel.EXTREME })
    }

    @Test
    fun maxGpuPlanForcesMaxLevelAndKeepsRequestedTraversal() {
        val configuration = PresetConfigurations.create(StressPreset.CUSTOM).copy(
            gpuMode = GpuMode.MAX_GPU_STRESS,
            gpu3dLevel = Gpu3dStressLevel.LOW,
            gpu3dRunMode = Gpu3dRunMode.TRAVERSE_SCENES,
        )
        val plan = Gpu3dIntegratedPlan.create(configuration)
        assertEquals(Gpu3dStressScene.entries, plan.map { it.scene })
        assertTrue(plan.all { it.level == Gpu3dStressLevel.MAX })
    }

    @Test
    fun maxGpuNeverDropsToLowerLevels() {
        val configuration = PresetConfigurations.create(StressPreset.CUSTOM).copy(
            gpuMode = GpuMode.MAX_GPU_STRESS,
            gpu3dLevel = Gpu3dStressLevel.LOW,
            gpu3dRunMode = Gpu3dRunMode.TRAVERSE_LEVELS,
        )
        assertEquals(
            listOf(
                Gpu3dStressConfiguration(
                    Gpu3dStressLevel.MAX,
                    Gpu3dStressScene.OVERDRAW_STRESS,
                ),
            ),
            Gpu3dIntegratedPlan.create(configuration),
        )
    }

    @Test
    fun oldConfigurationGetsSafeGpu3dDefaults() {
        val restored = SessionJsonCodec.configurationFromJson(
            org.json.JSONObject()
                .put("preset", StressPreset.EXTREME.name)
                .put("gpuMode", GpuMode.COMPUTE.name),
        )
        assertEquals(Gpu3dStressLevel.MEDIUM, restored.gpu3dLevel)
        assertEquals(Gpu3dRunMode.FIXED, restored.gpu3dRunMode)
        assertEquals(20, restored.gpu3dStepDurationSeconds)
    }

    @Test
    fun phase5SessionRoundTripsIntegratedFrameStatistics() {
        val configuration = PresetConfigurations.create(StressPreset.CUSTOM).copy(
            gpuMode = GpuMode.GEOMETRY_STRESS,
            gpu3dLevel = Gpu3dStressLevel.HIGH,
            gpu3dRunMode = Gpu3dRunMode.TRAVERSE_LEVELS,
            gpu3dStepDurationSeconds = 10,
        )
        val original = StressSessionSnapshot(
            sessionId = 5L,
            startWallTimeMs = 10L,
            elapsedTimeMs = 20_000L,
            configuration = configuration,
            resolvedMemoryTargetBytes = 0L,
            allocatedMemoryBytes = 0L,
            peakCpuLoadPercent = 0.0,
            peakCoreEquivalentPercent = 0.0,
            peakAppPssBytes = 0L,
            peakNativePssBytes = 0L,
            peakMemoryActivityBytesPerSecond = 0.0,
            peakDispatchRate = 0.0,
            averageGpuWorkTimeNanos = 0.0,
            storageWorkingSetBytes = 0L,
            storageBytesRead = 0L,
            storageBytesWritten = 0L,
            peakStorageReadActivityBytesPerSecond = 0.0,
            peakStorageWriteActivityBytesPerSecond = 0.0,
            startBatteryTemperatureCelsius = 35.0,
            peakBatteryTemperatureCelsius = 39.0,
            highestThermalStatus = 1,
            stopReason = StopReason.DURATION_COMPLETED,
            lastError = null,
            peakVisualFps = 59.8,
            minimumVisualFps = 42.5,
            averageVisualFrameTimeNanos = 16_800_000.0,
            maximumVisualFrameTimeNanos = 38_200_000.0,
            eventTimeline = listOf(
                SessionEvent(0L, SessionEventType.GPU_3D_STEP, "GEOMETRY_STRESS/LOW"),
            ),
        )
        val restored = SessionJsonCodec.fromJson(SessionJsonCodec.toJson(original))
        assertEquals(original, restored)
        assertEquals(42.5, restored.minimumVisualFps, 0.0)
        assertEquals(38_200_000.0, restored.maximumVisualFrameTimeNanos, 0.0)
        assertEquals(SessionEventType.GPU_3D_STEP, restored.eventTimeline.single().type)
    }
}
