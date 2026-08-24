package com.androidresourcestress

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

class StressForegroundService : Service(), CombinedStressController.Listener {
    interface Observer {
        fun onStressSnapshot(snapshot: CombinedRuntimeSnapshot) = Unit
        fun onStressStateChanged(state: CombinedStressState) = Unit
        fun onGpuInfoAvailable(info: GpuInfo) = Unit
        fun onSessionFinished(session: StressSessionSnapshot) = Unit
        fun onStressError(message: String) = Unit
    }

    inner class LocalBinder : Binder() {
        val service: StressForegroundService
            get() = this@StressForegroundService
    }

    private val binder = LocalBinder()
    private val observers = CopyOnWriteArraySet<Observer>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: CombinedStressController
    private lateinit var wakeLocks: StressWakeLockController
    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private lateinit var diagnosticLog: DiagnosticLog
    private var foreground = false
    private var receiverRegistered = false
    private var latestSnapshot: CombinedRuntimeSnapshot? = null
    private var lastNotificationSecond = -1L

    val state: CombinedStressState
        get() = if (::controller.isInitialized) controller.state else CombinedStressState.IDLE

    val isWakeLockHeld: Boolean
        get() = ::wakeLocks.isInitialized && wakeLocks.isHeld

    val gpuInfo: GpuInfo?
        get() = if (::controller.isInitialized) controller.gpuInfo else null

    val snapshot: CombinedRuntimeSnapshot?
        get() = latestSnapshot

    override fun onCreate() {
        super.onCreate()
        NativeStress.ensureLoaded()
        notificationManager = getSystemService(NotificationManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        diagnosticLog = DiagnosticLog(this)
        wakeLocks = StressWakeLockController(this)
        createNotificationChannel()
        val coreCount = DeviceMonitor(this).deviceInfo().logicalCoreCount
        controller = CombinedStressController(this, coreCount, this)
        controller.initialize()
        controller.recordScreenState(powerManager.isInteractive)
        registerScreenReceiver()
        handler.post(monitorRunnable)
        diagnosticLog.record("StressForegroundService created; process restart defaults to IDLE")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRequested(intent)
            ACTION_STOP -> controller.stopAll(StopReason.USER_STOP)
        }
        return START_NOT_STICKY
    }

    fun addObserver(observer: Observer) {
        observers += observer
        latestSnapshot?.let(observer::onStressSnapshot)
        observer.onStressStateChanged(state)
        gpuInfo?.let(observer::onGpuInfoAvailable)
    }

    fun removeObserver(observer: Observer) {
        observers -= observer
    }

    fun stopSession(reason: StopReason = StopReason.USER_STOP) {
        controller.stopAll(reason)
    }

    fun updateVisualMetrics(fps: Double, frameTimeNanos: Double) {
        controller.updateVisualMetrics(fps, frameTimeNanos)
    }

    fun reportOnscreenVisualError(message: String) {
        controller.reportOnscreenVisualError(message)
    }

    fun setVisualSurfaceAttached(attached: Boolean) {
        controller.setVisualSurfaceAttached(attached)
    }

    override fun onCombinedStateChanged(state: CombinedStressState) {
        if (state == CombinedStressState.RUNNING) {
            wakeLocks.acquire()
        }
        if (state == CombinedStressState.IDLE && foreground) {
            // Covers validation/start failures that happen before a Session
            // object exists and therefore cannot emit onSessionFinished().
            wakeLocks.release()
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        observers.forEach { it.onStressStateChanged(state) }
        updateNotification(force = true)
    }

    override fun onGpuInfoAvailable(info: GpuInfo) {
        observers.forEach { it.onGpuInfoAvailable(info) }
    }

    override fun onSessionFinished(session: StressSessionSnapshot) {
        wakeLocks.release()
        observers.forEach { it.onSessionFinished(session) }
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (session.configuration.screenMode == ScreenMode.OFF && !powerManager.isInteractive) {
            notificationManager.notify(NOTIFICATION_COMPLETE_ID, completedNotification(session))
        }
        stopSelf()
    }

    override fun onCombinedError(message: String) {
        observers.forEach { it.onStressError(message) }
    }

    override fun onSessionStopRequested(reason: StopReason) {
        attemptSessionWake(reason.name)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        diagnosticLog.record("Task removed; active service session continues=${state == CombinedStressState.RUNNING}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        if (::controller.isInitialized) {
            if (controller.state != CombinedStressState.IDLE) {
                controller.stopAll(StopReason.SERVICE_ERROR)
            }
            controller.close()
        }
        wakeLocks.close()
        diagnosticLog.record("StressForegroundService destroyed; WakeLock released")
        super.onDestroy()
    }

    private fun startRequested(intent: Intent) {
        if (controller.state != CombinedStressState.IDLE) {
            diagnosticLog.record("Duplicate START ignored; existing session remains active")
            return
        }
        val configuration = configurationFromIntent(intent)
        startAsForeground(startingNotification(configuration))
        wakeLocks.acquire()
        controller.recordScreenState(powerManager.isInteractive)
        controller.start(configuration)
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun attemptSessionWake(reason: String) {
        val current = latestSnapshot?.currentSession ?: return
        if (current.configuration.screenMode != ScreenMode.OFF || powerManager.isInteractive) return
        val succeeded = runCatching { wakeLocks.attemptWake() }.getOrDefault(false)
        controller.recordWakeResult(attempted = true, succeeded = succeeded, reason = reason)
        diagnosticLog.record("Screen wake attempted reason=$reason succeeded=$succeeded")
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!::controller.isInitialized) return
            val next = runCatching { controller.runtimeSnapshot() }.onFailure { error ->
                Log.e(LOG_TAG, "Service monitor failed", error)
                if (controller.state != CombinedStressState.IDLE) {
                    controller.stopAll(StopReason.SERVICE_ERROR)
                }
            }.getOrNull()
            if (next != null) {
                latestSnapshot = next
                observers.forEach { it.onStressSnapshot(next) }
                if (next.state != CombinedStressState.IDLE) updateNotification(force = false)
            }
            handler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> controller.recordScreenState(false)
                Intent.ACTION_SCREEN_ON -> controller.recordScreenState(true)
            }
            updateNotification(force = true)
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun updateNotification(force: Boolean) {
        if (!foreground) return
        val snapshot = latestSnapshot
        val second = (snapshot?.elapsedTimeMs ?: 0L) / 1_000L
        if (!force && second == lastNotificationSecond) return
        lastNotificationSecond = second
        notificationManager.notify(NOTIFICATION_ID, runningNotification(snapshot))
    }

    private fun startingNotification(configuration: CombinedStressConfiguration): Notification =
        baseNotificationBuilder()
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(getString(R.string.notification_starting, presetLabel(configuration.preset)))
            .setOngoing(true)
            .addAction(notificationAction(getString(R.string.open), openPendingIntent()))
            .addAction(notificationAction(getString(R.string.stop), stopPendingIntent()))
            .build()

    private fun runningNotification(snapshot: CombinedRuntimeSnapshot?): Notification {
        val session = snapshot?.currentSession
        val preset = session?.configuration?.preset?.let(::presetLabel) ?: getString(R.string.app_name)
        val elapsed = DurationFormatter.format(snapshot?.elapsedTimeMs ?: 0L)
        val thermal = snapshot?.thermal?.status?.let(::thermalStatusDisplay) ?: getString(R.string.unknown)
        return baseNotificationBuilder()
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(getString(R.string.notification_running_summary, preset, elapsed))
            .setSubText(getString(R.string.notification_thermal, thermal))
            .setOngoing(true)
            .setContentIntent(openPendingIntent())
            .addAction(notificationAction(getString(R.string.open), openPendingIntent()))
            .addAction(notificationAction(getString(R.string.stop), stopPendingIntent()))
            .build()
    }

    private fun completedNotification(session: StressSessionSnapshot): Notification =
        baseNotificationBuilder()
            .setContentTitle(getString(R.string.notification_finished_title))
            .setContentText(getString(R.string.notification_finished_summary, stopReasonLabel(session.stopReason)))
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent())
            .build()

    private fun baseNotificationBuilder(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

    private fun notificationAction(title: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_launcher),
            title,
            intent,
        ).build()

    private fun openPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        2,
        Intent(this, StressForegroundService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun presetLabel(preset: StressPreset): String = applicationContext.presetLabel(preset)
    private fun thermalStatusDisplay(status: Int): String =
        applicationContext.thermalStatusDisplay(status)
    private fun stopReasonLabel(reason: StopReason?): String =
        applicationContext.stopReasonLabel(reason)

    companion object {
        private const val LOG_TAG = "ResourceStress"
        private const val ACTION_START = "com.androidresourcestress.action.START"
        private const val ACTION_STOP = "com.androidresourcestress.action.STOP"
        private const val CHANNEL_ID = "stress_session"
        private const val NOTIFICATION_ID = 4105
        private const val NOTIFICATION_COMPLETE_ID = 4106
        private const val MONITOR_INTERVAL_MS = 750L

        fun start(context: Context, configuration: CombinedStressConfiguration) {
            val intent = Intent(context, StressForegroundService::class.java)
                .setAction(ACTION_START)
                .putConfiguration(configuration)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StressForegroundService::class.java).setAction(ACTION_STOP),
            )
        }

        private fun Intent.putConfiguration(configuration: CombinedStressConfiguration): Intent =
            apply {
                putExtra("preset", configuration.preset.name)
                putExtra("cpuEnabled", configuration.cpuEnabled)
                putExtra("gpuEnabled", configuration.gpuEnabled)
                putExtra("memoryEnabled", configuration.memoryEnabled)
                putExtra("storageEnabled", configuration.storageEnabled)
                putExtra("cpuTarget", configuration.cpuTargetPercent)
                putExtra("gpuTarget", configuration.gpuTargetPercent)
                putExtra("memoryTarget", configuration.memoryTarget.name)
                putExtra("storageMode", configuration.storageMode.name)
                putExtra("storageLevel", configuration.storageLevel.name)
                putExtra("gpuMode", configuration.gpuMode.name)
                putExtra("duration", configuration.duration.name)
                putExtra("screenMode", configuration.screenMode.name)
            }

        private fun configurationFromIntent(intent: Intent): CombinedStressConfiguration {
            fun <T : Enum<T>> enumValue(value: String?, fallback: T, parser: (String) -> T): T =
                value?.let { runCatching { parser(it) }.getOrNull() } ?: fallback
            val preset = enumValue(intent.getStringExtra("preset"), StressPreset.EXTREME, StressPreset::valueOf)
            val fallback = PresetConfigurations.create(preset)
            return fallback.copy(
                cpuEnabled = intent.getBooleanExtra("cpuEnabled", fallback.cpuEnabled),
                gpuEnabled = intent.getBooleanExtra("gpuEnabled", fallback.gpuEnabled),
                memoryEnabled = intent.getBooleanExtra("memoryEnabled", fallback.memoryEnabled),
                storageEnabled = intent.getBooleanExtra("storageEnabled", false),
                cpuTargetPercent = intent.getIntExtra("cpuTarget", fallback.cpuTargetPercent),
                gpuTargetPercent = intent.getIntExtra("gpuTarget", fallback.gpuTargetPercent),
                memoryTarget = enumValue(intent.getStringExtra("memoryTarget"), fallback.memoryTarget, MemoryTarget::valueOf),
                storageMode = enumValue(intent.getStringExtra("storageMode"), StorageMode.MIXED, StorageMode::valueOf),
                storageLevel = enumValue(intent.getStringExtra("storageLevel"), StorageLevel.LOW, StorageLevel::valueOf),
                gpuMode = enumValue(intent.getStringExtra("gpuMode"), GpuMode.COMPUTE, GpuMode::valueOf),
                duration = enumValue(intent.getStringExtra("duration"), StressDuration.MINUTES_5, StressDuration::valueOf),
                screenMode = enumValue(intent.getStringExtra("screenMode"), ScreenMode.ON, ScreenMode::valueOf),
            )
        }
    }
}
