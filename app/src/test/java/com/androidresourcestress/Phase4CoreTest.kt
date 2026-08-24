package com.androidresourcestress

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4CoreTest {
    @Test
    fun byteFormatterUsesReadableBinaryUnits() {
        assertEquals("0 B", ByteFormatter.formatBytes(0L))
        assertEquals("1 KB", ByteFormatter.formatBytes(1024L))
        assertEquals("1.00 GB", ByteFormatter.formatBytes(1024L * 1024L * 1024L))
        assertEquals("4.0 MB/s", ByteFormatter.formatRate(4.0 * 1024 * 1024))
    }

    @Test
    fun engineeringFormattersUseHonestUnitsAndFallbacks() {
        assertEquals("42.3 °C", TemperatureFormatter.format(42.34))
        assertEquals("N/A", TemperatureFormatter.format(null))
        assertEquals("2.40 GHz", FrequencyFormatter.format(2_400_000_000L))
        assertEquals("800 MHz", FrequencyFormatter.format(800_000_000L))
        assertEquals("Unsupported", FrequencyFormatter.format(null))
        assertEquals("4.080 V", PowerFormatter.voltage(4.08))
        assertEquals("-4.820 A", PowerFormatter.current(-4.82))
        assertEquals("19.67 W", PowerFormatter.power(19.67))
    }

    @Test
    fun presetsNeverEnableStorageByDefault() {
        StressPreset.entries.filter { it != StressPreset.CUSTOM }.forEach { preset ->
            assertFalse(PresetConfigurations.create(preset).storageEnabled)
        }
        val extreme = PresetConfigurations.create(StressPreset.EXTREME)
        assertEquals(100, extreme.cpuTargetPercent)
        assertEquals(100, extreme.gpuTargetPercent)
        assertEquals(MemoryTarget.AUTO, extreme.memoryTarget)
        assertEquals(GpuMode.COMPUTE, extreme.gpuMode)
    }

    @Test
    fun durationFormatterUsesClockFormat() {
        assertEquals("00:00:00", DurationFormatter.format(0L))
        assertEquals("01:02:03", DurationFormatter.format(3_723_999L))
    }

    @Test
    fun storageSafetyHonorsRatioTargetAndReserve() {
        val gib = 1024L * 1024L * 1024L
        val mib = 1024L * 1024L
        assertEquals(512L * mib, StorageSafety.calculateWorkingSetBytes(10L * gib, 512L * mib))
        assertEquals(307L * mib, StorageSafety.calculateWorkingSetBytes(3L * gib, 512L * mib))
        assertEquals(0L, StorageSafety.calculateWorkingSetBytes(2L * gib, 128L * mib))
    }

    @Test
    fun sessionJsonRoundTripsPhase4AndV050Fields() {
        val original = sampleSession()
        val restored = SessionJsonCodec.fromJson(SessionJsonCodec.toJson(original))
        assertEquals(original, restored)
        assertTrue(restored.configuration.storageEnabled)
        assertEquals(StorageMode.MIXED, restored.configuration.storageMode)
        assertEquals(GpuMode.MIXED, restored.configuration.gpuMode)
        assertEquals(2, restored.thermalTimeline.size)
        assertEquals(1, restored.cpuFrequencyObservations.size)
        assertEquals(ScreenMode.OFF, restored.screenMode)
        assertEquals(2, restored.screenTransitionCount)
        assertTrue(restored.screenFallbackUsed)
        assertEquals(3, restored.eventTimeline.size)
    }

    @Test
    fun oldSessionJsonGetsSafeStorageDefaults() {
        val configuration = org.json.JSONObject()
            .put("preset", "EXTREME")
            .put("cpuEnabled", true)
            .put("gpuEnabled", true)
            .put("memoryEnabled", true)
        val restored = SessionJsonCodec.configurationFromJson(configuration)
        assertFalse(restored.storageEnabled)
        assertEquals(StorageMode.MIXED, restored.storageMode)
        assertEquals(StorageLevel.LOW, restored.storageLevel)
        assertEquals(GpuMode.COMPUTE, restored.gpuMode)
        assertEquals(ScreenMode.ON, restored.screenMode)
    }

    @Test
    fun phase4SessionJsonGetsSafeScreenAndStopReasonDefaults() {
        val old = SessionJsonCodec.toJson(sampleSession()).apply {
            remove("screenMode")
            remove("screenOffAtElapsedMs")
            remove("screenOnAtElapsedMs")
            remove("screenOffDurationMs")
            remove("screenTransitionCount")
            remove("screenFallbackUsed")
            remove("wakeAttempted")
            remove("wakeSucceeded")
            remove("wakeReason")
            remove("eventTimeline")
            put("stopReason", "USER")
            getJSONObject("configuration").remove("screenMode")
        }
        val restored = SessionJsonCodec.fromJson(old)
        assertEquals(ScreenMode.ON, restored.screenMode)
        assertEquals(0L, restored.screenOffDurationMs)
        assertFalse(restored.screenFallbackUsed)
        assertFalse(restored.wakeAttempted)
        assertTrue(restored.eventTimeline.isEmpty())
        assertEquals(StopReason.USER_STOP, restored.stopReason)
    }

    @Test
    fun thermalPolicyContinuesAtSevereAndStopsAtCritical() {
        assertFalse(ThermalPolicy.shouldStop(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(ThermalPolicy.shouldStop(PowerManager.THERMAL_STATUS_CRITICAL))
        assertTrue(ThermalPolicy.shouldStop(PowerManager.THERMAL_STATUS_EMERGENCY))
        assertTrue(ThermalPolicy.isWarning(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals("SEVERE", thermalStatusLabel(PowerManager.THERMAL_STATUS_SEVERE))
    }

    @Test
    fun thermalTimelineRecordsChangesAndFindsThresholdTimes() {
        val none = ThermalEvent(0L, PowerManager.THERMAL_STATUS_NONE, 35.0)
        val same = ThermalEvent(500L, PowerManager.THERMAL_STATUS_NONE, 35.2)
        val moderate = ThermalEvent(38_000L, PowerManager.THERMAL_STATUS_MODERATE, 40.0)
        val severe = ThermalEvent(64_000L, PowerManager.THERMAL_STATUS_SEVERE, 43.0)
        var events = ThermalTimelineAnalysis.appendIfChanged(emptyList(), none)
        events = ThermalTimelineAnalysis.appendIfChanged(events, same)
        events = ThermalTimelineAnalysis.appendIfChanged(events, moderate)
        events = ThermalTimelineAnalysis.appendIfChanged(events, severe)
        assertEquals(3, events.size)
        assertEquals(38_000L, ThermalTimelineAnalysis.timeToStatus(events, PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(64_000L, ThermalTimelineAnalysis.timeToStatus(events, PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(null, ThermalTimelineAnalysis.timeToStatus(events, PowerManager.THERMAL_STATUS_CRITICAL))
    }

    @Test
    fun historyTrimmingKeepsNewestItems() {
        val newestFirst = (50 downTo 1).toList()
        assertEquals((50 downTo 31).toList(), HistoryPolicy.trimNewest(newestFirst, 20))
        assertEquals(30, HistoryPolicy.trimNewest(newestFirst, 30).size)
        assertEquals(50, HistoryPolicy.trimNewest(newestFirst + 0, 50).size)
    }

    @Test
    fun languagePreferenceParsingHasSafeSystemFallback() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.parse(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.parse("INVALID"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.parse("SIMPLIFIED_CHINESE"))
        assertEquals("zh-CN", AppLanguage.SIMPLIFIED_CHINESE.languageTag)
        assertEquals("en", AppLanguage.ENGLISH.languageTag)
    }

    private fun sampleSession(): StressSessionSnapshot = StressSessionSnapshot(
        sessionId = 123L,
        startWallTimeMs = 123L,
        elapsedTimeMs = 30_000L,
        configuration = PresetConfigurations.create(StressPreset.EXTREME, StressDuration.SECONDS_30)
            .copy(
                storageEnabled = true,
                storageMode = StorageMode.MIXED,
                gpuMode = GpuMode.MIXED,
                screenMode = ScreenMode.OFF,
            ),
        resolvedMemoryTargetBytes = 512L,
        allocatedMemoryBytes = 500L,
        peakCpuLoadPercent = 91.2,
        peakCoreEquivalentPercent = 729.0,
        peakAppPssBytes = 638L,
        peakNativePssBytes = 520L,
        peakMemoryActivityBytesPerSecond = 4_000.0,
        peakDispatchRate = 76.0,
        averageGpuWorkTimeNanos = 12_800_000.0,
        storageWorkingSetBytes = 128L,
        storageBytesRead = 200L,
        storageBytesWritten = 100L,
        peakStorageReadActivityBytesPerSecond = 20.0,
        peakStorageWriteActivityBytesPerSecond = 10.0,
        startBatteryTemperatureCelsius = 41.0,
        peakBatteryTemperatureCelsius = 46.0,
        highestThermalStatus = 3,
        stopReason = StopReason.USER_STOP,
        lastError = null,
        thermalTimeline = listOf(
            ThermalEvent(0L, PowerManager.THERMAL_STATUS_NONE, 41.0),
            ThermalEvent(20_000L, PowerManager.THERMAL_STATUS_SEVERE, 46.0),
        ),
        cpuFrequencyObservations = listOf(
            CpuFrequencySessionObservation("policy0", 2_000L, 1_000L, 3_000L, 1_500L),
        ),
        startPowerObservation = PowerObservation(0L, 90, "CHARGING", 4.0, 1.0, 4.0),
        endPowerObservation = PowerObservation(30_000L, 91, "CHARGING", 4.1, 1.2, 4.92),
        peakEstimatedBatteryPowerWatts = 4.92,
        peakVisualFps = 59.5,
        averageVisualFrameTimeNanos = 16_800_000.0,
        screenMode = ScreenMode.OFF,
        screenOffAtElapsedMs = 3_000L,
        screenOnAtElapsedMs = 28_000L,
        screenOffDurationMs = 25_000L,
        screenTransitionCount = 2,
        screenFallbackUsed = true,
        wakeAttempted = true,
        wakeSucceeded = true,
        wakeReason = "DURATION_COMPLETED",
        eventTimeline = listOf(
            SessionEvent(0L, SessionEventType.SESSION_START),
            SessionEvent(3_000L, SessionEventType.SCREEN_OFF),
            SessionEvent(3_050L, SessionEventType.COMPUTE_FALLBACK_STARTED),
        ),
    )
}
