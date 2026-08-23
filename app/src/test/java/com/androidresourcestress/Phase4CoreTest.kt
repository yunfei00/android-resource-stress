package com.androidresourcestress

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
    fun presetsNeverEnableStorageByDefault() {
        StressPreset.entries.filter { it != StressPreset.CUSTOM }.forEach { preset ->
            assertFalse(PresetConfigurations.create(preset).storageEnabled)
        }
        val extreme = PresetConfigurations.create(StressPreset.EXTREME)
        assertEquals(100, extreme.cpuTargetPercent)
        assertEquals(100, extreme.gpuTargetPercent)
        assertEquals(MemoryTarget.AUTO, extreme.memoryTarget)
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
    fun sessionJsonRoundTripsPhase4Fields() {
        val original = sampleSession()
        val restored = SessionJsonCodec.fromJson(SessionJsonCodec.toJson(original))
        assertEquals(original, restored)
        assertTrue(restored.configuration.storageEnabled)
        assertEquals(StorageMode.MIXED, restored.configuration.storageMode)
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
    }

    private fun sampleSession(): StressSessionSnapshot = StressSessionSnapshot(
        sessionId = 123L,
        startWallTimeMs = 123L,
        elapsedTimeMs = 30_000L,
        configuration = PresetConfigurations.create(StressPreset.EXTREME, StressDuration.SECONDS_30)
            .copy(storageEnabled = true, storageMode = StorageMode.MIXED),
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
        stopReason = StopReason.USER,
        lastError = null,
    )
}
