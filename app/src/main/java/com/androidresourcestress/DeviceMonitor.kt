package com.androidresourcestress

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.util.concurrent.TimeUnit

data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val primaryAbi: String,
    val logicalCoreCount: Int,
    val socManufacturer: String,
    val socModel: String,
    val boardPlatform: String,
    val hardware: String,
    val board: String,
    val buildFingerprint: String,
    val kernelVersion: String,
)

data class ThermalSnapshot(
    val batteryTemperatureCelsius: Double?,
    val status: Int,
) {
    val statusLabel: String
        get() = thermalStatusLabel(status)

    val requiresImmediateStop: Boolean
        get() = ThermalPolicy.shouldStop(status)

    val isSevereOrHigher: Boolean
        get() = status >= PowerManager.THERMAL_STATUS_SEVERE
}

class DeviceMonitor(private val context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    fun deviceInfo(): DeviceInfo {
        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            "Unknown"
        }.orEmpty().ifBlank { "Unknown" }
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            "Unknown"
        }.orEmpty().ifBlank { "Unknown" }
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" },
            brand = Build.BRAND.orEmpty().ifBlank { "Unknown" },
            model = Build.MODEL.orEmpty().ifBlank { "Unknown" },
            androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "Unknown" },
            sdk = Build.VERSION.SDK_INT,
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown",
            logicalCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            socManufacturer = socManufacturer,
            socModel = socModel,
            boardPlatform = systemProperty("ro.board.platform"),
            hardware = Build.HARDWARE.orEmpty().ifBlank { "Unknown" },
            board = Build.BOARD.orEmpty().ifBlank { "Unknown" },
            buildFingerprint = Build.FINGERPRINT.orEmpty().ifBlank { "Unknown" },
            kernelVersion = System.getProperty("os.version").orEmpty().ifBlank { "Unknown" },
        )
    }

    fun storageCapacity(): StorageCapacity {
        val stats = StatFs(context.filesDir.absolutePath)
        return StorageCapacity(
            totalBytes = stats.totalBytes.coerceAtLeast(0L),
            availableBytes = stats.availableBytes.coerceAtLeast(0L),
        )
    }

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

    private fun systemProperty(key: String): String = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            "Unknown"
        } else {
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        }
    }.getOrDefault("Unknown").ifBlank { "Unknown" }
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
