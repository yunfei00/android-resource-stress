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

enum class GpuMode {
    COMPUTE,
    VISUAL,
    MIXED,
}

enum class ScreenMode {
    ON,
    OFF,
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
    USER_STOP,
    DURATION_COMPLETED,
    THERMAL_CRITICAL,
    THERMAL_EMERGENCY,
    THERMAL_SHUTDOWN,
    GPU_ERROR,
    MEMORY_ERROR,
    STORAGE_ERROR,
    SERVICE_ERROR,
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
    val gpuMode: GpuMode = GpuMode.COMPUTE,
    val duration: StressDuration,
    val screenMode: ScreenMode = ScreenMode.ON,
)

enum class SessionEventType {
    SESSION_START,
    SCREEN_OFF,
    SCREEN_ON,
    VISUAL_PAUSED,
    VISUAL_RESUMED,
    COMPUTE_FALLBACK_STARTED,
    COMPUTE_FALLBACK_STOPPED,
    THERMAL_CHANGE,
    SCREEN_WAKE_REQUEST,
    SCREEN_WAKE_RESULT,
    SESSION_STOP,
}

data class SessionEvent(
    val elapsedTimeMs: Long,
    val type: SessionEventType,
    val detail: String? = null,
)

data class ThermalEvent(
    val elapsedTimeMs: Long,
    val status: Int,
    val batteryTemperatureCelsius: Double?,
)

data class CpuFrequencySessionObservation(
    val policy: String,
    val startHz: Long?,
    val minimumObservedHz: Long?,
    val peakObservedHz: Long?,
    val endHz: Long?,
)

data class PowerObservation(
    val elapsedTimeMs: Long,
    val batteryLevelPercent: Int?,
    val chargingState: String,
    val voltageVolts: Double?,
    val currentAmpsRaw: Double?,
    val estimatedBatteryPowerWatts: Double?,
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
    val thermalTimeline: List<ThermalEvent> = emptyList(),
    val cpuFrequencyObservations: List<CpuFrequencySessionObservation> = emptyList(),
    val startPowerObservation: PowerObservation? = null,
    val endPowerObservation: PowerObservation? = null,
    val peakEstimatedBatteryPowerWatts: Double? = null,
    val peakVisualFps: Double = 0.0,
    val averageVisualFrameTimeNanos: Double = 0.0,
    val screenMode: ScreenMode = configuration.screenMode,
    val screenOffAtElapsedMs: Long? = null,
    val screenOnAtElapsedMs: Long? = null,
    val screenOffDurationMs: Long = 0L,
    val screenTransitionCount: Int = 0,
    val screenFallbackUsed: Boolean = false,
    val wakeAttempted: Boolean = false,
    val wakeSucceeded: Boolean = false,
    val wakeReason: String? = null,
    val eventTimeline: List<SessionEvent> = emptyList(),
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
    val hardware: HardwareSnapshot = HardwareSnapshot.empty(),
    val visualFps: Double = 0.0,
    val visualFrameTimeNanos: Double = 0.0,
    val visualVulkanFrameCount: Long = 0L,
    val visualVulkanFrameWorkNanos: Long = 0L,
)

object ThermalPolicy {
    fun shouldStop(status: Int): Boolean =
        status >= android.os.PowerManager.THERMAL_STATUS_CRITICAL

    fun isWarning(status: Int): Boolean =
        status >= android.os.PowerManager.THERMAL_STATUS_MODERATE

    fun isSevere(status: Int): Boolean =
        status >= android.os.PowerManager.THERMAL_STATUS_SEVERE
}

object ThermalTimelineAnalysis {
    fun timeToStatus(events: List<ThermalEvent>, targetStatus: Int): Long? =
        events.firstOrNull { it.status >= targetStatus }?.elapsedTimeMs

    fun appendIfChanged(events: List<ThermalEvent>, event: ThermalEvent): List<ThermalEvent> =
        if (events.lastOrNull()?.status == event.status) events else events + event
}

object HistoryPolicy {
    fun <T> trimNewest(items: List<T>, limit: Int): List<T> =
        items.take(limit.coerceIn(1, AppPreferences.MAX_HISTORY_LIMIT))
}

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
            screenMode = ScreenMode.ON,
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
            screenMode = ScreenMode.ON,
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
            screenMode = ScreenMode.ON,
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
