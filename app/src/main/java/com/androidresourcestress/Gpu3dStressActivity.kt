package com.androidresourcestress

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class Gpu3dStressActivity : LocalizedActivity() {
    private lateinit var surface: Gpu3dStressSurfaceView
    private lateinit var status: TextView
    private lateinit var currentFps: TextView
    private lateinit var averageFps: TextView
    private lateinit var minimumFps: TextView
    private lateinit var frameTime: TextView
    private lateinit var maximumFrameTime: TextView
    private lateinit var runtime: TextView
    private lateinit var sceneSpinner: Spinner
    private lateinit var stressLevelSpinner: Spinner
    private lateinit var renderResolution: TextView
    private lateinit var fpsLimit: TextView
    private lateinit var profileSummary: TextView
    private lateinit var traversalModeSpinner: Spinner
    private lateinit var autoDurationSpinner: Spinner
    private lateinit var customDuration: EditText
    private lateinit var autoStartButton: Button
    private lateinit var autoStatus: TextView
    private lateinit var autoResultsView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var reportedError = ""
    private var autoPlan: Gpu3dAutoTestPlan? = null
    private var autoStepIndex = 0
    private var autoStepStartedWallMs = 0L
    private val autoResults = mutableListOf<Gpu3dAutoTestStepResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (activityManager.deviceConfigurationInfo.reqGlEsVersion < REQUIRED_GLES_VERSION) {
            Toast.makeText(this, R.string.gpu3d_es3_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_gpu3d_stress)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        }
        bindViews()
        configureControls(savedInstanceState)
        startButton.setOnClickListener { startTest() }
        autoStartButton.setOnClickListener { startAutoTest() }
        stopButton.setOnClickListener { stopTest() }
        renderMetrics(surface.snapshot())
        renderAutoResults()
    }

    override fun onResume() {
        super.onResume()
        if (!::surface.isInitialized) return
        resumed = true
        surface.onResume()
        handler.post(metricsRunnable)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacksAndMessages(null)
        if (::surface.isInitialized) {
            stopTest()
            surface.onPause()
        }
        super.onPause()
    }

    override fun onStop() {
        if (::surface.isInitialized) stopTest()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::surface.isInitialized) surface.stopTest()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::stressLevelSpinner.isInitialized) {
            outState.putInt(STATE_LEVEL, stressLevelSpinner.selectedItemPosition)
            outState.putInt(STATE_SCENE, sceneSpinner.selectedItemPosition)
            outState.putInt(STATE_TRAVERSAL, traversalModeSpinner.selectedItemPosition)
            outState.putInt(STATE_AUTO_DURATION, autoDurationSpinner.selectedItemPosition)
            outState.putString(STATE_CUSTOM_DURATION, customDuration.text.toString())
        }
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        surface = findViewById(R.id.gpu3dSurface)
        status = findViewById(R.id.gpu3dStatusValue)
        currentFps = findViewById(R.id.gpu3dCurrentFpsValue)
        averageFps = findViewById(R.id.gpu3dAverageFpsValue)
        minimumFps = findViewById(R.id.gpu3dMinimumFpsValue)
        frameTime = findViewById(R.id.gpu3dFrameTimeValue)
        maximumFrameTime = findViewById(R.id.gpu3dMaximumFrameTimeValue)
        runtime = findViewById(R.id.gpu3dRuntimeValue)
        sceneSpinner = findViewById(R.id.gpu3dSceneSpinner)
        stressLevelSpinner = findViewById(R.id.gpu3dStressLevelSpinner)
        renderResolution = findViewById(R.id.gpu3dRenderResolutionValue)
        fpsLimit = findViewById(R.id.gpu3dFpsLimitValue)
        profileSummary = findViewById(R.id.gpu3dProfileSummary)
        traversalModeSpinner = findViewById(R.id.gpu3dTraversalModeSpinner)
        autoDurationSpinner = findViewById(R.id.gpu3dAutoDurationSpinner)
        customDuration = findViewById(R.id.gpu3dCustomDuration)
        autoStartButton = findViewById(R.id.gpu3dAutoStartButton)
        autoStatus = findViewById(R.id.gpu3dAutoStatus)
        autoResultsView = findViewById(R.id.gpu3dAutoResults)
        startButton = findViewById(R.id.gpu3dStartButton)
        stopButton = findViewById(R.id.gpu3dStopButton)
    }

    private fun configureControls(savedInstanceState: Bundle?) {
        sceneSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Gpu3dStressScene.entries.map { getString(it.labelResource()) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        stressLevelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Gpu3dStressLevel.entries.map { getString(it.labelResource()) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        traversalModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Gpu3dTraversalMode.entries.map { getString(it.labelResource()) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        autoDurationSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Gpu3dAutoDuration.entries.map { getString(it.labelResource()) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        stressLevelSpinner.setSelection(
            savedInstanceState?.getInt(STATE_LEVEL) ?: Gpu3dStressLevel.MEDIUM.ordinal,
        )
        sceneSpinner.setSelection(
            savedInstanceState?.getInt(STATE_SCENE) ?: Gpu3dStressScene.WATER_RACE.ordinal,
        )
        traversalModeSpinner.setSelection(
            savedInstanceState?.getInt(STATE_TRAVERSAL) ?: Gpu3dTraversalMode.LEVELS.ordinal,
        )
        autoDurationSpinner.setSelection(
            savedInstanceState?.getInt(STATE_AUTO_DURATION) ?: Gpu3dAutoDuration.SECONDS_10.ordinal,
        )
        customDuration.setText(savedInstanceState?.getString(STATE_CUSTOM_DURATION) ?: "15")
        val selectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                renderProfile(selectedConfiguration().workload)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        sceneSpinner.onItemSelectedListener = selectionListener
        stressLevelSpinner.onItemSelectedListener = selectionListener
        autoDurationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                updateCustomDurationEnabled()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateCustomDurationEnabled()
        renderProfile(selectedConfiguration().workload)
    }

    private fun startTest() {
        if (autoPlan != null) return
        startConfiguration(selectedConfiguration())
    }

    private fun startAutoTest() {
        if (!resumed || surface.snapshot().running || autoPlan != null) return
        val duration = selectedAutoDuration()
        val customSeconds = customDuration.text.toString().trim().toIntOrNull()
        val seconds = runCatching { duration.resolveSeconds(customSeconds) }.getOrElse {
            Toast.makeText(this, R.string.gpu3d_auto_invalid_duration, Toast.LENGTH_LONG).show()
            return
        }
        autoPlan = Gpu3dAutoTestPlan.create(
            traversalMode = selectedTraversalMode(),
            selected = selectedConfiguration(),
            stepDurationSeconds = seconds,
        )
        autoStepIndex = 0
        autoResults.clear()
        renderAutoResults()
        startAutoStep()
    }

    private fun startAutoStep() {
        val plan = autoPlan ?: return
        val configuration = plan.steps[autoStepIndex].configuration
        sceneSpinner.setSelection(configuration.scene.ordinal)
        stressLevelSpinner.setSelection(configuration.level.ordinal)
        autoStepStartedWallMs = 0L
        startConfiguration(configuration)
        renderAutoProgress(0L)
    }

    private fun startConfiguration(configuration: Gpu3dStressConfiguration) {
        if (!resumed || surface.snapshot().running) return
        reportedError = ""
        surface.startTest(configuration)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setControlsRunning(true)
        val workload = configuration.workload
        renderMetrics(
            Gpu3dStressMetrics(
                running = true,
                level = configuration.level,
                scene = configuration.scene,
                resolution = configuration.resolution,
                fpsLimit = configuration.fpsLimit,
                renderWidth = configuration.profile.renderWidth,
                renderHeight = configuration.profile.renderHeight,
                msaaSamples = configuration.profile.msaaSamples,
                modelCount = workload.sceneModelCount,
                triangleCount = workload.triangleCount,
                particleCount = workload.particleCount,
                particleLayers = workload.particleLayers,
                shaderIterations = workload.shaderIterations,
                overdrawLayers = workload.fullScreenOverdrawLayers,
            ),
        )
    }

    private fun stopTest() {
        if (!::surface.isInitialized) return
        if (autoPlan != null) {
            recordAutoResult(surface.snapshot())
            autoPlan = null
            autoStatus.text = getString(R.string.gpu3d_auto_stopped)
            renderAutoResults()
        }
        stopRenderer()
    }

    private fun stopRenderer() {
        surface.stopTest()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setControlsRunning(false)
        renderMetrics(surface.snapshot())
    }

    private fun setControlsRunning(running: Boolean) {
        sceneSpinner.isEnabled = !running
        stressLevelSpinner.isEnabled = !running
        traversalModeSpinner.isEnabled = !running
        autoDurationSpinner.isEnabled = !running
        autoStartButton.isEnabled = !running
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        updateCustomDurationEnabled(running)
    }

    private fun updateCustomDurationEnabled(running: Boolean = stopButton.isEnabled) {
        if (!::customDuration.isInitialized || !::autoDurationSpinner.isInitialized) return
        customDuration.isEnabled = !running &&
            selectedAutoDuration() == Gpu3dAutoDuration.CUSTOM
    }

    private fun renderMetrics(metrics: Gpu3dStressMetrics) {
        val failed = metrics.lastError.isNotBlank()
        status.text = when {
            failed -> getString(R.string.state_error)
            metrics.running -> getString(R.string.state_running)
            else -> getString(R.string.status_idle)
        }
        status.setTextColor(
            getColor(
                when {
                    failed -> R.color.danger
                    metrics.running -> R.color.safe
                    else -> R.color.text_secondary
                },
            ),
        )
        currentFps.text = getString(R.string.gpu3d_current_fps_value, metrics.currentFps)
        averageFps.text = getString(R.string.gpu3d_current_fps_value, metrics.averageFps)
        minimumFps.text = if (metrics.minimumFps > 0.0) {
            getString(R.string.gpu3d_current_fps_value, metrics.minimumFps)
        } else {
            getString(R.string.not_available)
        }
        frameTime.text = getString(R.string.gpu3d_frame_time_value, metrics.frameTimeMs)
        maximumFrameTime.text = getString(
            R.string.gpu3d_frame_time_value,
            metrics.maximumFrameTimeMs,
        )
        runtime.text = DurationFormatter.format(metrics.runtimeMs)
        val configuration = if (metrics.running || failed) {
            Gpu3dStressConfiguration(metrics.level, metrics.scene)
        } else {
            selectedConfiguration()
        }
        renderProfile(
            configuration.workload,
            if (metrics.running && metrics.renderedFrames > 0L) {
                metrics.msaaSamples
            } else {
                configuration.profile.msaaSamples
            },
        )
        if (failed && reportedError != metrics.lastError) {
            reportedError = metrics.lastError
            if (autoPlan != null) {
                recordAutoResult(metrics)
                autoPlan = null
                autoStatus.text = getString(R.string.gpu3d_auto_error)
                renderAutoResults()
            }
            surface.stopTest()
            Toast.makeText(
                this,
                getString(R.string.gpu3d_renderer_error, metrics.lastError),
                Toast.LENGTH_LONG,
            ).show()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setControlsRunning(false)
        }
    }

    private fun updateAutoTest(metrics: Gpu3dStressMetrics) {
        val plan = autoPlan ?: return
        if (metrics.lastError.isNotBlank() || !metrics.running) return
        if (metrics.renderedFrames <= 0L) return
        if (autoStepStartedWallMs == 0L) {
            autoStepStartedWallMs = System.currentTimeMillis() - metrics.runtimeMs
        }
        val elapsedMs = metrics.runtimeMs.coerceAtLeast(0L)
        renderAutoProgress(elapsedMs)
        if (elapsedMs < plan.stepDurationMs) return

        recordAutoResult(metrics)
        surface.stopTest()
        autoStepIndex += 1
        if (autoStepIndex >= plan.steps.size) {
            autoPlan = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setControlsRunning(false)
            autoStatus.text = getString(R.string.gpu3d_auto_complete)
            renderAutoResults()
            renderMetrics(surface.snapshot())
        } else {
            startAutoStep()
        }
    }

    private fun recordAutoResult(metrics: Gpu3dStressMetrics) {
        if (autoStepStartedWallMs == 0L || metrics.renderedFrames == 0L) return
        autoResults += Gpu3dAutoTestStepResult.fromMetrics(
            metrics = metrics,
            startTimeMillis = autoStepStartedWallMs,
            endTimeMillis = System.currentTimeMillis(),
        )
        autoStepStartedWallMs = 0L
    }

    private fun renderAutoProgress(elapsedMs: Long) {
        val plan = autoPlan ?: return
        val step = plan.steps[autoStepIndex]
        autoStatus.text = getString(
            R.string.gpu3d_auto_progress,
            autoStepIndex + 1,
            plan.steps.size,
            getString(step.configuration.scene.labelResource()),
            getString(step.configuration.level.labelResource()),
            (elapsedMs / 1_000L).coerceAtMost(plan.stepDurationSeconds.toLong()),
            plan.stepDurationSeconds,
        )
    }

    private fun renderAutoResults() {
        autoResultsView.text = if (autoResults.isEmpty()) {
            getString(R.string.gpu3d_auto_no_results)
        } else {
            autoResults.joinToString("\n") { result ->
                getString(
                    R.string.gpu3d_auto_result_item,
                    DateFormat.format("HH:mm:ss", result.startTimeMillis),
                    DateFormat.format("HH:mm:ss", result.endTimeMillis),
                    getString(result.scene.labelResource()),
                    getString(result.level.labelResource()),
                    result.durationMs / 1_000.0,
                    result.averageFps,
                    result.minimumFps,
                    result.maximumFrameTimeMs,
                    result.averageFrameTimeMs,
                )
            }
        }
    }

    private fun renderProfile(
        workload: Gpu3dSceneWorkload,
        actualMsaaSamples: Int = workload.profile.msaaSamples,
    ) {
        val profile = workload.profile
        renderResolution.text = getString(
            R.string.gpu3d_resolution_value,
            profile.renderWidth,
            profile.renderHeight,
        )
        fpsLimit.text = getString(
            R.string.gpu3d_fps_limit_value,
            profile.fpsLimit.framesPerSecond,
        )
        profileSummary.text = getString(
            R.string.gpu3d_profile_summary,
            profile.renderScale,
            workload.sceneModelCount,
            workload.triangleCount,
            workload.particleCount,
            workload.particleLayers,
            profile.lightCount,
            actualMsaaSamples,
            profile.shadowMapSize,
            workload.shaderIterations,
            workload.postProcessQuality,
            workload.reflectionSteps,
            workload.fullScreenOverdrawLayers,
        )
    }

    private fun selectedConfiguration(): Gpu3dStressConfiguration = Gpu3dStressConfiguration(
        level = selectedLevel(),
        scene = selectedScene(),
    )

    private fun selectedLevel(): Gpu3dStressLevel =
        Gpu3dStressLevel.entries[stressLevelSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun selectedScene(): Gpu3dStressScene =
        Gpu3dStressScene.entries[sceneSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun selectedTraversalMode(): Gpu3dTraversalMode =
        Gpu3dTraversalMode.entries[traversalModeSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun selectedAutoDuration(): Gpu3dAutoDuration =
        Gpu3dAutoDuration.entries[autoDurationSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun Gpu3dStressLevel.labelResource(): Int = when (this) {
        Gpu3dStressLevel.LOW -> R.string.gpu3d_level_low
        Gpu3dStressLevel.MEDIUM -> R.string.gpu3d_level_medium
        Gpu3dStressLevel.HIGH -> R.string.gpu3d_level_high
        Gpu3dStressLevel.EXTREME -> R.string.gpu3d_level_extreme
        Gpu3dStressLevel.MAX -> R.string.gpu3d_level_max
    }

    private fun Gpu3dStressScene.labelResource(): Int = when (this) {
        Gpu3dStressScene.WATER_RACE -> R.string.gpu3d_scene_water_race
        Gpu3dStressScene.PARTICLE_STORM -> R.string.gpu3d_scene_particle_storm
        Gpu3dStressScene.SHADER_STRESS -> R.string.gpu3d_scene_shader_stress
        Gpu3dStressScene.GEOMETRY_STRESS -> R.string.gpu3d_scene_geometry_stress
        Gpu3dStressScene.OVERDRAW_STRESS -> R.string.gpu3d_scene_overdraw_stress
    }

    private fun Gpu3dTraversalMode.labelResource(): Int = when (this) {
        Gpu3dTraversalMode.LEVELS -> R.string.gpu3d_traversal_levels
        Gpu3dTraversalMode.SCENES -> R.string.gpu3d_traversal_scenes
    }

    private fun Gpu3dAutoDuration.labelResource(): Int = when (this) {
        Gpu3dAutoDuration.SECONDS_10 -> R.string.gpu3d_auto_10s
        Gpu3dAutoDuration.SECONDS_20 -> R.string.gpu3d_auto_20s
        Gpu3dAutoDuration.SECONDS_30 -> R.string.gpu3d_auto_30s
        Gpu3dAutoDuration.SECONDS_60 -> R.string.gpu3d_auto_60s
        Gpu3dAutoDuration.CUSTOM -> R.string.gpu3d_auto_custom
    }

    private val metricsRunnable = object : Runnable {
        override fun run() {
            if (!resumed) return
            val metrics = surface.snapshot()
            renderMetrics(metrics)
            updateAutoTest(metrics)
            handler.postDelayed(this, METRICS_INTERVAL_MS)
        }
    }

    private companion object {
        const val REQUIRED_GLES_VERSION = 0x00030000
        const val METRICS_INTERVAL_MS = 500L
        const val STATE_LEVEL = "gpu3d.level"
        const val STATE_SCENE = "gpu3d.scene"
        const val STATE_TRAVERSAL = "gpu3d.traversal"
        const val STATE_AUTO_DURATION = "gpu3d.autoDuration"
        const val STATE_CUSTOM_DURATION = "gpu3d.customDuration"
    }
}
