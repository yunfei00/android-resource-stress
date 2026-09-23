package com.androidresourcestress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gpu3dPhase0Test {
    @Test
    fun internalResolutionProfilesAreFixedAndRepeatable() {
        assertEquals(1280, Gpu3dResolution.P720.width)
        assertEquals(720, Gpu3dResolution.P720.height)
        assertEquals(1920, Gpu3dResolution.P1080.width)
        assertEquals(1080, Gpu3dResolution.P1080.height)
        assertEquals(
            Gpu3dStressConfiguration(),
            Gpu3dStressConfiguration(Gpu3dStressLevel.MEDIUM),
        )
        assertEquals(Gpu3dResolution.P720, Gpu3dStressLevel.LOW.profile.resolution)
        assertEquals(Gpu3dFpsLimit.FPS_30, Gpu3dStressLevel.LOW.profile.fpsLimit)
        assertEquals(Gpu3dResolution.P1080, Gpu3dStressLevel.EXTREME.profile.resolution)
        assertEquals(Gpu3dFpsLimit.FPS_60, Gpu3dStressLevel.EXTREME.profile.fpsLimit)
    }

    @Test
    fun thirtyFpsPacerAcceptsEveryOtherSixtyHertzCallback() {
        val pacer = Gpu3dFramePacer(Gpu3dFpsLimit.FPS_30)
        val start = 1_000_000_000L
        assertTrue(pacer.shouldRender(start))
        assertFalse(pacer.shouldRender(start + 16_666_667L))
        assertTrue(pacer.shouldRender(start + 33_333_334L))
        assertFalse(pacer.shouldRender(start + 50_000_001L))
        assertTrue(pacer.shouldRender(start + 66_666_668L))
    }

    @Test
    fun sixtyFpsPacerAcceptsSixtyHertzCallbacksAndCanReset() {
        val pacer = Gpu3dFramePacer(Gpu3dFpsLimit.FPS_60)
        val start = 2_000_000_000L
        assertTrue(pacer.shouldRender(start))
        assertTrue(pacer.shouldRender(start + 16_666_667L))
        assertTrue(pacer.shouldRender(start + 33_333_334L))
        pacer.reset()
        assertTrue(pacer.shouldRender(10L))
    }

    @Test
    fun sixtyFpsPacerUsesAlternatingCallbacksOnNinetyHertzDisplay() {
        val pacer = Gpu3dFramePacer(Gpu3dFpsLimit.FPS_60)
        val start = 3_000_000_000L
        val decisions = (0..9).map { callback ->
            pacer.shouldRender(start + callback * 11_111_111L)
        }
        assertEquals(7, decisions.count { it })
    }
}
