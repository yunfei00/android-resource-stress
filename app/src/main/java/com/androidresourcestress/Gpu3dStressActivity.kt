package com.androidresourcestress

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class Gpu3dStressActivity : LocalizedActivity() {
    private lateinit var surface: Gpu3dStressSurfaceView
    private lateinit var status: TextView
    private lateinit var currentFps: TextView
    private lateinit var frameTime: TextView
    private lateinit var runtime: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
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
        if (::resolutionSpinner.isInitialized) {
            outState.putInt(STATE_RESOLUTION, resolutionSpinner.selectedItemPosition)
            outState.putInt(STATE_FPS, fpsSpinner.selectedItemPosition)
        }
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        surface = findViewById(R.id.gpu3dSurface)
        status = findViewById(R.id.gpu3dStatusValue)
        currentFps = findViewById(R.id.gpu3dCurrentFpsValue)
        frameTime = findViewById(R.id.gpu3dFrameTimeValue)
        runtime = findViewById(R.id.gpu3dRuntimeValue)
        resolutionSpinner = findViewById(R.id.gpu3dResolutionSpinner)
        fpsSpinner = findViewById(R.id.gpu3dFpsSpinner)
        startButton = findViewById(R.id.gpu3dStartButton)
        stopButton = findViewById(R.id.gpu3dStopButton)
    }

    private fun configureControls(savedInstanceState: Bundle?) {
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(getString(R.string.gpu3d_720p), getString(R.string.gpu3d_1080p)),
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(getString(R.string.gpu3d_30_fps), getString(R.string.gpu3d_60_fps)),
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        resolutionSpinner.setSelection(savedInstanceState?.getInt(STATE_RESOLUTION) ?: 0)
        fpsSpinner.setSelection(savedInstanceState?.getInt(STATE_FPS) ?: 1)
    }

    private fun startTest() {
        if (!resumed || surface.snapshot().running) return
        val configuration = Gpu3dStressConfiguration(
            resolution = Gpu3dResolution.entries[resolutionSpinner.selectedItemPosition],
            fpsLimit = Gpu3dFpsLimit.entries[fpsSpinner.selectedItemPosition],
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
        resolutionSpinner.isEnabled = !running
        fpsSpinner.isEnabled = !running
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
        frameTime.text = getString(R.string.gpu3d_frame_time_value, metrics.frameTimeMs)
        runtime.text = DurationFormatter.format(metrics.runtimeMs)
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
        const val STATE_RESOLUTION = "gpu3d.resolution"
        const val STATE_FPS = "gpu3d.fps"
    }
}
