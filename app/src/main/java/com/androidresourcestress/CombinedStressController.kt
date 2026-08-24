package com.androidresourcestress

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class CombinedStressController(
    context: Context,
    private val logicalCoreCount: Int,
    listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onCombinedStateChanged(state: CombinedStressState)
        fun onGpuInfoAvailable(info: GpuInfo)
        fun onSessionFinished(session: StressSessionSnapshot)
        fun onCombinedError(message: String)
    }

    private data class MutableSession(
        val sessionId: Long,
        val startWallTimeMs: Long,
        val startElapsedTimeMs: Long,
        val configuration: CombinedStressConfiguration,
        val resolvedMemoryTargetBytes: Long,
        var allocatedMemoryBytes: Long,
        var peakCpuLoadPercent: Double = 0.0,
        var peakCoreEquivalentPercent: Double = 0.0,
        var peakAppPssBytes: Long = 0L,
        var peakNativePssBytes: Long = 0L,
        var peakMemoryActivityBytesPerSecond: Double = 0.0,
        var peakDispatchRate: Double = 0.0,
        var gpuWorkTimeTotalNanos: Double = 0.0,
        var gpuWorkTimeSampleCount: Long = 0L,
        var storageWorkingSetBytes: Long = 0L,
        var storageBytesRead: Long = 0L,
        var storageBytesWritten: Long = 0L,
        var peakStorageReadActivityBytesPerSecond: Double = 0.0,
        var peakStorageWriteActivityBytesPerSecond: Double = 0.0,
        val startBatteryTemperatureCelsius: Double?,
        var peakBatteryTemperatureCelsius: Double?,
        var highestThermalStatus: Int,
        val thermalTimeline: MutableList<ThermalEvent>,
        var lastThermalStatus: Int,
        val cpuFrequencyObservations:
            MutableMap<String, MutableCpuFrequencyObservation> = linkedMapOf(),
        var startPowerObservation: PowerObservation? = null,
        var endPowerObservation: PowerObservation? = null,
        var peakEstimatedBatteryPowerWatts: Double? = null,
        var peakVisualFps: Double = 0.0,
        var visualFrameTimeTotalNanos: Double = 0.0,
        var visualFrameTimeSampleCount: Long = 0L,
        var stopReason: StopReason? = null,
        var lastError: String? = null,
    ) {
        fun snapshot(nowElapsedTimeMs: Long): StressSessionSnapshot = StressSessionSnapshot(
            sessionId = sessionId,
            startWallTimeMs = startWallTimeMs,
            elapsedTimeMs = (nowElapsedTimeMs - startElapsedTimeMs).coerceAtLeast(0L),
            configuration = configuration,
            resolvedMemoryTargetBytes = resolvedMemoryTargetBytes,
            allocatedMemoryBytes = allocatedMemoryBytes,
            peakCpuLoadPercent = peakCpuLoadPercent,
            peakCoreEquivalentPercent = peakCoreEquivalentPercent,
            peakAppPssBytes = peakAppPssBytes,
            peakNativePssBytes = peakNativePssBytes,
            peakMemoryActivityBytesPerSecond = peakMemoryActivityBytesPerSecond,
            peakDispatchRate = peakDispatchRate,
            averageGpuWorkTimeNanos = if (gpuWorkTimeSampleCount > 0L) {
                gpuWorkTimeTotalNanos / gpuWorkTimeSampleCount
            } else {
                0.0
            },
            storageWorkingSetBytes = storageWorkingSetBytes,
            storageBytesRead = storageBytesRead,
            storageBytesWritten = storageBytesWritten,
            peakStorageReadActivityBytesPerSecond =
                peakStorageReadActivityBytesPerSecond,
            peakStorageWriteActivityBytesPerSecond =
                peakStorageWriteActivityBytesPerSecond,
            startBatteryTemperatureCelsius = startBatteryTemperatureCelsius,
            peakBatteryTemperatureCelsius = peakBatteryTemperatureCelsius,
            highestThermalStatus = highestThermalStatus,
            stopReason = stopReason,
            lastError = lastError,
            thermalTimeline = thermalTimeline.toList(),
            cpuFrequencyObservations = cpuFrequencyObservations.values.map { it.snapshot() },
            startPowerObservation = startPowerObservation,
            endPowerObservation = endPowerObservation,
            peakEstimatedBatteryPowerWatts = peakEstimatedBatteryPowerWatts,
            peakVisualFps = peakVisualFps,
            averageVisualFrameTimeNanos = if (visualFrameTimeSampleCount > 0L) {
                visualFrameTimeTotalNanos / visualFrameTimeSampleCount
            } else {
                0.0
            },
        )
    }

    private data class MutableCpuFrequencyObservation(
        val policy: String,
        val startHz: Long?,
        var minimumObservedHz: Long?,
        var peakObservedHz: Long?,
        var endHz: Long?,
    ) {
        fun record(currentHz: Long?) {
            if (currentHz == null || currentHz <= 0L) return
            minimumObservedHz = minimumObservedHz?.let { min(it, currentHz) } ?: currentHz
            peakObservedHz = peakObservedHz?.let { max(it, currentHz) } ?: currentHz
            endHz = currentHz
        }

        fun snapshot(): CpuFrequencySessionObservation = CpuFrequencySessionObservation(
            policy = policy,
            startHz = startHz,
            minimumObservedHz = minimumObservedHz,
            peakObservedHz = peakObservedHz,
            endHz = endHz,
        )
    }

    private class StartCancelled : RuntimeException()

    private val lock = Any()
    private val metricsLock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "combined-stress-controller").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memoryMonitor = MemoryMonitor(context.applicationContext)
    private val deviceMonitor = DeviceMonitor(context.applicationContext)
    private val hardwareMonitor = HardwareMonitorService(context.applicationContext)
    private val cpuMonitor = CpuMonitor(logicalCoreCount)
    private val memoryActivityMonitor = MemoryActivityMonitor()
    private val gpuActivityMonitor = GpuActivityMonitor()
    private val diagnosticLog = DiagnosticLog(context.applicationContext)
    private val historyStore = SessionHistoryStore(context.applicationContext)
    private val preferences = AppPreferences(context.applicationContext)
    private val storageStress = StorageStress(context.applicationContext) { event ->
        logInfo(event)
    }
    private var listener: Listener? = listener
    private var generation = 0L
    private var closed = false
    private var currentSession: MutableSession? = null
    private var lastSession: StressSessionSnapshot? = null
    private var pendingStopReason = StopReason.USER
    private var lastLoggedThermalStatus: Int? = null

    @Volatile
    private var visualFps = 0.0

    @Volatile
    private var visualFrameTimeNanos = 0.0

    @Volatile
    var state: CombinedStressState = CombinedStressState.IDLE
        private set

    @Volatile
    var gpuInfo: GpuInfo? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun initialize() {
        synchronized(lock) {
            if (closed) return
        }
        executor.execute {
            val info = initializeGpuAndReadInfo()
            synchronized(lock) {
                if (closed) return@execute
                gpuInfo = info
            }
            postToMain { listener?.onGpuInfoAvailable(info) }
        }
    }

    fun start(configuration: CombinedStressConfiguration) {
        val operationGeneration = synchronized(lock) {
            if (closed || state != CombinedStressState.IDLE) return
            generation += 1L
            state = CombinedStressState.STARTING
            lastError = null
            pendingStopReason = StopReason.USER
            lastLoggedThermalStatus = null
            visualFps = 0.0
            visualFrameTimeNanos = 0.0
            generation
        }
        postState(CombinedStressState.STARTING)
        executor.execute { performStart(operationGeneration, configuration) }
    }

    fun stopAll(reason: StopReason = StopReason.USER) {
        val scheduleStop = synchronized(lock) {
            if (closed || state == CombinedStressState.IDLE) return
            pendingStopReason = higherPriorityReason(pendingStopReason, reason)
            if (state == CombinedStressState.STOPPING) {
                false
            } else {
                generation += 1L
                state = CombinedStressState.STOPPING
                true
            }
        }
        if (!scheduleStop) return
        postState(CombinedStressState.STOPPING)
        executor.execute {
            cleanupNativeResources()
            finishCurrentSession(reason = synchronized(lock) { pendingStopReason })
            val changed = synchronized(lock) {
                if (closed) {
                    false
                } else {
                    state = CombinedStressState.IDLE
                    true
                }
            }
            if (changed) postState(CombinedStressState.IDLE)
        }
    }

    fun runtimeSnapshot(): CombinedRuntimeSnapshot {
        val memory = runCatching { memoryMonitor.sample() }.getOrElse {
            MemorySnapshot(0L, 0L, 0L, 0L, 0L, false, 0L)
        }
        val cpuLoad = synchronized(metricsLock) { cpuMonitor.sample() }
        val cpuThreads = nativeCpuThreadCount()
        val allocated = nativeAllocatedMemoryBytes()
        val processed = nativeProcessedMemoryBytes()
        val memoryActivity = synchronized(metricsLock) {
            memoryActivityMonitor.sample(processed)
        }
        val gpu = nativeGpuSnapshot()
        val visualGpuStatus = nativeVisualGpuStatus()
        val visualGpuFrameCount = nativeVisualGpuFrameCount()
        val visualGpuFrameWorkNanos = nativeVisualGpuFrameWorkNanos()
        val gpuActivity = synchronized(metricsLock) { gpuActivityMonitor.sample(gpu) }
        val storage = storageStress.snapshot()
        val thermal = runCatching { deviceMonitor.thermalSnapshot() }
            .getOrElse { ThermalSnapshot(null, PowerManager.THERMAL_STATUS_NONE) }
        val hardware = hardwareMonitor.snapshot()
        val now = SystemClock.elapsedRealtime()

        var activeSession: StressSessionSnapshot?
        var recentSession: StressSessionSnapshot?
        var controllerState: CombinedStressState
        var errorToStop: String? = null
        var thermalStop = false
        var durationStop = false
        synchronized(lock) {
            currentSession?.let { session ->
                session.peakCpuLoadPercent = max(session.peakCpuLoadPercent, cpuLoad.appCpuLoadPercent)
                session.peakCoreEquivalentPercent = max(
                    session.peakCoreEquivalentPercent,
                    cpuLoad.coreEquivalentPercent,
                )
                session.peakAppPssBytes = max(session.peakAppPssBytes, memory.appPssBytes)
                session.peakNativePssBytes = max(session.peakNativePssBytes, memory.nativePssBytes)
                session.peakMemoryActivityBytesPerSecond = max(
                    session.peakMemoryActivityBytesPerSecond,
                    memoryActivity,
                )
                session.peakDispatchRate = max(
                    session.peakDispatchRate,
                    gpuActivity.dispatchesPerSecond,
                )
                if (gpu.status == GpuNativeStatus.RUNNING && gpu.gpuWorkNanos > 0L) {
                    session.gpuWorkTimeTotalNanos += gpu.gpuWorkNanos.toDouble()
                    session.gpuWorkTimeSampleCount += 1L
                }
                session.storageBytesRead = max(session.storageBytesRead, storage.bytesRead)
                session.storageBytesWritten = max(
                    session.storageBytesWritten,
                    storage.bytesWritten,
                )
                session.peakStorageReadActivityBytesPerSecond = max(
                    session.peakStorageReadActivityBytesPerSecond,
                    storage.readActivityBytesPerSecond,
                )
                session.peakStorageWriteActivityBytesPerSecond = max(
                    session.peakStorageWriteActivityBytesPerSecond,
                    storage.writeActivityBytesPerSecond,
                )
                session.highestThermalStatus = max(session.highestThermalStatus, thermal.status)
                thermal.batteryTemperatureCelsius?.let { temperature ->
                    session.peakBatteryTemperatureCelsius = max(
                        session.peakBatteryTemperatureCelsius ?: temperature,
                        temperature,
                    )
                }
                recordThermalEventIfChanged(session, thermal, now)
                recordHardwareObservation(session, hardware, now)
                session.peakVisualFps = max(session.peakVisualFps, visualFps)
                if (visualFrameTimeNanos > 0.0) {
                    session.visualFrameTimeTotalNanos += visualFrameTimeNanos
                    session.visualFrameTimeSampleCount += 1L
                }

                if (state == CombinedStressState.RUNNING) {
                    errorToStop = runtimeResourceError(
                        session.configuration,
                        cpuThreads,
                        allocated,
                        gpu,
                        visualGpuStatus,
                        storage,
                    )
                    thermalStop = thermal.requiresImmediateStop
                    durationStop = session.configuration.duration.durationMs > 0L &&
                        now - session.startElapsedTimeMs >=
                        session.configuration.duration.durationMs
                }
            }
            activeSession = currentSession?.snapshot(now)
            recentSession = lastSession
            controllerState = state
        }

        when {
            thermalStop -> triggerThermalStop(thermal.statusLabel)
            errorToStop != null -> triggerRuntimeError(errorToStop!!)
            durationStop -> stopAll(StopReason.DURATION_COMPLETED)
        }

        return CombinedRuntimeSnapshot(
            state = controllerState,
            elapsedTimeMs = activeSession?.elapsedTimeMs ?: 0L,
            currentSession = activeSession,
            lastSession = recentSession,
            cpuLoadPercent = cpuLoad.appCpuLoadPercent,
            coreEquivalentPercent = cpuLoad.coreEquivalentPercent,
            cpuThreadCount = cpuThreads,
            memory = memory,
            allocatedMemoryBytes = allocated,
            memoryActivityBytesPerSecond = memoryActivity,
            gpu = gpu,
            gpuDispatchRate = if (gpu.status == GpuNativeStatus.RUNNING) {
                gpuActivity.dispatchesPerSecond
            } else {
                0.0
            },
            gpuWorkGroupsPerSecond = if (gpu.status == GpuNativeStatus.RUNNING) {
                gpuActivity.workGroupsPerSecond
            } else {
                0.0
            },
            storage = storage,
            thermal = thermal,
            lastError = lastError,
            hardware = hardware,
            visualFps = visualFps,
            visualFrameTimeNanos = visualFrameTimeNanos,
            visualVulkanFrameCount = visualGpuFrameCount,
            visualVulkanFrameWorkNanos = visualGpuFrameWorkNanos,
        )
    }

    fun updateVisualMetrics(fps: Double, frameTimeNanos: Double) {
        visualFps = fps.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        visualFrameTimeNanos = frameTimeNanos.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    }

    private fun performStart(
        operationGeneration: Long,
        configuration: CombinedStressConfiguration,
    ) {
        try {
            validateConfiguration(configuration)
            val thermal = deviceMonitor.thermalSnapshot()
            if (thermal.requiresImmediateStop) {
                throw IllegalStateException(
                    "Thermal status is ${thermal.statusLabel}; stress start refused",
                )
            }

            val memoryBeforeStart = memoryMonitor.sample()
            val resolvedMemoryTarget = if (configuration.memoryEnabled) {
                resolveMemoryTarget(configuration.memoryTarget, memoryBeforeStart)
            } else {
                0L
            }
            val startWallTimeMs = System.currentTimeMillis()
            val startHardware = hardwareMonitor.snapshot()
            val startElapsedTimeMs = SystemClock.elapsedRealtime()
            val session = MutableSession(
                sessionId = startWallTimeMs,
                startWallTimeMs = startWallTimeMs,
                startElapsedTimeMs = startElapsedTimeMs,
                configuration = configuration,
                resolvedMemoryTargetBytes = resolvedMemoryTarget,
                allocatedMemoryBytes = 0L,
                startBatteryTemperatureCelsius = thermal.batteryTemperatureCelsius,
                peakBatteryTemperatureCelsius = thermal.batteryTemperatureCelsius,
                highestThermalStatus = thermal.status,
                thermalTimeline = mutableListOf(
                    ThermalEvent(0L, thermal.status, thermal.batteryTemperatureCelsius),
                ),
                lastThermalStatus = thermal.status,
                startPowerObservation = powerObservation(startHardware.battery, 0L),
                endPowerObservation = powerObservation(startHardware.battery, 0L),
                peakEstimatedBatteryPowerWatts =
                    startHardware.battery.estimatedBatteryPowerWatts,
            )
            startHardware.cpuFrequencies.forEach { policy ->
                session.cpuFrequencyObservations[policy.policy] =
                    MutableCpuFrequencyObservation(
                        policy = policy.policy,
                        startHz = policy.currentHz,
                        minimumObservedHz = policy.currentHz,
                        peakObservedHz = policy.currentHz,
                        endHz = policy.currentHz,
                    )
            }
            synchronized(lock) {
                ensureStartActive(operationGeneration)
                currentSession = session
            }
            logInfo(
                "StressSession START preset=${configuration.preset} " +
                    "cpu=${configuration.cpuEnabled}/${configuration.cpuTargetPercent}% " +
                    "gpu=${configuration.gpuEnabled}/${configuration.gpuTargetPercent}% " +
                    "memory=${configuration.memoryEnabled}/$resolvedMemoryTarget " +
                    "storage=${configuration.storageEnabled}/" +
                    "${configuration.storageMode}/${configuration.storageLevel} " +
                    "duration=${configuration.duration}",
            )

            if (configuration.memoryEnabled) {
                val reserveBytes = max(
                    memoryBeforeStart.lowMemoryThresholdBytes,
                    MIN_SYSTEM_RESERVE_BYTES,
                )
                val allocated = NativeStress.startMemoryStressSafely(
                    resolvedMemoryTarget,
                    reserveBytes,
                )
                if (allocated <= 0L) {
                    throw IllegalStateException("Native memory allocation failed")
                }
                if (!NativeStress.isMemoryStressRunning()) {
                    throw IllegalStateException("Memory stress worker did not enter RUNNING")
                }
                session.allocatedMemoryBytes = allocated
                logInfo("Memory started: allocated=$allocated target=$resolvedMemoryTarget")
                ensureStartActive(operationGeneration)
            }

            if (configuration.storageEnabled) {
                val workingSet = storageStress.start(
                    configuration.storageMode,
                    configuration.storageLevel,
                )
                if (workingSet <= 0L || !storageStress.isRunning()) {
                    throw IllegalStateException("Storage stress worker did not enter RUNNING")
                }
                session.storageWorkingSetBytes = workingSet
                logInfo(
                    "Storage started: mode=${configuration.storageMode} " +
                        "level=${configuration.storageLevel} workingSet=$workingSet",
                )
                ensureStartActive(operationGeneration)
            }

            if (configuration.gpuEnabled && configuration.gpuMode != GpuMode.VISUAL) {
                val info = gpuInfo ?: initializeGpuAndReadInfo().also { detected ->
                    gpuInfo = detected
                    postToMain { listener?.onGpuInfoAvailable(detected) }
                }
                if (!info.supported || !info.computeQueueSupported) {
                    throw IllegalStateException("Vulkan compute is unsupported")
                }
                if (!NativeStress.startGpuStress(configuration.gpuTargetPercent)) {
                    throw IllegalStateException(nativeGpuError("GPU stress could not be started"))
                }
                if (GpuNativeStatus.fromCode(NativeStress.getGpuStressStatus()) !=
                    GpuNativeStatus.RUNNING
                ) {
                    throw IllegalStateException(nativeGpuError("GPU worker did not enter RUNNING"))
                }
                logInfo(
                    "GPU compute started: mode=${configuration.gpuMode} " +
                        "target=${configuration.gpuTargetPercent}%",
                )
                ensureStartActive(operationGeneration)
            }
            if (configuration.gpuEnabled && configuration.gpuMode != GpuMode.COMPUTE) {
                if (!NativeStress.startVisualGpuStress(configuration.gpuTargetPercent)) {
                    throw IllegalStateException(
                        runCatching { NativeStress.getVisualGpuLastError() }
                            .getOrDefault("Vulkan visual stress could not be started"),
                    )
                }
                if (nativeVisualGpuStatus() != GpuNativeStatus.RUNNING) {
                    throw IllegalStateException("Vulkan visual worker did not enter RUNNING")
                }
                logInfo(
                    "GPU Vulkan visual started: mode=${configuration.gpuMode} " +
                        "target=${configuration.gpuTargetPercent}%",
                )
                ensureStartActive(operationGeneration)
            }

            if (configuration.cpuEnabled) {
                if (!NativeStress.startCpuStress(logicalCoreCount, configuration.cpuTargetPercent)) {
                    throw IllegalStateException("CPU stress workers could not be started")
                }
                if (NativeStress.getCpuStressThreadCount() <= 0) {
                    throw IllegalStateException("CPU workers did not enter RUNNING")
                }
                logInfo(
                    "CPU started: threads=$logicalCoreCount target=${configuration.cpuTargetPercent}%",
                )
                ensureStartActive(operationGeneration)
            }

            synchronized(metricsLock) {
                cpuMonitor.reset()
                memoryActivityMonitor.reset(nativeProcessedMemoryBytes())
                gpuActivityMonitor.reset(nativeGpuSnapshot())
            }
            val changed = synchronized(lock) {
                ensureStartActive(operationGeneration)
                state = CombinedStressState.RUNNING
                true
            }
            if (changed) postState(CombinedStressState.RUNNING)
        } catch (_: StartCancelled) {
            cleanupNativeResources()
        } catch (error: Throwable) {
            handleStartFailure(operationGeneration, error)
        }
    }

    private fun handleStartFailure(operationGeneration: Long, error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        val shouldHandle = synchronized(lock) {
            if (closed || generation != operationGeneration ||
                state == CombinedStressState.STOPPING
            ) {
                false
            } else {
                state = CombinedStressState.ERROR
                lastError = message
                currentSession?.lastError = message
                currentSession?.stopReason = StopReason.RESOURCE_ERROR
                true
            }
        }
        cleanupNativeResources()
        if (!shouldHandle) return
        logError("StressSession ERROR: $message")
        postState(CombinedStressState.ERROR)
        postToMain { listener?.onCombinedError(message) }
        finishCurrentSession(StopReason.RESOURCE_ERROR, message)
        val changed = synchronized(lock) {
            if (closed) false else {
                state = CombinedStressState.IDLE
                true
            }
        }
        if (changed) postState(CombinedStressState.IDLE)
    }

    private fun triggerThermalStop(statusLabel: String) {
        val changed = synchronized(lock) {
            if (closed || state != CombinedStressState.RUNNING) {
                false
            } else {
                state = CombinedStressState.THERMAL_LIMITED
                true
            }
        }
        if (!changed) return
        logError("Thermal $statusLabel: stopping all stress resources")
        postState(CombinedStressState.THERMAL_LIMITED)
        stopAll(StopReason.THERMAL)
    }

    private fun triggerRuntimeError(message: String) {
        val changed = synchronized(lock) {
            if (closed || state != CombinedStressState.RUNNING) {
                false
            } else {
                generation += 1L
                state = CombinedStressState.ERROR
                lastError = message
                currentSession?.lastError = message
                currentSession?.stopReason = StopReason.RESOURCE_ERROR
                true
            }
        }
        if (!changed) return
        logError("Runtime resource error: $message")
        postState(CombinedStressState.ERROR)
        postToMain { listener?.onCombinedError(message) }
        executor.execute {
            cleanupNativeResources()
            finishCurrentSession(StopReason.RESOURCE_ERROR, message)
            val idle = synchronized(lock) {
                if (closed) false else {
                    state = CombinedStressState.IDLE
                    true
                }
            }
            if (idle) postState(CombinedStressState.IDLE)
        }
    }

    private fun validateConfiguration(configuration: CombinedStressConfiguration) {
        if (!configuration.cpuEnabled && !configuration.gpuEnabled &&
            !configuration.memoryEnabled && !configuration.storageEnabled
        ) {
            throw IllegalArgumentException("Select at least one stress resource")
        }
        if (configuration.cpuTargetPercent !in VALID_TARGETS ||
            configuration.gpuTargetPercent !in VALID_TARGETS
        ) {
            throw IllegalArgumentException("CPU/GPU target must be 25, 50, 75, or 100")
        }
    }

    private fun resolveMemoryTarget(
        target: MemoryTarget,
        memory: MemorySnapshot,
    ): Long {
        if (memory.lowMemory) {
            throw IllegalStateException("Android reports low memory; memory stress refused")
        }
        val reserveBytes = max(memory.lowMemoryThresholdBytes, MIN_SYSTEM_RESERVE_BYTES)
        val maximumSafeTarget = (memory.availableBytes - reserveBytes)
            .coerceAtLeast(0L)
            .floorToMib()
        val requestedTarget = target.fixedBytes ?: min(
            memory.availableBytes / AUTO_MEMORY_DIVISOR,
            MAX_AUTO_TARGET_BYTES,
        ).floorToMib()
        val resolved = min(requestedTarget, maximumSafeTarget).floorToMib()
        if (resolved < MIN_NATIVE_TARGET_BYTES) {
            throw IllegalStateException("Not enough safely available RAM for memory stress")
        }
        return resolved
    }

    private fun runtimeResourceError(
        configuration: CombinedStressConfiguration,
        cpuThreads: Int,
        allocatedMemoryBytes: Long,
        gpu: GpuSnapshot,
        visualGpuStatus: GpuNativeStatus,
        storage: StorageRuntimeSnapshot,
    ): String? = when {
        configuration.cpuEnabled && cpuThreads <= 0 -> "CPU stress worker stopped unexpectedly"
        configuration.memoryEnabled && allocatedMemoryBytes <= 0L ->
            "Memory stress allocation disappeared unexpectedly"
        configuration.memoryEnabled && !nativeMemoryRunning() ->
            "Memory stress worker stopped unexpectedly"
        configuration.gpuEnabled && configuration.gpuMode != GpuMode.VISUAL &&
            gpu.status == GpuNativeStatus.ERROR ->
            nativeGpuError("GPU worker stopped after a Vulkan error")
        configuration.gpuEnabled && configuration.gpuMode != GpuMode.VISUAL &&
            gpu.status != GpuNativeStatus.RUNNING ->
            "GPU stress worker stopped unexpectedly"
        configuration.gpuEnabled && configuration.gpuMode != GpuMode.COMPUTE &&
            visualGpuStatus == GpuNativeStatus.ERROR ->
            runCatching { NativeStress.getVisualGpuLastError() }
                .getOrDefault("Vulkan visual worker stopped after an error")
        configuration.gpuEnabled && configuration.gpuMode != GpuMode.COMPUTE &&
            visualGpuStatus != GpuNativeStatus.RUNNING ->
            "Vulkan visual worker stopped unexpectedly"
        configuration.storageEnabled && storage.status == StorageStressStatus.ERROR ->
            storage.lastError ?: "Storage stress worker stopped after an I/O error"
        configuration.storageEnabled && !storageStress.isRunning() ->
            "Storage stress worker stopped unexpectedly"
        else -> null
    }

    private fun cleanupNativeResources() {
        runCatching { NativeStress.stopCpuStress() }
        runCatching { NativeStress.stopGpuStress() }
        runCatching { NativeStress.stopVisualGpuStress() }
        runCatching { NativeStress.stopMemoryStress() }
        val finalStorage = runCatching { storageStress.stop() }.getOrNull()
        if (finalStorage != null) {
            synchronized(lock) {
                currentSession?.let { session ->
                    session.storageWorkingSetBytes = max(
                        session.storageWorkingSetBytes,
                        finalStorage.workingSetBytes,
                    )
                    session.storageBytesRead = max(
                        session.storageBytesRead,
                        finalStorage.bytesRead,
                    )
                    session.storageBytesWritten = max(
                        session.storageBytesWritten,
                        finalStorage.bytesWritten,
                    )
                    session.peakStorageReadActivityBytesPerSecond = max(
                        session.peakStorageReadActivityBytesPerSecond,
                        finalStorage.readActivityBytesPerSecond,
                    )
                    session.peakStorageWriteActivityBytesPerSecond = max(
                        session.peakStorageWriteActivityBytesPerSecond,
                        finalStorage.writeActivityBytesPerSecond,
                    )
                }
            }
        }
    }

    private fun finishCurrentSession(reason: StopReason, error: String? = null) {
        val finished = synchronized(lock) {
            val session = currentSession ?: return@synchronized null
            session.stopReason = higherPriorityReason(session.stopReason ?: reason, reason)
            if (error != null) session.lastError = error
            val snapshot = session.snapshot(SystemClock.elapsedRealtime())
            currentSession = null
            lastSession = snapshot
            snapshot
        } ?: return
        runCatching { historyStore.add(finished, preferences.historyLimit) }
            .onFailure { logError("History persistence failed: ${it.message}") }
        logInfo(
            "StressSession STOP reason=${finished.stopReason} " +
                "elapsed=${finished.elapsedTimeMs}ms " +
                "peakCpu=${finished.peakCpuLoadPercent} " +
                "peakCore=${finished.peakCoreEquivalentPercent} " +
                "peakDispatch=${finished.peakDispatchRate} " +
                "peakMemoryBps=${finished.peakMemoryActivityBytesPerSecond} " +
                "peakAppPss=${finished.peakAppPssBytes} " +
                "peakNativePss=${finished.peakNativePssBytes} " +
                "storageRead=${finished.storageBytesRead} " +
                "storageWritten=${finished.storageBytesWritten} " +
                "peakStorageReadBps=${finished.peakStorageReadActivityBytesPerSecond} " +
                "peakStorageWriteBps=${finished.peakStorageWriteActivityBytesPerSecond} " +
                "startBattery=${finished.startBatteryTemperatureCelsius} " +
                "peakBattery=${finished.peakBatteryTemperatureCelsius} " +
                "highestThermal=${thermalStatusLabel(finished.highestThermalStatus)}",
        )
        postToMain { listener?.onSessionFinished(finished) }
    }

    private fun ensureStartActive(operationGeneration: Long) {
        synchronized(lock) {
            if (closed || generation != operationGeneration ||
                state != CombinedStressState.STARTING
            ) {
                throw StartCancelled()
            }
        }
    }

    private fun initializeGpuAndReadInfo(): GpuInfo {
        return GpuInfoReader.read()
    }

    private fun nativeGpuSnapshot(): GpuSnapshot = runCatching {
        GpuSnapshot(
            status = GpuNativeStatus.fromCode(NativeStress.getGpuStressStatus()),
            dispatchCount = NativeStress.getGpuDispatchCount().coerceAtLeast(0L),
            workGroupCount = NativeStress.getGpuWorkGroupCount().coerceAtLeast(0L),
            gpuWorkNanos = NativeStress.getGpuWorkTimeNanos().coerceAtLeast(0L),
            outputChecksum = NativeStress.getGpuOutputChecksum(),
        )
    }.getOrElse { GpuSnapshot(GpuNativeStatus.ERROR, 0L, 0L, 0L, 0L) }

    private fun nativeCpuThreadCount(): Int = runCatching {
        NativeStress.getCpuStressThreadCount()
    }.getOrDefault(0)

    private fun nativeAllocatedMemoryBytes(): Long = runCatching {
        NativeStress.getAllocatedMemoryBytes()
    }.getOrDefault(0L)

    private fun nativeProcessedMemoryBytes(): Long = runCatching {
        NativeStress.getProcessedMemoryBytes()
    }.getOrDefault(0L)

    private fun nativeMemoryRunning(): Boolean = runCatching {
        NativeStress.isMemoryStressRunning()
    }.getOrDefault(false)

    private fun nativeGpuError(fallback: String): String = runCatching {
        NativeStress.getGpuLastError().ifBlank { fallback }
    }.getOrDefault(fallback)

    private fun nativeVisualGpuStatus(): GpuNativeStatus = runCatching {
        GpuNativeStatus.fromCode(NativeStress.getVisualGpuStressStatus())
    }.getOrDefault(GpuNativeStatus.ERROR)

    private fun nativeVisualGpuFrameCount(): Long = runCatching {
        NativeStress.getVisualGpuFrameCount().coerceAtLeast(0L)
    }.getOrDefault(0L)

    private fun nativeVisualGpuFrameWorkNanos(): Long = runCatching {
        NativeStress.getVisualGpuFrameWorkNanos().coerceAtLeast(0L)
    }.getOrDefault(0L)

    private fun recordThermalEventIfChanged(
        session: MutableSession,
        thermal: ThermalSnapshot,
        nowElapsedTimeMs: Long,
    ) {
        if (session.lastThermalStatus == thermal.status) return
        session.lastThermalStatus = thermal.status
        val elapsed = (nowElapsedTimeMs - session.startElapsedTimeMs).coerceAtLeast(0L)
        session.thermalTimeline += ThermalEvent(
            elapsedTimeMs = elapsed,
            status = thermal.status,
            batteryTemperatureCelsius = thermal.batteryTemperatureCelsius,
        )
        if (lastLoggedThermalStatus != thermal.status) {
            lastLoggedThermalStatus = thermal.status
            val message = "Thermal changed elapsed=${elapsed}ms status=${thermal.statusLabel} " +
                "battery=${thermal.batteryTemperatureCelsius}"
            if (ThermalPolicy.isWarning(thermal.status)) logWarning(message) else logInfo(message)
        }
    }

    private fun recordHardwareObservation(
        session: MutableSession,
        hardware: HardwareSnapshot,
        nowElapsedTimeMs: Long,
    ) {
        val elapsed = (nowElapsedTimeMs - session.startElapsedTimeMs).coerceAtLeast(0L)
        hardware.cpuFrequencies.forEach { policy ->
            val observation = session.cpuFrequencyObservations.getOrPut(policy.policy) {
                MutableCpuFrequencyObservation(
                    policy = policy.policy,
                    startHz = policy.currentHz,
                    minimumObservedHz = policy.currentHz,
                    peakObservedHz = policy.currentHz,
                    endHz = policy.currentHz,
                )
            }
            observation.record(policy.currentHz)
        }
        session.endPowerObservation = powerObservation(hardware.battery, elapsed)
        hardware.battery.estimatedBatteryPowerWatts?.let { power ->
            session.peakEstimatedBatteryPowerWatts = max(
                session.peakEstimatedBatteryPowerWatts ?: power,
                power,
            )
        }
    }

    private fun powerObservation(
        battery: BatteryPowerSnapshot,
        elapsedTimeMs: Long,
    ): PowerObservation = PowerObservation(
        elapsedTimeMs = elapsedTimeMs,
        batteryLevelPercent = battery.levelPercent,
        chargingState = battery.chargingState,
        voltageVolts = battery.voltageVolts,
        currentAmpsRaw = battery.currentAmpsRaw,
        estimatedBatteryPowerWatts = battery.estimatedBatteryPowerWatts,
    )

    private fun postState(newState: CombinedStressState) {
        logInfo("State -> $newState")
        postToMain { listener?.onCombinedStateChanged(newState) }
    }

    private fun logInfo(message: String) {
        Log.i(LOG_TAG, message)
        diagnosticLog.record(message)
    }

    private fun logWarning(message: String) {
        Log.w(LOG_TAG, message)
        diagnosticLog.record("WARNING $message")
    }

    private fun logError(message: String) {
        Log.e(LOG_TAG, message)
        diagnosticLog.record("ERROR $message")
    }

    private fun postToMain(action: () -> Unit) {
        mainHandler.post {
            synchronized(lock) {
                if (closed) return@post
            }
            action()
        }
    }

    override fun close() {
        val shutdownComplete = CountDownLatch(1)
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1L
            listener = null
        }
        executor.execute {
            try {
                cleanupNativeResources()
                runCatching { NativeStress.shutdownGpu() }
            } finally {
                shutdownComplete.countDown()
            }
        }
        executor.shutdown()
        shutdownComplete.await(5L, TimeUnit.SECONDS)
        hardwareMonitor.close()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun higherPriorityReason(first: StopReason, second: StopReason): StopReason =
        if (reasonPriority(second) > reasonPriority(first)) second else first

    private fun reasonPriority(reason: StopReason): Int = when (reason) {
        StopReason.USER -> 0
        StopReason.DURATION_COMPLETED -> 1
        StopReason.ACTIVITY_STOPPED -> 2
        StopReason.THERMAL -> 3
        StopReason.RESOURCE_ERROR -> 4
    }

    private fun Long.floorToMib(): Long = this / MIB * MIB

    companion object {
        private const val LOG_TAG = "ResourceStress"
        private val VALID_TARGETS = setOf(25, 50, 75, 100)
        private const val MIB = 1024L * 1024L
        private const val MIN_SYSTEM_RESERVE_BYTES = 256L * MIB
        private const val MIN_NATIVE_TARGET_BYTES = 8L * MIB
        private const val MAX_AUTO_TARGET_BYTES = 1536L * MIB
        private const val AUTO_MEMORY_DIVISOR = 5L
    }
}
