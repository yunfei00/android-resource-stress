package com.androidresourcestress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gpu3dPhase2Test {
    @Test
    fun stressLevelsHaveStablePublicOrder() {
        assertEquals(
            listOf("LOW", "MEDIUM", "HIGH", "EXTREME", "MAX"),
            Gpu3dStressLevel.entries.map { it.name },
        )
    }

    @Test
    fun fixedProfilesProduceExpectedRenderTargetsAndLimits() {
        val expected = listOf(
            Gpu3dStressLevel.LOW to Triple(960, 540, 30),
            Gpu3dStressLevel.MEDIUM to Triple(1280, 720, 60),
            Gpu3dStressLevel.HIGH to Triple(1536, 864, 60),
            Gpu3dStressLevel.EXTREME to Triple(1920, 1080, 60),
            Gpu3dStressLevel.MAX to Triple(2560, 1440, 60),
        )

        expected.forEach { (level, target) ->
            assertEquals(target.first, level.profile.renderWidth)
            assertEquals(target.second, level.profile.renderHeight)
            assertEquals(target.third, level.profile.fpsLimit.framesPerSecond)
            assertEquals(0, level.profile.renderWidth % 2)
            assertEquals(0, level.profile.renderHeight % 2)
        }
    }

    @Test
    fun selectingTheSameLevelAlwaysReturnsTheSameParameters() {
        Gpu3dStressLevel.entries.forEach { level ->
            val first = Gpu3dStressConfiguration(level).profile
            val second = Gpu3dStressConfiguration(level).profile
            assertEquals(first, second)
            assertEquals(first.hashCode(), second.hashCode())
        }
    }

    @Test
    fun workloadComplexityIncreasesWithEveryLevel() {
        val profiles = Gpu3dStressLevel.entries.map { it.profile }
        profiles.zipWithNext().forEach { (lower, higher) ->
            assertTrue(higher.renderWidth * higher.renderHeight > lower.renderWidth * lower.renderHeight)
            assertTrue(higher.sceneModelCount > lower.sceneModelCount)
            assertTrue(higher.triangleCount > lower.triangleCount)
            assertTrue(higher.particleCount > lower.particleCount)
            assertTrue(higher.shadowMapSize > lower.shadowMapSize)
            assertTrue(higher.shaderIterations > lower.shaderIterations)
            assertTrue(higher.postProcessQuality > lower.postProcessQuality)
            assertTrue(higher.reflectionSteps > lower.reflectionSteps)
            assertTrue(higher.overdrawLayers >= lower.overdrawLayers)
        }
    }

    @Test
    fun derivedTriangleCountIncludesWaterAndEverySceneModel() {
        Gpu3dStressLevel.entries.forEach { level ->
            val profile = level.profile
            val expected = profile.waterColumns * profile.waterRows * 2 +
                profile.sceneModelCount * 12
            assertEquals(expected, profile.triangleCount)
        }
    }
}
