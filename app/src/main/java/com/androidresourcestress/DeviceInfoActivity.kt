package com.androidresourcestress

import android.app.Activity
import android.os.Bundle

class DeviceInfoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = PageUi.content(this, "DEVICE & CAPABILITIES")
        val monitor = DeviceMonitor(this)
        val device = monitor.deviceInfo()
        val storage = monitor.storageCapacity()
        val memory = MemoryMonitor(this).sample()
        val thermal = monitor.thermalSnapshot()
        val gpu = GpuInfoReader.read()
        val ai = AiCapabilityDetector.detect()
        content.addView(PageUi.body(this, buildString {
            append("Manufacturer: ").append(device.manufacturer).append('\n')
            append("Brand: ").append(device.brand).append('\n')
            append("Model: ").append(device.model).append('\n')
            append("Android: ").append(device.androidVersion).append(" · SDK ").append(device.sdk).append('\n')
            append("Primary ABI: ").append(device.primaryAbi).append('\n')
            append("Logical CPUs: ").append(device.logicalCoreCount).append("\n\n")
            append("RAM total / available: ").append(ByteFormatter.formatBytes(memory.totalBytes))
            append(" / ").append(ByteFormatter.formatBytes(memory.availableBytes)).append('\n')
            append("App-private volume total / available: ").append(ByteFormatter.formatBytes(storage.totalBytes))
            append(" / ").append(ByteFormatter.formatBytes(storage.availableBytes)).append("\n\n")
            append("GPU: ").append(gpu.deviceName).append('\n')
            append("Vulkan: ").append(gpu.apiVersionLabel).append('\n')
            append("Compute supported: ").append(gpu.computeQueueSupported).append('\n')
            append("Vendor / device: ").append(gpu.vendorDeviceLabel).append('\n')
            append("Max group count: ").append(gpu.maxWorkGroupCountLabel).append('\n')
            append("Max group size: ").append(gpu.maxWorkGroupSizeLabel).append('\n')
            append("Max invocations: ").append(gpu.maxWorkGroupInvocations).append('\n')
            append("Timestamp query: ").append(gpu.timestampSupported).append('\n')
            append("Stress buffer: ").append(ByteFormatter.formatBytes(gpu.bufferBytes)).append("\n\n")
            append("Thermal API: Available\n")
            append("Current thermal status: ").append(thermal.statusLabel).append("\n\n")
            append("Dedicated AI accelerator: ").append(ai.dedicatedAccelerator).append('\n')
            append("NNAPI: ").append(ai.androidNeuralNetworksApi).append('\n')
            append("AI vendor hint: ").append(ai.vendor).append('\n')
            append("AI status: ").append(ai.status)
        }))
    }
}
