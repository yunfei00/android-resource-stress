package com.androidresourcestress

object NativeStress {
    init {
        System.loadLibrary("resource_stress")
    }

    @JvmStatic
    external fun startCpuStress(threadCount: Int, targetLoadPercent: Int): Boolean

    @JvmStatic
    external fun stopCpuStress()

    @JvmStatic
    external fun getCpuStressThreadCount(): Int

    @JvmStatic
    external fun startMemoryStress(targetBytes: Long): Long

    @JvmStatic
    external fun stopMemoryStress()

    @JvmStatic
    external fun getAllocatedMemoryBytes(): Long

    @JvmStatic
    external fun getProcessedMemoryBytes(): Long

    fun ensureLoaded() = Unit
}
