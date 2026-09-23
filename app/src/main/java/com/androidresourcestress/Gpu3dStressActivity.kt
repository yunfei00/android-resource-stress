package com.androidresourcestress

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
    private lateinit var stressLevelSpinner: Spinner
    private lateinit var renderResolution: TextView
    private lateinit var fpsLimit: TextView
    private lateinit var profileSummary: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var reportedError = ""

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
        stopButton.setOnClickListener { stopTest() }
        renderMetrics(surface.snapshot())
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
        stressLevelSpinner = findViewById(R.id.gpu3dStressLevelSpinner)
        renderResolution = findViewById(R.id.gpu3dRenderResolutionValue)
        fpsLimit = findViewById(R.id.gpu3dFpsLimitValue)
        profileSummary = findViewById(R.id.gpu3dProfileSummary)
        startButton = findViewById(R.id.gpu3dStartButton)
        stopButton = findViewById(R.id.gpu3dStopButton)
    }

    private fun configureControls(savedInstanceState: Bundle?) {
        stressLevelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Gpu3dStressLevel.entries.map { getString(it.labelResource()) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        stressLevelSpinner.setSelection(
            savedInstanceState?.getInt(STATE_LEVEL) ?: Gpu3dStressLevel.MEDIUM.ordinal,
        )
        stressLevelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                renderProfile(Gpu3dStressLevel.entries[position].profile)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        renderProfile(selectedLevel().profile)
    }

    private fun startTest() {
        if (!resumed || surface.snapshot().running) return
        val configuration = Gpu3dStressConfiguration(
            level = selectedLevel(),
        )
        reportedError = ""
        surface.startTest(configuration)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setControlsRunning(true)
        renderMetrics(surface.snapshot())
    }

    private fun stopTest() {
        if (!::surface.isInitialized) return
        surface.stopTest()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setControlsRunning(false)
        renderMetrics(surface.snapshot())
    }

    private fun setControlsRunning(running: Boolean) {
        stressLevelSpinner.isEnabled = !running
        startButton.isEnabled = !running
        stopButton.isEnabled = running
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
        val profile = metrics.level.profile
        renderProfile(
            profile,
            if (metrics.renderedFrames > 0L) metrics.msaaSamples else profile.msaaSamples,
        )
        if (failed && reportedError != metrics.lastError) {
            reportedError = metrics.lastError
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

    private fun renderProfile(profile: Gpu3dStressProfile, actualMsaaSamples: Int = profile.msaaSamples) {
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
            profile.sceneModelCount,
            profile.triangleCount,
            profile.particleCount,
            profile.overdrawLayers,
            profile.lightCount,
            actualMsaaSamples,
            profile.shadowMapSize,
            profile.shaderIterations,
            profile.postProcessQuality,
            profile.reflectionSteps,
        )
    }

    private fun selectedLevel(): Gpu3dStressLevel =
        Gpu3dStressLevel.entries[stressLevelSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun Gpu3dStressLevel.labelResource(): Int = when (this) {
        Gpu3dStressLevel.LOW -> R.string.gpu3d_level_low
        Gpu3dStressLevel.MEDIUM -> R.string.gpu3d_level_medium
        Gpu3dStressLevel.HIGH -> R.string.gpu3d_level_high
        Gpu3dStressLevel.EXTREME -> R.string.gpu3d_level_extreme
        Gpu3dStressLevel.MAX -> R.string.gpu3d_level_max
    }

    private val metricsRunnable = object : Runnable {
        override fun run() {
            if (!resumed) return
            renderMetrics(surface.snapshot())
            handler.postDelayed(this, METRICS_INTERVAL_MS)
        }
    }

    private companion object {
        const val REQUIRED_GLES_VERSION = 0x00030000
        const val METRICS_INTERVAL_MS = 500L
        const val STATE_LEVEL = "gpu3d.level"
    }
}
