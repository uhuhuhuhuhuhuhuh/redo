package com.druvane.glasseshub

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent

object MediaControls {
    fun launchSpotify(context: Context): Boolean = launchPackage(context, "com.spotify.music")

    fun launchPackage(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun dialer(context: Context): Boolean {
        return try {
            context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun mediaKey(context: Context, keyCode: Int) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val now = android.os.SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    fun playPause(context: Context) = mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun next(context: Context) = mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous(context: Context) = mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
}
