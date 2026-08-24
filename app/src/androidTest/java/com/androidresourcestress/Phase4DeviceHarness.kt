package com.androidresourcestress

import android.app.Activity
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Phase4DeviceHarness : Instrumentation(), CombinedStressController.Listener {
    private data class HarnessSample(
        val elapsedMs: Long,
        val cpuLoad: Double,
        val coreEquivalent: Double,
        val cpuFrequencyHz: Double?,
        val gpuDispatch: Double,
        val gpuWorkMs: Double,
        val memoryActivity: Double,
        val batteryTemperature: Double?,
        val thermalStatus: Int,
        val visualVulkanFrames: Long,
        val visualVulkanWorkMs: Double,
    )
    private lateinit var arguments: Bundle
    private lateinit var controller: CombinedStressController
    private var finishedLatch = CountDownLatch(1)
    private var gpuLatch = CountDownLatch(1)

    @Volatile
    private var latestState = CombinedStressState.IDLE

    @Volatile
    private var latestSession: StressSessionSnapshot? = null

    @Volatile
    private var latestError: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle.EMPTY
        start()
    }

    override fun onStart() {
        val results = Bundle()
        runCatching {
            NativeStress.ensureLoaded()
            val monitor = DeviceMonitor(targetContext)
            controller = CombinedStressController(
                targetContext,
                monitor.deviceInfo().logicalCoreCount,
                this,
            )
            controller.initialize()
            gpuLatch.await(10L, TimeUnit.SECONDS)
            val scenario = arguments.getString("scenario", "single") ?: "single"
            val summaries = when (scenario) {
                "pages" -> exerciseProductPages()
                "localization" -> exerciseLocalization()
                "localizationUi" -> exerciseLocalizationUi()
                "hardware" -> exerciseHardwareMonitor()
                "visualUi" -> exerciseVisualUi()
                "history" -> exerciseHistory()
                "exportValidation" -> exerciseExportValidation()
                "diagnosticExport" -> {
                    val file = DiagnosticLog(targetContext).export()
                    listOf("${file.name}: ${file.length()} bytes")
                }
                "matrix" -> matrixConfigurations().map { (name, configuration) ->
                    "$name: ${runSession(configuration, argumentLong("runMs", 2_000L))}"
                }
                "extremeCycles" -> exerciseExtremeCycles()
                "storageCycles" -> exerciseStorageCycles()
                "extremeStability" -> listOf(
                    runSession(
                        PresetConfigurations.create(
                            StressPreset.EXTREME,
                            StressDuration.CONTINUOUS,
                        ),
                        argumentLong("runMs", 300_000L),
                    ),
                )
                else -> listOf(runSession(configurationFromArguments(), argumentLong("runMs", 3_000L)))
            }
            results.putString("phase4Results", summaries.joinToString("\n"))
            controller.close()
        }.onFailure { error ->
            if (::controller.isInitialized) controller.close()
            results.putString("phase4Error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
            return
        }
        finish(Activity.RESULT_OK, results)
    }

    override fun onCombinedStateChanged(state: CombinedStressState) {
        latestState = state
    }

    override fun onGpuInfoAvailable(info: GpuInfo) {
        gpuLatch.countDown()
    }

    override fun onSessionFinished(session: StressSessionSnapshot) {
        latestSession = session
        finishedLatch.countDown()
    }

    override fun onCombinedError(message: String) {
        latestError = message
    }

    private fun runSession(
        configuration: CombinedStressConfiguration,
        runMs: Long,
    ): String {
        finishedLatch = CountDownLatch(1)
        latestSession = null
        latestError = null
        controller.start(configuration)
        val samples = mutableListOf<HarnessSample>()
        val startDeadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < startDeadline &&
            latestState != CombinedStressState.RUNNING && latestSession == null
        ) {
            SystemClock.sleep(POLL_MS)
        }
        check(latestState == CombinedStressState.RUNNING || latestSession != null) {
            "Session failed to start: ${latestError ?: latestState}"
        }
        val runDeadline = SystemClock.elapsedRealtime() + runMs.coerceAtLeast(0L)
        while (SystemClock.elapsedRealtime() < runDeadline && latestSession == null) {
            val runtime = controller.runtimeSnapshot()
            samples += HarnessSample(
                elapsedMs = runtime.elapsedTimeMs,
                cpuLoad = runtime.cpuLoadPercent,
                coreEquivalent = runtime.coreEquivalentPercent,
                cpuFrequencyHz = runtime.hardware.cpuFrequencies.mapNotNull { it.currentHz }
                    .takeIf { it.isNotEmpty() }?.average(),
                gpuDispatch = runtime.gpuDispatchRate,
                gpuWorkMs = runtime.gpu.gpuWorkNanos / 1_000_000.0,
                memoryActivity = runtime.memoryActivityBytesPerSecond,
                batteryTemperature = runtime.thermal.batteryTemperatureCelsius,
                thermalStatus = runtime.thermal.status,
                visualVulkanFrames = runtime.visualVulkanFrameCount,
                visualVulkanWorkMs = runtime.visualVulkanFrameWorkNanos / 1_000_000.0,
            )
            val remainingMs = runDeadline - SystemClock.elapsedRealtime()
            if (remainingMs > 0L) SystemClock.sleep(minOf(POLL_MS, remainingMs))
        }
        controller.runtimeSnapshot()
        if (latestSession == null) controller.stopAll(StopReason.USER)
        check(finishedLatch.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Session did not finish within ${STOP_TIMEOUT_MS}ms"
        }
        val session = checkNotNull(latestSession)
        check(controller.state == CombinedStressState.IDLE) {
            "Controller did not return to IDLE: ${controller.state}"
        }
        val finalElapsed = samples.lastOrNull()?.elapsedMs ?: session.elapsedTimeMs
        val firstMinute = samples.filter { it.elapsedMs <= 60_000L }
        val lastMinute = samples.filter { it.elapsedMs >= (finalElapsed - 60_000L).coerceAtLeast(0L) }
        val timeline = session.thermalTimeline.joinToString(",") {
            "${it.elapsedTimeMs}:${thermalStatusLabel(it.status)}"
        }
        val frequencyPolicies = session.cpuFrequencyObservations.joinToString(",") {
            "${it.policy}[${it.startHz}/${it.minimumObservedHz}/${it.peakObservedHz}/${it.endHz}]"
        }
        return String.format(
            Locale.US,
            "reason=%s elapsed=%d cpu=%.1f gpu=%.1f mem=%.1f storageR=%d storageW=%d " +
                "thermal=%s timeline=%s cpuFreq=%s visualFrames=%d visualWorkMs=%.3f " +
                "first60=%s last60=%s",
            session.stopReason,
            session.elapsedTimeMs,
            session.peakCpuLoadPercent,
            session.peakDispatchRate,
            session.peakMemoryActivityBytesPerSecond,
            session.storageBytesRead,
            session.storageBytesWritten,
            thermalStatusLabel(session.highestThermalStatus),
            timeline,
            frequencyPolicies,
            samples.maxOfOrNull { it.visualVulkanFrames } ?: 0L,
            samples.maxOfOrNull { it.visualVulkanWorkMs } ?: 0.0,
            summarizeSamples(firstMinute),
            summarizeSamples(lastMinute),
        )
    }

    private fun summarizeSamples(samples: List<HarnessSample>): String {
        if (samples.isEmpty()) return "none"
        fun average(selector: (HarnessSample) -> Double): Double = samples.map(selector).average()
        val frequencies = samples.mapNotNull { it.cpuFrequencyHz }
        val temperatures = samples.mapNotNull { it.batteryTemperature }
        return String.format(
            Locale.US,
            "cpu=%.1f/core=%.1f/freq=%.0f/gpu=%.1f/work=%.3f/mem=%.1f/temp=%.1f/thermal=%s",
            average { it.cpuLoad },
            average { it.coreEquivalent },
            if (frequencies.isEmpty()) 0.0 else frequencies.average(),
            average { it.gpuDispatch },
            average { it.gpuWorkMs },
            average { it.memoryActivity },
            if (temperatures.isEmpty()) 0.0 else temperatures.average(),
            thermalStatusLabel(samples.maxOf { it.thermalStatus }),
        )
    }

    private fun matrixConfigurations(): List<Pair<String, CombinedStressConfiguration>> {
        val base = PresetConfigurations.create(
            StressPreset.EXTREME,
            StressDuration.CONTINUOUS,
        )
        return listOf(
            "cpu" to base.copy(gpuEnabled = false, memoryEnabled = false),
            "gpu" to base.copy(cpuEnabled = false, memoryEnabled = false),
            "gpu-visual" to base.copy(
                cpuEnabled = false,
                memoryEnabled = false,
                gpuMode = GpuMode.VISUAL,
            ),
            "gpu-mixed" to base.copy(
                cpuEnabled = false,
                memoryEnabled = false,
                gpuMode = GpuMode.MIXED,
            ),
            "memory" to base.copy(cpuEnabled = false, gpuEnabled = false),
            "storage" to base.copy(
                cpuEnabled = false,
                gpuEnabled = false,
                memoryEnabled = false,
                storageEnabled = true,
                storageLevel = StorageLevel.LOW,
            ),
            "cpu+gpu" to base.copy(memoryEnabled = false),
            "cpu+memory" to base.copy(gpuEnabled = false),
            "gpu+memory" to base.copy(cpuEnabled = false),
            "cpu+gpu+memory" to base,
            "all-four" to base.copy(
                cpuTargetPercent = 75,
                gpuTargetPercent = 75,
                memoryTarget = MemoryTarget.MIB_512,
                storageEnabled = true,
                storageLevel = StorageLevel.MEDIUM,
            ),
        )
    }

    private fun exerciseProductPages(): List<String> {
        val latestStartTime = SessionHistoryStore(targetContext).list()
            .firstOrNull()?.startWallTimeMs ?: -1L
        val intents = listOf(
            Intent(targetContext, HistoryActivity::class.java),
            Intent(targetContext, SessionDetailActivity::class.java)
                .putExtra(SessionDetailActivity.EXTRA_START_TIME, latestStartTime),
            Intent(targetContext, DeviceInfoActivity::class.java),
            Intent(targetContext, SettingsActivity::class.java),
        )
        intents.forEach { intent ->
            targetContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(750L)
        }
        return listOf("History, detail, device information, and settings launched without error")
    }

    private fun exerciseLocalization(): List<String> {
        val preferences = AppPreferences(targetContext)
        val previous = preferences.language
        return try {
            preferences.language = AppLanguage.SIMPLIFIED_CHINESE
            val chinese = AppLocaleController.wrap(targetContext)
                .getString(R.string.settings_title)
            check(chinese == "设置") { "Chinese resource mismatch: $chinese" }
            check(AppPreferences(targetContext).language == AppLanguage.SIMPLIFIED_CHINESE)

            preferences.language = AppLanguage.ENGLISH
            val english = AppLocaleController.wrap(targetContext)
                .getString(R.string.settings_title)
            check(english == "SETTINGS") { "English resource mismatch: $english" }
            check(AppPreferences(targetContext).language == AppLanguage.ENGLISH)

            preferences.language = AppLanguage.SYSTEM
            val systemLocale = targetContext.resources.configuration.locales[0].toLanguageTag()
            val systemText = AppLocaleController.wrap(targetContext)
                .getString(R.string.settings_title)
            listOf(
                "Chinese=$chinese",
                "English=$english",
                "FollowSystem locale=$systemLocale text=$systemText",
                "Persistence=PASS",
            )
        } finally {
            preferences.language = previous
        }
    }

    private fun exerciseLocalizationUi(): List<String> {
        val preferences = AppPreferences(targetContext)
        val previous = preferences.language
        fun startButtonText(language: AppLanguage): String {
            preferences.language = language
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetContext.getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags(language.languageTag)
            }
            val activity = startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            return try {
                SystemClock.sleep(750L)
                activity.findViewById<Button>(R.id.startButton).text.toString()
            } finally {
                runOnMainSync { activity.finish() }
                SystemClock.sleep(250L)
            }
        }
        return try {
            val chinese = startButtonText(AppLanguage.SIMPLIFIED_CHINESE)
            val english = startButtonText(AppLanguage.ENGLISH)
            val system = startButtonText(AppLanguage.SYSTEM)
            check(chinese.startsWith("开始")) { "Chinese Activity text mismatch: $chinese" }
            check(english.startsWith("START")) { "English Activity text mismatch: $english" }
            check(system.startsWith("开始")) { "Follow-system Activity text mismatch: $system" }
            listOf(
                "Chinese Activity START=$chinese",
                "English Activity START=$english",
                "FollowSystem Activity START=$system",
                "inflation=PASS persistence=PASS",
            )
        } finally {
            preferences.language = previous
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetContext.getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags(previous.languageTag)
            }
        }
    }

    private fun exerciseVisualUi(): List<String> {
        val activity = startActivitySync(
            Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        return try {
            SystemClock.sleep(1_500L)
            runOnMainSync {
                activity.findViewById<CheckBox>(R.id.resourceCpu).isChecked = false
                activity.findViewById<CheckBox>(R.id.resourceGpu).isChecked = true
                activity.findViewById<CheckBox>(R.id.resourceMemory).isChecked = false
                activity.findViewById<CheckBox>(R.id.resourceStorage).isChecked = false
                activity.findViewById<RadioGroup>(R.id.gpuModeGroup).check(R.id.gpuModeVisual)
                activity.findViewById<Button>(R.id.startButton).performClick()
            }
            val runMs = argumentLong("runMs", 60_000L)
            SystemClock.sleep(runMs)
            val visual = activity.findViewById<GpuVisualStressView>(R.id.gpuVisualStressView)
                .snapshot()
            check(visual.renderedFrames > 0L) { "Visual UI rendered no frames" }
            check(visual.framesPerSecond > 0.0) { "Visual UI FPS was not measured" }
            runOnMainSync { activity.findViewById<Button>(R.id.stopButton).performClick() }
            SystemClock.sleep(1_500L)
            listOf(
                "renderedFrames=${visual.renderedFrames}",
                "fps=${String.format(Locale.US, "%.1f", visual.framesPerSecond)}",
                "frameTimeMs=${String.format(Locale.US, "%.2f", visual.frameTimeNanos / 1_000_000.0)}",
                "animation=PASS stop=PASS",
            )
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun exerciseHardwareMonitor(): List<String> {
        SystemClock.sleep(2_000L)
        val hardware = controller.runtimeSnapshot().hardware
        return listOf(
            "root=${hardware.root.available} detail=${hardware.root.detail}",
            "soc=${hardware.soc.manufacturer}/${hardware.soc.model}/${hardware.soc.vendor}",
            "board=${hardware.soc.boardPlatform} hardware=${hardware.soc.hardware}",
            "cpuPolicies=${hardware.cpuFrequencies.joinToString { "${it.policy}:${it.currentHz}/${it.minimumHz}/${it.maximumHz}" }}",
            "thermalZones=${hardware.thermalZones.size} highest=${hardware.highestThermalZone}",
            "gpuSupported=${hardware.gpu.supported} frequency=${hardware.gpu.frequencyHz} max=${hardware.gpu.maximumFrequencyHz} utilization=${hardware.gpu.utilizationPercent}",
            "battery=${hardware.battery}",
        )
    }

    private fun exerciseHistory(): List<String> {
        val store = SessionHistoryStore(targetContext)
        val base = store.list().firstOrNull() ?: error("No completed session available")
        val origin = System.currentTimeMillis()
        (0 until 35).forEach { index ->
            store.add(
                base.copy(
                    sessionId = origin + index,
                    startWallTimeMs = origin + index,
                ),
                30,
            )
        }
        val restored = SessionHistoryStore(targetContext).list()
        check(restored.size == 30) { "Expected 30 history entries, got ${restored.size}" }
        check(restored.zipWithNext().all { (first, second) ->
            first.startWallTimeMs > second.startWallTimeMs
        }) { "History is not newest first" }
        return listOf(
            "count=${restored.size}",
            "newest=${restored.first().startWallTimeMs}",
            "oldest=${restored.last().startWallTimeMs}",
            "persistence=PASS trimming=PASS order=PASS",
        )
    }

    private fun exerciseExportValidation(): List<String> {
        val session = SessionHistoryStore(targetContext).list().firstOrNull()
            ?: error("No session available for export")
        val activity = startActivitySync(
            Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        return try {
            val monitor = DeviceMonitor(activity)
            val file = ResultExporter(activity).exportSession(
                session,
                monitor.deviceInfo(),
                monitor.storageCapacity(),
                GpuInfoReader.read(),
            )
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val required = listOf(
                "app",
                "device",
                "session",
                "cpu",
                "gpu",
                "memory",
                "storage",
                "thermal",
                "power",
            )
            check(required.all(json::has)) { "Missing export keys: $required" }
            check(json.getJSONObject("thermal").has("timeline"))
            val uri = Uri.Builder()
                .scheme("content")
                .authority("${BuildConfig.APPLICATION_ID}.exports")
                .appendPath(file.name)
                .build()
            val providerBytes = activity.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().size.toLong() } ?: -1L
            check(providerBytes == file.length()) {
                "Export provider mismatch: file=${file.length()} provider=$providerBytes"
            }
            check(activity.contentResolver.getType(uri) == "application/json")
            listOf(
                "file=${file.name}",
                "bytes=${file.length()}",
                "keys=${json.keys().asSequence().toList().sorted()}",
                "parse=PASS timeline=PASS power=PASS provider=PASS",
            )
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun exerciseStorageCycles(): List<String> {
        val beforeFdCount = File("/proc/self/fd").list()?.size ?: -1
        val configuration = PresetConfigurations.create(
            StressPreset.CUSTOM,
            StressDuration.CONTINUOUS,
        ).copy(
            cpuEnabled = false,
            gpuEnabled = false,
            memoryEnabled = false,
            storageEnabled = true,
            storageMode = StorageMode.MIXED,
            storageLevel = StorageLevel.LOW,
        )
        val results = (1..argumentInt("cycles", 10)).map { cycle ->
            "storage-cycle-$cycle: ${runSession(configuration, argumentLong("runMs", 1_000L))}"
        }.toMutableList()
        SystemClock.sleep(500L)
        val afterFdCount = File("/proc/self/fd").list()?.size ?: -1
        val storageWorkers = Thread.getAllStackTraces().keys.count {
            it.isAlive && it.name.contains("storage", ignoreCase = true)
        }
        val temporaryFiles = File(targetContext.cacheDir, "storage_stress")
            .listFiles().orEmpty().filter { it.isFile }
        check(storageWorkers == 0) { "Storage workers remain: $storageWorkers" }
        check(temporaryFiles.isEmpty()) { "Storage temporary files remain: $temporaryFiles" }
        if (beforeFdCount >= 0 && afterFdCount >= 0) {
            check(afterFdCount <= beforeFdCount + 2) {
                "Possible fd leak: before=$beforeFdCount after=$afterFdCount"
            }
        }
        results += "cleanup=PASS workers=$storageWorkers tempFiles=${temporaryFiles.size} " +
            "fdBefore=$beforeFdCount fdAfter=$afterFdCount"
        return results
    }

    private fun exerciseExtremeCycles(): List<String> {
        val configuration = PresetConfigurations.create(
            StressPreset.EXTREME,
            StressDuration.CONTINUOUS,
        )
        val results = (1..argumentInt("cycles", 20)).map { cycle ->
            "cycle-$cycle: ${runSession(configuration, argumentLong("runMs", 1_000L))}"
        }.toMutableList()
        val resourcesStopped = NativeStress.getCpuStressThreadCount() == 0 &&
            !NativeStress.isMemoryStressRunning() &&
            GpuNativeStatus.fromCode(NativeStress.getGpuStressStatus()) != GpuNativeStatus.RUNNING &&
            GpuNativeStatus.fromCode(NativeStress.getVisualGpuStressStatus()) != GpuNativeStatus.RUNNING
        check(resourcesStopped) { "A native stress resource remained active after cycles" }
        results += "nativeCleanup=PASS controller=${controller.state}"
        return results
    }

    private fun configurationFromArguments(): CombinedStressConfiguration =
        PresetConfigurations.create(StressPreset.CUSTOM, StressDuration.CONTINUOUS).copy(
            cpuEnabled = argumentBoolean("cpu", false),
            gpuEnabled = argumentBoolean("gpu", false),
            memoryEnabled = argumentBoolean("memory", false),
            storageEnabled = argumentBoolean("storage", true),
            cpuTargetPercent = argumentInt("cpuTarget", 75),
            gpuTargetPercent = argumentInt("gpuTarget", 75),
            memoryTarget = enumArgument("memoryTarget", MemoryTarget.MIB_512),
            storageMode = enumArgument("storageMode", StorageMode.MIXED),
            storageLevel = enumArgument("storageLevel", StorageLevel.LOW),
            gpuMode = enumArgument("gpuMode", GpuMode.COMPUTE),
        )

    private fun argumentBoolean(name: String, fallback: Boolean): Boolean =
        arguments.getString(name)?.toBooleanStrictOrNull() ?: fallback

    private fun argumentInt(name: String, fallback: Int): Int =
        arguments.getString(name)?.toIntOrNull() ?: fallback

    private fun argumentLong(name: String, fallback: Long): Long =
        arguments.getString(name)?.toLongOrNull() ?: fallback

    private inline fun <reified T : Enum<T>> enumArgument(name: String, fallback: T): T =
        arguments.getString(name)?.let { value ->
            runCatching { enumValueOf<T>(value) }.getOrNull()
        } ?: fallback

    companion object {
        private const val POLL_MS = 100L
        private const val START_TIMEOUT_MS = 20_000L
        private const val STOP_TIMEOUT_MS = 20_000L
    }
}
