package com.androidresourcestress

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object SessionResultFormatter {
    fun compact(session: StressSessionSnapshot): String = buildString {
        append(DateFormat.getDateTimeInstance().format(Date(session.startWallTimeMs)))
        append(" · ").append(session.configuration.preset.name)
        append(" · ").append(DurationFormatter.format(session.elapsedTimeMs))
        append('\n')
        append(enabledResources(session.configuration))
        append(" · ").append(session.stopReason?.name ?: "UNKNOWN")
        append('\n')
        append(String.format(Locale.US, "CPU %.1f%% · GPU %.1f dispatch/s", session.peakCpuLoadPercent, session.peakDispatchRate))
        append(" · PSS ").append(ByteFormatter.formatBytes(session.peakAppPssBytes))
        if (session.configuration.storageEnabled) {
            append('\n').append("Storage ").append(session.configuration.storageMode.name)
            append(" · R ").append(ByteFormatter.formatBytes(session.storageBytesRead))
            append(" · W ").append(ByteFormatter.formatBytes(session.storageBytesWritten))
        } else {
            append('\n').append("Storage: Not enabled")
        }
        append('\n').append("Peak battery ").append(temperature(session.peakBatteryTemperatureCelsius))
        append(" · ").append(thermalStatusLabel(session.highestThermalStatus))
    }

    fun detailed(session: StressSessionSnapshot): String = buildString {
        append(compact(session)).append("\n\n")
        append("Session ID: ").append(session.sessionId).append('\n')
        append("Configured duration: ").append(session.configuration.duration.displayLabel).append('\n')
        append("CPU target / peak: ").append(session.configuration.cpuTargetPercent).append("% / ")
        append(String.format(Locale.US, "%.1f%%", session.peakCpuLoadPercent)).append('\n')
        append("Core equivalent peak: ").append(String.format(Locale.US, "%.1f%%", session.peakCoreEquivalentPercent)).append('\n')
        append("GPU target / peak dispatch: ").append(session.configuration.gpuTargetPercent).append("% / ")
        append(String.format(Locale.US, "%.1f dispatch/s", session.peakDispatchRate)).append('\n')
        append("GPU average work time: ").append(String.format(Locale.US, "%.3f ms", session.averageGpuWorkTimeNanos / 1_000_000.0)).append('\n')
        append("Memory target / allocated: ").append(ByteFormatter.formatBytes(session.resolvedMemoryTargetBytes))
        append(" / ").append(ByteFormatter.formatBytes(session.allocatedMemoryBytes)).append('\n')
        append("Peak app / native PSS: ").append(ByteFormatter.formatBytes(session.peakAppPssBytes))
        append(" / ").append(ByteFormatter.formatBytes(session.peakNativePssBytes)).append('\n')
        append("Peak memory activity: ").append(ByteFormatter.formatRate(session.peakMemoryActivityBytesPerSecond)).append('\n')
        append("Storage: ")
        if (session.configuration.storageEnabled) {
            append(session.configuration.storageMode.name).append(" / ").append(session.configuration.storageLevel.displayLabel).append('\n')
            append("Working set: ").append(ByteFormatter.formatBytes(session.storageWorkingSetBytes)).append('\n')
            append("Read / written: ").append(ByteFormatter.formatBytes(session.storageBytesRead)).append(" / ")
            append(ByteFormatter.formatBytes(session.storageBytesWritten)).append('\n')
            append("Peak read / write activity: ").append(ByteFormatter.formatRate(session.peakStorageReadActivityBytesPerSecond))
            append(" / ").append(ByteFormatter.formatRate(session.peakStorageWriteActivityBytesPerSecond)).append('\n')
        } else {
            append("Not enabled\n")
        }
        append("Battery temperature start / peak: ").append(temperature(session.startBatteryTemperatureCelsius))
        append(" / ").append(temperature(session.peakBatteryTemperatureCelsius)).append('\n')
        append("Battery temperature delta: ").append(temperatureDelta(session)).append('\n')
        append("Highest thermal status: ").append(thermalStatusLabel(session.highestThermalStatus)).append('\n')
        append("Error: ").append(session.lastError ?: "None")
    }

    fun enabledResources(configuration: CombinedStressConfiguration): String = buildList {
        if (configuration.cpuEnabled) add("CPU")
        if (configuration.gpuEnabled) add("GPU")
        if (configuration.memoryEnabled) add("MEMORY")
        if (configuration.storageEnabled) add("STORAGE")
    }.joinToString(" + ").ifBlank { "None" }

    private fun temperature(value: Double?): String = value?.let {
        String.format(Locale.US, "%.1f °C", it)
    } ?: "N/A"

    private fun temperatureDelta(session: StressSessionSnapshot): String {
        val start = session.startBatteryTemperatureCelsius ?: return "N/A"
        val peak = session.peakBatteryTemperatureCelsius ?: return "N/A"
        return String.format(Locale.US, "%+.1f °C", peak - start)
    }
}
