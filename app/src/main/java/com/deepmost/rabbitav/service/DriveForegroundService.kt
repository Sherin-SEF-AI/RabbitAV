package com.deepmost.rabbitav.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.deepmost.rabbitav.core.data.repo.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Section 5.11: owns camera, sensors, GPS, and all pipelines via DrivePipeline;
 * survives activity death; crash-safe restart with mode recovery.
 */
@AndroidEntryPoint
class DriveForegroundService : LifecycleService() {

    @Inject lateinit var pipeline: DrivePipeline
    @Inject lateinit var settings: SettingsRepository

    private lateinit var notifications: NotificationHelper
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifUpdateMs = 0L

    override fun onCreate() {
        super.onCreate()
        notifications = NotificationHelper(this)
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val mode = intent.getStringExtra(EXTRA_MODE)
                    ?.let { runCatching { DriveMode.valueOf(it) }.getOrNull() }
                    ?: DriveMode.FULL_ADAS
                val replayPath = intent.getStringExtra(EXTRA_REPLAY_PATH)
                startSession(mode, replayPath)
            }
            null -> {
                // START_STICKY restart after process death: recover last mode.
                lifecycleScope.launch(Dispatchers.Default) {
                    val saved = settings.lastPosition() // cheap datastore warm-up
                    Timber.tag(TAG).w("service restarted by system (state recovery), lastPos=%s", saved)
                }
                startSession(DriveMode.POCKET, null) // safest recovery: no camera dependency
            }
        }
        return START_STICKY
    }

    private fun startSession(mode: DriveMode, replayPath: String?) {
        if (pipeline.isRunning) {
            Timber.tag(TAG).i("session already running; ignoring start")
            return
        }
        // FGS type must match granted permissions (Android 14+ enforcement).
        val hasCamera = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val effectiveMode = if (mode == DriveMode.FULL_ADAS && !hasCamera) {
            Timber.tag(TAG).w("camera permission missing; falling back to POCKET mode")
            DriveMode.POCKET
        } else mode

        try {
            startInForeground(effectiveMode, hasCamera, hasLocation)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "startForeground failed; stopping")
            stopSelf()
            return
        }

        acquireWakeLock()

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                pipeline.start(
                    mode = effectiveMode,
                    lifecycleOwner = this@DriveForegroundService,
                    replayFile = replayPath?.let { File(it) },
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "pipeline start failed; stopping service")
                stopSession()
                return@launch
            }
            // notification updates from HUD state, throttled to 5 s
            launch {
                pipeline.hud.collectLatest { hud ->
                    val now = System.currentTimeMillis()
                    if (hud.running && now - lastNotifUpdateMs >= NOTIF_UPDATE_MS) {
                        lastNotifUpdateMs = now
                        updateNotification(notifications.build(hud))
                    }
                }
            }
        }
    }

    private fun startInForeground(mode: DriveMode, hasCamera: Boolean, hasLocation: Boolean) {
        val notification: Notification = notifications.build(HudState(running = true, mode = mode))
        if (Build.VERSION.SDK_INT >= 29) {
            var types = 0
            // Camera type ONLY when the camera is actually used (FULL_ADAS):
            // replay decodes from a file and pocket has no camera at all, and
            // an unnecessary camera-type FGS invites while-in-use policy kills
            // (observed via OEM app-sleep during the on-device soak).
            if (mode == DriveMode.FULL_ADAS && hasCamera) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (types == 0) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION // manifest-declared superset
            startForeground(NotificationHelper.NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    private fun stopSession() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                pipeline.stop()
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "pipeline stop failed")
            }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rabbitav:drive").apply {
            setReferenceCounted(false)
            // 4 h ceiling: a forgotten pocket-mode session cannot drain the
            // battery to zero. Long drives re-acquire via the running service.
            acquire(4 * 3600 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "wakelock release failed")
        }
        wakeLock = null
    }

    override fun onDestroy() {
        // Service destroyed without ACTION_STOP (system kill): release resources.
        if (pipeline.isRunning) {
            kotlinx.coroutines.runBlocking { pipeline.stop() }
        }
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RAV-Svc"
        const val ACTION_START = "com.deepmost.rabbitav.action.START"
        const val ACTION_STOP = "com.deepmost.rabbitav.action.STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_REPLAY_PATH = "replay_path"
        const val NOTIF_UPDATE_MS = 5000L

        fun start(context: Context, mode: DriveMode, replayPath: String? = null) {
            val intent = Intent(context, DriveForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode.name)
            if (replayPath != null) intent.putExtra(EXTRA_REPLAY_PATH, replayPath)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DriveForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
