package com.androidresourcestress

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Phase4DeviceHarness : Instrumentation(), CombinedStressController.Listener {
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
                "diagnosticExport" -> {
                    val file = DiagnosticLog(targetContext).export()
                    listOf("${file.name}: ${file.length()} bytes")
                }
                "matrix" -> matrixConfigurations().map { (name, configuration) ->
                    "$name: ${runSession(configuration, argumentLong("runMs", 2_000L))}"
                }
                "extremeCycles" -> (1..argumentInt("cycles", 20)).map { cycle ->
                    "cycle-$cycle: ${runSession(
                        PresetConfigurations.create(
                            StressPreset.EXTREME,
                            StressDuration.CONTINUOUS,
                        ),
                        argumentLong("runMs", 1_000L),
                    )}"
                }
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
            controller.runtimeSnapshot()
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
        return String.format(
            Locale.US,
            "reason=%s elapsed=%d cpu=%.1f gpu=%.1f mem=%.1f storageR=%d storageW=%d thermal=%s",
            session.stopReason,
            session.elapsedTimeMs,
            session.peakCpuLoadPercent,
            session.peakDispatchRate,
            session.peakMemoryActivityBytesPerSecond,
            session.storageBytesRead,
            session.storageBytesWritten,
            thermalStatusLabel(session.highestThermalStatus),
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
