package com.androidresourcestress

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class GpuVisualActivity : LocalizedActivity(), StressForegroundService.Observer {
    private lateinit var surface: VulkanVisualSurfaceView
    private lateinit var status: TextView
    private lateinit var elapsed: TextView
    private lateinit var metrics: TextView
    private lateinit var thermal: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var service: StressForegroundService? = null
    private var bound = false
    private var activeConfiguration: CombinedStressConfiguration? = null
    private var surfaceReported = false
    private var reportedVisualError: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? StressForegroundService.LocalBinder)?.service
            bound = service != null
            service?.addObserver(this@GpuVisualActivity)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gpu_visual)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        }
        surface = findViewById(R.id.vulkanVisualSurface)
        status = findViewById(R.id.visualStatus)
        elapsed = findViewById(R.id.visualElapsed)
        metrics = findViewById(R.id.visualMetrics)
        thermal = findViewById(R.id.visualThermal)
        findViewById<Button>(R.id.visualStopButton).setOnClickListener {
            service?.stopSession(StopReason.USER_STOP) ?: StressForegroundService.stop(this)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, StressForegroundService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(metricRunnable)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        surface.stop()
        service?.updateVisualMetrics(0.0, 0.0)
        if (surfaceReported) service?.setVisualSurfaceAttached(false)
        surfaceReported = false
        service?.removeObserver(this)
        if (bound) unbindService(connection)
        bound = false
        service = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onStressSnapshot(snapshot: CombinedRuntimeSnapshot) {
        val configuration = snapshot.currentSession?.configuration
        if (configuration != null && configuration.gpuEnabled &&
            configuration.gpuMode.usesVulkanVisual
        ) {
            activeConfiguration = configuration
            surface.start(configuration.gpuTargetPercent)
            if (!surfaceReported) {
                surfaceReported = true
                service?.setVisualSurfaceAttached(true)
            }
        }
        status.text = combinedStateLabel(snapshot.state)
        elapsed.text = DurationFormatter.format(snapshot.elapsedTimeMs)
        thermal.text = thermalStatusDisplay(snapshot.thermal.status)
        thermal.setTextColor(
            when {
                snapshot.thermal.status >= PowerManager.THERMAL_STATUS_SEVERE -> getColor(R.color.danger)
                snapshot.thermal.status >= PowerManager.THERMAL_STATUS_MODERATE -> getColor(R.color.warning)
                else -> getColor(R.color.safe)
            },
        )
    }

    override fun onSessionFinished(session: StressSessionSnapshot) {
        finish()
    }

    override fun onStressError(message: String) {
        metrics.text = message
        metrics.setTextColor(getColor(R.color.danger))
    }

    private val metricRunnable = object : Runnable {
        override fun run() {
            val visual = surface.snapshot()
            service?.updateVisualMetrics(visual.framesPerSecond, visual.frameTimeNanos)
            metrics.text = if (visual.running) {
                getString(
                    R.string.visual_metrics,
                    visual.framesPerSecond,
                    visual.frameTimeNanos / 1_000_000.0,
                    visual.frameCount,
                )
            } else if (visual.lastError.isNotBlank()) visual.lastError
            else getString(R.string.visual_metrics_waiting)
            if (visual.lastError.isNotBlank() && reportedVisualError != visual.lastError) {
                reportedVisualError = visual.lastError
                service?.reportOnscreenVisualError(visual.lastError)
            }
            handler.postDelayed(this, 500L)
        }
    }
}
