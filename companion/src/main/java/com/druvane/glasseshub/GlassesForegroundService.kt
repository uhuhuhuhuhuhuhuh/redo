package com.druvane.glasseshub

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class GlassesForegroundService : Service() {
    private lateinit var webServer: LocalWebServer
    private val testPattern = TestPatternGenerator()
    private var webMode = MODE_STOPPED

    override fun onCreate() {
        super.onCreate()
        webServer = LocalWebServer(applicationContext)
        MetaGlassesController.startConnection()
        startForeground(NOTIFICATION_ID, buildNotification("Maintaining glasses connection"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WEB -> startWeb(useTestPattern = false)
            ACTION_START_TEST -> startWeb(useTestPattern = true)
            ACTION_STOP_WEB -> stopWeb()
            ACTION_RECONNECT -> MetaGlassesController.forceReconnect()
            ACTION_STOP_SERVICE -> {
                stopWeb()
                MetaGlassesController.stopConnection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> MetaGlassesController.startConnection()
        }
        return START_STICKY
    }

    private fun startWeb(useTestPattern: Boolean) {
        val started = webServer.start()
        if (!started) {
            WebCameraState.update(
                WebCameraState.State(
                    running = false,
                    mode = "Failed",
                    error = "Port ${LocalWebServer.DEFAULT_PORT} is unavailable",
                ),
            )
            return
        }

        if (useTestPattern) {
            MetaGlassesController.setCameraRequested(false)
            testPattern.start()
            webMode = MODE_TEST
            FrameHub.resetSource("Starting test pattern")
        } else {
            testPattern.stop()
            MetaGlassesController.setCameraRequested(true)
            webMode = MODE_GLASSES
            FrameHub.resetSource("Waiting for Meta glasses camera")
        }
        WebCameraState.update(
            WebCameraState.State(
                running = true,
                url = webServer.viewerUrl(),
                mode = webMode,
            ),
        )
        updateNotification("Web camera active: $webMode")
    }

    private fun stopWeb() {
        testPattern.stop()
        webServer.stop()
        MetaGlassesController.setCameraRequested(false)
        webMode = MODE_STOPPED
        WebCameraState.update(WebCameraState.State())
        updateNotification("Maintaining glasses connection")
    }

    private fun updateNotification(text: String) {
        val manager = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopWebIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, GlassesForegroundService::class.java).setAction(ACTION_STOP_WEB),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reconnectIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, GlassesForegroundService::class.java).setAction(ACTION_RECONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Glasses Hub")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Reconnect", reconnectIntent)
            .addAction(0, "Stop web", stopWebIntent)
            .build()
    }

    override fun onDestroy() {
        stopWeb()
        MetaGlassesController.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "glasses_hub_connection"
        private const val NOTIFICATION_ID = 4108
        const val ACTION_START_WEB = "com.druvane.glasseshub.action.START_WEB"
        const val ACTION_START_TEST = "com.druvane.glasseshub.action.START_TEST"
        const val ACTION_STOP_WEB = "com.druvane.glasseshub.action.STOP_WEB"
        const val ACTION_RECONNECT = "com.druvane.glasseshub.action.RECONNECT"
        const val ACTION_STOP_SERVICE = "com.druvane.glasseshub.action.STOP_SERVICE"
        private const val MODE_STOPPED = "Stopped"
        private const val MODE_GLASSES = "Meta glasses 720x1280 / 30 FPS"
        private const val MODE_TEST = "Test pattern 720x1280 / 10 FPS"

        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlassesForegroundService::class.java),
            )
        }

        fun sendAction(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlassesForegroundService::class.java).setAction(action),
            )
        }
    }
}
