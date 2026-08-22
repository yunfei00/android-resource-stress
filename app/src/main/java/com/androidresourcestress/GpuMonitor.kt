package com.androidresourcestress

import android.os.SystemClock
import java.util.Locale

data class GpuInfo(
    val supported: Boolean,
    val deviceName: String,
    val apiVersion: Int,
    val vendorId: Long,
    val deviceId: Long,
    val computeQueueSupported: Boolean,
    val maxWorkGroupCount: LongArray,
    val maxWorkGroupSize: LongArray,
    val maxWorkGroupInvocations: Long,
    val timestampSupported: Boolean,
    val bufferBytes: Long,
) {
    val apiVersionLabel: String
        get() {
            if (apiVersion == 0) return "N/A"
            val major = apiVersion ushr 22 and 0x7F
            val minor = apiVersion ushr 12 and 0x3FF
            val patch = apiVersion and 0xFFF
            return "$major.$minor.$patch"
        }

    val vendorDeviceLabel: String
        get() = String.format(Locale.US, "0x%04X / 0x%04X", vendorId, deviceId)

    val maxWorkGroupCountLabel: String
        get() = maxWorkGroupCount.joinToString(" × ")

    val maxWorkGroupSizeLabel: String
        get() = maxWorkGroupSize.joinToString(" × ")
}

enum class GpuNativeStatus(val code: Int) {
    STOPPED(0),
    RUNNING(1),
    UNSUPPORTED(2),
    ERROR(3),
    STARTING(4),
    STOPPING(5),
    CHECKING(6),
    ;

    companion object {
        fun fromCode(code: Int): GpuNativeStatus = entries.firstOrNull { it.code == code } ?: ERROR
    }
}

data class GpuSnapshot(
    val status: GpuNativeStatus,
    val dispatchCount: Long,
    val workGroupCount: Long,
    val gpuWorkNanos: Long,
    val outputChecksum: Long,
)

data class GpuActivitySnapshot(
    val dispatchesPerSecond: Double,
    val workGroupsPerSecond: Double,
)

class GpuActivityMonitor {
    private var previousDispatchCount = 0L
    private var previousWorkGroupCount = 0L
    private var previousWallMs = 0L

    fun reset(snapshot: GpuSnapshot? = null) {
        previousDispatchCount = snapshot?.dispatchCount?.coerceAtLeast(0L) ?: 0L
        previousWorkGroupCount = snapshot?.workGroupCount?.coerceAtLeast(0L) ?: 0L
        previousWallMs = SystemClock.elapsedRealtime()
    }

    fun sample(snapshot: GpuSnapshot): GpuActivitySnapshot {
        val now = SystemClock.elapsedRealtime()
        if (previousWallMs == 0L ||
            snapshot.dispatchCount < previousDispatchCount ||
            snapshot.workGroupCount < previousWorkGroupCount
        ) {
            reset(snapshot)
            return GpuActivitySnapshot(0.0, 0.0)
        }
        val elapsedMs = (now - previousWallMs).coerceAtLeast(1L)
        val dispatchDelta = snapshot.dispatchCount - previousDispatchCount
        val workGroupDelta = snapshot.workGroupCount - previousWorkGroupCount
        previousDispatchCount = snapshot.dispatchCount
        previousWorkGroupCount = snapshot.workGroupCount
        previousWallMs = now
        return GpuActivitySnapshot(
            dispatchesPerSecond = dispatchDelta.toDouble() * 1000.0 / elapsedMs,
            workGroupsPerSecond = workGroupDelta.toDouble() * 1000.0 / elapsedMs,
        )
    }
}

object GpuMetricsFormatter {
    fun formatDispatchRate(rate: Double): String =
        String.format(Locale.US, "%.1f dispatch/s", rate.coerceAtLeast(0.0))

    fun formatWorkGroupRate(rate: Double): String {
        val safeRate = rate.coerceAtLeast(0.0)
        return when {
            safeRate >= 1_000_000.0 -> String.format(Locale.US, "%.2f Mgroups/s", safeRate / 1_000_000.0)
            safeRate >= 1_000.0 -> String.format(Locale.US, "%.1f Kgroups/s", safeRate / 1_000.0)
            else -> String.format(Locale.US, "%.0f groups/s", safeRate)
        }
    }

    fun formatGpuWorkTime(nanos: Long, supported: Boolean): String = when {
        !supported -> "Unsupported"
        nanos <= 0L -> "Waiting"
        else -> String.format(Locale.US, "%.2f ms", nanos / 1_000_000.0)
    }

    fun formatChecksum(checksum: Long): String = if (checksum == 0L) {
        "Waiting"
    } else {
        java.lang.Long.toUnsignedString(checksum, 16).uppercase(Locale.US).takeLast(12)
    }
}
