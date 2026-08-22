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

    @JvmStatic
    external fun initializeGpu(): Boolean

    @JvmStatic
    external fun isGpuStressSupported(): Boolean

    @JvmStatic
    external fun getGpuDeviceName(): String

    @JvmStatic
    external fun getGpuApiVersion(): Int

    @JvmStatic
    external fun getGpuVendorId(): Long

    @JvmStatic
    external fun getGpuDeviceId(): Long

    @JvmStatic
    external fun isGpuComputeQueueSupported(): Boolean

    @JvmStatic
    external fun getGpuMaxWorkGroupCountX(): Long

    @JvmStatic
    external fun getGpuMaxWorkGroupCountY(): Long

    @JvmStatic
    external fun getGpuMaxWorkGroupCountZ(): Long

    @JvmStatic
    external fun getGpuMaxWorkGroupSizeX(): Long

    @JvmStatic
    external fun getGpuMaxWorkGroupSizeY(): Long

    @JvmStatic
    external fun getGpuMaxWorkGroupSizeZ(): Long

    @JvmStatic
    external fun getGpuMaxWorkGroupInvocations(): Long

    @JvmStatic
    external fun isGpuTimestampSupported(): Boolean

    @JvmStatic
    external fun getGpuBufferBytes(): Long

    @JvmStatic
    external fun startGpuStress(targetLoadPercent: Int): Boolean

    @JvmStatic
    external fun stopGpuStress()

    @JvmStatic
    external fun shutdownGpu()

    @JvmStatic
    external fun getGpuStressStatus(): Int

    @JvmStatic
    external fun getGpuDispatchCount(): Long

    @JvmStatic
    external fun getGpuWorkGroupCount(): Long

    @JvmStatic
    external fun getGpuWorkTimeNanos(): Long

    @JvmStatic
    external fun getGpuOutputChecksum(): Long

    @JvmStatic
    external fun getGpuLastError(): String

    fun ensureLoaded() = Unit
}
