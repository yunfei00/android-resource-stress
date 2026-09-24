package com.androidresourcestress

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SessionJsonCodec {
    const val SCHEMA_VERSION = 4

    fun toJson(session: StressSessionSnapshot): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("sessionId", session.sessionId)
        put("startWallTimeMs", session.startWallTimeMs)
        put("elapsedTimeMs", session.elapsedTimeMs)
        put("configuration", configurationToJson(session.configuration))
        put("resolvedMemoryTargetBytes", session.resolvedMemoryTargetBytes)
        put("allocatedMemoryBytes", session.allocatedMemoryBytes)
        put("peakCpuLoadPercent", session.peakCpuLoadPercent)
        put("peakCoreEquivalentPercent", session.peakCoreEquivalentPercent)
        put("peakAppPssBytes", session.peakAppPssBytes)
        put("peakNativePssBytes", session.peakNativePssBytes)
        put("peakMemoryActivityBytesPerSecond", session.peakMemoryActivityBytesPerSecond)
        put("peakDispatchRate", session.peakDispatchRate)
        put("averageGpuWorkTimeNanos", session.averageGpuWorkTimeNanos)
        put("storageWorkingSetBytes", session.storageWorkingSetBytes)
        put("storageBytesRead", session.storageBytesRead)
        put("storageBytesWritten", session.storageBytesWritten)
        put(
            "peakStorageReadActivityBytesPerSecond",
            session.peakStorageReadActivityBytesPerSecond,
        )
        put(
            "peakStorageWriteActivityBytesPerSecond",
            session.peakStorageWriteActivityBytesPerSecond,
        )
        putNullable("startBatteryTemperatureCelsius", session.startBatteryTemperatureCelsius)
        putNullable("peakBatteryTemperatureCelsius", session.peakBatteryTemperatureCelsius)
        put("highestThermalStatus", session.highestThermalStatus)
        put("thermalTimeline", JSONArray().apply {
            session.thermalTimeline.forEach { event ->
                put(JSONObject().apply {
                    put("elapsedTimeMs", event.elapsedTimeMs)
                    put("status", event.status)
                    putNullable("batteryTemperatureCelsius", event.batteryTemperatureCelsius)
                })
            }
        })
        put("cpuFrequencyObservations", JSONArray().apply {
            session.cpuFrequencyObservations.forEach { observation ->
                put(JSONObject().apply {
                    put("policy", observation.policy)
                    putNullable("startHz", observation.startHz)
                    putNullable("minimumObservedHz", observation.minimumObservedHz)
                    putNullable("peakObservedHz", observation.peakObservedHz)
                    putNullable("endHz", observation.endHz)
                })
            }
        })
        putNullable("startPowerObservation", session.startPowerObservation?.let(::powerToJson))
        putNullable("endPowerObservation", session.endPowerObservation?.let(::powerToJson))
        putNullable("peakEstimatedBatteryPowerWatts", session.peakEstimatedBatteryPowerWatts)
        put("peakVisualFps", session.peakVisualFps)
        put("minimumVisualFps", session.minimumVisualFps)
        put("averageVisualFrameTimeNanos", session.averageVisualFrameTimeNanos)
        put("maximumVisualFrameTimeNanos", session.maximumVisualFrameTimeNanos)
        put("screenMode", session.screenMode.name)
        putNullable("screenOffAtElapsedMs", session.screenOffAtElapsedMs)
        putNullable("screenOnAtElapsedMs", session.screenOnAtElapsedMs)
        put("screenOffDurationMs", session.screenOffDurationMs)
        put("screenTransitionCount", session.screenTransitionCount)
        put("screenFallbackUsed", session.screenFallbackUsed)
        put("wakeAttempted", session.wakeAttempted)
        put("wakeSucceeded", session.wakeSucceeded)
        putNullable("wakeReason", session.wakeReason)
        put("eventTimeline", JSONArray().apply {
            session.eventTimeline.forEach { event ->
                put(JSONObject().apply {
                    put("elapsedTimeMs", event.elapsedTimeMs)
                    put("type", event.type.name)
                    putNullable("detail", event.detail)
                })
            }
        })
        putNullable("stopReason", session.stopReason?.name)
        putNullable("lastError", session.lastError)
    }

    fun fromJson(json: JSONObject): StressSessionSnapshot {
        val configuration = configurationFromJson(json.optJSONObject("configuration"))
        return StressSessionSnapshot(
            sessionId = json.optLong("sessionId", json.optLong("startWallTimeMs", 0L)),
            startWallTimeMs = json.optLong("startWallTimeMs", 0L),
            elapsedTimeMs = json.optLong("elapsedTimeMs", 0L),
            configuration = configuration,
            resolvedMemoryTargetBytes = json.optLong("resolvedMemoryTargetBytes", 0L),
            allocatedMemoryBytes = json.optLong("allocatedMemoryBytes", 0L),
            peakCpuLoadPercent = json.optDouble("peakCpuLoadPercent", 0.0),
            peakCoreEquivalentPercent = json.optDouble("peakCoreEquivalentPercent", 0.0),
            peakAppPssBytes = json.optLong("peakAppPssBytes", 0L),
            peakNativePssBytes = json.optLong("peakNativePssBytes", 0L),
            peakMemoryActivityBytesPerSecond = json.optDouble(
                "peakMemoryActivityBytesPerSecond",
                0.0,
            ),
            peakDispatchRate = json.optDouble("peakDispatchRate", 0.0),
            averageGpuWorkTimeNanos = json.optDouble("averageGpuWorkTimeNanos", 0.0),
            storageWorkingSetBytes = json.optLong("storageWorkingSetBytes", 0L),
            storageBytesRead = json.optLong("storageBytesRead", 0L),
            storageBytesWritten = json.optLong("storageBytesWritten", 0L),
            peakStorageReadActivityBytesPerSecond = json.optDouble(
                "peakStorageReadActivityBytesPerSecond",
                0.0,
            ),
            peakStorageWriteActivityBytesPerSecond = json.optDouble(
                "peakStorageWriteActivityBytesPerSecond",
                0.0,
            ),
            startBatteryTemperatureCelsius = json.optionalDouble(
                "startBatteryTemperatureCelsius",
            ),
            peakBatteryTemperatureCelsius = json.optionalDouble(
                "peakBatteryTemperatureCelsius",
            ),
            highestThermalStatus = json.optInt("highestThermalStatus", 0),
            stopReason = json.optionalString("stopReason")?.let(::parseStopReason),
            lastError = json.optionalString("lastError"),
            thermalTimeline = json.optJSONArray("thermalTimeline").toThermalEvents(),
            cpuFrequencyObservations =
                json.optJSONArray("cpuFrequencyObservations").toCpuFrequencyObservations(),
            startPowerObservation = json.optJSONObject("startPowerObservation")?.toPowerObservation(),
            endPowerObservation = json.optJSONObject("endPowerObservation")?.toPowerObservation(),
            peakEstimatedBatteryPowerWatts =
                json.optionalDouble("peakEstimatedBatteryPowerWatts"),
            peakVisualFps = json.optDouble("peakVisualFps", 0.0),
            minimumVisualFps = json.optDouble("minimumVisualFps", 0.0),
            averageVisualFrameTimeNanos = json.optDouble("averageVisualFrameTimeNanos", 0.0),
            maximumVisualFrameTimeNanos = json.optDouble(
                "maximumVisualFrameTimeNanos",
                0.0,
            ),
            screenMode = json.enumValue("screenMode", configuration.screenMode, ScreenMode::valueOf),
            screenOffAtElapsedMs = json.optionalLong("screenOffAtElapsedMs"),
            screenOnAtElapsedMs = json.optionalLong("screenOnAtElapsedMs"),
            screenOffDurationMs = json.optLong("screenOffDurationMs", 0L),
            screenTransitionCount = json.optInt("screenTransitionCount", 0),
            screenFallbackUsed = json.optBoolean("screenFallbackUsed", false),
            wakeAttempted = json.optBoolean("wakeAttempted", false),
            wakeSucceeded = json.optBoolean("wakeSucceeded", false),
            wakeReason = json.optionalString("wakeReason"),
            eventTimeline = json.optJSONArray("eventTimeline").toSessionEvents(),
        )
    }

    fun configurationToJson(configuration: CombinedStressConfiguration): JSONObject =
        JSONObject().apply {
            put("preset", configuration.preset.name)
            put("cpuEnabled", configuration.cpuEnabled)
            put("gpuEnabled", configuration.gpuEnabled)
            put("memoryEnabled", configuration.memoryEnabled)
            put("storageEnabled", configuration.storageEnabled)
            put("cpuTargetPercent", configuration.cpuTargetPercent)
            put("gpuTargetPercent", configuration.gpuTargetPercent)
            put("memoryTarget", configuration.memoryTarget.name)
            put("storageMode", configuration.storageMode.name)
            put("storageLevel", configuration.storageLevel.name)
            put("gpuMode", configuration.gpuMode.name)
            put("duration", configuration.duration.name)
            put("screenMode", configuration.screenMode.name)
            put("gpu3dLevel", configuration.gpu3dLevel.name)
            put("gpu3dRunMode", configuration.gpu3dRunMode.name)
            put("gpu3dStepDurationSeconds", configuration.gpu3dStepDurationSeconds)
        }

    fun configurationFromJson(json: JSONObject?): CombinedStressConfiguration {
        if (json == null) return PresetConfigurations.create(StressPreset.EXTREME)
        val preset = json.enumValue("preset", StressPreset.EXTREME, StressPreset::valueOf)
        val fallback = PresetConfigurations.create(preset)
        return CombinedStressConfiguration(
            preset = preset,
            cpuEnabled = json.optBoolean("cpuEnabled", fallback.cpuEnabled),
            gpuEnabled = json.optBoolean("gpuEnabled", fallback.gpuEnabled),
            memoryEnabled = json.optBoolean("memoryEnabled", fallback.memoryEnabled),
            storageEnabled = json.optBoolean("storageEnabled", false),
            cpuTargetPercent = json.optInt("cpuTargetPercent", fallback.cpuTargetPercent),
            gpuTargetPercent = json.optInt("gpuTargetPercent", fallback.gpuTargetPercent),
            memoryTarget = json.enumValue(
                "memoryTarget",
                fallback.memoryTarget,
                MemoryTarget::valueOf,
            ),
            storageMode = json.enumValue(
                "storageMode",
                StorageMode.MIXED,
                StorageMode::valueOf,
            ),
            storageLevel = json.enumValue(
                "storageLevel",
                StorageLevel.LOW,
                StorageLevel::valueOf,
            ),
            gpuMode = json.enumValue(
                "gpuMode",
                GpuMode.COMPUTE,
                GpuMode::valueOf,
            ),
            duration = json.enumValue(
                "duration",
                StressDuration.MINUTES_5,
                StressDuration::valueOf,
            ),
            screenMode = json.enumValue("screenMode", ScreenMode.ON, ScreenMode::valueOf),
            gpu3dLevel = json.enumValue(
                "gpu3dLevel",
                Gpu3dStressLevel.MEDIUM,
                Gpu3dStressLevel::valueOf,
            ),
            gpu3dRunMode = json.enumValue(
                "gpu3dRunMode",
                Gpu3dRunMode.FIXED,
                Gpu3dRunMode::valueOf,
            ),
            gpu3dStepDurationSeconds = json.optInt("gpu3dStepDurationSeconds", 20)
                .coerceIn(Gpu3dAutoDuration.MIN_SECONDS, Gpu3dAutoDuration.MAX_SECONDS),
        )
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optionalDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun <T> JSONObject.optionalEnum(key: String, parser: (String) -> T): T? =
        optionalString(key)?.let { runCatching { parser(it) }.getOrNull() }

    private fun <T> JSONObject.enumValue(
        key: String,
        fallback: T,
        parser: (String) -> T,
    ): T = runCatching { parser(optString(key, fallback.toString())) }.getOrDefault(fallback)

    private fun powerToJson(observation: PowerObservation): JSONObject = JSONObject().apply {
        put("elapsedTimeMs", observation.elapsedTimeMs)
        putNullable("batteryLevelPercent", observation.batteryLevelPercent)
        put("chargingState", observation.chargingState)
        putNullable("voltageVolts", observation.voltageVolts)
        putNullable("currentAmpsRaw", observation.currentAmpsRaw)
        putNullable("estimatedBatteryPowerWatts", observation.estimatedBatteryPowerWatts)
    }

    private fun JSONObject.toPowerObservation(): PowerObservation = PowerObservation(
        elapsedTimeMs = optLong("elapsedTimeMs", 0L),
        batteryLevelPercent = if (has("batteryLevelPercent") && !isNull("batteryLevelPercent")) {
            optInt("batteryLevelPercent")
        } else {
            null
        },
        chargingState = optString("chargingState", "UNKNOWN"),
        voltageVolts = optionalDouble("voltageVolts"),
        currentAmpsRaw = optionalDouble("currentAmpsRaw"),
        estimatedBatteryPowerWatts = optionalDouble("estimatedBatteryPowerWatts"),
    )

    private fun JSONArray?.toThermalEvents(): List<ThermalEvent> = buildList {
        val array = this@toThermalEvents ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                ThermalEvent(
                    elapsedTimeMs = item.optLong("elapsedTimeMs", 0L),
                    status = item.optInt("status", 0),
                    batteryTemperatureCelsius = item.optionalDouble(
                        "batteryTemperatureCelsius",
                    ),
                ),
            )
        }
    }

    private fun JSONArray?.toCpuFrequencyObservations(): List<CpuFrequencySessionObservation> =
        buildList {
            val array = this@toCpuFrequencyObservations ?: return@buildList
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    CpuFrequencySessionObservation(
                        policy = item.optString("policy", "Unknown"),
                        startHz = item.optionalLong("startHz"),
                        minimumObservedHz = item.optionalLong("minimumObservedHz"),
                        peakObservedHz = item.optionalLong("peakObservedHz"),
                        endHz = item.optionalLong("endHz"),
                    ),
                )
            }
        }

    private fun JSONArray?.toSessionEvents(): List<SessionEvent> = buildList {
        val array = this@toSessionEvents ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val type = runCatching {
                SessionEventType.valueOf(item.optString("type"))
            }.getOrNull() ?: continue
            add(
                SessionEvent(
                    elapsedTimeMs = item.optLong("elapsedTimeMs", 0L),
                    type = type,
                    detail = item.optionalString("detail"),
                ),
            )
        }
    }

    private fun parseStopReason(value: String): StopReason = when (value) {
        "USER" -> StopReason.USER_STOP
        "THERMAL" -> StopReason.THERMAL_CRITICAL
        "ACTIVITY_STOPPED" -> StopReason.USER_STOP
        else -> runCatching { StopReason.valueOf(value) }.getOrDefault(StopReason.RESOURCE_ERROR)
    }

    private fun JSONObject.optionalLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)
}

class SessionHistoryStore(context: Context) {
    private val historyFile = File(context.filesDir, HISTORY_FILE_NAME)
    private val lock = Any()

    fun add(session: StressSessionSnapshot, limit: Int) {
        synchronized(lock) {
            val updated = ArrayList<StressSessionSnapshot>()
            updated += session
            updated += readInternal().filter { it.startWallTimeMs != session.startWallTimeMs }
            writeInternal(HistoryPolicy.trimNewest(updated, limit))
        }
    }

    fun list(): List<StressSessionSnapshot> = synchronized(lock) { readInternal() }

    fun find(startWallTimeMs: Long): StressSessionSnapshot? = synchronized(lock) {
        readInternal().firstOrNull { it.startWallTimeMs == startWallTimeMs }
    }

    fun clear() {
        synchronized(lock) {
            if (historyFile.exists()) historyFile.delete()
        }
    }

    fun trim(limit: Int) {
        synchronized(lock) {
            writeInternal(HistoryPolicy.trimNewest(readInternal(), limit))
        }
    }

    private fun readInternal(): List<StressSessionSnapshot> {
        if (!historyFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(historyFile.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    runCatching { SessionJsonCodec.fromJson(item) }.getOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeInternal(sessions: List<StressSessionSnapshot>) {
        val array = JSONArray()
        sessions.forEach { array.put(SessionJsonCodec.toJson(it)) }
        val temporaryFile = File(historyFile.parentFile, "$HISTORY_FILE_NAME.tmp")
        temporaryFile.writeText(array.toString(), Charsets.UTF_8)
        if (!temporaryFile.renameTo(historyFile)) {
            historyFile.writeText(array.toString(), Charsets.UTF_8)
            temporaryFile.delete()
        }
    }

    companion object {
        private const val HISTORY_FILE_NAME = "session-history.json"
    }
}
