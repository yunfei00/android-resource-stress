package com.androidresourcestress

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import java.util.Locale

class MonitorActivity : LocalizedActivity(), StressForegroundService.Observer {
    private lateinit var monitorText: TextView
    private var service: StressForegroundService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? StressForegroundService.LocalBinder)?.service
            bound = service != null
            service?.addObserver(this@MonitorActivity)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
        monitorText = findViewById(R.id.monitorText)
        installInsets()
        findViewById<Button>(R.id.stressNavButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.historyNavButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.deviceInfoButton).setOnClickListener {
            startActivity(Intent(this, DeviceInfoActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, StressForegroundService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.removeObserver(this)
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    override fun onStressSnapshot(snapshot: CombinedRuntimeSnapshot) {
        monitorText.text = formatMonitor(snapshot)
    }

    override fun onStressError(message: String) {
        monitorText.text = getString(R.string.monitor_error, message)
    }

    private fun formatMonitor(snapshot: CombinedRuntimeSnapshot): String = buildString {
        val session = snapshot.currentSession
        fun section(title: String) {
            if (isNotEmpty()) append("\n\n")
            append(title.uppercase(Locale.getDefault())).append('\n')
        }
        fun line(label: String, value: String) {
            append(String.format(Locale.US, "%-24s %s\n", label, value))
        }
        section(getString(R.string.section_session))
        line(getString(R.string.status), combinedStateLabel(snapshot.state))
        line(getString(R.string.elapsed), DurationFormatter.format(snapshot.elapsedTimeMs))
        line(getString(R.string.preset), session?.configuration?.preset?.let(::presetLabel) ?: getString(R.string.not_available))
        line(getString(R.string.duration), session?.configuration?.duration?.let(::durationLabel) ?: getString(R.string.not_available))
        line(getString(R.string.label_screen_mode), session?.screenMode?.let(::screenModeLabel) ?: getString(R.string.not_available))
        line(getString(R.string.label_wake_lock), if (service?.isWakeLockHeld == true) getString(R.string.held) else getString(R.string.released))

        section(getString(R.string.section_cpu))
        line(getString(R.string.label_app_cpu_load), percent(snapshot.cpuLoadPercent))
        line(getString(R.string.label_core_equivalent), percent(snapshot.coreEquivalentPercent))
        line(getString(R.string.label_threads), snapshot.cpuThreadCount.toString())
        snapshot.hardware.cpuFrequencies.forEach { policy ->
            line(policy.policy, frequencyDisplay(policy.currentHz))
        }

        section(getString(R.string.section_gpu))
        line(getString(R.string.gpu_mode), session?.configuration?.gpuMode?.let(::gpuModeLabel) ?: getString(R.string.not_available))
        line(getString(R.string.gpu_target), session?.configuration?.gpuTargetPercent?.let { getString(R.string.percent_value, it) } ?: getString(R.string.not_available))
        line(getString(R.string.gpu_dispatch_rate), GpuMetricsFormatter.formatDispatchRate(snapshot.gpuDispatchRate))
        line(getString(R.string.gpu_work_time), GpuMetricsFormatter.formatGpuWorkTime(snapshot.gpu.gpuWorkNanos, service?.gpuInfo?.timestampSupported == true))
        line(getString(R.string.label_fps), if (snapshot.visualFps > 0.0) getString(R.string.fps_value, snapshot.visualFps) else getString(R.string.not_available))
        line(getString(R.string.label_frame_time), if (snapshot.visualFrameTimeNanos > 0.0) getString(R.string.frame_time_value, snapshot.visualFrameTimeNanos / 1_000_000.0) else getString(R.string.not_available))
        line(getString(R.string.label_gpu_frequency), frequencyDisplay(snapshot.hardware.gpu.frequencyHz))
        line(getString(R.string.label_gpu_utilization), snapshot.hardware.gpu.utilizationPercent?.let(::percent) ?: getString(R.string.unsupported_value))

        section(getString(R.string.section_memory))
        line(getString(R.string.label_allocated), ByteFormatter.formatBytes(snapshot.allocatedMemoryBytes))
        line(getString(R.string.label_app_pss), ByteFormatter.formatBytes(snapshot.memory.appPssBytes))
        line(getString(R.string.label_native_pss), ByteFormatter.formatBytes(snapshot.memory.nativePssBytes))
        line(getString(R.string.label_memory_activity), ByteFormatter.formatRate(snapshot.memoryActivityBytesPerSecond))
        line(getString(R.string.label_available_ram), ByteFormatter.formatBytes(snapshot.memory.availableBytes))

        section(getString(R.string.section_storage))
        line(getString(R.string.label_storage_status), resourceStateLabel(snapshot.storage.status.name))
        line(getString(R.string.label_storage_mode), snapshot.storage.mode.name)
        line(getString(R.string.label_read_activity), ByteFormatter.formatRate(snapshot.storage.readActivityBytesPerSecond))
        line(getString(R.string.label_write_activity), ByteFormatter.formatRate(snapshot.storage.writeActivityBytesPerSecond))
        line(getString(R.string.label_bytes_read), ByteFormatter.formatBytes(snapshot.storage.bytesRead))
        line(getString(R.string.label_bytes_written), ByteFormatter.formatBytes(snapshot.storage.bytesWritten))

        section(getString(R.string.section_thermal))
        line(getString(R.string.label_current_thermal), thermalStatusDisplay(snapshot.thermal.status))
        line(getString(R.string.label_battery_temperature), TemperatureFormatter.format(snapshot.thermal.batteryTemperatureCelsius))
        snapshot.hardware.highestThermalZone?.let {
            line(getString(R.string.label_highest_sensor), getString(R.string.sensor_value, it.name, TemperatureFormatter.format(it.temperatureCelsius)))
        }
        line(getString(R.string.label_thermal_timeline), session?.thermalTimeline?.size?.toString() ?: "0")

        section(getString(R.string.section_power))
        line(getString(R.string.label_battery_level), snapshot.hardware.battery.levelPercent?.let { getString(R.string.battery_level_value, it) } ?: getString(R.string.unsupported_value))
        line(getString(R.string.label_voltage), voltageDisplay(snapshot.hardware.battery.voltageVolts))
        line(getString(R.string.label_current_raw), currentDisplay(snapshot.hardware.battery.currentAmpsRaw))
        line(getString(R.string.label_estimated_battery_power), powerDisplay(snapshot.hardware.battery.estimatedBatteryPowerWatts))
    }

    private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
    private fun screenModeLabel(mode: ScreenMode): String = getString(if (mode == ScreenMode.OFF) R.string.screen_test_off else R.string.screen_test_on)

    private fun installInsets() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        findViewById<View>(R.id.monitorRoot).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
