package com.androidresourcestress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gpu3dPhase1Test {
    @Test
    fun frameStatisticsReportAverageMinimumAndMaximumWithoutStartupBias() {
        val statistics = Gpu3dFrameStatistics()
        val start = 4_000_000_000L
        var snapshot = statistics.recordFrame(start)
        repeat(60) { frame ->
            snapshot = statistics.recordFrame(start + (frame + 1L) * 16_666_667L)
        }
        assertEquals(60.0, snapshot.currentFps, 0.1)
        assertEquals(60.0, snapshot.averageFps, 0.1)
        assertEquals(60.0, snapshot.minimumFps, 0.1)
        assertEquals(16.67, snapshot.frameTimeMs, 0.1)
        assertEquals(16.67, snapshot.maximumFrameTimeMs, 0.1)

        snapshot = statistics.recordFrame(start + 1_050_000_000L)
        assertTrue(snapshot.maximumFrameTimeMs >= 49.9)
    }

    @Test
    fun frameStatisticsResetClearsPreviousSession() {
        val statistics = Gpu3dFrameStatistics()
        statistics.recordFrame(0L)
        val second = statistics.recordFrame(1_000_000_000L)
        assertEquals(2L, second.renderedFrames)
        statistics.reset()
        val reset = statistics.recordFrame(3_000_000_000L)
        assertEquals(1L, reset.renderedFrames)
        assertEquals(0.0, reset.minimumFps, 0.0)
        assertEquals(0.0, reset.maximumFrameTimeMs, 0.0)
    }

    @Test
    fun frameStatisticsKeepTheSlowestCompletedOneSecondWindow() {
        val statistics = Gpu3dFrameStatistics()
        val start = 5_000_000_000L
        var snapshot = statistics.recordFrame(start)
        repeat(60) { frame ->
            snapshot = statistics.recordFrame(start + (frame + 1L) * 16_666_667L)
        }
        val secondWindowStart = start + 60L * 16_666_667L
        repeat(30) { frame ->
            snapshot = statistics.recordFrame(
                secondWindowStart + (frame + 1L) * 33_333_334L,
            )
        }
        assertEquals(30.0, snapshot.currentFps, 0.1)
        assertEquals(30.0, snapshot.minimumFps, 0.1)
        assertTrue(snapshot.maximumFrameTimeMs >= 33.3)
    }
}
