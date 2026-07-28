package com.druvane.glasseshub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class GlassesHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e(TAG, "DAT initialization failed: ${error.description}") }
        MetaGlassesController.initialize(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                GlassesForegroundService.CHANNEL_ID,
                "Glasses connection and web camera",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the glasses session and local camera viewer active."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "GlassesHubApp"
    }
}
