package com.androidresourcestress

enum class CombinedStressState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    THERMAL_LIMITED,
    ERROR,
}

enum class StressPreset {
    BALANCED,
    HIGH,
    EXTREME,
    CUSTOM,
}

enum class MemoryTarget(val fixedBytes: Long?) {
    MIB_256(256L * 1024L * 1024L),
    MIB_512(512L * 1024L * 1024L),
    GIB_1(1024L * 1024L * 1024L),
    AUTO(null),
}

enum class StressDuration(val durationMs: Long, val displayLabel: String) {
    SECONDS_30(30_000L, "30 seconds"),
    MINUTE_1(60_000L, "1 minute"),
    MINUTES_2(120_000L, "2 minutes"),
    MINUTES_5(300_000L, "5 minutes"),
    MINUTES_10(600_000L, "10 minutes"),
    MINUTES_30(1_800_000L, "30 minutes"),
    CONTINUOUS(0L, "Continuous"),
}

enum class StopReason {
    USER,
    DURATION_COMPLETED,
    THERMAL,
    ACTIVITY_STOPPED,
    RESOURCE_ERROR,
}

data class CombinedStressConfiguration(
    val preset: StressPreset,
    val cpuEnabled: Boolean,
    val gpuEnabled: Boolean,
    val memoryEnabled: Boolean,
    val storageEnabled: Boolean = false,
    val cpuTargetPercent: Int,
    val gpuTargetPercent: Int,
    val memoryTarget: MemoryTarget,
    val storageMode: StorageMode = StorageMode.MIXED,
    val storageLevel: StorageLevel = StorageLevel.LOW,
    val duration: StressDuration,
)

data class StressSessionSnapshot(
    val sessionId: Long,
    val startWallTimeMs: Long,
    val elapsedTimeMs: Long,
    val configuration: CombinedStressConfiguration,
    val resolvedMemoryTargetBytes: Long,
    val allocatedMemoryBytes: Long,
    val peakCpuLoadPercent: Double,
    val peakCoreEquivalentPercent: Double,
    val peakAppPssBytes: Long,
    val peakNativePssBytes: Long,
    val peakMemoryActivityBytesPerSecond: Double,
    val peakDispatchRate: Double,
    val averageGpuWorkTimeNanos: Double,
    val storageWorkingSetBytes: Long,
    val storageBytesRead: Long,
    val storageBytesWritten: Long,
    val peakStorageReadActivityBytesPerSecond: Double,
    val peakStorageWriteActivityBytesPerSecond: Double,
    val startBatteryTemperatureCelsius: Double?,
    val peakBatteryTemperatureCelsius: Double?,
    val highestThermalStatus: Int,
    val stopReason: StopReason?,
    val lastError: String?,
)

data class CombinedRuntimeSnapshot(
    val state: CombinedStressState,
    val elapsedTimeMs: Long,
    val currentSession: StressSessionSnapshot?,
    val lastSession: StressSessionSnapshot?,
    val cpuLoadPercent: Double,
    val coreEquivalentPercent: Double,
    val cpuThreadCount: Int,
    val memory: MemorySnapshot,
    val allocatedMemoryBytes: Long,
    val memoryActivityBytesPerSecond: Double,
    val gpu: GpuSnapshot,
    val gpuDispatchRate: Double,
    val gpuWorkGroupsPerSecond: Double,
    val storage: StorageRuntimeSnapshot,
    val thermal: ThermalSnapshot,
    val lastError: String?,
)

object PresetConfigurations {
    fun create(
        preset: StressPreset,
        duration: StressDuration = StressDuration.MINUTES_5,
    ): CombinedStressConfiguration = when (preset) {
        StressPreset.BALANCED -> CombinedStressConfiguration(
            preset = preset,
            cpuEnabled = true,
            gpuEnabled = true,
            memoryEnabled = true,
            storageEnabled = false,
            cpuTargetPercent = 50,
            gpuTargetPercent = 50,
            memoryTarget = MemoryTarget.MIB_512,
            duration = duration,
        )
        StressPreset.HIGH -> CombinedStressConfiguration(
            preset = preset,
            cpuEnabled = true,
            gpuEnabled = true,
            memoryEnabled = true,
            storageEnabled = false,
            cpuTargetPercent = 75,
            gpuTargetPercent = 75,
            memoryTarget = MemoryTarget.AUTO,
            duration = duration,
        )
        StressPreset.EXTREME -> CombinedStressConfiguration(
            preset = preset,
            cpuEnabled = true,
            gpuEnabled = true,
            memoryEnabled = true,
            storageEnabled = false,
            cpuTargetPercent = 100,
            gpuTargetPercent = 100,
            memoryTarget = MemoryTarget.AUTO,
            duration = duration,
        )
        StressPreset.CUSTOM -> create(StressPreset.EXTREME, duration).copy(
            preset = StressPreset.CUSTOM,
        )
    }
}

object DurationFormatter {
    fun format(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return String.format(
            java.util.Locale.US,
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds,
        )
    }
}
