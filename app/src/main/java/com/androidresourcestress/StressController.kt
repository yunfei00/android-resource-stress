package com.androidresourcestress

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StressController(listener: Listener) : AutoCloseable {
    enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
    }

    data class Configuration(
        val cpuThreadCount: Int,
        val cpuTargetLoadPercent: Int,
        val memoryTargetBytes: Long,
    )

    interface Listener {
        fun onStateChanged(state: State)
        fun onStarted(allocatedMemoryBytes: Long, requestedMemoryBytes: Long)
        fun onError(message: String)
    }

    private val lock = Any()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "stress-controller").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: Listener? = listener
    private var generation = 0L
    private var activeStartLatch: CountDownLatch? = null
    private var closed = false

    @Volatile
    var state: State = State.STOPPED
        private set

    fun start(configuration: Configuration) {
        val operationGeneration: Long
        val startLatch = CountDownLatch(1)
        synchronized(lock) {
            if (closed || state != State.STOPPED) return
            generation += 1L
            operationGeneration = generation
            activeStartLatch = startLatch
            state = State.STARTING
        }
        postState(State.STARTING)

        executor.execute {
            try {
                val cpuStarted = synchronized(lock) {
                    if (closed || generation != operationGeneration) return@execute
                    NativeStress.startCpuStress(
                        configuration.cpuThreadCount,
                        configuration.cpuTargetLoadPercent,
                    )
                }
                if (!cpuStarted) {
                    throw IllegalStateException("CPU stress workers could not be started")
                }

                if (isCancelled(operationGeneration)) {
                    cleanupNative()
                    return@execute
                }

                val allocatedBytes = NativeStress.startMemoryStress(configuration.memoryTargetBytes)
                if (isCancelled(operationGeneration)) {
                    cleanupNative()
                    return@execute
                }
                if (allocatedBytes <= 0L) {
                    throw IllegalStateException("Native memory allocation failed")
                }

                synchronized(lock) {
                    if (closed || generation != operationGeneration || state != State.STARTING) {
                        cleanupNative()
                        return@execute
                    }
                    state = State.RUNNING
                }
                postState(State.RUNNING)
                postToMain {
                    listener?.onStarted(allocatedBytes, configuration.memoryTargetBytes)
                }
            } catch (error: Throwable) {
                cleanupNative()
                val shouldReport = synchronized(lock) {
                    if (!closed && generation == operationGeneration && state != State.STOPPING) {
                        state = State.STOPPED
                        true
                    } else {
                        false
                    }
                }
                if (shouldReport) {
                    postState(State.STOPPED)
                    postToMain {
                        listener?.onError(error.message ?: error.javaClass.simpleName)
                    }
                }
            } finally {
                startLatch.countDown()
                synchronized(lock) {
                    if (activeStartLatch === startLatch) activeStartLatch = null
                }
            }
        }
    }

    fun stop() {
        val startLatch: CountDownLatch?
        synchronized(lock) {
            if (closed || state == State.STOPPED || state == State.STOPPING) return
            generation += 1L
            state = State.STOPPING
            startLatch = activeStartLatch
        }
        postState(State.STOPPING)
        executor.execute {
            cleanupNative()
            startLatch?.await(30L, TimeUnit.SECONDS)
            cleanupNative()
            val didStop = synchronized(lock) {
                if (!closed && state == State.STOPPING) {
                    state = State.STOPPED
                    true
                } else {
                    false
                }
            }
            if (didStop) postState(State.STOPPED)
        }
    }

    private fun isCancelled(operationGeneration: Long): Boolean = synchronized(lock) {
        closed || generation != operationGeneration || state != State.STARTING
    }

    private fun cleanupNative() {
        runCatching { NativeStress.stopCpuStress() }
        runCatching { NativeStress.stopMemoryStress() }
    }

    private fun postState(newState: State) {
        postToMain { listener?.onStateChanged(newState) }
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
        val startLatch: CountDownLatch?
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1L
            startLatch = activeStartLatch
            listener = null
        }
        executor.execute {
            cleanupNative()
            startLatch?.await(30L, TimeUnit.SECONDS)
            cleanupNative()
        }
        executor.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
