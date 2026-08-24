package com.androidresourcestress

import android.content.Context
import android.os.PowerManager

fun Context.durationLabel(duration: StressDuration): String = getString(
    when (duration) {
        StressDuration.SECONDS_30 -> R.string.duration_30_seconds
        StressDuration.MINUTE_1 -> R.string.duration_1_minute
        StressDuration.MINUTES_2 -> R.string.duration_2_minutes
        StressDuration.MINUTES_5 -> R.string.duration_5_minutes
        StressDuration.MINUTES_10 -> R.string.duration_10_minutes
        StressDuration.MINUTES_30 -> R.string.duration_30_minutes
        StressDuration.CONTINUOUS -> R.string.duration_continuous
    },
)

fun Context.thermalStatusDisplay(status: Int): String = getString(
    when (status) {
        PowerManager.THERMAL_STATUS_NONE -> R.string.thermal_none_label
        PowerManager.THERMAL_STATUS_LIGHT -> R.string.thermal_light_label
        PowerManager.THERMAL_STATUS_MODERATE -> R.string.thermal_moderate_label
        PowerManager.THERMAL_STATUS_SEVERE -> R.string.thermal_severe_label
        PowerManager.THERMAL_STATUS_CRITICAL -> R.string.thermal_critical_label
        PowerManager.THERMAL_STATUS_EMERGENCY -> R.string.thermal_emergency_label
        PowerManager.THERMAL_STATUS_SHUTDOWN -> R.string.thermal_shutdown_label
        else -> R.string.unknown
    },
)

fun Context.gpuModeLabel(mode: GpuMode): String = getString(
    when (mode) {
        GpuMode.COMPUTE -> R.string.gpu_mode_compute
        GpuMode.VISUAL -> R.string.gpu_mode_visual
        GpuMode.MIXED -> R.string.gpu_mode_mixed
    },
)

fun Context.presetLabel(preset: StressPreset): String = getString(
    when (preset) {
        StressPreset.BALANCED -> R.string.preset_balanced
        StressPreset.HIGH -> R.string.preset_high
        StressPreset.EXTREME -> R.string.preset_extreme
        StressPreset.CUSTOM -> R.string.preset_custom
    },
)

fun Context.combinedStateLabel(state: CombinedStressState): String = getString(
    when (state) {
        CombinedStressState.IDLE -> R.string.status_idle
        CombinedStressState.STARTING -> R.string.state_starting
        CombinedStressState.RUNNING -> R.string.state_running
        CombinedStressState.STOPPING -> R.string.state_stopping
        CombinedStressState.THERMAL_LIMITED -> R.string.state_thermal_limited
        CombinedStressState.ERROR -> R.string.state_error
    },
)

fun Context.stopReasonLabel(reason: StopReason?): String = if (reason == null) {
    getString(R.string.not_available)
} else {
    getString(
        when (reason) {
            StopReason.USER_STOP -> R.string.stop_user
            StopReason.DURATION_COMPLETED -> R.string.stop_duration
            StopReason.THERMAL_CRITICAL -> R.string.stop_thermal_critical
            StopReason.THERMAL_EMERGENCY -> R.string.stop_thermal_emergency
            StopReason.THERMAL_SHUTDOWN -> R.string.stop_thermal_shutdown
            StopReason.GPU_ERROR -> R.string.stop_gpu_error
            StopReason.MEMORY_ERROR -> R.string.stop_memory_error
            StopReason.STORAGE_ERROR -> R.string.stop_storage_error
            StopReason.SERVICE_ERROR -> R.string.stop_service_error
            StopReason.RESOURCE_ERROR -> R.string.stop_resource_error
        },
    )
}

fun Context.resourceStateLabel(state: String): String = getString(
    when (state) {
        "RUNNING" -> R.string.state_running
        "STARTING" -> R.string.state_starting
        "STOPPING" -> R.string.state_stopping
        "THERMAL_LIMITED" -> R.string.state_thermal_limited
        "ERROR" -> R.string.state_error
        else -> R.string.state_off
    },
)

fun Context.frequencyDisplay(hertz: Long?): String = hertz?.takeIf { it > 0L }
    ?.let(FrequencyFormatter::format) ?: getString(R.string.unsupported_value)

fun Context.voltageDisplay(volts: Double?): String = volts?.takeIf { it.isFinite() && it > 0.0 }
    ?.let(PowerFormatter::voltage) ?: getString(R.string.unsupported_value)

fun Context.currentDisplay(amps: Double?): String = amps?.takeIf { it.isFinite() }
    ?.let(PowerFormatter::current) ?: getString(R.string.unsupported_value)

fun Context.powerDisplay(watts: Double?): String = watts?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let(PowerFormatter::power) ?: getString(R.string.unsupported_value)

fun Context.chargingStateDisplay(state: String): String = getString(
    when (state) {
        "CHARGING" -> R.string.charging
        "FULL" -> R.string.full
        "DISCHARGING" -> R.string.discharging
        "NOT_CHARGING" -> R.string.not_charging
        else -> R.string.unknown
    },
)
