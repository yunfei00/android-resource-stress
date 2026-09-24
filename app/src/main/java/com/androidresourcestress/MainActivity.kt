package com.androidresourcestress

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : LocalizedActivity(), StressForegroundService.Observer {
    private lateinit var preferences: AppPreferences
    private lateinit var stateValue: TextView
    private lateinit var thermalValue: TextView
    private lateinit var elapsedValue: TextView
    private lateinit var topTitle: TextView
    private lateinit var cpuCard: Button
    private lateinit var gpuCard: Button
    private lateinit var memoryCard: Button
    private lateinit var storageCard: Button
    private lateinit var presetGroup: RadioGroup
    private lateinit var durationSpinner: Spinner
    private lateinit var screenModeGroup: RadioGroup
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val durations = arrayOf(
        StressDuration.MINUTE_1,
        StressDuration.MINUTES_5,
        StressDuration.MINUTES_10,
        StressDuration.MINUTES_30,
        StressDuration.CONTINUOUS,
    )
    private var configuration = PresetConfigurations.create(StressPreset.EXTREME)
    private var applyingControls = false
    private var service: StressForegroundService? = null
    private var serviceBound = false
    private var latestSnapshot: CombinedRuntimeSnapshot? = null
    private var screenOffPromptPending = false
    private var visualAutoLaunchedSessionId: Long? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? StressForegroundService.LocalBinder)?.service
            serviceBound = service != null
            service?.addObserver(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeObserver(this@MainActivity)
            service = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeStress.ensureLoaded()
        setContentView(R.layout.activity_main)
        installInsets()
        preferences = AppPreferences(this)
        configuration = preferences.loadConfiguration()
        bindViews()
        configureDuration()
        installListeners()
        applyConfiguration(configuration)
        render(null, CombinedStressState.IDLE)
        requestNotificationPermissionIfNeeded()
        DiagnosticLog(this).record("App started version=${BuildConfig.VERSION_NAME}; dashboard V2")
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, StressForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        service?.removeObserver(this)
        if (serviceBound) unbindService(serviceConnection)
        serviceBound = false
        service = null
        handler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onStressSnapshot(snapshot: CombinedRuntimeSnapshot) {
        latestSnapshot = snapshot
        snapshot.currentSession?.configuration?.let { configuration = it }
        render(snapshot, snapshot.state)
        val session = snapshot.currentSession
        if (session != null && session.configuration.gpuEnabled &&
            session.configuration.gpuMode.requiresOnscreenSurface &&
            session.screenOnAtElapsedMs != null &&
            getSystemService(PowerManager::class.java).isInteractive &&
            visualAutoLaunchedSessionId != session.sessionId
        ) {
            visualAutoLaunchedSessionId = session.sessionId
            launchGpuSurfaceActivity(session.configuration)
        }
    }

    override fun onStressStateChanged(state: CombinedStressState) {
        render(latestSnapshot, state)
        if (state == CombinedStressState.RUNNING && screenOffPromptPending) {
            screenOffPromptPending = false
            showScreenOffCountdown()
        }
    }

    override fun onSessionFinished(session: StressSessionSnapshot) {
        Toast.makeText(
            this,
            getString(R.string.session_finished_toast, stopReasonLabel(session.stopReason)),
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onStressError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun bindViews() {
        topTitle = findViewById(R.id.topTitle)
        stateValue = findViewById(R.id.dashboardState)
        thermalValue = findViewById(R.id.dashboardThermal)
        elapsedValue = findViewById(R.id.dashboardElapsed)
        cpuCard = findViewById(R.id.cpuCard)
        gpuCard = findViewById(R.id.gpuCard)
        memoryCard = findViewById(R.id.memoryCard)
        storageCard = findViewById(R.id.storageCard)
        presetGroup = findViewById(R.id.compactPresetGroup)
        durationSpinner = findViewById(R.id.compactDuration)
        screenModeGroup = findViewById(R.id.screenModeGroup)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
    }

    private fun installListeners() {
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.monitorNavButton).setOnClickListener {
            startActivity(Intent(this, MonitorActivity::class.java))
        }
        findViewById<Button>(R.id.historyNavButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        cpuCard.setOnClickListener {
            if (isIdle()) showCpuDialog() else openMonitor()
        }
        gpuCard.setOnClickListener {
            if (isIdle()) showGpuDialog() else if (
                configuration.gpuEnabled && configuration.gpuMode.requiresOnscreenSurface
            ) {
                launchGpuSurfaceActivity(configuration)
            } else openMonitor()
        }
        memoryCard.setOnClickListener {
            if (isIdle()) showMemoryDialog() else openMonitor()
        }
        storageCard.setOnClickListener {
            if (isIdle()) showStorageDialog() else openMonitor()
        }
        presetGroup.setOnCheckedChangeListener { _, id ->
            if (applyingControls || !isIdle()) return@setOnCheckedChangeListener
            val preset = when (id) {
                R.id.presetBalanced -> StressPreset.BALANCED
                R.id.presetHigh -> StressPreset.HIGH
                R.id.presetExtreme -> StressPreset.EXTREME
                else -> StressPreset.CUSTOM
            }
            if (preset != StressPreset.CUSTOM) {
                configuration = PresetConfigurations.create(preset, selectedDuration()).copy(
                    screenMode = selectedScreenMode(),
                )
                saveAndRenderConfiguration()
            }
        }
        screenModeGroup.setOnCheckedChangeListener { _, _ ->
            if (applyingControls || !isIdle()) return@setOnCheckedChangeListener
            configuration = configuration.copy(screenMode = selectedScreenMode())
            saveAndRenderConfiguration()
        }
        startButton.setOnClickListener { requestStart() }
        stopButton.setOnClickListener {
            (service?.let { it.stopSession(StopReason.USER_STOP) }
                ?: StressForegroundService.stop(this))
        }
    }

    private fun configureDuration() {
        durationSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            durations.map(::durationLabel),
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        durationSpinner.setSelection(durations.indexOf(configuration.duration).coerceAtLeast(0))
        durationSpinner.onItemSelectedListener = SimpleItemSelectedListener {
            if (!applyingControls && isIdle()) {
                configuration = configuration.copy(duration = selectedDuration())
                preferences.saveConfiguration(configuration)
            }
        }
    }

    private fun requestStart() {
        configuration = configuration.copy(
            duration = selectedDuration(),
            screenMode = selectedScreenMode(),
        )
        if (!configuration.cpuEnabled && !configuration.gpuEnabled &&
            !configuration.memoryEnabled && !configuration.storageEnabled
        ) {
            Toast.makeText(this, R.string.select_resource, Toast.LENGTH_LONG).show()
            return
        }
        if (configuration.screenMode == ScreenMode.OFF && configuration.gpuEnabled &&
            configuration.gpuMode.requiresOnscreenSurface
        ) {
            AlertDialog.Builder(this)
                .setTitle(R.string.screen_off_visual_title)
                .setMessage(R.string.screen_off_visual_message)
                .setPositiveButton(R.string.start_session) { _, _ -> startSession() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            startSession()
        }
    }

    private fun startSession() {
        preferences.saveConfiguration(configuration)
        screenOffPromptPending = configuration.screenMode == ScreenMode.OFF
        StressForegroundService.start(this, configuration)
        if (configuration.screenMode == ScreenMode.ON && configuration.gpuEnabled &&
            configuration.gpuMode.requiresOnscreenSurface
        ) {
            launchGpuSurfaceActivity(configuration)
        }
    }

    private fun showScreenOffCountdown() {
        var remaining = 3
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.screen_off_countdown, remaining))
            .setMessage(R.string.screen_off_preparing)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.open_monitor) { _, _ -> openMonitor() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            val tick = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing) return
                    remaining -= 1
                    if (remaining > 0) {
                        dialog.setTitle(getString(R.string.screen_off_countdown, remaining))
                        handler.postDelayed(this, 1_000L)
                    } else {
                        dialog.setTitle(R.string.screen_off_manual_title)
                        dialog.setMessage(getString(R.string.screen_off_manual_message))
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }
            }
            handler.postDelayed(tick, 1_000L)
        }
        dialog.show()
    }

    private fun showCpuDialog() {
        val enabled = CheckBox(this).apply {
            setText(R.string.enabled)
            isChecked = configuration.cpuEnabled
        }
        val target = choiceGroup(
            listOf(25 to R.string.load_25, 50 to R.string.load_50, 75 to R.string.load_75, 100 to R.string.load_100),
            configuration.cpuTargetPercent,
        )
        showConfigDialog(R.string.resource_cpu, enabled, target) {
            configuration = configuration.copy(
                preset = StressPreset.CUSTOM,
                cpuEnabled = enabled.isChecked,
                cpuTargetPercent = target.checkedRadioButtonId,
            )
        }
    }

    private fun showGpuDialog() {
        val enabled = CheckBox(this).apply {
            setText(R.string.enabled)
            isChecked = configuration.gpuEnabled
        }
        val mode = choiceGroup(
            GpuMode.entries.map { gpuMode ->
                100 + gpuMode.ordinal to gpuMode.labelResource()
            },
            100 + configuration.gpuMode.ordinal,
        )
        val target = choiceGroup(
            listOf(25 to R.string.load_25, 50 to R.string.load_50, 75 to R.string.load_75, 100 to R.string.load_100),
            configuration.gpuTargetPercent,
        )
        val gpu3dLevel = choiceGroup(
            Gpu3dStressLevel.entries.map { level ->
                200 + level.ordinal to level.labelResource()
            },
            200 + configuration.gpu3dLevel.ordinal,
        )
        val gpu3dRunMode = choiceGroup(
            Gpu3dRunMode.entries.map { runMode ->
                300 + runMode.ordinal to runMode.labelResource()
            },
            300 + configuration.gpu3dRunMode.ordinal,
        )
        val gpu3dStepDuration = choiceGroup(
            listOf(
                10 to R.string.gpu3d_auto_10s,
                20 to R.string.gpu3d_auto_20s,
                30 to R.string.gpu3d_auto_30s,
                60 to R.string.gpu3d_auto_60s,
            ),
            configuration.gpu3dStepDurationSeconds.takeIf { it in setOf(10, 20, 30, 60) }
                ?: 20,
        )
        showConfigDialog(
            R.string.resource_gpu,
            enabled,
            labeledGroup(R.string.gpu_mode, mode),
            target,
            labeledGroup(R.string.gpu3d_stress_level, gpu3dLevel),
            labeledGroup(R.string.gpu3d_run_mode, gpu3dRunMode),
            labeledGroup(R.string.gpu3d_step_duration, gpu3dStepDuration),
        ) {
            configuration = configuration.copy(
                preset = StressPreset.CUSTOM,
                gpuEnabled = enabled.isChecked,
                gpuMode = GpuMode.entries[mode.checkedRadioButtonId - 100],
                gpuTargetPercent = target.checkedRadioButtonId,
                gpu3dLevel = Gpu3dStressLevel.entries[
                    gpu3dLevel.checkedRadioButtonId - 200
                ],
                gpu3dRunMode = Gpu3dRunMode.entries[
                    gpu3dRunMode.checkedRadioButtonId - 300
                ],
                gpu3dStepDurationSeconds = gpu3dStepDuration.checkedRadioButtonId,
            )
        }
    }

    private fun showMemoryDialog() {
        val enabled = CheckBox(this).apply {
            setText(R.string.enabled)
            isChecked = configuration.memoryEnabled
        }
        val choices = choiceGroup(
            listOf(
                100 + MemoryTarget.MIB_256.ordinal to R.string.ram_256,
                100 + MemoryTarget.MIB_512.ordinal to R.string.ram_512,
                100 + MemoryTarget.GIB_1.ordinal to R.string.ram_1gb,
                100 + MemoryTarget.AUTO.ordinal to R.string.ram_auto,
            ),
            100 + configuration.memoryTarget.ordinal,
        )
        showConfigDialog(R.string.resource_memory, enabled, choices) {
            configuration = configuration.copy(
                preset = StressPreset.CUSTOM,
                memoryEnabled = enabled.isChecked,
                memoryTarget = MemoryTarget.entries[choices.checkedRadioButtonId - 100],
            )
        }
    }

    private fun showStorageDialog() {
        val enabled = CheckBox(this).apply {
            setText(R.string.enabled)
            isChecked = configuration.storageEnabled
        }
        val mode = choiceGroup(
            listOf(
                100 + StorageMode.READ.ordinal to R.string.storage_read,
                100 + StorageMode.MIXED.ordinal to R.string.storage_mixed,
                100 + StorageMode.WRITE.ordinal to R.string.storage_write,
            ),
            100 + configuration.storageMode.ordinal,
        )
        val level = choiceGroup(
            listOf(
                200 + StorageLevel.LOW.ordinal to R.string.storage_low,
                200 + StorageLevel.MEDIUM.ordinal to R.string.storage_medium,
                200 + StorageLevel.HIGH.ordinal to R.string.storage_high,
            ),
            200 + configuration.storageLevel.ordinal,
        )
        val wasEnabled = configuration.storageEnabled
        showConfigDialog(
            R.string.resource_storage,
            enabled,
            labeledGroup(R.string.storage_mode, mode),
            labeledGroup(R.string.storage_level, level),
        ) {
            val apply = {
                configuration = configuration.copy(
                    preset = StressPreset.CUSTOM,
                    storageEnabled = enabled.isChecked,
                    storageMode = StorageMode.entries[mode.checkedRadioButtonId - 100],
                    storageLevel = StorageLevel.entries[level.checkedRadioButtonId - 200],
                )
                saveAndRenderConfiguration()
            }
            if (!wasEnabled && enabled.isChecked && preferences.confirmStorageStress) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.storage_warning_title)
                    .setMessage(R.string.storage_warning_message)
                    .setPositiveButton(R.string.enable) { _, _ -> apply() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                false
            } else {
                apply()
                false
            }
        }
    }

    private fun showConfigDialog(title: Int, vararg views: View, onApply: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            views.forEach(::addView)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.apply) { _, _ ->
                onApply()
                saveAndRenderConfiguration()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun choiceGroup(options: List<Pair<Int, Int>>, checked: Int): RadioGroup =
        RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            options.forEach { (id, label) ->
                addView(RadioButton(this@MainActivity).apply {
                    this.id = id
                    setText(label)
                    isChecked = id == checked
                })
            }
        }

    private fun labeledGroup(label: Int, group: RadioGroup): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                setText(label)
                setTextColor(getColor(R.color.text_secondary))
            })
            addView(group)
        }

    private fun saveAndRenderConfiguration() {
        preferences.saveConfiguration(configuration)
        applyConfiguration(configuration)
        render(latestSnapshot, service?.state ?: CombinedStressState.IDLE)
    }

    private fun applyConfiguration(value: CombinedStressConfiguration) {
        applyingControls = true
        configuration = value
        presetGroup.check(
            when (value.preset) {
                StressPreset.BALANCED -> R.id.presetBalanced
                StressPreset.HIGH -> R.id.presetHigh
                StressPreset.EXTREME -> R.id.presetExtreme
                StressPreset.CUSTOM -> R.id.presetCustom
            },
        )
        screenModeGroup.check(
            if (value.screenMode == ScreenMode.OFF) R.id.screenModeOff else R.id.screenModeOn,
        )
        durationSpinner.setSelection(durations.indexOf(value.duration).coerceAtLeast(0))
        applyingControls = false
    }

    private fun render(snapshot: CombinedRuntimeSnapshot?, state: CombinedStressState) {
        val running = state != CombinedStressState.IDLE
        val active = snapshot?.currentSession?.configuration ?: configuration
        topTitle.text = if (running) {
            getString(R.string.running_top_title, presetLabel(active.preset), DurationFormatter.format(snapshot?.elapsedTimeMs ?: 0L))
        } else getString(R.string.dashboard_title)
        stateValue.text = combinedStateLabel(state)
        stateValue.setTextColor(
            when (state) {
                CombinedStressState.RUNNING -> getColor(R.color.safe)
                CombinedStressState.ERROR -> getColor(R.color.danger)
                CombinedStressState.IDLE -> getColor(R.color.text_secondary)
                else -> getColor(R.color.warning)
            },
        )
        elapsedValue.text = DurationFormatter.format(snapshot?.elapsedTimeMs ?: 0L)
        val thermal = snapshot?.thermal?.status ?: PowerManager.THERMAL_STATUS_NONE
        thermalValue.text = getString(R.string.dashboard_thermal, thermalStatusDisplay(thermal))
        thermalValue.setTextColor(
            when {
                thermal >= PowerManager.THERMAL_STATUS_SEVERE -> getColor(R.color.danger)
                thermal >= PowerManager.THERMAL_STATUS_MODERATE -> getColor(R.color.warning)
                else -> getColor(R.color.safe)
            },
        )
        if (state == CombinedStressState.RUNNING && snapshot != null) {
            cpuCard.text = getString(R.string.resource_card_cpu, formatPercent(snapshot.cpuLoadPercent))
            gpuCard.text = getString(
                R.string.resource_card_gpu,
                gpuModeLabel(active.gpuMode),
                if (active.gpuMode.requiresOnscreenSurface && snapshot.visualFps > 0.0) {
                    getString(R.string.fps_value, snapshot.visualFps)
                } else GpuMetricsFormatter.formatDispatchRate(snapshot.gpuDispatchRate),
            )
            memoryCard.text = getString(R.string.resource_card_ram, ByteFormatter.formatRate(snapshot.memoryActivityBytesPerSecond))
            storageCard.text = getString(
                R.string.resource_card_storage,
                if (active.storageEnabled) ByteFormatter.formatRate(snapshot.storage.writeActivityBytesPerSecond) else getString(R.string.status_off),
            )
        } else {
            cpuCard.text = getString(R.string.resource_card_cpu, if (active.cpuEnabled) getString(R.string.percent_value, active.cpuTargetPercent) else getString(R.string.status_off))
            gpuCard.text = getString(R.string.resource_card_gpu, gpuModeLabel(active.gpuMode), if (active.gpuEnabled) getString(R.string.percent_value, active.gpuTargetPercent) else getString(R.string.status_off))
            memoryCard.text = getString(R.string.resource_card_ram, if (active.memoryEnabled) memoryTargetLabel(active.memoryTarget) else getString(R.string.status_off))
            storageCard.text = getString(R.string.resource_card_storage, if (active.storageEnabled) getString(R.string.storage_enabled_compact, active.storageMode.name, storageLevelLabel(active.storageLevel)) else getString(R.string.status_off))
        }
        val canConfigure = state == CombinedStressState.IDLE
        presetGroup.isEnabled = canConfigure
        for (index in 0 until presetGroup.childCount) presetGroup.getChildAt(index).isEnabled = canConfigure
        durationSpinner.isEnabled = canConfigure
        screenModeGroup.isEnabled = canConfigure
        for (index in 0 until screenModeGroup.childCount) screenModeGroup.getChildAt(index).isEnabled = canConfigure
        startButton.isEnabled = canConfigure
        stopButton.isEnabled = !canConfigure
        if (state == CombinedStressState.RUNNING && preferences.keepScreenOnWhileRunning &&
            active.screenMode == ScreenMode.ON
        ) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun isIdle(): Boolean = (service?.state ?: CombinedStressState.IDLE) == CombinedStressState.IDLE
    private fun openMonitor() = startActivity(Intent(this, MonitorActivity::class.java))
    private fun launchGpuSurfaceActivity(configuration: CombinedStressConfiguration) {
        val intent = if (configuration.gpuMode.usesGpu3d) {
            Gpu3dStressActivity.serviceSessionIntent(this)
        } else {
            Intent(this, GpuVisualActivity::class.java)
        }
        startActivity(intent)
    }

    private fun selectedDuration(): StressDuration = durations.getOrElse(durationSpinner.selectedItemPosition) { StressDuration.MINUTES_5 }
    private fun selectedScreenMode(): ScreenMode = if (screenModeGroup.checkedRadioButtonId == R.id.screenModeOff) ScreenMode.OFF else ScreenMode.ON
    private fun formatPercent(value: Double): String = String.format(java.util.Locale.US, "%.1f%%", value)
    private fun memoryTargetLabel(target: MemoryTarget): String = getString(
        when (target) {
            MemoryTarget.MIB_256 -> R.string.ram_256
            MemoryTarget.MIB_512 -> R.string.ram_512
            MemoryTarget.GIB_1 -> R.string.ram_1gb
            MemoryTarget.AUTO -> R.string.ram_auto
        },
    )
    private fun storageLevelLabel(level: StorageLevel): String = getString(
        when (level) {
            StorageLevel.LOW -> R.string.storage_low
            StorageLevel.MEDIUM -> R.string.storage_medium
            StorageLevel.HIGH -> R.string.storage_high
        },
    )

    private fun installInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        findViewById<View>(R.id.dashboardRoot).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(12.dp + bars.left, bars.top, 12.dp + bars.right, bars.bottom)
            insets
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 500)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

private class SimpleItemSelectedListener(
    private val selected: () -> Unit,
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = selected()
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
