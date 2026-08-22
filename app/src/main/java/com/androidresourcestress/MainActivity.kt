package com.androidresourcestress

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), StressController.Listener {
    private lateinit var statusValue: TextView
    private lateinit var elapsedValue: TextView
    private lateinit var cpuStateValue: TextView
    private lateinit var cpuTargetValue: TextView
    private lateinit var appCpuLoadValue: TextView
    private lateinit var coreEquivalentValue: TextView
    private lateinit var threadsValue: TextView
    private lateinit var memoryStateValue: TextView
    private lateinit var totalRamValue: TextView
    private lateinit var availableRamValue: TextView
    private lateinit var systemUsedValue: TextView
    private lateinit var appPssValue: TextView
    private lateinit var nativePssValue: TextView
    private lateinit var stressTargetValue: TextView
    private lateinit var allocatedValue: TextView
    private lateinit var memoryActivityValue: TextView
    private lateinit var batteryTemperatureValue: TextView
    private lateinit var thermalStatusValue: TextView
    private lateinit var cpuLoadGroup: RadioGroup
    private lateinit var ramTargetGroup: RadioGroup
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var configurationPanel: View

    private lateinit var deviceMonitor: DeviceMonitor
    private lateinit var memoryMonitor: MemoryMonitor
    private lateinit var cpuMonitor: CpuMonitor
    private lateinit var memoryActivityMonitor: MemoryActivityMonitor
    private lateinit var stressController: StressController

    private val monitorHandler = Handler(Looper.getMainLooper())
    private var monitoring = false
    private var nativeAvailable = false
    private var runStartedAtMs = 0L
    private var thermalStopTriggered = false
    private var logicalCoreCount = 1

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!monitoring) return
            updateLiveMetrics()
            monitorHandler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nativeLoadError = runCatching { NativeStress.ensureLoaded() }.exceptionOrNull()
        nativeAvailable = nativeLoadError == null

        setContentView(R.layout.activity_main)
        bindViews()

        deviceMonitor = DeviceMonitor(this)
        memoryMonitor = MemoryMonitor(this)
        memoryActivityMonitor = MemoryActivityMonitor()
        val deviceInfo = deviceMonitor.deviceInfo()
        logicalCoreCount = deviceInfo.logicalCoreCount
        cpuMonitor = CpuMonitor(logicalCoreCount)
        stressController = StressController(this)

        renderDeviceInfo(deviceInfo)
        installConfigurationListeners()
        startButton.setOnClickListener { startStress() }
        stopButton.setOnClickListener { stressController.stop() }
        renderState(StressController.State.STOPPED)

        if (nativeLoadError != null) {
            Toast.makeText(
                this,
                getString(
                    R.string.native_load_failed,
                    nativeLoadError.message ?: nativeLoadError.javaClass.simpleName,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onStart() {
        super.onStart()
        monitoring = true
        cpuMonitor.reset()
        memoryActivityMonitor.reset(nativeProcessedBytes())
        monitorHandler.removeCallbacks(monitorRunnable)
        monitorHandler.post(monitorRunnable)
    }

    override fun onStop() {
        monitoring = false
        monitorHandler.removeCallbacks(monitorRunnable)
        stressController.stop()
        super.onStop()
    }

    override fun onDestroy() {
        monitoring = false
        monitorHandler.removeCallbacksAndMessages(null)
        stressController.close()
        super.onDestroy()
    }

    override fun onStateChanged(state: StressController.State) {
        when (state) {
            StressController.State.STARTING -> {
                runStartedAtMs = SystemClock.elapsedRealtime()
                thermalStopTriggered = false
                cpuMonitor.reset()
                memoryActivityMonitor.reset(nativeProcessedBytes())
            }

            StressController.State.STOPPED -> {
                runStartedAtMs = 0L
                memoryActivityMonitor.reset(nativeProcessedBytes())
            }

            StressController.State.RUNNING,
            StressController.State.STOPPING,
            -> Unit
        }
        renderState(state)
        updateLiveMetrics()
    }

    override fun onStarted(allocatedMemoryBytes: Long, requestedMemoryBytes: Long) {
        if (allocatedMemoryBytes < requestedMemoryBytes) {
            Toast.makeText(
                this,
                "Memory allocation stopped at ${ByteFormatter.formatBytes(allocatedMemoryBytes)} " +
                    "of ${ByteFormatter.formatBytes(requestedMemoryBytes)}.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun bindViews() {
        statusValue = findViewById(R.id.statusValue)
        elapsedValue = findViewById(R.id.elapsedValue)
        cpuStateValue = findViewById(R.id.cpuStateValue)
        cpuTargetValue = findViewById(R.id.cpuTargetValue)
        appCpuLoadValue = findViewById(R.id.appCpuLoadValue)
        coreEquivalentValue = findViewById(R.id.coreEquivalentValue)
        threadsValue = findViewById(R.id.threadsValue)
        memoryStateValue = findViewById(R.id.memoryStateValue)
        totalRamValue = findViewById(R.id.totalRamValue)
        availableRamValue = findViewById(R.id.availableRamValue)
        systemUsedValue = findViewById(R.id.systemUsedValue)
        appPssValue = findViewById(R.id.appPssValue)
        nativePssValue = findViewById(R.id.nativePssValue)
        stressTargetValue = findViewById(R.id.stressTargetValue)
        allocatedValue = findViewById(R.id.allocatedValue)
        memoryActivityValue = findViewById(R.id.memoryActivityValue)
        batteryTemperatureValue = findViewById(R.id.batteryTemperatureValue)
        thermalStatusValue = findViewById(R.id.thermalStatusValue)
        cpuLoadGroup = findViewById(R.id.cpuLoadGroup)
        ramTargetGroup = findViewById(R.id.ramTargetGroup)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        configurationPanel = findViewById(R.id.configurationPanel)
    }

    private fun renderDeviceInfo(info: DeviceInfo) {
        findViewById<TextView>(R.id.manufacturerValue).text = info.manufacturer
        findViewById<TextView>(R.id.modelValue).text = info.model
        findViewById<TextView>(R.id.androidValue).text = info.androidVersion
        findViewById<TextView>(R.id.sdkValue).text = info.sdk.toString()
        findViewById<TextView>(R.id.abiValue).text = info.primaryAbi
        findViewById<TextView>(R.id.cpuCoresValue).text = info.logicalCoreCount.toString()
    }

    private fun installConfigurationListeners() {
        cpuLoadGroup.setOnCheckedChangeListener { _, _ ->
            cpuTargetValue.text = "${selectedCpuLoadPercent()}%"
        }
        ramTargetGroup.setOnCheckedChangeListener { _, _ -> updateMemoryTargetPreview() }
        cpuTargetValue.text = "${selectedCpuLoadPercent()}%"
        updateMemoryTargetPreview()
    }

    private fun startStress() {
        if (!nativeAvailable || stressController.state != StressController.State.STOPPED) return
        val memory = memoryMonitor.sample()
        if (memory.lowMemory) {
            Toast.makeText(this, R.string.low_memory_refusal, Toast.LENGTH_LONG).show()
            return
        }

        val requestedTarget = selectedMemoryTargetBytes(memory.availableBytes)
        val reserveBytes = max(memory.lowMemoryThresholdBytes, MIN_SYSTEM_RESERVE_BYTES)
        val maximumSafeTarget = (memory.availableBytes - reserveBytes).coerceAtLeast(0L)
        if (requestedTarget < MIN_NATIVE_TARGET_BYTES || requestedTarget > maximumSafeTarget) {
            Toast.makeText(this, R.string.unsafe_memory_refusal, Toast.LENGTH_LONG).show()
            return
        }

        stressTargetValue.text = ByteFormatter.formatBytes(requestedTarget)
        stressController.start(
            StressController.Configuration(
                cpuThreadCount = logicalCoreCount,
                cpuTargetLoadPercent = selectedCpuLoadPercent(),
                memoryTargetBytes = requestedTarget,
            ),
        )
    }

    private fun selectedCpuLoadPercent(): Int = when (cpuLoadGroup.checkedRadioButtonId) {
        R.id.cpu25 -> 25
        R.id.cpu50 -> 50
        R.id.cpu75 -> 75
        else -> 100
    }

    private fun selectedMemoryTargetBytes(availableBytes: Long): Long =
        when (ramTargetGroup.checkedRadioButtonId) {
            R.id.ram256 -> 256L * MIB
            R.id.ram1gb -> 1024L * MIB
            R.id.ramAuto -> min(availableBytes / 5L, MAX_AUTO_TARGET_BYTES)
                .floorToMib()

            else -> DEFAULT_MEMORY_TARGET_BYTES
        }

    private fun updateMemoryTargetPreview() {
        val memory = runCatching { memoryMonitor.sample() }.getOrNull()
        val target = selectedMemoryTargetBytes(memory?.availableBytes ?: 0L)
        stressTargetValue.text = if (ramTargetGroup.checkedRadioButtonId == R.id.ramAuto) {
            "${ByteFormatter.formatBytes(target)} (Auto)"
        } else {
            ByteFormatter.formatBytes(target)
        }
    }

    private fun renderState(state: StressController.State) {
        val stateLabel = state.name
        statusValue.text = stateLabel
        cpuStateValue.text = stateLabel
        memoryStateValue.text = stateLabel
        val statusColor = when (state) {
            StressController.State.RUNNING -> getColor(R.color.safe)
            StressController.State.STARTING,
            StressController.State.STOPPING,
            -> getColor(R.color.warning)

            StressController.State.STOPPED -> getColor(R.color.text_secondary)
        }
        statusValue.setTextColor(statusColor)
        cpuStateValue.setTextColor(statusColor)
        memoryStateValue.setTextColor(statusColor)

        val isStopped = state == StressController.State.STOPPED
        startButton.isEnabled = nativeAvailable && isStopped
        stopButton.isEnabled = state == StressController.State.STARTING ||
            state == StressController.State.RUNNING
        configurationPanel.alpha = if (isStopped) 1.0f else 0.55f
        cpuLoadGroup.isEnabled = isStopped
        ramTargetGroup.isEnabled = isStopped
        for (index in 0 until cpuLoadGroup.childCount) {
            cpuLoadGroup.getChildAt(index).isEnabled = isStopped
        }
        for (index in 0 until ramTargetGroup.childCount) {
            ramTargetGroup.getChildAt(index).isEnabled = isStopped
        }
    }

    private fun updateLiveMetrics() {
        val memory = runCatching { memoryMonitor.sample() }.getOrNull()
        if (memory != null) {
            totalRamValue.text = ByteFormatter.formatBytes(memory.totalBytes)
            availableRamValue.text = ByteFormatter.formatBytes(memory.availableBytes)
            systemUsedValue.text = ByteFormatter.formatBytes(memory.systemUsedBytes)
            appPssValue.text = ByteFormatter.formatBytes(memory.appPssBytes)
            nativePssValue.text = ByteFormatter.formatBytes(memory.nativePssBytes)
        }

        val cpu = cpuMonitor.sample()
        appCpuLoadValue.text = String.format(Locale.US, "%.1f%%", cpu.appCpuLoadPercent)
        coreEquivalentValue.text = String.format(Locale.US, "%.0f%%", cpu.coreEquivalentPercent)
        threadsValue.text = nativeCpuThreadCount().toString()

        val allocated = nativeAllocatedBytes()
        val processed = nativeProcessedBytes()
        allocatedValue.text = ByteFormatter.formatBytes(allocated)
        memoryActivityValue.text = ByteFormatter.formatRate(memoryActivityMonitor.sample(processed))

        val thermal = runCatching { deviceMonitor.thermalSnapshot() }.getOrNull()
        if (thermal != null) {
            batteryTemperatureValue.text = thermal.batteryTemperatureCelsius?.let {
                String.format(Locale.US, "%.1f °C", it)
            } ?: getString(R.string.not_available)
            thermalStatusValue.text = thermal.statusLabel
            thermalStatusValue.setTextColor(thermalColor(thermal.status))
            val isActive = stressController.state == StressController.State.STARTING ||
                stressController.state == StressController.State.RUNNING
            if (thermal.isSevereOrHigher && isActive && !thermalStopTriggered) {
                thermalStopTriggered = true
                stressController.stop()
                Toast.makeText(
                    this,
                    getString(R.string.thermal_stop, thermal.statusLabel),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        elapsedValue.text = formatElapsedTime()
    }

    private fun thermalColor(status: Int): Int = when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> getColor(R.color.danger)
        status >= PowerManager.THERMAL_STATUS_MODERATE -> getColor(R.color.warning)
        else -> getColor(R.color.safe)
    }

    private fun formatElapsedTime(): String {
        if (runStartedAtMs == 0L) return getString(R.string.elapsed_zero)
        val totalSeconds = ((SystemClock.elapsedRealtime() - runStartedAtMs) / 1000L)
            .coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun nativeCpuThreadCount(): Int = if (nativeAvailable) {
        runCatching { NativeStress.getCpuStressThreadCount() }.getOrDefault(0)
    } else {
        0
    }

    private fun nativeAllocatedBytes(): Long = if (nativeAvailable) {
        runCatching { NativeStress.getAllocatedMemoryBytes() }.getOrDefault(0L)
    } else {
        0L
    }

    private fun nativeProcessedBytes(): Long = if (nativeAvailable) {
        runCatching { NativeStress.getProcessedMemoryBytes() }.getOrDefault(0L)
    } else {
        0L
    }

    private fun Long.floorToMib(): Long = this / MIB * MIB

    companion object {
        private const val MONITOR_INTERVAL_MS = 750L
        private const val MIB = 1024L * 1024L
        private const val DEFAULT_MEMORY_TARGET_BYTES = 512L * MIB
        private const val MAX_AUTO_TARGET_BYTES = 1536L * MIB
        private const val MIN_SYSTEM_RESERVE_BYTES = 256L * MIB
        private const val MIN_NATIVE_TARGET_BYTES = 8L * MIB
    }
}
