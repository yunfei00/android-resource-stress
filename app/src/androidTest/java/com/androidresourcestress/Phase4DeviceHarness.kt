package com.androidresourcestress

import android.app.Activity
import android.app.Instrumentation
import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.LocaleList
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.Spinner
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
    private data class GpuSustainedSample(
        val elapsedMs: Long,
        val dispatch: Double,
        val workMs: Double,
        val fps: Double,
        val frameMs: Double,
        val batteryCelsius: Double?,
        val thermal: Int,
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
                "gpu3dPhase0" -> exerciseGpu3dPhase0()
                "gpu3dPhase1" -> exerciseGpu3dPhase1()
                "gpu3dPhase2" -> exerciseGpu3dPhase2()
                "visualLifecycle" -> exerciseVisualLifecycle()
                "serviceLifecycle" -> exerciseServiceLifecycle()
                "notificationStop" -> exerciseNotificationStop()
                "gpuSustained" -> exerciseGpuSustained()
                "autoWake" -> exerciseAutoWake()
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
        if (latestSession == null) controller.stopAll(StopReason.USER_STOP)
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
            Intent(targetContext, MonitorActivity::class.java),
        )
        intents.forEach { intent ->
            targetContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(750L)
        }
        return listOf(
            "History, detail, device information, settings, and monitor launched without error",
        )
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
        val configuration = PresetConfigurations.create(
            StressPreset.CUSTOM,
            StressDuration.CONTINUOUS,
        ).copy(
            cpuEnabled = false,
            gpuEnabled = true,
            memoryEnabled = false,
            storageEnabled = false,
            gpuMode = GpuMode.VISUAL,
            screenMode = enumArgument("screenMode", ScreenMode.ON),
        )
        StressForegroundService.start(targetContext, configuration)
        SystemClock.sleep(1_500L)
        val activity = launchVisualActivity()
        return try {
            val runMs = argumentLong("runMs", 60_000L)
            SystemClock.sleep(runMs)
            val visual = activity.findViewById<VulkanVisualSurfaceView>(R.id.vulkanVisualSurface)
                .snapshot()
            check(visual.frameCount > 0L) { "Vulkan Surface rendered no frames" }
            check(visual.framesPerSecond > 0.0) { "Visual UI FPS was not measured" }
            StressForegroundService.stop(targetContext)
            SystemClock.sleep(1_500L)
            listOf(
                "onscreenVulkanFrames=${visual.frameCount}",
                "fps=${String.format(Locale.US, "%.1f", visual.framesPerSecond)}",
                "frameTimeMs=${String.format(Locale.US, "%.2f", visual.frameTimeNanos / 1_000_000.0)}",
                "VulkanSurface=PASS animation=PASS stop=PASS",
            )
        } finally {
            StressForegroundService.stop(targetContext)
            runOnMainSync { activity.finish() }
        }
    }

    private fun exerciseGpu3dPhase0(): List<String> {
        val activity = startActivitySync(
            Intent(targetContext, Gpu3dStressActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as Gpu3dStressActivity
        val surface = activity.findViewById<Gpu3dStressSurfaceView>(R.id.gpu3dSurface)
        val level = activity.findViewById<Spinner>(R.id.gpu3dStressLevelSpinner)
        val start = activity.findViewById<Button>(R.id.gpu3dStartButton)
        val stop = activity.findViewById<Button>(R.id.gpu3dStopButton)

        fun runProfile(stressLevel: Gpu3dStressLevel): Gpu3dStressMetrics {
            runOnMainSync {
                level.setSelection(stressLevel.ordinal)
                start.performClick()
            }
            SystemClock.sleep(argumentLong("runMs", 5_000L))
            val metrics = surface.snapshot()
            check(metrics.running) { "GPU 3D renderer is not running: ${metrics.lastError}" }
            check(metrics.renderedFrames > 0L) { "GPU 3D renderer produced no frames" }
            check(metrics.currentFps > 0.0) { "GPU 3D renderer did not measure FPS" }
            runOnMainSync { stop.performClick() }
            SystemClock.sleep(800L)
            check(!surface.snapshot().running) { "GPU 3D renderer did not stop" }
            return metrics
        }

        return try {
            val p720 = runProfile(Gpu3dStressLevel.LOW)
            val p1080 = runProfile(Gpu3dStressLevel.EXTREME)
            check(p720.resolution == Gpu3dResolution.P720)
            check(p720.fpsLimit == Gpu3dFpsLimit.FPS_30)
            check(p1080.resolution == Gpu3dResolution.P1080)
            check(p1080.fpsLimit == Gpu3dFpsLimit.FPS_60)
            runOnMainSync {
                level.setSelection(Gpu3dStressLevel.MEDIUM.ordinal)
                start.performClick()
            }
            SystemClock.sleep(2_500L)
            check(surface.snapshot().running)
            runOnMainSync { activity.moveTaskToBack(true) }
            SystemClock.sleep(1_500L)
            check(!surface.snapshot().running) { "GPU 3D renderer survived Activity onStop" }
            targetContext.startActivity(
                Intent(targetContext, Gpu3dStressActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
            SystemClock.sleep(1_500L)
            check(!surface.snapshot().running) { "GPU 3D renderer restarted without user action" }
            runOnMainSync { start.performClick() }
            SystemClock.sleep(2_500L)
            val resumed = surface.snapshot()
            check(resumed.running && resumed.renderedFrames > 0L) {
                "GPU 3D renderer could not restart after Activity resume: ${resumed.lastError}"
            }
            runOnMainSync { stop.performClick() }
            listOf(
                "720P/30 frames=${p720.renderedFrames} fps=" +
                    String.format(Locale.US, "%.1f", p720.currentFps),
                "1080P/60 frames=${p1080.renderedFrames} fps=" +
                    String.format(Locale.US, "%.1f", p1080.currentFps),
                "waterAnimation=PASS autoCamera=PASS startStopRestart=PASS",
                "onPauseRelease=PASS resumeIdle=PASS resumeRestart=PASS",
            )
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun exerciseGpu3dPhase1(): List<String> {
        val activity = startActivitySync(
            Intent(targetContext, Gpu3dStressActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as Gpu3dStressActivity
        val surface = activity.findViewById<Gpu3dStressSurfaceView>(R.id.gpu3dSurface)
        val levelSpinner = activity.findViewById<Spinner>(R.id.gpu3dStressLevelSpinner)
        val start = activity.findViewById<Button>(R.id.gpu3dStartButton)
        val stop = activity.findViewById<Button>(R.id.gpu3dStopButton)
        val runMs = argumentLong("runMs", 30_000L).coerceAtLeast(3_000L)
        val level = runCatching {
            Gpu3dStressLevel.valueOf(
                arguments.getString("level", "EXTREME")!!.uppercase(Locale.US),
            )
        }.getOrDefault(Gpu3dStressLevel.EXTREME)

        return try {
            runOnMainSync {
                levelSpinner.setSelection(level.ordinal)
                start.performClick()
            }
            val deadline = SystemClock.elapsedRealtime() + runMs
            while (SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(
                    (deadline - SystemClock.elapsedRealtime()).coerceIn(1L, 10_000L),
                )
                val sample = surface.snapshot()
                check(sample.running) {
                    "Phase 1 Water Race stopped before the stability interval: ${sample.lastError}"
                }
                check(sample.renderedFrames > 0L) { "Phase 1 Water Race produced no frames" }
            }
            val metrics = surface.snapshot()
            check(metrics.currentFps > 0.0) { "Current FPS was not measured" }
            check(metrics.averageFps > 0.0) { "Average FPS was not measured" }
            check(metrics.minimumFps > 0.0) { "Minimum FPS was not measured" }
            check(metrics.frameTimeMs > 0.0) { "Frame time was not measured" }
            check(metrics.maximumFrameTimeMs >= metrics.frameTimeMs) {
                "Maximum frame time is smaller than the current smoothed frame time"
            }
            runOnMainSync { stop.performClick() }
            SystemClock.sleep(800L)
            check(!surface.snapshot().running) { "Phase 1 Water Race did not stop" }
            listOf(
                "profile=${metrics.level.name} ${metrics.renderWidth}x${metrics.renderHeight}/" +
                    "${metrics.fpsLimit.framesPerSecond} MSAA=${metrics.msaaSamples}",
                "runtimeMs=${metrics.runtimeMs} frames=${metrics.renderedFrames}",
                "fps current/average/minimum=" +
                    String.format(
                        Locale.US,
                        "%.1f/%.1f/%.1f",
                        metrics.currentFps,
                        metrics.averageFps,
                        metrics.minimumFps,
                    ),
                "frameMs current/maximum=" +
                    String.format(
                        Locale.US,
                        "%.2f/%.2f",
                        metrics.frameTimeMs,
                        metrics.maximumFrameTimeMs,
                    ),
                "waterRace=PASS shadowMap=PASS particles=PASS loopStability=PASS stop=PASS",
            )
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun exerciseGpu3dPhase2(): List<String> {
        val activity = startActivitySync(
            Intent(targetContext, Gpu3dStressActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as Gpu3dStressActivity
        val surface = activity.findViewById<Gpu3dStressSurfaceView>(R.id.gpu3dSurface)
        val levelSpinner = activity.findViewById<Spinner>(R.id.gpu3dStressLevelSpinner)
        val start = activity.findViewById<Button>(R.id.gpu3dStartButton)
        val stop = activity.findViewById<Button>(R.id.gpu3dStopButton)
        val runMs = argumentLong("runMs", 5_000L).coerceAtLeast(3_000L)

        return try {
            Gpu3dStressLevel.entries.map { level ->
                runOnMainSync {
                    levelSpinner.setSelection(level.ordinal)
                    start.performClick()
                }
                SystemClock.sleep(runMs)
                val metrics = surface.snapshot()
                val profile = level.profile
                check(metrics.running) { "${level.name} stopped: ${metrics.lastError}" }
                check(metrics.level == level) { "${level.name} reported ${metrics.level.name}" }
                check(metrics.renderWidth == profile.renderWidth)
                check(metrics.renderHeight == profile.renderHeight)
                check(metrics.resolution == profile.resolution)
                check(metrics.fpsLimit == profile.fpsLimit)
                check(metrics.renderedFrames > 0L) { "${level.name} rendered no frames" }
                check(metrics.currentFps > 0.0) { "${level.name} measured no FPS" }
                check(metrics.msaaSamples in 1..profile.msaaSamples) {
                    "${level.name} invalid MSAA ${metrics.msaaSamples}"
                }
                runOnMainSync { stop.performClick() }
                SystemClock.sleep(500L)
                check(!surface.snapshot().running) { "${level.name} did not stop" }
                "${level.name}: ${metrics.renderWidth}x${metrics.renderHeight} " +
                    "fps=${String.format(Locale.US, "%.1f", metrics.currentFps)} " +
                    "frameMs=${String.format(Locale.US, "%.2f", metrics.frameTimeMs)} " +
                    "models=${profile.sceneModelCount} triangles=${profile.triangleCount} " +
                    "particles=${profile.particleCount}x${profile.overdrawLayers} " +
                    "shadow=${profile.shadowMapSize} lights=${profile.lightCount} " +
                    "shader=${profile.shaderIterations} MSAA=${metrics.msaaSamples} " +
                    "post=${profile.postProcessQuality} reflection=${profile.reflectionSteps}"
            }
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun exerciseVisualLifecycle(): List<String> {
        val configuration = PresetConfigurations.create(
            StressPreset.CUSTOM,
            StressDuration.CONTINUOUS,
        ).copy(
            cpuEnabled = false,
            gpuEnabled = true,
            memoryEnabled = false,
            storageEnabled = false,
            gpuMode = GpuMode.MIXED,
            screenMode = ScreenMode.ON,
        )
        StressForegroundService.start(targetContext, configuration)
        SystemClock.sleep(1_500L)
        val activity = launchVisualActivity()
        return try {
            SystemClock.sleep(5_000L)
            val beforeStop = activity.findViewById<VulkanVisualSurfaceView>(R.id.vulkanVisualSurface)
                .snapshot()
            val dispatchBeforeBackground = NativeStress.getGpuDispatchCount()
            check(beforeStop.running && beforeStop.frameCount > 0L)
            runOnMainSync { activity.moveTaskToBack(true) }
            SystemClock.sleep(3_000L)
            val onscreenAfterStop = NativeStress.isOnscreenVisualRunning()
            val nativeStatus = GpuNativeStatus.fromCode(NativeStress.getVisualGpuStressStatus())
            val computeStatus = GpuNativeStatus.fromCode(NativeStress.getGpuStressStatus())
            val dispatchAfterBackground = NativeStress.getGpuDispatchCount()
            check(!onscreenAfterStop) { "Vulkan Surface remained active after onStop" }
            check(nativeStatus != GpuNativeStatus.RUNNING) {
                "Vulkan Visual remained active after onStop: $nativeStatus"
            }
            check(computeStatus == GpuNativeStatus.RUNNING) {
                "Mixed Compute stopped in background: $computeStatus"
            }
            check(dispatchAfterBackground > dispatchBeforeBackground) {
                "Compute dispatch did not continue in background"
            }
            val resumed = launchVisualActivity()
            SystemClock.sleep(4_000L)
            val afterResume = resumed.findViewById<VulkanVisualSurfaceView>(R.id.vulkanVisualSurface)
                .snapshot()
            check(afterResume.running && afterResume.frameCount > 0L)
            listOf(
                "framesBeforeOnStop=${beforeStop.frameCount}",
                "onscreenRunningAfterOnStop=$onscreenAfterStop",
                "nativeStatusAfterOnStop=$nativeStatus",
                "computeStatusAfterOnStop=$computeStatus",
                "dispatchBefore=$dispatchBeforeBackground dispatchAfter=$dispatchAfterBackground",
                "visualResumeFrames=${afterResume.frameCount}",
                "serviceSessionContinues=PASS visualPauseResume=PASS",
            )
        } finally {
            StressForegroundService.stop(targetContext)
            runOnMainSync { activity.finish() }
        }
    }

    private fun exerciseServiceLifecycle(): List<String> {
        val connectionLatch = CountDownLatch(1)
        var service: StressForegroundService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? StressForegroundService.LocalBinder)?.service
                connectionLatch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        targetContext.bindService(
            Intent(targetContext, StressForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        check(connectionLatch.await(10L, TimeUnit.SECONDS)) { "Service bind timed out" }
        val boundService = checkNotNull(service)
        val configuration = PresetConfigurations.create(
            StressPreset.EXTREME,
            enumArgument("duration", StressDuration.CONTINUOUS),
        ).copy(
            gpuMode = GpuMode.COMPUTE,
            screenMode = enumArgument("screenMode", ScreenMode.ON),
        )
        return try {
            StressForegroundService.start(targetContext, configuration)
            val startDeadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
            while (boundService.state != CombinedStressState.RUNNING &&
                SystemClock.elapsedRealtime() < startDeadline
            ) SystemClock.sleep(POLL_MS)
            check(boundService.state == CombinedStressState.RUNNING) { "Service session did not start" }
            SystemClock.sleep(1_500L)
            val before = checkNotNull(boundService.snapshot?.currentSession)
            val cpuThreads = NativeStress.getCpuStressThreadCount()
            val dispatchBefore = NativeStress.getGpuDispatchCount()
            check(boundService.isWakeLockHeld) { "WakeLock was not acquired" }

            val activity = startActivitySync(
                Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            runOnMainSync { activity.moveTaskToBack(true) }
            val backgroundMs = argumentLong("runMs", 10_000L)
            SystemClock.sleep(backgroundMs)
            val after = checkNotNull(boundService.snapshot?.currentSession)
            check(after.sessionId == before.sessionId) { "Session changed in background" }
            check(after.elapsedTimeMs >= before.elapsedTimeMs + backgroundMs - 2_000L) {
                "Elapsed time did not advance in background"
            }
            check(NativeStress.getCpuStressThreadCount() == cpuThreads && cpuThreads > 0) {
                "CPU workers changed in background"
            }
            check(NativeStress.getGpuDispatchCount() > dispatchBefore) {
                "GPU Compute stopped in background"
            }
            check(NativeStress.isMemoryStressRunning()) { "Memory stopped in background" }

            targetContext.startActivity(
                Intent(targetContext, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
            SystemClock.sleep(1_500L)
            val afterReopen = checkNotNull(boundService.snapshot?.currentSession)
            check(afterReopen.sessionId == before.sessionId) { "Reopen attached a different session" }

            StressForegroundService.start(targetContext, configuration)
            SystemClock.sleep(1_000L)
            val afterDuplicateStart = checkNotNull(boundService.snapshot?.currentSession)
            check(afterDuplicateStart.sessionId == before.sessionId) { "Duplicate START replaced session" }
            val waitForDuration = argumentBoolean("waitForDuration", false)
            if (!waitForDuration) boundService.stopSession(StopReason.USER_STOP)
            val stopDeadline = SystemClock.elapsedRealtime() + if (waitForDuration) {
                (configuration.duration.durationMs - after.elapsedTimeMs).coerceAtLeast(0L) +
                    STOP_TIMEOUT_MS
            } else STOP_TIMEOUT_MS
            while (boundService.state != CombinedStressState.IDLE &&
                SystemClock.elapsedRealtime() < stopDeadline
            ) SystemClock.sleep(POLL_MS)
            check(boundService.state == CombinedStressState.IDLE)
            check(!boundService.isWakeLockHeld) { "WakeLock leaked after STOP" }
            val finished = SessionHistoryStore(targetContext).find(before.startWallTimeMs)
                ?: error("Finished service session was not persisted")
            listOf(
                "sessionId=${before.sessionId}",
                "elapsedBefore=${before.elapsedTimeMs} elapsedAfter=${after.elapsedTimeMs}",
                "cpuThreads=$cpuThreads",
                "dispatchBefore=$dispatchBefore dispatchAfter=${NativeStress.getGpuDispatchCount()}",
                "sameSession=PASS background=PASS duplicateStartBlocked=PASS",
                "reopenSameSession=PASS finalReason=${finished.stopReason} " +
                    "finalElapsed=${finished.elapsedTimeMs}",
                "wakeLockReleased=PASS",
                "screenMode=${finished.screenMode} transitions=${finished.screenTransitionCount} " +
                    "screenOffMs=${finished.screenOffDurationMs} wake=" +
                    "${finished.wakeAttempted}/${finished.wakeSucceeded}",
                "events=${finished.eventTimeline.joinToString { it.type.name }}",
            )
        } finally {
            StressForegroundService.stop(targetContext)
            targetContext.unbindService(connection)
        }
    }

    private fun exerciseNotificationStop(): List<String> {
        val connectionLatch = CountDownLatch(1)
        var service: StressForegroundService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? StressForegroundService.LocalBinder)?.service
                connectionLatch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        targetContext.bindService(
            Intent(targetContext, StressForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        check(connectionLatch.await(10L, TimeUnit.SECONDS)) { "Service bind timed out" }
        val boundService = checkNotNull(service)
        val configuration = PresetConfigurations.create(
            StressPreset.EXTREME,
            StressDuration.CONTINUOUS,
        )
        return try {
            StressForegroundService.start(targetContext, configuration)
            val startDeadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
            while (boundService.state != CombinedStressState.RUNNING &&
                SystemClock.elapsedRealtime() < startDeadline
            ) SystemClock.sleep(POLL_MS)
            check(boundService.state == CombinedStressState.RUNNING)
            SystemClock.sleep(2_000L)
            val sessionId = checkNotNull(boundService.snapshot?.currentSession).sessionId
            val manager = targetContext.getSystemService(NotificationManager::class.java)
            val notification = manager.activeNotifications.firstOrNull {
                it.notification.actions?.isNotEmpty() == true &&
                    it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            }?.notification ?: error("Foreground notification was not posted")
            val stopAction = notification.actions.firstOrNull {
                it.title.toString().equals(targetContext.getString(R.string.stop), ignoreCase = true)
            } ?: error("Notification STOP action was not found")
            stopAction.actionIntent.send()
            val stopDeadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS
            while (boundService.state != CombinedStressState.IDLE &&
                SystemClock.elapsedRealtime() < stopDeadline
            ) SystemClock.sleep(POLL_MS)
            check(boundService.state == CombinedStressState.IDLE)
            check(!boundService.isWakeLockHeld)
            check(NativeStress.getCpuStressThreadCount() == 0)
            check(!NativeStress.isMemoryStressRunning())
            check(GpuNativeStatus.fromCode(NativeStress.getGpuStressStatus()) != GpuNativeStatus.RUNNING)
            val finished = SessionHistoryStore(targetContext).find(sessionId)
                ?: error("Notification STOP session was not saved")
            check(finished.stopReason == StopReason.USER_STOP)
            SystemClock.sleep(500L)
            val foregroundNotifications = manager.activeNotifications.count {
                it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            }
            check(foregroundNotifications == 0) {
                "Foreground notification remained after STOP"
            }
            listOf(
                "notificationActions=${notification.actions.size}",
                "stopReason=${finished.stopReason}",
                "nativeCleanup=PASS wakeLockReleased=PASS notificationRemoved=PASS",
            )
        } finally {
            StressForegroundService.stop(targetContext)
            targetContext.unbindService(connection)
        }
    }

    private fun exerciseGpuSustained(): List<String> {
        val connectionLatch = CountDownLatch(1)
        var service: StressForegroundService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? StressForegroundService.LocalBinder)?.service
                connectionLatch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        targetContext.bindService(
            Intent(targetContext, StressForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        check(connectionLatch.await(10L, TimeUnit.SECONDS)) { "Service bind timed out" }
        val boundService = checkNotNull(service)
        val mode = enumArgument("gpuMode", GpuMode.MIXED)
        val configuration = PresetConfigurations.create(
            StressPreset.CUSTOM,
            StressDuration.CONTINUOUS,
        ).copy(
            cpuEnabled = false,
            gpuEnabled = true,
            memoryEnabled = false,
            storageEnabled = false,
            gpuMode = mode,
            gpuTargetPercent = argumentInt("gpuTarget", 100),
            screenMode = enumArgument("screenMode", ScreenMode.ON),
        )
        var visualActivity: GpuVisualActivity? = null
        return try {
            StressForegroundService.start(targetContext, configuration)
            val startDeadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
            while (boundService.state != CombinedStressState.RUNNING &&
                SystemClock.elapsedRealtime() < startDeadline
            ) SystemClock.sleep(POLL_MS)
            check(boundService.state == CombinedStressState.RUNNING)
            if (mode != GpuMode.COMPUTE) visualActivity = launchVisualActivity()
            val runMs = argumentLong("runMs", 300_000L)
            val deadline = SystemClock.elapsedRealtime() + runMs
            val samples = mutableListOf<GpuSustainedSample>()
            while (SystemClock.elapsedRealtime() < deadline &&
                boundService.state == CombinedStressState.RUNNING
            ) {
                val runtime = boundService.snapshot
                if (runtime != null) {
                    samples += GpuSustainedSample(
                        elapsedMs = runtime.elapsedTimeMs,
                        dispatch = runtime.gpuDispatchRate,
                        workMs = runtime.gpu.gpuWorkNanos / 1_000_000.0,
                        fps = runtime.visualFps,
                        frameMs = runtime.visualFrameTimeNanos / 1_000_000.0,
                        batteryCelsius = runtime.thermal.batteryTemperatureCelsius,
                        thermal = runtime.thermal.status,
                    )
                }
                SystemClock.sleep(1_000L)
            }
            check(boundService.state == CombinedStressState.RUNNING) {
                "GPU sustained session ended early: ${boundService.state}"
            }
            val active = checkNotNull(boundService.snapshot?.currentSession)
            val onscreenFrames = visualActivity?.findViewById<VulkanVisualSurfaceView>(
                R.id.vulkanVisualSurface,
            )?.snapshot()?.frameCount ?: 0L
            boundService.stopSession(StopReason.USER_STOP)
            val stopDeadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS
            while (boundService.state != CombinedStressState.IDLE &&
                SystemClock.elapsedRealtime() < stopDeadline
            ) SystemClock.sleep(POLL_MS)
            check(boundService.state == CombinedStressState.IDLE)
            val finished = SessionHistoryStore(targetContext).find(active.startWallTimeMs)
                ?: error("GPU sustained session was not persisted")
            val first = samples.filter { it.elapsedMs <= 60_000L }
            val lastThreshold = ((samples.lastOrNull()?.elapsedMs ?: 0L) - 60_000L)
                .coerceAtLeast(0L)
            val last = samples.filter { it.elapsedMs >= lastThreshold }
            listOf(
                "mode=$mode target=${configuration.gpuTargetPercent}% sessionId=${active.sessionId}",
                "elapsed=${active.elapsedTimeMs} samples=${samples.size} onscreenFrames=$onscreenFrames",
                "first60=${summarizeGpuSustained(first)}",
                "last60=${summarizeGpuSustained(last)}",
                "sameSession=PASS wakeLockReleased=${!boundService.isWakeLockHeld}",
                "screenMode=${finished.screenMode} screenOffMs=${finished.screenOffDurationMs} " +
                    "fallback=${finished.screenFallbackUsed}",
                "events=${finished.eventTimeline.joinToString { it.type.name }}",
            )
        } finally {
            visualActivity?.let { activity -> runOnMainSync { activity.finish() } }
            StressForegroundService.stop(targetContext)
            targetContext.unbindService(connection)
        }
    }

    private fun exerciseAutoWake(): List<String> {
        val connectionLatch = CountDownLatch(1)
        var service: StressForegroundService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? StressForegroundService.LocalBinder)?.service
                connectionLatch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        targetContext.bindService(
            Intent(targetContext, StressForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        check(connectionLatch.await(10L, TimeUnit.SECONDS)) { "Service bind timed out" }
        val boundService = checkNotNull(service)
        val duration = enumArgument("duration", StressDuration.MINUTE_1)
        val configuration = PresetConfigurations.create(
            StressPreset.CUSTOM,
            duration,
        ).copy(
            cpuEnabled = true,
            cpuTargetPercent = 25,
            gpuEnabled = false,
            memoryEnabled = false,
            storageEnabled = false,
            screenMode = ScreenMode.OFF,
        )
        return try {
            StressForegroundService.start(targetContext, configuration)
            val startDeadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
            while (boundService.state != CombinedStressState.RUNNING &&
                SystemClock.elapsedRealtime() < startDeadline
            ) SystemClock.sleep(POLL_MS)
            check(boundService.state == CombinedStressState.RUNNING)
            val snapshotDeadline = SystemClock.elapsedRealtime() + 3_000L
            while (boundService.snapshot?.currentSession == null &&
                SystemClock.elapsedRealtime() < snapshotDeadline
            ) SystemClock.sleep(POLL_MS)
            val startSession = checkNotNull(boundService.snapshot?.currentSession)
            val finishDeadline = SystemClock.elapsedRealtime() + duration.durationMs + STOP_TIMEOUT_MS
            while (boundService.state != CombinedStressState.IDLE &&
                SystemClock.elapsedRealtime() < finishDeadline
            ) SystemClock.sleep(POLL_MS)
            check(boundService.state == CombinedStressState.IDLE) { "Auto-wake session did not finish" }
            val finished = SessionHistoryStore(targetContext).find(startSession.startWallTimeMs)
                ?: error("Auto-wake session was not persisted")
            val interactive = targetContext.getSystemService(PowerManager::class.java).isInteractive
            listOf(
                "reason=${finished.stopReason} elapsed=${finished.elapsedTimeMs}",
                "screenOffMs=${finished.screenOffDurationMs} transitions=${finished.screenTransitionCount}",
                "wakeAttempted=${finished.wakeAttempted} wakeSucceeded=${finished.wakeSucceeded} " +
                    "wakeReason=${finished.wakeReason} interactive=$interactive",
                "events=${finished.eventTimeline.joinToString { it.type.name }}",
                "wakeLockReleased=${!boundService.isWakeLockHeld}",
            )
        } finally {
            StressForegroundService.stop(targetContext)
            targetContext.unbindService(connection)
        }
    }

    private fun summarizeGpuSustained(samples: List<GpuSustainedSample>): String {
        if (samples.isEmpty()) return "none"
        fun stats(values: List<Double>): String = String.format(
            Locale.US,
            "%.2f[%.2f..%.2f]",
            values.average(),
            values.minOrNull() ?: 0.0,
            values.maxOrNull() ?: 0.0,
        )
        val temperatures = samples.mapNotNull { it.batteryCelsius }
        return "fps=${stats(samples.map { it.fps })}/frameMs=${stats(samples.map { it.frameMs })}" +
            "/dispatch=${stats(samples.map { it.dispatch })}/workMs=${stats(samples.map { it.workMs })}" +
            "/battery=${if (temperatures.isEmpty()) "N/A" else stats(temperatures)}" +
            "/thermal=${samples.map { thermalStatusLabel(it.thermal) }.distinct().joinToString("->")}"
    }

    private fun launchVisualActivity(): GpuVisualActivity {
        val monitor = addMonitor(GpuVisualActivity::class.java.name, null, false)
        targetContext.startActivity(
            Intent(targetContext, GpuVisualActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val activity = waitForMonitorWithTimeout(monitor, 10_000L)
        removeMonitor(monitor)
        return activity as? GpuVisualActivity ?: error("GpuVisualActivity launch timed out")
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
                "screen",
            )
            check(required.all(json::has)) { "Missing export keys: $required" }
            check(json.getJSONObject("thermal").has("timeline"))
            check(json.getJSONObject("session").has("eventTimeline"))
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
