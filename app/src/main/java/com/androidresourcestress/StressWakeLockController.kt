package com.androidresourcestress

import android.content.Context
import android.os.PowerManager

class StressWakeLockController(context: Context) : AutoCloseable {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val partialWakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "AndroidResourceStress:ActiveSession",
    ).apply { setReferenceCounted(false) }

    val isHeld: Boolean
        get() = partialWakeLock.isHeld

    fun acquire() {
        if (!partialWakeLock.isHeld) partialWakeLock.acquire()
    }

    fun release() {
        if (partialWakeLock.isHeld) partialWakeLock.release()
    }

    @Suppress("DEPRECATION")
    fun attemptWake(): Boolean {
        if (powerManager.isInteractive) return true
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "AndroidResourceStress:SessionFinished",
        )
        wakeLock.acquire(WAKE_TIMEOUT_MS)
        val succeeded = powerManager.isInteractive
        if (wakeLock.isHeld) wakeLock.release()
        return succeeded
    }

    override fun close() = release()

    private companion object {
        const val WAKE_TIMEOUT_MS = 3_000L
    }
}
