package com.androidresourcestress

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultExporter(private val activity: Activity) {
    private val exportDirectory = File(activity.cacheDir, EXPORT_DIRECTORY)

    fun exportSession(
        session: StressSessionSnapshot,
        deviceInfo: DeviceInfo,
        storageCapacity: StorageCapacity,
        gpuInfo: GpuInfo?,
    ): File {
        exportDirectory.mkdirs()
        val output = File(
            exportDirectory,
            "stress-session-${fileTimestamp(session.startWallTimeMs)}.json",
        )
        val enabledResources = JSONArray().apply {
            if (session.configuration.cpuEnabled) put("CPU")
            if (session.configuration.gpuEnabled) put("GPU")
            if (session.configuration.memoryEnabled) put("MEMORY")
            if (session.configuration.storageEnabled) put("STORAGE")
        }
        val root = JSONObject().apply {
            put("version", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("device", JSONObject().apply {
                put("manufacturer", deviceInfo.manufacturer)
                put("brand", deviceInfo.brand)
                put("model", deviceInfo.model)
                put("androidVersion", deviceInfo.androidVersion)
                put("sdk", deviceInfo.sdk)
                put("primaryAbi", deviceInfo.primaryAbi)
                put("logicalCpuCount", deviceInfo.logicalCoreCount)
                put("storageTotalBytes", storageCapacity.totalBytes)
                put("storageAvailableBytes", storageCapacity.availableBytes)
                put("gpuDevice", gpuInfo?.deviceName ?: "Unknown")
                put("vulkanVersion", gpuInfo?.apiVersionLabel ?: "Unknown")
            })
            put("session", JSONObject().apply {
                put("timestampMs", session.startWallTimeMs)
                put("durationMs", session.elapsedTimeMs)
                put("preset", session.configuration.preset.name)
                put("enabledResources", enabledResources)
                put("stopReason", session.stopReason?.name ?: "UNKNOWN")
                put("lastError", session.lastError ?: JSONObject.NULL)
            })
            put("cpu", JSONObject().apply {
                put("enabled", session.configuration.cpuEnabled)
                put("targetPercent", session.configuration.cpuTargetPercent)
                put("peakAppCpuLoadPercent", session.peakCpuLoadPercent)
                put("peakCoreEquivalentPercent", session.peakCoreEquivalentPercent)
            })
            put("gpu", JSONObject().apply {
                put("enabled", session.configuration.gpuEnabled)
                put("targetPercent", session.configuration.gpuTargetPercent)
                put("peakDispatchRatePerSecond", session.peakDispatchRate)
                put("averageWorkTimeNanos", session.averageGpuWorkTimeNanos)
            })
            put("memory", JSONObject().apply {
                put("enabled", session.configuration.memoryEnabled)
                put("target", session.configuration.memoryTarget.name)
                put("resolvedTargetBytes", session.resolvedMemoryTargetBytes)
                put("peakAllocatedBytes", session.allocatedMemoryBytes)
                put("peakAppPssBytes", session.peakAppPssBytes)
                put("peakNativePssBytes", session.peakNativePssBytes)
                put("peakActivityBytesPerSecond", session.peakMemoryActivityBytesPerSecond)
            })
            put("storage", JSONObject().apply {
                put("enabled", session.configuration.storageEnabled)
                put("mode", session.configuration.storageMode.name)
                put("level", session.configuration.storageLevel.name)
                put("workingSetBytes", session.storageWorkingSetBytes)
                put("bytesRead", session.storageBytesRead)
                put("bytesWritten", session.storageBytesWritten)
                put(
                    "peakReadActivityBytesPerSecond",
                    session.peakStorageReadActivityBytesPerSecond,
                )
                put(
                    "peakWriteActivityBytesPerSecond",
                    session.peakStorageWriteActivityBytesPerSecond,
                )
            })
            put("thermal", JSONObject().apply {
                put(
                    "startBatteryTemperatureCelsius",
                    session.startBatteryTemperatureCelsius ?: JSONObject.NULL,
                )
                put(
                    "peakBatteryTemperatureCelsius",
                    session.peakBatteryTemperatureCelsius ?: JSONObject.NULL,
                )
                put("highestStatus", thermalStatusLabel(session.highestThermalStatus))
            })
        }
        output.writeText(root.toString(2), Charsets.UTF_8)
        return output
    }

    fun share(file: File, mimeType: String, chooserTitle: String) {
        val uri = Uri.Builder()
            .scheme("content")
            .authority("${BuildConfig.APPLICATION_ID}.exports")
            .appendPath(file.name)
            .build()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    private fun fileTimestamp(timestampMs: Long): String = SimpleDateFormat(
        "yyyyMMdd-HHmmss",
        Locale.US,
    ).format(Date(timestampMs))

    companion object {
        const val EXPORT_DIRECTORY = "exports"
    }
}
