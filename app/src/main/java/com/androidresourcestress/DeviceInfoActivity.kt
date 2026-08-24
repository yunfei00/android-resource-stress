package com.androidresourcestress

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.util.Locale

class DeviceInfoActivity : LocalizedActivity() {
    private lateinit var hardwareMonitor: HardwareMonitorService
    private lateinit var body: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val update = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = PageUi.content(this, getString(R.string.device_capabilities_title))
        body = PageUi.body(this)
        content.addView(body)
        hardwareMonitor = HardwareMonitorService(this)
        handler.post(update)
    }

    private fun render() {
        val monitor = DeviceMonitor(this)
        val device = monitor.deviceInfo()
        val storage = monitor.storageCapacity()
        val memory = MemoryMonitor(this).sample()
        val thermal = monitor.thermalSnapshot()
        val gpu = GpuInfoReader.read()
        val hardware = hardwareMonitor.snapshot()
        body.text = buildString {
            section(getString(R.string.section_device))
            line(getString(R.string.label_manufacturer), device.manufacturer)
            line(getString(R.string.label_brand), device.brand)
            line(getString(R.string.label_model), device.model)
            line(getString(R.string.label_android), "${device.androidVersion} · SDK ${device.sdk}")
            line(getString(R.string.label_abi), device.primaryAbi)
            line(getString(R.string.label_logical_cpus), device.logicalCoreCount.toString())
            line(
                getString(R.string.label_ram_total_available),
                "${ByteFormatter.formatBytes(memory.totalBytes)} / ${ByteFormatter.formatBytes(memory.availableBytes)}",
            )
            line(
                getString(R.string.label_storage_total_available),
                "${ByteFormatter.formatBytes(storage.totalBytes)} / ${ByteFormatter.formatBytes(storage.availableBytes)}",
            )

            section(getString(R.string.soc_information_title))
            line(getString(R.string.label_soc_manufacturer), device.socManufacturer)
            line(getString(R.string.label_soc_model), device.socModel)
            line(getString(R.string.label_board_platform), device.boardPlatform)
            line(getString(R.string.label_hardware), device.hardware)
            line(getString(R.string.label_board), device.board)
            line(getString(R.string.label_fingerprint), device.buildFingerprint)
            line(getString(R.string.label_kernel), device.kernelVersion)

            section(getString(R.string.root_access))
            line(
                getString(R.string.root_access),
                getString(if (hardware.root.available) R.string.available else R.string.unavailable),
            )
            line(getString(R.string.label_root_detail), hardware.root.detail)
            line(getString(R.string.label_monitor_backend), hardwareMonitor.backendName)

            section(getString(R.string.section_gpu))
            line(getString(R.string.label_gpu_device), gpu.deviceName)
            line(getString(R.string.label_vulkan_version), gpu.apiVersionLabel)
            line(
                getString(R.string.label_compute_supported),
                getString(if (gpu.computeQueueSupported) R.string.boolean_yes else R.string.boolean_no),
            )
            line(getString(R.string.label_vendor_device), gpu.vendorDeviceLabel)

            section(getString(R.string.gpu_hardware_title))
            line(getString(R.string.label_current), frequencyDisplay(hardware.gpu.frequencyHz))
            line(getString(R.string.label_maximum), frequencyDisplay(hardware.gpu.maximumFrequencyHz))
            line(
                getString(R.string.label_gpu_hardware_utilization),
                hardware.gpu.utilizationPercent?.let { String.format(Locale.US, "%.1f%%", it) }
                    ?: getString(R.string.unsupported_value),
            )
            line(getString(R.string.label_source), hardware.gpu.source ?: getString(R.string.unsupported_value))

            section(getString(R.string.cpu_frequency_title))
            if (hardware.cpuFrequencies.isEmpty()) {
                append(getString(R.string.unsupported_value)).append('\n')
            } else {
                hardware.cpuFrequencies.forEach { policy ->
                    append(policy.policy).append('\n')
                    line(getString(R.string.label_current), frequencyDisplay(policy.currentHz))
                    line(getString(R.string.label_minimum), frequencyDisplay(policy.minimumHz))
                    line(getString(R.string.label_maximum), frequencyDisplay(policy.maximumHz))
                }
            }

            section(getString(R.string.thermal_sensors_title))
            line(getString(R.string.label_current_thermal), thermalStatusDisplay(thermal.status))
            if (hardware.thermalZones.isEmpty()) {
                append(getString(R.string.unsupported_value)).append('\n')
            } else {
                hardware.thermalZones.forEach { zone ->
                    line(zone.name, TemperatureFormatter.format(zone.temperatureCelsius))
                }
            }

            section(getString(R.string.power_title))
            line(getString(R.string.label_battery_level), hardware.battery.levelPercent?.let { "$it%" } ?: getString(R.string.unsupported_value))
            line(getString(R.string.label_charging_state), chargingStateDisplay(hardware.battery.chargingState))
            line(getString(R.string.label_voltage), voltageDisplay(hardware.battery.voltageVolts))
            line(getString(R.string.label_current_raw), currentDisplay(hardware.battery.currentAmpsRaw))
            line(getString(R.string.label_estimated_battery_power), powerDisplay(hardware.battery.estimatedBatteryPowerWatts))

            section(getString(R.string.npu_planned))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hardwareMonitor.close()
        super.onDestroy()
    }

    private fun StringBuilder.section(title: String) {
        if (isNotEmpty()) append('\n')
        append(title).append('\n')
    }

    private fun StringBuilder.line(label: String, value: String) {
        append(label).append(": ").append(value).append('\n')
    }
}
