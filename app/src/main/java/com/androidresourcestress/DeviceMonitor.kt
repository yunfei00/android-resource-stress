package com.androidresourcestress

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val primaryAbi: String,
    val logicalCoreCount: Int,
)

data class ThermalSnapshot(
    val batteryTemperatureCelsius: Double?,
    val status: Int,
) {
    val statusLabel: String
        get() = thermalStatusLabel(status)

    val isSevereOrHigher: Boolean
        get() = status >= PowerManager.THERMAL_STATUS_SEVERE
}

class DeviceMonitor(private val context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    fun deviceInfo(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" },
        model = Build.MODEL.orEmpty().ifBlank { "Unknown" },
        androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "Unknown" },
        sdk = Build.VERSION.SDK_INT,
        primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown",
        logicalCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    )

    fun thermalSnapshot(): ThermalSnapshot {
        val battery = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val rawTemperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperature = rawTemperature
            ?.takeUnless { it == Int.MIN_VALUE || it == 0 }
            ?.div(10.0)
        return ThermalSnapshot(
            batteryTemperatureCelsius = temperature,
            status = powerManager.currentThermalStatus,
        )
    }
}

fun thermalStatusLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "NONE"
    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
    else -> "UNKNOWN"
}
