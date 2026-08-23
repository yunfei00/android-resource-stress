package com.androidresourcestress

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.min

class MainActivity : Activity(), CombinedStressController.Listener {
    private lateinit var statusValue: TextView
    private lateinit var elapsedValue: TextView
    private lateinit var sessionPresetValue: TextView
    private lateinit var sessionDurationValue: TextView
    private lateinit var sessionStopReasonValue: TextView
    private lateinit var sessionPeaksValue: TextView
    private lateinit var lastErrorValue: TextView
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
    private lateinit var gpuDeviceValue: TextView
    private lateinit var gpuVulkanValue: TextView
    private lateinit var gpuComputeValue: TextView
    private lateinit var gpuTargetValue: TextView
    private lateinit var gpuStatusValue: TextView
    private lateinit var gpuDispatchCountValue: TextView
    private lateinit var gpuDispatchRateValue: TextView
    private lateinit var gpuWorkTimeValue: TextView
    private lateinit var gpuComputeActivityValue: TextView
    private lateinit var gpuOutputValue: TextView
    private lateinit var thermalProtectionValue: TextView
    private lateinit var thermalStatusValue: TextView
    private lateinit var batteryTemperatureValue: TextView
    private lateinit var startTemperatureValue: TextView
    private lateinit var peakTemperatureValue: TextView
    private lateinit var deltaTemperatureValue: TextView
    private lateinit var thermalMessageValue: TextView
    private lateinit var configurationPanel: ViewGroup
    private lateinit var presetGroup: RadioGroup
    private lateinit var cpuLoadGroup: RadioGroup
    private lateinit var gpuLoadGroup: RadioGroup
    private lateinit var ramTargetGroup: RadioGroup
    private lateinit var resourceCpu: CheckBox
    private lateinit var resourceGpu: CheckBox
    private lateinit var resourceMemory: CheckBox
    private lateinit var durationSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private lateinit var controller: CombinedStressController
    private val monitorHandler = Handler(Looper.getMainLooper())
    private val durations = StressDuration.entries.toTypedArray()
    private var monitoring = false
    private var nativeAvailable = false
    private var applyingPreset = false
    private var lastSnapshot: CombinedRuntimeSnapshot? = null

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
        installSystemBarInsets()
        bindViews()

        val deviceMonitor = DeviceMonitor(this)
        val deviceInfo = deviceMonitor.deviceInfo()
        renderDeviceInfo(deviceInfo)
        controller = CombinedStressController(this, deviceInfo.logicalCoreCount, this)

        installConfigurationListeners()
        configureDurationSpinner()
        applyPreset(StressPreset.EXTREME)
        startButton.setOnClickListener { startSession() }
        stopButton.setOnClickListener { controller.stopAll(StopReason.USER) }
        renderState(CombinedStressState.IDLE)

        if (nativeLoadError == null) {
            controller.initialize()
        } else {
            val message = getString(
                R.string.native_load_failed,
                nativeLoadError.message ?: nativeLoadError.javaClass.simpleName,
            )
            lastErrorValue.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        monitoring = true
        monitorHandler.removeCallbacks(monitorRunnable)
        monitorHandler.post(monitorRunnable)
    }

    override fun onStop() {
        monitoring = false
        monitorHandler.removeCallbacks(monitorRunnable)
        controller.stopAll(StopReason.ACTIVITY_STOPPED)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onDestroy() {
        monitoring = false
        monitorHandler.removeCallbacksAndMessages(null)
        controller.close()
        super.onDestroy()
    }

    override fun onCombinedStateChanged(state: CombinedStressState) {
        renderState(state)
        updateLiveMetrics()
    }

    override fun onGpuInfoAvailable(info: GpuInfo) {
        gpuDeviceValue.text = info.deviceName
        gpuVulkanValue.text = info.apiVersionLabel
        gpuComputeValue.text = if (info.computeQueueSupported) {
            getString(R.string.supported)
        } else {
            getString(R.string.unsupported)
        }
    }

    override fun onSessionFinished(session: StressSessionSnapshot) {
        renderSession(session, session)
    }

    override fun onCombinedError(message: String) {
        lastErrorValue.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun bindViews() {
        statusValue = findViewById(R.id.statusValue)
        elapsedValue = findViewById(R.id.elapsedValue)
        sessionPresetValue = findViewById(R.id.sessionPresetValue)
        sessionDurationValue = findViewById(R.id.sessionDurationValue)
        sessionStopReasonValue = findViewById(R.id.sessionStopReasonValue)
        sessionPeaksValue = findViewById(R.id.sessionPeaksValue)
        lastErrorValue = findViewById(R.id.lastErrorValue)
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
        gpuDeviceValue = findViewById(R.id.gpuDeviceValue)
        gpuVulkanValue = findViewById(R.id.gpuVulkanValue)
        gpuComputeValue = findViewById(R.id.gpuComputeValue)
        gpuTargetValue = findViewById(R.id.gpuTargetValue)
        gpuStatusValue = findViewById(R.id.gpuStatusValue)
        gpuDispatchCountValue = findViewById(R.id.gpuDispatchCountValue)
        gpuDispatchRateValue = findViewById(R.id.gpuDispatchRateValue)
        gpuWorkTimeValue = findViewById(R.id.gpuWorkTimeValue)
        gpuComputeActivityValue = findViewById(R.id.gpuComputeActivityValue)
        gpuOutputValue = findViewById(R.id.gpuOutputValue)
        thermalProtectionValue = findViewById(R.id.thermalProtectionValue)
        thermalStatusValue = findViewById(R.id.thermalStatusValue)
        batteryTemperatureValue = findViewById(R.id.batteryTemperatureValue)
        startTemperatureValue = findViewById(R.id.startTemperatureValue)
        peakTemperatureValue = findViewById(R.id.peakTemperatureValue)
        deltaTemperatureValue = findViewById(R.id.deltaTemperatureValue)
        thermalMessageValue = findViewById(R.id.thermalMessageValue)
        configurationPanel = findViewById(R.id.configurationPanel)
        presetGroup = findViewById(R.id.presetGroup)
        cpuLoadGroup = findViewById(R.id.cpuLoadGroup)
        gpuLoadGroup = findViewById(R.id.gpuLoadGroup)
        ramTargetGroup = findViewById(R.id.ramTargetGroup)
        resourceCpu = findViewById(R.id.resourceCpu)
        resourceGpu = findViewById(R.id.resourceGpu)
        resourceMemory = findViewById(R.id.resourceMemory)
        durationSpinner = findViewById(R.id.durationSpinner)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
    }

    private fun installSystemBarInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        findViewById<View>(R.id.rootScroll).setOnApplyWindowInsetsListener { view, insets ->
            val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
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
        presetGroup.setOnCheckedChangeListener { _, checkedId ->
            if (applyingPreset) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.presetBalanced -> applyPreset(StressPreset.BALANCED)
                R.id.presetHigh -> applyPreset(StressPreset.HIGH)
                R.id.presetExtreme -> applyPreset(StressPreset.EXTREME)
                else -> renderConfigurationPreview()
            }
        }
        val manualGroupListener = RadioGroup.OnCheckedChangeListener { _, _ ->
            markCustomPreset()
        }
        cpuLoadGroup.setOnCheckedChangeListener(manualGroupListener)
        gpuLoadGroup.setOnCheckedChangeListener(manualGroupListener)
        ramTargetGroup.setOnCheckedChangeListener(manualGroupListener)
        val resourceListener = View.OnClickListener { markCustomPreset() }
        resourceCpu.setOnClickListener(resourceListener)
        resourceGpu.setOnClickListener(resourceListener)
        resourceMemory.setOnClickListener(resourceListener)
    }

    private fun configureDurationSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            durations.map { it.displayLabel },
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        durationSpinner.adapter = adapter
        durationSpinner.setSelection(durations.indexOf(StressDuration.MINUTES_5))
        durationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderConfigurationPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun applyPreset(preset: StressPreset) {
        applyingPreset = true
        presetGroup.check(
            when (preset) {
                StressPreset.BALANCED -> R.id.presetBalanced
                StressPreset.HIGH -> R.id.presetHigh
                StressPreset.EXTREME -> R.id.presetExtreme
                StressPreset.CUSTOM -> R.id.presetCustom
            },
        )
        resourceCpu.isChecked = true
        resourceGpu.isChecked = true
        resourceMemory.isChecked = true
        when (preset) {
            StressPreset.BALANCED -> {
                cpuLoadGroup.check(R.id.cpu50)
                gpuLoadGroup.check(R.id.gpu50)
                ramTargetGroup.check(R.id.ram512)
            }
            StressPreset.HIGH -> {
                cpuLoadGroup.check(R.id.cpu75)
                gpuLoadGroup.check(R.id.gpu75)
                ramTargetGroup.check(R.id.ramAuto)
            }
            StressPreset.EXTREME -> {
                cpuLoadGroup.check(R.id.cpu100)
                gpuLoadGroup.check(R.id.gpu100)
                ramTargetGroup.check(R.id.ramAuto)
            }
            StressPreset.CUSTOM -> Unit
        }
        applyingPreset = false
        renderConfigurationPreview()
    }

    private fun markCustomPreset() {
        if (applyingPreset) return
        applyingPreset = true
        presetGroup.check(R.id.presetCustom)
        applyingPreset = false
        renderConfigurationPreview()
    }

    private fun startSession() {
        if (!nativeAvailable || controller.state != CombinedStressState.IDLE) return
        if (!resourceCpu.isChecked && !resourceGpu.isChecked && !resourceMemory.isChecked) {
            Toast.makeText(this, R.string.select_resource, Toast.LENGTH_LONG).show()
            return
        }
        controller.start(configurationFromControls())
    }

    private fun configurationFromControls(): CombinedStressConfiguration =
        CombinedStressConfiguration(
            preset = selectedPreset(),
            cpuEnabled = resourceCpu.isChecked,
            gpuEnabled = resourceGpu.isChecked,
            memoryEnabled = resourceMemory.isChecked,
            cpuTargetPercent = selectedCpuTarget(),
            gpuTargetPercent = selectedGpuTarget(),
            memoryTarget = selectedMemoryTarget(),
            duration = durations.getOrElse(durationSpinner.selectedItemPosition) {
                StressDuration.MINUTES_5
            },
        )

    private fun selectedPreset(): StressPreset = when (presetGroup.checkedRadioButtonId) {
        R.id.presetBalanced -> StressPreset.BALANCED
        R.id.presetHigh -> StressPreset.HIGH
        R.id.presetExtreme -> StressPreset.EXTREME
        else -> StressPreset.CUSTOM
    }

    private fun selectedCpuTarget(): Int = when (cpuLoadGroup.checkedRadioButtonId) {
        R.id.cpu25 -> 25
        R.id.cpu50 -> 50
        R.id.cpu75 -> 75
        else -> 100
    }

    private fun selectedGpuTarget(): Int = when (gpuLoadGroup.checkedRadioButtonId) {
        R.id.gpu25 -> 25
        R.id.gpu50 -> 50
        R.id.gpu75 -> 75
        else -> 100
    }

    private fun selectedMemoryTarget(): MemoryTarget = when (ramTargetGroup.checkedRadioButtonId) {
        R.id.ram256 -> MemoryTarget.MIB_256
        R.id.ram512 -> MemoryTarget.MIB_512
        R.id.ram1gb -> MemoryTarget.GIB_1
        else -> MemoryTarget.AUTO
    }

    private fun updateLiveMetrics() {
        val snapshot = controller.runtimeSnapshot()
        lastSnapshot = snapshot
        renderState(snapshot.state)
        renderRuntime(snapshot)
    }

    private fun renderRuntime(snapshot: CombinedRuntimeSnapshot) {
        elapsedValue.text = formatElapsed(snapshot.elapsedTimeMs)
        appCpuLoadValue.text = formatPercent(snapshot.cpuLoadPercent, 1)
        coreEquivalentValue.text = formatPercent(snapshot.coreEquivalentPercent, 0)
        threadsValue.text = snapshot.cpuThreadCount.toString()

        totalRamValue.text = ByteFormatter.formatBytes(snapshot.memory.totalBytes)
        availableRamValue.text = ByteFormatter.formatBytes(snapshot.memory.availableBytes)
        systemUsedValue.text = ByteFormatter.formatBytes(snapshot.memory.systemUsedBytes)
        appPssValue.text = ByteFormatter.formatBytes(snapshot.memory.appPssBytes)
        nativePssValue.text = ByteFormatter.formatBytes(snapshot.memory.nativePssBytes)
        allocatedValue.text = ByteFormatter.formatBytes(snapshot.allocatedMemoryBytes)
        memoryActivityValue.text = ByteFormatter.formatRate(snapshot.memoryActivityBytesPerSecond)

        gpuDispatchCountValue.text = snapshot.gpu.dispatchCount.toString()
        gpuDispatchRateValue.text = GpuMetricsFormatter.formatDispatchRate(snapshot.gpuDispatchRate)
        gpuComputeActivityValue.text = GpuMetricsFormatter.formatWorkGroupRate(
            snapshot.gpuWorkGroupsPerSecond,
        )
        gpuWorkTimeValue.text = GpuMetricsFormatter.formatGpuWorkTime(
            snapshot.gpu.gpuWorkNanos,
            controller.gpuInfo?.timestampSupported == true,
        )
        gpuOutputValue.text = GpuMetricsFormatter.formatChecksum(snapshot.gpu.outputChecksum)

        val configuration = snapshot.currentSession?.configuration ?: configurationFromControls()
        cpuTargetValue.text = "${configuration.cpuTargetPercent}%"
        gpuTargetValue.text = "${configuration.gpuTargetPercent}%"
        stressTargetValue.text = snapshot.currentSession?.let {
            ByteFormatter.formatBytes(it.resolvedMemoryTargetBytes)
        } ?: formatMemoryTarget(configuration.memoryTarget, snapshot.memory.availableBytes)
        renderResourceStates(configuration, snapshot)
        renderThermal(snapshot)
        renderSession(snapshot.currentSession, snapshot.lastSession)
    }

    private fun renderResourceStates(
        configuration: CombinedStressConfiguration,
        snapshot: CombinedRuntimeSnapshot,
    ) {
        cpuStateValue.text = resourceState(
            configuration.cpuEnabled,
            snapshot.state,
            snapshot.cpuThreadCount > 0,
        )
        memoryStateValue.text = resourceState(
            configuration.memoryEnabled,
            snapshot.state,
            snapshot.allocatedMemoryBytes > 0L,
        )
        gpuStatusValue.text = if (!configuration.gpuEnabled &&
            snapshot.state != CombinedStressState.STARTING
        ) {
            "OFF"
        } else {
            when (snapshot.state) {
                CombinedStressState.STARTING -> "STARTING"
                CombinedStressState.STOPPING -> "STOPPING"
                CombinedStressState.THERMAL_LIMITED -> "THERMAL_LIMITED"
                CombinedStressState.ERROR -> "ERROR"
                else -> snapshot.gpu.status.name
            }
        }
        cpuStateValue.setTextColor(resourceColor(cpuStateValue.text.toString()))
        memoryStateValue.setTextColor(resourceColor(memoryStateValue.text.toString()))
        gpuStatusValue.setTextColor(resourceColor(gpuStatusValue.text.toString()))
    }

    private fun resourceState(
        enabled: Boolean,
        state: CombinedStressState,
        nativeRunning: Boolean,
    ): String {
        if (!enabled && state != CombinedStressState.STARTING) return "OFF"
        return when (state) {
            CombinedStressState.STARTING -> "STARTING"
            CombinedStressState.RUNNING -> if (nativeRunning) "RUNNING" else "ERROR"
            CombinedStressState.STOPPING -> "STOPPING"
            CombinedStressState.THERMAL_LIMITED -> "THERMAL_LIMITED"
            CombinedStressState.ERROR -> "ERROR"
            CombinedStressState.IDLE -> "OFF"
        }
    }

    private fun renderThermal(snapshot: CombinedRuntimeSnapshot) {
        thermalProtectionValue.setTextColor(getColor(R.color.safe))
        thermalStatusValue.text = snapshot.thermal.statusLabel
        thermalStatusValue.setTextColor(thermalColor(snapshot.thermal.status))
        batteryTemperatureValue.text = formatTemperature(
            snapshot.thermal.batteryTemperatureCelsius,
        )
        val session = snapshot.currentSession ?: snapshot.lastSession
        startTemperatureValue.text = formatTemperature(session?.startBatteryTemperatureCelsius)
        peakTemperatureValue.text = formatTemperature(session?.peakBatteryTemperatureCelsius)
        val comparisonTemperature = if (snapshot.currentSession != null) {
            snapshot.thermal.batteryTemperatureCelsius
        } else {
            session?.peakBatteryTemperatureCelsius
        }
        deltaTemperatureValue.text = if (
            session?.startBatteryTemperatureCelsius != null && comparisonTemperature != null
        ) {
            String.format(
                Locale.US,
                "%+.1f °C",
                comparisonTemperature - session.startBatteryTemperatureCelsius,
            )
        } else {
            getString(R.string.not_available)
        }
        val warming = snapshot.thermal.status >= PowerManager.THERMAL_STATUS_MODERATE
        thermalMessageValue.setText(
            if (warming) R.string.thermal_warming else R.string.thermal_normal,
        )
        thermalMessageValue.setTextColor(
            if (warming) getColor(R.color.warning) else getColor(R.color.text_secondary),
        )
    }

    private fun renderSession(
        current: StressSessionSnapshot?,
        last: StressSessionSnapshot?,
    ) {
        val session = current ?: last
        val configuration = current?.configuration ?: if (last == null) {
            configurationFromControls()
        } else {
            last.configuration
        }
        sessionPresetValue.text = configuration.preset.name
        sessionDurationValue.text = configuration.duration.displayLabel
        sessionStopReasonValue.text = session?.stopReason?.name ?: getString(R.string.not_available)
        sessionPeaksValue.text = session?.let {
            String.format(
                Locale.US,
                "CPU %.1f%% · GPU %.1f/s · PSS %s",
                it.peakCpuLoadPercent,
                it.peakDispatchRate,
                ByteFormatter.formatBytes(it.peakAppPssBytes),
            )
        } ?: getString(R.string.waiting)
        lastErrorValue.text = session?.lastError ?: controller.lastError ?: getString(R.string.none)
    }

    private fun renderConfigurationPreview() {
        if (!::durationSpinner.isInitialized || durationSpinner.adapter == null) return
        val configuration = configurationFromControls()
        val availableBytes = lastSnapshot?.memory?.availableBytes ?: 0L
        if (controller.state == CombinedStressState.IDLE) {
            sessionPresetValue.text = configuration.preset.name
            sessionDurationValue.text = configuration.duration.displayLabel
            cpuTargetValue.text = "${configuration.cpuTargetPercent}%"
            gpuTargetValue.text = "${configuration.gpuTargetPercent}%"
            stressTargetValue.text = formatMemoryTarget(configuration.memoryTarget, availableBytes)
        }
        startButton.setText(
            if (configuration.preset == StressPreset.EXTREME &&
                configuration.cpuEnabled && configuration.gpuEnabled &&
                configuration.memoryEnabled
            ) {
                R.string.start_extreme
            } else {
                R.string.start_session
            },
        )
    }

    private fun renderState(state: CombinedStressState) {
        statusValue.text = state.name
        statusValue.setTextColor(
            when (state) {
                CombinedStressState.RUNNING -> getColor(R.color.safe)
                CombinedStressState.STARTING,
                CombinedStressState.STOPPING,
                CombinedStressState.THERMAL_LIMITED,
                -> getColor(R.color.warning)
                CombinedStressState.ERROR -> getColor(R.color.danger)
                CombinedStressState.IDLE -> getColor(R.color.text_secondary)
            },
        )
        if (state == CombinedStressState.RUNNING) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val canConfigure = state == CombinedStressState.IDLE
        setEnabledRecursively(configurationPanel, canConfigure)
        configurationPanel.alpha = if (canConfigure) 1.0f else 0.55f
        startButton.isEnabled = nativeAvailable && canConfigure
        stopButton.isEnabled = state == CombinedStressState.STARTING ||
            state == CombinedStressState.RUNNING ||
            state == CombinedStressState.THERMAL_LIMITED ||
            state == CombinedStressState.ERROR
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setEnabledRecursively(view.getChildAt(index), enabled)
            }
        }
    }

    private fun formatMemoryTarget(target: MemoryTarget, availableBytes: Long): String {
        val fixed = target.fixedBytes
        if (fixed != null) return ByteFormatter.formatBytes(fixed)
        if (availableBytes <= 0L) return "Auto"
        val estimated = min(availableBytes / 5L, MAX_AUTO_TARGET_BYTES).floorToMib()
        return "${ByteFormatter.formatBytes(estimated)} (Auto)"
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatTemperature(value: Double?): String = value?.let {
        String.format(Locale.US, "%.1f °C", it)
    } ?: getString(R.string.not_available)

    private fun formatPercent(value: Double, decimals: Int): String =
        String.format(Locale.US, if (decimals == 0) "%.0f%%" else "%.1f%%", value)

    private fun thermalColor(status: Int): Int = when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> getColor(R.color.danger)
        status >= PowerManager.THERMAL_STATUS_MODERATE -> getColor(R.color.warning)
        else -> getColor(R.color.safe)
    }

    private fun resourceColor(state: String): Int = when (state) {
        "RUNNING" -> getColor(R.color.safe)
        "STARTING", "STOPPING", "THERMAL_LIMITED" -> getColor(R.color.warning)
        "ERROR" -> getColor(R.color.danger)
        else -> getColor(R.color.text_secondary)
    }

    private fun Long.floorToMib(): Long = this / MIB * MIB

    companion object {
        private const val MONITOR_INTERVAL_MS = 1_250L
        private const val MIB = 1024L * 1024L
        private const val MAX_AUTO_TARGET_BYTES = 1536L * MIB
    }
}
