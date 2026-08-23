package com.androidresourcestress

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

enum class StorageMode {
    READ,
    WRITE,
    MIXED,
}

enum class StorageLevel(val targetBytes: Long, val displayLabel: String) {
    LOW(128L * 1024L * 1024L, "LOW · 128 MB"),
    MEDIUM(256L * 1024L * 1024L, "MEDIUM · 256 MB"),
    HIGH(512L * 1024L * 1024L, "HIGH · 512 MB"),
}

enum class StorageStressStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class StorageCapacity(
    val totalBytes: Long,
    val availableBytes: Long,
)

data class StorageRuntimeSnapshot(
    val status: StorageStressStatus,
    val mode: StorageMode,
    val level: StorageLevel,
    val workingSetBytes: Long,
    val bytesRead: Long,
    val bytesWritten: Long,
    val readActivityBytesPerSecond: Double,
    val writeActivityBytesPerSecond: Double,
    val temporaryFileBytes: Long,
    val lastError: String?,
)

object StorageSafety {
    const val MIN_FREE_RESERVE_BYTES = 2L * 1024L * 1024L * 1024L
    const val MIN_WORKING_SET_BYTES = 16L * 1024L * 1024L

    fun calculateWorkingSetBytes(
        availableBytes: Long,
        configuredTargetBytes: Long,
        reserveBytes: Long = MIN_FREE_RESERVE_BYTES,
    ): Long {
        if (availableBytes <= 0L || configuredTargetBytes <= 0L || reserveBytes < 0L) {
            return 0L
        }
        val ratioLimit = availableBytes / 10L
        val reserveLimit = (availableBytes - reserveBytes).coerceAtLeast(0L)
        return min(configuredTargetBytes, min(ratioLimit, reserveLimit)).floorToMib()
    }

    private fun Long.floorToMib(): Long = this / MIB * MIB

    private const val MIB = 1024L * 1024L
}

class StorageStress(
    context: Context,
    private val eventLogger: ((String) -> Unit)? = null,
) : AutoCloseable {
    private val lock = Any()
    private val activityLock = Any()
    private val stressDirectory = File(context.cacheDir, DIRECTORY_NAME)
    private val stressFile = File(stressDirectory, FILE_NAME)
    private val stopRequested = AtomicBoolean(true)
    private val bytesRead = AtomicLong(0L)
    private val bytesWritten = AtomicLong(0L)
    private val preparationBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(PREPARATION_CHUNK_BYTES)
    }
    private val ioBuffer: ByteBuffer by lazy { ByteBuffer.allocateDirect(IO_CHUNK_BYTES) }
    private var worker: Thread? = null
    private var currentMode = StorageMode.MIXED
    private var currentLevel = StorageLevel.LOW
    private var currentWorkingSetBytes = 0L
    private var previousReadBytes = 0L
    private var previousWrittenBytes = 0L
    private var previousSampleTimeMs = 0L

    @Volatile
    var status: StorageStressStatus = StorageStressStatus.STOPPED
        private set

    @Volatile
    var lastError: String? = null
        private set

    init {
        cleanupOrphans()
    }

    fun capacity(): StorageCapacity {
        val stats = StatFs(stressDirectory.parentFile?.absolutePath ?: stressDirectory.absolutePath)
        return StorageCapacity(
            totalBytes = stats.totalBytes.coerceAtLeast(0L),
            availableBytes = stats.availableBytes.coerceAtLeast(0L),
        )
    }

    fun start(mode: StorageMode, level: StorageLevel): Long {
        synchronized(lock) {
            check(status == StorageStressStatus.STOPPED && worker == null) {
                "Storage stress is already active"
            }
            status = StorageStressStatus.STARTING
            lastError = null
            currentMode = mode
            currentLevel = level
            bytesRead.set(0L)
            bytesWritten.set(0L)
            stopRequested.set(false)
        }
        resetActivitySampling()
        cleanupOrphans()

        val available = capacity().availableBytes
        val workingSet = StorageSafety.calculateWorkingSetBytes(
            availableBytes = available,
            configuredTargetBytes = level.targetBytes,
        )
        if (workingSet < StorageSafety.MIN_WORKING_SET_BYTES) {
            failStart("Insufficient free space for storage stress")
        }

        try {
            if (!stressDirectory.exists() && !stressDirectory.mkdirs()) {
                failStart("Unable to create app-private storage stress directory")
            }
            currentWorkingSetBytes = workingSet
            prepareWorkingFile(mode, workingSet)
            check(!stopRequested.get()) { "Storage start cancelled" }
            val createdWorker = Thread(
                { workerLoop(mode, workingSet) },
                "storage-stress-worker",
            )
            synchronized(lock) {
                worker = createdWorker
                status = StorageStressStatus.RUNNING
            }
            createdWorker.start()
            eventLogger?.invoke(
                "Storage started mode=$mode level=$level workingSet=$workingSet",
            )
            return workingSet
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            synchronized(lock) {
                lastError = message
                status = StorageStressStatus.ERROR
            }
            stopRequested.set(true)
            cleanupStressFiles()
            throw IllegalStateException(message, error)
        }
    }

    fun snapshot(): StorageRuntimeSnapshot {
        val read = bytesRead.get().coerceAtLeast(0L)
        val written = bytesWritten.get().coerceAtLeast(0L)
        val now = SystemClock.elapsedRealtime()
        val activities = synchronized(activityLock) {
            if (previousSampleTimeMs == 0L || read < previousReadBytes ||
                written < previousWrittenBytes
            ) {
                previousReadBytes = read
                previousWrittenBytes = written
                previousSampleTimeMs = now
                0.0 to 0.0
            } else {
                val elapsedMs = (now - previousSampleTimeMs).coerceAtLeast(1L)
                val readRate = (read - previousReadBytes).toDouble() * 1000.0 / elapsedMs
                val writeRate = (written - previousWrittenBytes).toDouble() * 1000.0 / elapsedMs
                previousReadBytes = read
                previousWrittenBytes = written
                previousSampleTimeMs = now
                readRate to writeRate
            }
        }
        return StorageRuntimeSnapshot(
            status = status,
            mode = currentMode,
            level = currentLevel,
            workingSetBytes = currentWorkingSetBytes,
            bytesRead = read,
            bytesWritten = written,
            readActivityBytesPerSecond = activities.first,
            writeActivityBytesPerSecond = activities.second,
            temporaryFileBytes = runCatching { stressFile.length() }.getOrDefault(0L),
            lastError = lastError,
        )
    }

    fun isRunning(): Boolean = synchronized(lock) {
        status == StorageStressStatus.RUNNING && worker?.isAlive == true
    }

    fun stop(): StorageRuntimeSnapshot {
        val workerToJoin = synchronized(lock) {
            if (status == StorageStressStatus.STOPPED && worker == null) {
                cleanupOrphans()
                return stoppedSnapshot()
            }
            status = StorageStressStatus.STOPPING
            stopRequested.set(true)
            worker.also { worker = null }
        }
        workerToJoin?.interrupt()
        workerToJoin?.join(STOP_JOIN_TIMEOUT_MS)
        if (workerToJoin?.isAlive == true) {
            lastError = "Storage worker did not stop within ${STOP_JOIN_TIMEOUT_MS}ms"
        }
        val finalSnapshot = snapshot()
        cleanupStressFiles()
        if (hasTemporaryFiles()) {
            lastError = "Unable to delete all storage stress temporary files"
            eventLogger?.invoke(lastError!!)
        }
        synchronized(lock) {
            status = StorageStressStatus.STOPPED
            currentWorkingSetBytes = 0L
        }
        bytesRead.set(0L)
        bytesWritten.set(0L)
        resetActivitySampling()
        return finalSnapshot
    }

    fun cleanupOrphans(): Long {
        if (!stressDirectory.exists()) return 0L
        val bytes = stressDirectory.walkTopDown()
            .filter { it.isFile }
            .sumOf { runCatching { it.length() }.getOrDefault(0L) }
        cleanupStressFiles()
        if (bytes > 0L) eventLogger?.invoke("Removed orphan storage stress files bytes=$bytes")
        return bytes
    }

    fun hasTemporaryFiles(): Boolean = stressDirectory.exists() &&
        stressDirectory.walkTopDown().any { it.isFile }

    override fun close() {
        stop()
    }

    private fun prepareWorkingFile(mode: StorageMode, workingSetBytes: Long) {
        RandomAccessFile(stressFile, "rw").use { file ->
            file.setLength(workingSetBytes)
            if (mode == StorageMode.WRITE) return
            val channel = file.channel
            val buffer = preparationBuffer
            fillBuffer(buffer, 0L)
            var position = 0L
            while (position < workingSetBytes && !stopRequested.get()) {
                buffer.clear()
                buffer.limit(min(buffer.capacity().toLong(), workingSetBytes - position).toInt())
                channel.position(position)
                writeFully(channel, buffer, countActivity = false)
                position += buffer.limit().toLong()
            }
            channel.force(false)
        }
    }

    private fun workerLoop(mode: StorageMode, workingSetBytes: Long) {
        try {
            RandomAccessFile(stressFile, "rw").use { file ->
                val channel = file.channel
                val buffer = ioBuffer
                var position = 0L
                var sequence = 1L
                while (!stopRequested.get()) {
                    val chunkBytes = min(buffer.capacity().toLong(), workingSetBytes - position)
                        .toInt()
                    when (mode) {
                        StorageMode.READ -> readChunk(channel, buffer, position, chunkBytes)
                        StorageMode.WRITE -> {
                            fillBuffer(buffer, sequence++)
                            writeChunk(channel, buffer, position, chunkBytes)
                        }
                        StorageMode.MIXED -> {
                            fillBuffer(buffer, sequence++)
                            writeChunk(channel, buffer, position, chunkBytes)
                            if (!stopRequested.get()) {
                                readChunk(channel, buffer, position, chunkBytes)
                            }
                        }
                    }
                    position += chunkBytes.toLong()
                    if (position >= workingSetBytes) {
                        if (mode != StorageMode.READ) channel.force(false)
                        position = 0L
                    }
                }
            }
        } catch (error: Throwable) {
            if (!stopRequested.get()) {
                val message = error.message ?: error.javaClass.simpleName
                lastError = "Storage worker error: $message"
                status = StorageStressStatus.ERROR
                eventLogger?.invoke(lastError!!)
            }
        }
    }

    private fun readChunk(
        channel: FileChannel,
        buffer: ByteBuffer,
        position: Long,
        chunkBytes: Int,
    ) {
        buffer.clear()
        buffer.limit(chunkBytes)
        channel.position(position)
        while (buffer.hasRemaining() && !stopRequested.get()) {
            val read = channel.read(buffer)
            if (read <= 0) break
            bytesRead.addAndGet(read.toLong())
        }
    }

    private fun writeChunk(
        channel: FileChannel,
        buffer: ByteBuffer,
        position: Long,
        chunkBytes: Int,
    ) {
        buffer.position(0)
        buffer.limit(chunkBytes)
        channel.position(position)
        writeFully(channel, buffer, countActivity = true)
    }

    private fun writeFully(
        channel: FileChannel,
        buffer: ByteBuffer,
        countActivity: Boolean,
    ) {
        while (buffer.hasRemaining() && !stopRequested.get()) {
            val written = channel.write(buffer)
            if (written <= 0) break
            if (countActivity) bytesWritten.addAndGet(written.toLong())
        }
    }

    private fun fillBuffer(buffer: ByteBuffer, sequence: Long) {
        buffer.clear()
        var value = sequence xor 0x5A5A5A5A5A5A5A5AL
        while (buffer.remaining() >= Long.SIZE_BYTES) {
            value = value xor (value shl 13)
            value = value xor (value ushr 7)
            value = value xor (value shl 17)
            buffer.putLong(value)
        }
        buffer.flip()
    }

    private fun failStart(message: String): Nothing {
        synchronized(lock) {
            lastError = message
            status = StorageStressStatus.ERROR
        }
        throw IllegalStateException(message)
    }

    private fun cleanupStressFiles() {
        if (!stressDirectory.exists()) return
        stressDirectory.walkBottomUp().forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun resetActivitySampling() {
        synchronized(activityLock) {
            previousReadBytes = bytesRead.get()
            previousWrittenBytes = bytesWritten.get()
            previousSampleTimeMs = SystemClock.elapsedRealtime()
        }
    }

    private fun stoppedSnapshot(): StorageRuntimeSnapshot = StorageRuntimeSnapshot(
        status = StorageStressStatus.STOPPED,
        mode = currentMode,
        level = currentLevel,
        workingSetBytes = 0L,
        bytesRead = 0L,
        bytesWritten = 0L,
        readActivityBytesPerSecond = 0.0,
        writeActivityBytesPerSecond = 0.0,
        temporaryFileBytes = 0L,
        lastError = lastError,
    )

    companion object {
        private const val DIRECTORY_NAME = "storage_stress"
        private const val FILE_NAME = "stress.bin"
        private const val IO_CHUNK_BYTES = 1024 * 1024
        private const val PREPARATION_CHUNK_BYTES = 4 * 1024 * 1024
        private const val STOP_JOIN_TIMEOUT_MS = 10_000L
    }
}
