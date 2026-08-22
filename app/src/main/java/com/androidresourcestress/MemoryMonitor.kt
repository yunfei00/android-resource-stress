package com.androidresourcestress

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock

data class MemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val systemUsedBytes: Long,
    val appPssBytes: Long,
    val nativePssBytes: Long,
    val lowMemory: Boolean,
    val lowMemoryThresholdBytes: Long,
)

class MemoryMonitor(context: Context) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    fun sample(): MemorySnapshot {
        val systemInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(systemInfo)
        val processInfo = activityManager
            .getProcessMemoryInfo(intArrayOf(Process.myPid()))
            .firstOrNull() ?: Debug.MemoryInfo()
        val total = systemInfo.totalMem.coerceAtLeast(0L)
        val available = systemInfo.availMem.coerceAtLeast(0L)
        return MemorySnapshot(
            totalBytes = total,
            availableBytes = available,
            systemUsedBytes = (total - available).coerceAtLeast(0L),
            appPssBytes = processInfo.totalPss.toLong() * 1024L,
            nativePssBytes = processInfo.nativePss.toLong() * 1024L,
            lowMemory = systemInfo.lowMemory,
            lowMemoryThresholdBytes = systemInfo.threshold.coerceAtLeast(0L),
        )
    }
}

class MemoryActivityMonitor {
    private var previousProcessedBytes = 0L
    private var previousWallMs = 0L

    fun reset(processedBytes: Long = 0L) {
        previousProcessedBytes = processedBytes.coerceAtLeast(0L)
        previousWallMs = SystemClock.elapsedRealtime()
    }

    fun sample(processedBytes: Long): Double {
        val now = SystemClock.elapsedRealtime()
        if (previousWallMs == 0L || processedBytes < previousProcessedBytes) {
            reset(processedBytes)
            return 0.0
        }
        val elapsedMs = (now - previousWallMs).coerceAtLeast(1L)
        val deltaBytes = processedBytes - previousProcessedBytes
        previousProcessedBytes = processedBytes
        previousWallMs = now
        return deltaBytes.toDouble() * 1000.0 / elapsedMs
    }
}
