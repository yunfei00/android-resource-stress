package com.androidresourcestress

import android.os.Process
import android.os.SystemClock

data class CpuLoadSample(
    val appCpuLoadPercent: Double,
    val coreEquivalentPercent: Double,
)

class CpuMonitor(private val logicalCoreCount: Int) {
    private var previousProcessCpuMs = 0L
    private var previousWallMs = 0L

    fun reset() {
        previousProcessCpuMs = Process.getElapsedCpuTime()
        previousWallMs = SystemClock.elapsedRealtime()
    }

    fun sample(): CpuLoadSample {
        val processCpuMs = Process.getElapsedCpuTime()
        val wallMs = SystemClock.elapsedRealtime()
        if (previousWallMs == 0L) {
            previousProcessCpuMs = processCpuMs
            previousWallMs = wallMs
            return CpuLoadSample(0.0, 0.0)
        }

        val deltaCpuMs = (processCpuMs - previousProcessCpuMs).coerceAtLeast(0L)
        val deltaWallMs = (wallMs - previousWallMs).coerceAtLeast(1L)
        previousProcessCpuMs = processCpuMs
        previousWallMs = wallMs

        val maxCoreEquivalent = logicalCoreCount.coerceAtLeast(1) * 100.0
        val coreEquivalent = (deltaCpuMs.toDouble() / deltaWallMs * 100.0)
            .coerceIn(0.0, maxCoreEquivalent)
        val appLoad = (coreEquivalent / logicalCoreCount.coerceAtLeast(1))
            .coerceIn(0.0, 100.0)
        return CpuLoadSample(appLoad, coreEquivalent)
    }
}
