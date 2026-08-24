package com.androidresourcestress

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class SocVendor {
    QUALCOMM,
    MEDIATEK,
    GENERIC,
}

data class SocInfo(
    val manufacturer: String,
    val model: String,
    val boardPlatform: String,
    val hardware: String,
    val board: String,
    val fingerprint: String,
    val kernelVersion: String,
    val vendor: SocVendor,
)

data class RootCapability(
    val available: Boolean,
    val suPath: String?,
    val detail: String,
)

data class CpuFrequencyPolicy(
    val policy: String,
    val currentHz: Long?,
    val minimumHz: Long?,
    val maximumHz: Long?,
)

data class ThermalZoneReading(
    val name: String,
    val temperatureCelsius: Double,
    val path: String,
)

data class GpuHardwareSnapshot(
    val frequencyHz: Long?,
    val maximumFrequencyHz: Long?,
    val utilizationPercent: Double?,
    val source: String?,
) {
    val supported: Boolean
        get() = frequencyHz != null || maximumFrequencyHz != null || utilizationPercent != null
}

data class BatteryPowerSnapshot(
    val levelPercent: Int?,
    val chargingState: String,
    val voltageVolts: Double?,
    val currentAmpsRaw: Double?,
    val estimatedBatteryPowerWatts: Double?,
    val currentDirectionNote: String,
)

data class HardwareSnapshot(
    val sampledElapsedRealtimeMs: Long,
    val root: RootCapability,
    val soc: SocInfo,
    val cpuFrequencies: List<CpuFrequencyPolicy>,
    val thermalZones: List<ThermalZoneReading>,
    val gpu: GpuHardwareSnapshot,
    val battery: BatteryPowerSnapshot,
) {
    val highestThermalZone: ThermalZoneReading?
        get() = thermalZones.maxByOrNull { it.temperatureCelsius }

    companion object {
        fun empty(): HardwareSnapshot = HardwareSnapshot(
            sampledElapsedRealtimeMs = 0L,
            root = RootCapability(false, null, "Not checked"),
            soc = SocInfo(
                "Unknown",
                "Unknown",
                "Unknown",
                "Unknown",
                "Unknown",
                "Unknown",
                "Unknown",
                SocVendor.GENERIC,
            ),
            cpuFrequencies = emptyList(),
            thermalZones = emptyList(),
            gpu = GpuHardwareSnapshot(null, null, null, null),
            battery = BatteryPowerSnapshot(null, "Unknown", null, null, null, "Unknown"),
        )
    }
}

object RootCapabilityDetector {
    private val candidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
    )

    @Volatile
    private var cached: RootCapability? = null

    fun detect(timeoutMs: Long = 1_200L): RootCapability {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: detectUncached(timeoutMs).also { cached = it }
        }
    }

    private fun detectUncached(timeoutMs: Long): RootCapability {
        val suPath = candidates.firstOrNull { File(it).canExecute() }
            ?: commandPath("su", timeoutMs)
            ?: return RootCapability(false, null, "su executable not found")
        val process = runCatching { ProcessBuilder(suPath, "-c", "id").redirectErrorStream(true).start() }
            .getOrNull() ?: return RootCapability(false, suPath, "su could not be executed")
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                RootCapability(false, suPath, "su verification timed out")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                RootCapability(output.contains("uid=0"), suPath, output.ifBlank { "uid=0 not returned" })
            }
        } catch (_: Throwable) {
            process.destroyForcibly()
            RootCapability(false, suPath, "su verification failed")
        }
    }

    private fun commandPath(command: String, timeoutMs: Long): String? {
        val process = runCatching {
            ProcessBuilder("sh", "-c", "command -v $command").redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                null
            } else {
                process.inputStream.bufferedReader().use { it.readText() }.trim()
                    .lineSequence().firstOrNull()?.takeIf { it.startsWith('/') }
            }
        } catch (_: Throwable) {
            process.destroyForcibly()
            null
        }
    }
}

interface HardwareMonitor {
    val backendName: String
    fun sample(): HardwareSnapshot
}

class GenericAndroidMonitor(
    private val context: Context,
    private val rootCapability: RootCapability,
) : HardwareMonitor {
    override val backendName: String = "GenericAndroidMonitor"
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val socInfo = readSocInfo()

    override fun sample(): HardwareSnapshot = HardwareSnapshot(
        sampledElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        root = rootCapability,
        soc = socInfo,
        cpuFrequencies = readCpuFrequencies(),
        thermalZones = readThermalZones(),
        gpu = readGpuHardware(),
        battery = readBattery(),
    )

    private fun readSocInfo(): SocInfo {
        val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            "Unknown"
        }.orEmpty().ifBlank { "Unknown" }
        val model = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            "Unknown"
        }.orEmpty().ifBlank { "Unknown" }
        val boardPlatform = systemProperty("ro.board.platform")
        val searchable = "$manufacturer $model $boardPlatform ${Build.HARDWARE}".lowercase(Locale.US)
        val vendor = when {
            searchable.contains("qualcomm") || searchable.contains("qcom") ||
                searchable.contains("sm") && Regex("""sm\d{4}""").containsMatchIn(searchable) ->
                SocVendor.QUALCOMM
            searchable.contains("mediatek") || searchable.contains("mtk") ||
                Regex("\\bmt\\d{4,}\\b").containsMatchIn(searchable) -> SocVendor.MEDIATEK
            else -> SocVendor.GENERIC
        }
        return SocInfo(
            manufacturer = manufacturer,
            model = model,
            boardPlatform = boardPlatform,
            hardware = Build.HARDWARE.orEmpty().ifBlank { "Unknown" },
            board = Build.BOARD.orEmpty().ifBlank { "Unknown" },
            fingerprint = Build.FINGERPRINT.orEmpty().ifBlank { "Unknown" },
            kernelVersion = System.getProperty("os.version").orEmpty().ifBlank { "Unknown" },
            vendor = vendor,
        )
    }

    private fun readCpuFrequencies(): List<CpuFrequencyPolicy> {
        val policyRoot = File("/sys/devices/system/cpu/cpufreq")
        val policyDirectories = safeDirectories(policyRoot)
            .filter { it.name.startsWith("policy") }
            .sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
        val directories = if (policyDirectories.isNotEmpty()) {
            policyDirectories
        } else {
            safeDirectories(File("/sys/devices/system/cpu"))
                .filter { it.name.matches(Regex("cpu\\d+")) }
                .map { File(it, "cpufreq") }
                .filter { it.isDirectory }
                .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        }
        return directories.mapNotNull { directory ->
            val current = readKiloHertz(directory, "scaling_cur_freq")
                ?: readKiloHertz(directory, "cpuinfo_cur_freq")
            val minimum = readKiloHertz(directory, "scaling_min_freq")
                ?: readKiloHertz(directory, "cpuinfo_min_freq")
            val maximum = readKiloHertz(directory, "scaling_max_freq")
                ?: readKiloHertz(directory, "cpuinfo_max_freq")
            if (current == null && minimum == null && maximum == null) null else {
                CpuFrequencyPolicy(directory.name, current, minimum, maximum)
            }
        }
    }

    private fun readThermalZones(): List<ThermalZoneReading> =
        safeDirectories(File("/sys/class/thermal"))
            .filter { it.name.startsWith("thermal_zone") }
            .mapNotNull { zone ->
                val raw = readText(File(zone, "temp"))?.toDoubleOrNull() ?: return@mapNotNull null
                val celsius = normalizeTemperature(raw) ?: return@mapNotNull null
                val name = readText(File(zone, "type"))?.ifBlank { zone.name } ?: zone.name
                ThermalZoneReading(name, celsius, zone.absolutePath)
            }
            .sortedByDescending { it.temperatureCelsius }

    private fun readGpuHardware(): GpuHardwareSnapshot {
        val roots = buildList {
            add(File("/sys/class/kgsl/kgsl-3d0"))
            add(File("/sys/class/kgsl/kgsl-3d0/devfreq"))
            safeDirectories(File("/sys/class/devfreq")).forEach { directory ->
                val searchable = (directory.name + " " + (readText(File(directory, "name")) ?: ""))
                    .lowercase(Locale.US)
                if (searchable.contains("gpu") || searchable.contains("kgsl") ||
                    searchable.contains("mali") || searchable.contains("adreno")
                ) {
                    add(directory)
                }
            }
        }.filter { it.isDirectory }.distinctBy { it.absolutePath }
        for (root in roots) {
            val frequency = readLong(root, listOf("cur_freq", "gpuclk", "clock_mhz"))
                ?.let { normalizeFrequency(it, root) }
            val maximum = readLong(root, listOf("max_freq", "max_gpuclk"))
                ?.let { normalizeFrequency(it, root) }
            val utilization = readUtilization(root)
            if (frequency != null || maximum != null || utilization != null) {
                return GpuHardwareSnapshot(frequency, maximum, utilization, root.absolutePath)
            }
        }
        return GpuHardwareSnapshot(null, null, null, null)
    }

    private fun readBattery(): BatteryPowerSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            ?.takeIf { it >= 0 }
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            ?.takeIf { it > 0 } ?: 100
        val levelPercent = level?.let { (it * 100 / scale).coerceIn(0, 100) }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val chargingState = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }
        val voltageVolts = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE || it <= 0 }
            ?.div(1000.0)
        val currentMicroAmps = batteryManager
            ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeUnless { it == Long.MIN_VALUE || abs(it) > 100_000_000L }
        val currentAmps = currentMicroAmps?.div(1_000_000.0)
        val power = if (voltageVolts != null && currentAmps != null) {
            voltageVolts * abs(currentAmps)
        } else {
            null
        }
        return BatteryPowerSnapshot(
            levelPercent = levelPercent,
            chargingState = chargingState,
            voltageVolts = voltageVolts,
            currentAmpsRaw = currentAmps,
            estimatedBatteryPowerWatts = power,
            currentDirectionNote = if (currentAmps == null) {
                "Unavailable"
            } else {
                "Raw sign; vendor convention may differ · $chargingState"
            },
        )
    }

    private fun readUtilization(root: File): Double? {
        val files = listOf("gpu_busy_percentage", "utilization", "load", "busy_percent")
        for (name in files) {
            val text = readText(File(root, name)) ?: continue
            val value = Regex("-?\\d+(?:\\.\\d+)?").find(text)?.value?.toDoubleOrNull() ?: continue
            val normalized = when {
                value in 0.0..100.0 -> value
                value in 0.0..10_000.0 -> value / 100.0
                else -> continue
            }
            return normalized.coerceIn(0.0, 100.0)
        }
        return null
    }

    private fun readKiloHertz(directory: File, name: String): Long? =
        readText(File(directory, name))?.toLongOrNull()?.takeIf { it > 0L }?.times(1_000L)

    private fun readLong(directory: File, names: List<String>): Long? =
        names.firstNotNullOfOrNull { name ->
            readText(File(directory, name))?.filter { it.isDigit() || it == '-' }
                ?.toLongOrNull()?.takeIf { it > 0L }
        }

    private fun normalizeFrequency(value: Long, source: File): Long = when {
        source.name.contains("mhz", ignoreCase = true) -> value * 1_000_000L
        value < 20_000L -> value * 1_000_000L
        value < 20_000_000L -> value * 1_000L
        else -> value
    }

    private fun normalizeTemperature(raw: Double): Double? {
        val celsius = when {
            abs(raw) > 1_000.0 -> raw / 1_000.0
            else -> raw
        }
        return celsius.takeIf { it in -40.0..200.0 }
    }

    private fun safeDirectories(root: File): List<File> =
        runCatching { root.listFiles()?.filter { it.isDirectory }.orEmpty() }.getOrDefault(emptyList())

    private fun readText(file: File): String? = runCatching {
        file.bufferedReader().use { it.readLine() }?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }

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

open class RootHardwareMonitor(
    protected val delegate: HardwareMonitor,
    protected val rootCapability: RootCapability,
) : HardwareMonitor {
    override val backendName: String = "RootHardwareMonitor(${delegate.backendName})"
    override fun sample(): HardwareSnapshot = delegate.sample()
}

class QualcommMonitor(delegate: HardwareMonitor, root: RootCapability) :
    RootHardwareMonitor(delegate, root) {
    override val backendName: String = "QualcommMonitor(${delegate.backendName})"
}

class MediaTekMonitor(delegate: HardwareMonitor, root: RootCapability) :
    RootHardwareMonitor(delegate, root) {
    override val backendName: String = "MediaTekMonitor(${delegate.backendName})"
}

object HardwareMonitorFactory {
    fun create(context: Context, root: RootCapability): HardwareMonitor {
        val generic = GenericAndroidMonitor(context.applicationContext, root)
        val soc = generic.sample().soc
        val vendorMonitor: HardwareMonitor = when (soc.vendor) {
            SocVendor.QUALCOMM -> QualcommMonitor(generic, root)
            SocVendor.MEDIATEK -> MediaTekMonitor(generic, root)
            SocVendor.GENERIC -> generic
        }
        return if (root.available && vendorMonitor === generic) {
            RootHardwareMonitor(generic, root)
        } else {
            vendorMonitor
        }
    }
}

class HardwareMonitorService(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val diagnosticLog = DiagnosticLog(applicationContext)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hardware-monitor").apply { isDaemon = true }
    }

    @Volatile
    private var latest = HardwareSnapshot.empty()

    @Volatile
    var backendName: String = "Initializing"
        private set

    init {
        executor.execute {
            val root = RootCapabilityDetector.detect()
            diagnosticLog.record("Root detected available=${root.available} detail=${root.detail}")
            val monitor = HardwareMonitorFactory.create(applicationContext, root)
            backendName = monitor.backendName
            latest = runCatching { monitor.sample() }.getOrDefault(HardwareSnapshot.empty())
            diagnosticLog.record(
                "Hardware capability detected backend=$backendName root=${root.available} " +
                    "soc=${latest.soc.vendor} cpuPolicies=${latest.cpuFrequencies.size} " +
                    "thermalZones=${latest.thermalZones.size} gpuHardware=${latest.gpu.supported}",
            )
            executor.scheduleWithFixedDelay(
                { latest = runCatching { monitor.sample() }.getOrDefault(latest) },
                1L,
                1L,
                TimeUnit.SECONDS,
            )
        }
    }

    fun snapshot(): HardwareSnapshot = latest

    override fun close() {
        executor.shutdownNow()
        executor.awaitTermination(2L, TimeUnit.SECONDS)
    }
}
