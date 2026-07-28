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
    private lateinit var droidCamServer: DroidCamCompatServer
    private val testPattern = TestPatternGenerator()
    private var webMode = MODE_STOPPED

    override fun onCreate() {
        super.onCreate()
        webServer = LocalWebServer(applicationContext)
        droidCamServer = DroidCamCompatServer(applicationContext)
        MetaGlassesController.startConnection()
        startForeground(NOTIFICATION_ID, buildNotification("Maintaining glasses connection"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WEB -> startLanStreams(useTestPattern = false)
            ACTION_START_TEST -> startLanStreams(useTestPattern = true)
            ACTION_STOP_WEB -> stopLanStreams()
            ACTION_RECONNECT -> MetaGlassesController.forceReconnect()
            ACTION_STOP_SERVICE -> {
                stopLanStreams()
                MetaGlassesController.stopConnection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> MetaGlassesController.startConnection()
        }
        return START_STICKY
    }

    private fun startLanStreams(useTestPattern: Boolean) {
        val webStarted = webServer.start()
        val droidCamStarted = droidCamServer.start()
        if (!webStarted && !droidCamStarted) {
            val error = webServer.lastError ?: droidCamServer.lastError ?: "Unable to start Wi-Fi LAN streaming"
            WebCameraState.update(
                WebCameraState.State(
                    running = false,
                    mode = "Failed",
                    error = error,
                ),
            )
            updateNotification(error)
            return
        }

        if (useTestPattern) {
            MetaGlassesController.setCameraRequested(false)
            testPattern.start()
            AudioFrameHub.reset("Test pattern mode has no microphone audio")
            webMode = MODE_TEST
            FrameHub.resetSource("Starting test pattern")
        } else {
            testPattern.stop()
            MediaTimeline.reset()
            MetaGlassesController.setCameraRequested(true)
            webMode = MODE_GLASSES
            RawFramePipeline.clear("Waiting for Meta glasses camera")
            AudioFrameHub.reset("Waiting for Meta glasses microphones")
        }
        WebCameraState.update(
            WebCameraState.State(
                running = webStarted || droidCamStarted,
                url = if (webStarted) webServer.viewerUrl() else "",
                mode = webMode,
                error = when {
                    !webStarted -> webServer.lastError
                    !droidCamStarted -> droidCamServer.lastError
                    else -> null
                },
            ),
        )
        updateNotification(
            "Wi-Fi LAN active · ${LanStreamSettings.delayMs()} ms fixed delay · $webMode",
        )
    }

    private fun stopLanStreams() {
        testPattern.stop()
        webServer.stop()
        droidCamServer.stop()
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
            .addAction(0, "Stop LAN", stopWebIntent)
            .build()
    }

    override fun onDestroy() {
        stopLanStreams()
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
        private const val MODE_GLASSES = "Meta glasses max DAT live mode · 720x1280 / 30 FPS"
        private const val MODE_TEST = "Test pattern · 720x1280 / 10 FPS"

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
