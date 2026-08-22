package com.androidresourcestress

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GpuController(listener: Listener) : AutoCloseable {
    enum class State {
        CHECKING,
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        UNSUPPORTED,
        ERROR,
    }

    interface Listener {
        fun onGpuInfoAvailable(info: GpuInfo)
        fun onGpuStateChanged(state: State)
        fun onGpuError(message: String)
    }

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gpu-controller").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: Listener? = listener
    private var generation = 0L
    private var closed = false

    @Volatile
    var state: State = State.CHECKING
        private set

    @Volatile
    var info: GpuInfo? = null
        private set

    fun initialize() {
        val operationGeneration = synchronized(lock) {
            if (closed) return
            generation += 1L
            state = State.CHECKING
            generation
        }
        postState(State.CHECKING)
        executor.execute {
            val initialized = runCatching { NativeStress.initializeGpu() }.getOrDefault(false)
            val detectedInfo = readGpuInfo()
            val nativeStatus = runCatching {
                GpuNativeStatus.fromCode(NativeStress.getGpuStressStatus())
            }.getOrDefault(GpuNativeStatus.ERROR)
            val newState = when {
                initialized && detectedInfo.supported -> State.STOPPED
                nativeStatus == GpuNativeStatus.UNSUPPORTED -> State.UNSUPPORTED
                else -> State.ERROR
            }
            val accepted = synchronized(lock) {
                if (closed || generation != operationGeneration) {
                    false
                } else {
                    info = detectedInfo
                    state = newState
                    true
                }
            }
            if (accepted) {
                postToMain { listener?.onGpuInfoAvailable(detectedInfo) }
                postState(newState)
                if (newState == State.ERROR) {
                    postError(nativeError("Vulkan capability detection failed"))
                }
            }
        }
    }

    fun start(targetLoadPercent: Int) {
        val operationGeneration = synchronized(lock) {
            if (closed || state != State.STOPPED) return
            generation += 1L
            state = State.STARTING
            generation
        }
        postState(State.STARTING)
        executor.execute {
            val started = runCatching { NativeStress.startGpuStress(targetLoadPercent) }
                .getOrDefault(false)
            val accepted = synchronized(lock) {
                !closed && generation == operationGeneration && state == State.STARTING
            }
            if (!accepted) {
                if (started) runCatching { NativeStress.stopGpuStress() }
                return@execute
            }
            if (started) {
                synchronized(lock) { state = State.RUNNING }
                postState(State.RUNNING)
            } else {
                synchronized(lock) { state = State.ERROR }
                postState(State.ERROR)
                postError(nativeError("GPU stress could not be started"))
            }
        }
    }

    fun stop() {
        val operationGeneration = synchronized(lock) {
            if (closed || state == State.STOPPED || state == State.UNSUPPORTED ||
                state == State.CHECKING || state == State.STOPPING
            ) {
                return
            }
            generation += 1L
            state = State.STOPPING
            generation
        }
        postState(State.STOPPING)
        executor.execute {
            runCatching { NativeStress.stopGpuStress() }
            val didStop = synchronized(lock) {
                if (!closed && generation == operationGeneration && state == State.STOPPING) {
                    state = if (info?.supported == true) State.STOPPED else State.UNSUPPORTED
                    true
                } else {
                    false
                }
            }
            if (didStop) postState(state)
        }
    }

    fun snapshot(): GpuSnapshot = runCatching {
        GpuSnapshot(
            status = GpuNativeStatus.fromCode(NativeStress.getGpuStressStatus()),
            dispatchCount = NativeStress.getGpuDispatchCount().coerceAtLeast(0L),
            workGroupCount = NativeStress.getGpuWorkGroupCount().coerceAtLeast(0L),
            gpuWorkNanos = NativeStress.getGpuWorkTimeNanos().coerceAtLeast(0L),
            outputChecksum = NativeStress.getGpuOutputChecksum(),
        )
    }.getOrElse {
        GpuSnapshot(GpuNativeStatus.ERROR, 0L, 0L, 0L, 0L)
    }

    fun reconcileNativeError(snapshot: GpuSnapshot) {
        if (snapshot.status != GpuNativeStatus.ERROR) return
        val changed = synchronized(lock) {
            if (!closed && state == State.RUNNING) {
                state = State.ERROR
                true
            } else {
                false
            }
        }
        if (changed) {
            postState(State.ERROR)
            postError(nativeError("GPU worker stopped after a Vulkan error"))
        }
    }

    private fun readGpuInfo(): GpuInfo = runCatching {
        GpuInfo(
            supported = NativeStress.isGpuStressSupported(),
            deviceName = NativeStress.getGpuDeviceName(),
            apiVersion = NativeStress.getGpuApiVersion(),
            vendorId = NativeStress.getGpuVendorId(),
            deviceId = NativeStress.getGpuDeviceId(),
            computeQueueSupported = NativeStress.isGpuComputeQueueSupported(),
            maxWorkGroupCount = longArrayOf(
                NativeStress.getGpuMaxWorkGroupCountX(),
                NativeStress.getGpuMaxWorkGroupCountY(),
                NativeStress.getGpuMaxWorkGroupCountZ(),
            ),
            maxWorkGroupSize = longArrayOf(
                NativeStress.getGpuMaxWorkGroupSizeX(),
                NativeStress.getGpuMaxWorkGroupSizeY(),
                NativeStress.getGpuMaxWorkGroupSizeZ(),
            ),
            maxWorkGroupInvocations = NativeStress.getGpuMaxWorkGroupInvocations(),
            timestampSupported = NativeStress.isGpuTimestampSupported(),
            bufferBytes = NativeStress.getGpuBufferBytes(),
        )
    }.getOrElse {
        GpuInfo(
            supported = false,
            deviceName = "Unavailable",
            apiVersion = 0,
            vendorId = 0,
            deviceId = 0,
            computeQueueSupported = false,
            maxWorkGroupCount = longArrayOf(0, 0, 0),
            maxWorkGroupSize = longArrayOf(0, 0, 0),
            maxWorkGroupInvocations = 0,
            timestampSupported = false,
            bufferBytes = 0,
        )
    }

    private fun nativeError(fallback: String): String = runCatching {
        NativeStress.getGpuLastError().ifBlank { fallback }
    }.getOrDefault(fallback)

    private fun postState(newState: State) {
        postToMain { listener?.onGpuStateChanged(newState) }
    }

    private fun postError(message: String) {
        postToMain { listener?.onGpuError(message) }
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
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1L
            listener = null
        }
        val shutdownComplete = CountDownLatch(1)
        executor.execute {
            try {
                runCatching { NativeStress.stopGpuStress() }
                runCatching { NativeStress.shutdownGpu() }
            } finally {
                shutdownComplete.countDown()
            }
        }
        executor.shutdown()
        shutdownComplete.await(5L, TimeUnit.SECONDS)
        mainHandler.removeCallbacksAndMessages(null)
    }
}
