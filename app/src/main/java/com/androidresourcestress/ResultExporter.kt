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
            put("app", JSONObject().apply {
                put("name", "Android Resource Stress")
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("gitCommit", BuildConfig.GIT_COMMIT)
                put("gitTag", BuildConfig.GIT_TAG)
            })
            put("device", JSONObject().apply {
                put("manufacturer", deviceInfo.manufacturer)
                put("brand", deviceInfo.brand)
                put("model", deviceInfo.model)
                put("androidVersion", deviceInfo.androidVersion)
                put("sdk", deviceInfo.sdk)
                put("primaryAbi", deviceInfo.primaryAbi)
                put("logicalCpuCount", deviceInfo.logicalCoreCount)
                put("socManufacturer", deviceInfo.socManufacturer)
                put("socModel", deviceInfo.socModel)
                put("boardPlatform", deviceInfo.boardPlatform)
                put("hardware", deviceInfo.hardware)
                put("board", deviceInfo.board)
                put("buildFingerprint", deviceInfo.buildFingerprint)
                put("kernelVersion", deviceInfo.kernelVersion)
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
                put("mode", session.configuration.gpuMode.name)
                put("peakDispatchRatePerSecond", session.peakDispatchRate)
                put("averageWorkTimeNanos", session.averageGpuWorkTimeNanos)
                put("peakVisualFps", session.peakVisualFps)
                put("averageVisualFrameTimeNanos", session.averageVisualFrameTimeNanos)
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
                put("timeline", JSONArray().apply {
                    session.thermalTimeline.forEach { event ->
                        put(JSONObject().apply {
                            put("elapsedTimeMs", event.elapsedTimeMs)
                            put("status", thermalStatusLabel(event.status))
                            put(
                                "batteryTemperatureCelsius",
                                event.batteryTemperatureCelsius ?: JSONObject.NULL,
                            )
                        })
                    }
                })
            })
            put("cpuFrequencies", JSONArray().apply {
                session.cpuFrequencyObservations.forEach { observation ->
                    put(JSONObject().apply {
                        put("policy", observation.policy)
                        put("startHz", observation.startHz ?: JSONObject.NULL)
                        put("minimumObservedHz", observation.minimumObservedHz ?: JSONObject.NULL)
                        put("peakObservedHz", observation.peakObservedHz ?: JSONObject.NULL)
                        put("endHz", observation.endHz ?: JSONObject.NULL)
                    })
                }
            })
            put("power", JSONObject().apply {
                put("start", session.startPowerObservation?.let(::powerJson) ?: JSONObject.NULL)
                put("end", session.endPowerObservation?.let(::powerJson) ?: JSONObject.NULL)
                put(
                    "peakEstimatedBatteryPowerWatts",
                    session.peakEstimatedBatteryPowerWatts ?: JSONObject.NULL,
                )
                put("currentSignIsRaw", true)
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

    private fun powerJson(observation: PowerObservation): JSONObject = JSONObject().apply {
        put("elapsedTimeMs", observation.elapsedTimeMs)
        put("batteryLevelPercent", observation.batteryLevelPercent ?: JSONObject.NULL)
        put("chargingState", observation.chargingState)
        put("voltageVolts", observation.voltageVolts ?: JSONObject.NULL)
        put("currentAmpsRaw", observation.currentAmpsRaw ?: JSONObject.NULL)
        put(
            "estimatedBatteryPowerWatts",
            observation.estimatedBatteryPowerWatts ?: JSONObject.NULL,
        )
    }

    companion object {
        const val EXPORT_DIRECTORY = "exports"
    }
}
