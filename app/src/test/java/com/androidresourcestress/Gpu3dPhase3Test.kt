package com.androidresourcestress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gpu3dPhase3Test {
    @Test
    fun scenesHaveStablePublicOrder() {
        assertEquals(
            listOf(
                "WATER_RACE",
                "PARTICLE_STORM",
                "SHADER_STRESS",
                "GEOMETRY_STRESS",
                "OVERDRAW_STRESS",
            ),
            Gpu3dStressScene.entries.map { it.name },
        )
    }

    @Test
    fun everyLevelAndSceneCombinationIsDeterministic() {
        Gpu3dStressLevel.entries.forEach { level ->
            Gpu3dStressScene.entries.forEach { scene ->
                val first = Gpu3dStressConfiguration(level, scene).workload
                val second = Gpu3dStressConfiguration(level, scene).workload
                assertEquals(first, second)
                assertEquals(first.hashCode(), second.hashCode())
            }
        }
    }

    @Test
    fun eachSpecializedSceneEmphasizesItsNamedGpuWorkload() {
        Gpu3dStressLevel.entries.forEach { level ->
            val water = Gpu3dStressConfiguration(level, Gpu3dStressScene.WATER_RACE).workload
            val particles = Gpu3dStressConfiguration(level, Gpu3dStressScene.PARTICLE_STORM).workload
            val shader = Gpu3dStressConfiguration(level, Gpu3dStressScene.SHADER_STRESS).workload
            val geometry = Gpu3dStressConfiguration(level, Gpu3dStressScene.GEOMETRY_STRESS).workload
            val overdraw = Gpu3dStressConfiguration(level, Gpu3dStressScene.OVERDRAW_STRESS).workload

            assertTrue(particles.particleCount > water.particleCount)
            assertTrue(particles.particleLayers > water.particleLayers)
            assertTrue(shader.shaderIterations > water.shaderIterations)
            assertEquals(Gpu3dGeometryPrimitive.DENSE_SPHERE, geometry.geometryPrimitive)
            assertTrue(geometry.sceneModelCount > water.sceneModelCount)
            assertTrue(geometry.triangleCount > water.triangleCount)
            assertTrue(overdraw.fullScreenOverdrawLayers > 0)
            assertTrue(overdraw.particleCount > water.particleCount)
        }
    }

    @Test
    fun specializedPressureScalesUpAcrossLevels() {
        val levels = Gpu3dStressLevel.entries
        levels.zipWithNext().forEach { (lowerLevel, higherLevel) ->
            val lowerParticles = Gpu3dStressConfiguration(
                lowerLevel,
                Gpu3dStressScene.PARTICLE_STORM,
            ).workload
            val higherParticles = Gpu3dStressConfiguration(
                higherLevel,
                Gpu3dStressScene.PARTICLE_STORM,
            ).workload
            assertTrue(higherParticles.particleCount > lowerParticles.particleCount)

            val lowerGeometry = Gpu3dStressConfiguration(
                lowerLevel,
                Gpu3dStressScene.GEOMETRY_STRESS,
            ).workload
            val higherGeometry = Gpu3dStressConfiguration(
                higherLevel,
                Gpu3dStressScene.GEOMETRY_STRESS,
            ).workload
            assertTrue(higherGeometry.sceneModelCount > lowerGeometry.sceneModelCount)
            assertTrue(higherGeometry.triangleCount > lowerGeometry.triangleCount)

            val lowerOverdraw = Gpu3dStressConfiguration(
                lowerLevel,
                Gpu3dStressScene.OVERDRAW_STRESS,
            ).workload
            val higherOverdraw = Gpu3dStressConfiguration(
                higherLevel,
                Gpu3dStressScene.OVERDRAW_STRESS,
            ).workload
            assertTrue(
                higherOverdraw.fullScreenOverdrawLayers >
                    lowerOverdraw.fullScreenOverdrawLayers,
            )
        }
    }

    @Test
    fun geometryTriangleCountMatchesGeneratedDenseSphereTopology() {
        val workload = Gpu3dStressConfiguration(
            Gpu3dStressLevel.MAX,
            Gpu3dStressScene.GEOMETRY_STRESS,
        ).workload
        assertEquals(
            workload.geometrySegments * workload.geometryRings * 2,
            workload.primitiveTriangleCount,
        )
        assertEquals(
            workload.waterColumns * workload.waterRows * 2 +
                workload.sceneModelCount * workload.primitiveTriangleCount,
            workload.triangleCount,
        )
    }
}
