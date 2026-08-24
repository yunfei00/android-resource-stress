package com.androidresourcestress

import android.content.Context
import android.os.PowerManager
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object SessionResultFormatter {
    fun compact(context: Context, session: StressSessionSnapshot): String = buildString {
        append(DateFormat.getDateTimeInstance().format(Date(session.startWallTimeMs)))
        append(" · ").append(context.presetLabel(session.configuration.preset))
        append(" · ").append(DurationFormatter.format(session.elapsedTimeMs))
        append('\n')
        append(enabledResources(context, session.configuration))
        append(" · ").append(context.stopReasonLabel(session.stopReason))
        append('\n')
        append(
            context.getString(
                R.string.compact_peaks,
                session.peakCpuLoadPercent,
                session.peakDispatchRate,
                ByteFormatter.formatBytes(session.peakAppPssBytes),
            ),
        )
        if (session.configuration.storageEnabled) {
            append('\n').append(context.getString(R.string.label_storage)).append(' ')
            append(session.configuration.storageMode.name)
            append(" · ").append(
                context.getString(R.string.read_short, ByteFormatter.formatBytes(session.storageBytesRead)),
            )
            append(" · ").append(
                context.getString(
                    R.string.written_short,
                    ByteFormatter.formatBytes(session.storageBytesWritten),
                ),
            )
        } else {
            append('\n').append(context.getString(R.string.storage_not_enabled))
        }
        append('\n').append(
            context.getString(
                R.string.peak_battery_compact,
                temperature(session.peakBatteryTemperatureCelsius),
            ),
        )
        append(" · ").append(context.thermalStatusDisplay(session.highestThermalStatus))
    }

    fun detailed(context: Context, session: StressSessionSnapshot): String = buildString {
        append(compact(context, session)).append("\n\n")
        line(context.getString(R.string.label_session_id), session.sessionId.toString())
        line(context.getString(R.string.label_configured_duration), context.durationLabel(session.configuration.duration))
        line(
            context.getString(R.string.label_cpu_target_peak),
            "${session.configuration.cpuTargetPercent}% / ${percent(session.peakCpuLoadPercent)}",
        )
        line(context.getString(R.string.label_core_peak), percent(session.peakCoreEquivalentPercent))
        line(
            context.getString(R.string.label_gpu_target_dispatch),
            "${session.configuration.gpuTargetPercent}% / " +
                String.format(Locale.US, "%.1f dispatch/s", session.peakDispatchRate),
        )
        line(
            context.getString(R.string.label_gpu_average_work),
            String.format(Locale.US, "%.3f ms", session.averageGpuWorkTimeNanos / 1_000_000.0),
        )
        if (session.configuration.gpuMode != GpuMode.COMPUTE) {
            line(
                context.getString(R.string.label_visual_peak_fps),
                String.format(Locale.US, "%.1f FPS", session.peakVisualFps),
            )
            line(
                context.getString(R.string.label_visual_average_frame),
                String.format(Locale.US, "%.2f ms", session.averageVisualFrameTimeNanos / 1_000_000.0),
            )
        }
        line(
            context.getString(R.string.label_memory_target_allocated),
            "${ByteFormatter.formatBytes(session.resolvedMemoryTargetBytes)} / " +
                ByteFormatter.formatBytes(session.allocatedMemoryBytes),
        )
        line(
            context.getString(R.string.label_peak_pss),
            "${ByteFormatter.formatBytes(session.peakAppPssBytes)} / " +
                ByteFormatter.formatBytes(session.peakNativePssBytes),
        )
        line(
            context.getString(R.string.label_peak_memory_activity),
            ByteFormatter.formatRate(session.peakMemoryActivityBytesPerSecond),
        )
        if (session.configuration.storageEnabled) {
            line(
                context.getString(R.string.label_storage),
                "${session.configuration.storageMode.name} / ${session.configuration.storageLevel.displayLabel}",
            )
            line(context.getString(R.string.label_working_set), ByteFormatter.formatBytes(session.storageWorkingSetBytes))
            line(
                context.getString(R.string.label_read_written),
                "${ByteFormatter.formatBytes(session.storageBytesRead)} / ${ByteFormatter.formatBytes(session.storageBytesWritten)}",
            )
            line(
                context.getString(R.string.label_peak_storage_activity),
                "${ByteFormatter.formatRate(session.peakStorageReadActivityBytesPerSecond)} / " +
                    ByteFormatter.formatRate(session.peakStorageWriteActivityBytesPerSecond),
            )
        } else {
            append(context.getString(R.string.storage_not_enabled)).append('\n')
        }
        line(
            context.getString(R.string.label_battery_start_peak),
            "${temperature(session.startBatteryTemperatureCelsius)} / ${temperature(session.peakBatteryTemperatureCelsius)}",
        )
        line(context.getString(R.string.label_battery_delta_result), temperatureDelta(session))
        line(context.getString(R.string.label_highest_thermal), context.thermalStatusDisplay(session.highestThermalStatus))
        line(context.getString(R.string.label_peak_estimated_power), context.powerDisplay(session.peakEstimatedBatteryPowerWatts))
        append('\n').append(context.getString(R.string.thermal_timeline_title)).append('\n')
        session.thermalTimeline.forEach { event ->
            append(
                context.getString(
                    R.string.thermal_timeline_event,
                    DurationFormatter.format(event.elapsedTimeMs),
                    thermalStatusLabel(event.status),
                    temperature(event.batteryTemperatureCelsius),
                ),
            ).append('\n')
        }
        line(context.getString(R.string.time_to_moderate), timeToStatus(context, session, PowerManager.THERMAL_STATUS_MODERATE))
        line(context.getString(R.string.time_to_severe), timeToStatus(context, session, PowerManager.THERMAL_STATUS_SEVERE))
        if (session.cpuFrequencyObservations.isNotEmpty()) {
            append('\n').append(context.getString(R.string.cpu_frequency_title)).append('\n')
            session.cpuFrequencyObservations.forEach { observation ->
                append(
                    context.getString(
                        R.string.cpu_frequency_observation,
                        observation.policy,
                        context.frequencyDisplay(observation.startHz),
                        context.frequencyDisplay(observation.minimumObservedHz),
                        context.frequencyDisplay(observation.peakObservedHz),
                        context.frequencyDisplay(observation.endHz),
                    ),
                ).append('\n')
            }
        }
        line(context.getString(R.string.label_error), session.lastError ?: context.getString(R.string.none))
    }

    fun enabledResources(context: Context, configuration: CombinedStressConfiguration): String =
        buildList {
            if (configuration.cpuEnabled) add("CPU")
            if (configuration.gpuEnabled) add("GPU ${context.gpuModeLabel(configuration.gpuMode)}")
            if (configuration.memoryEnabled) add(context.getString(R.string.resource_memory))
            if (configuration.storageEnabled) add(context.getString(R.string.resource_storage))
        }.joinToString(" + ").ifBlank { context.getString(R.string.none) }

    private fun StringBuilder.line(label: String, value: String) {
        append(label).append(": ").append(value).append('\n')
    }

    private fun temperature(value: Double?): String = TemperatureFormatter.format(value)

    private fun temperatureDelta(session: StressSessionSnapshot): String {
        val start = session.startBatteryTemperatureCelsius ?: return "N/A"
        val peak = session.peakBatteryTemperatureCelsius ?: return "N/A"
        return String.format(Locale.US, "%+.1f °C", peak - start)
    }

    private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    private fun timeToStatus(context: Context, session: StressSessionSnapshot, status: Int): String =
        ThermalTimelineAnalysis.timeToStatus(session.thermalTimeline, status)?.let {
            DurationFormatter.format(it)
        } ?: context.getString(R.string.not_reached)
}
