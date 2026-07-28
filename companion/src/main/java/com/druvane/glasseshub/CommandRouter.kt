package com.druvane.glasseshub

import android.content.Context
import java.util.Locale

object CommandRouter {
    data class Result(val handled: Boolean, val message: String)

    fun route(context: Context, spoken: String): Result {
        val command = spoken.trim().lowercase(Locale.ROOT)
        return when {
            command.contains("start") && (command.contains("camera stream") || command.contains("web camera") || command.contains("video stream")) -> {
                GlassesForegroundService.sendAction(context, GlassesForegroundService.ACTION_START_WEB)
                Result(true, "Starting the glasses web camera")
            }
            command.contains("stop") && (command.contains("camera stream") || command.contains("web camera") || command.contains("video stream")) -> {
                GlassesForegroundService.sendAction(context, GlassesForegroundService.ACTION_STOP_WEB)
                Result(true, "Stopping the web camera")
            }
            command.contains("reconnect") && command.contains("glasses") -> {
                GlassesForegroundService.sendAction(context, GlassesForegroundService.ACTION_RECONNECT)
                Result(true, "Reconnecting the glasses")
            }
            command.contains("spotify") && (command.contains("open") || command.contains("start")) -> {
                Result(MediaControls.launchSpotify(context), "Opening Spotify")
            }
            command == "pause" || command.contains("pause music") -> {
                MediaControls.playPause(context)
                Result(true, "Pausing playback")
            }
            command == "play" || command.contains("resume music") -> {
                MediaControls.playPause(context)
                Result(true, "Resuming playback")
            }
            command.contains("next song") || command == "skip" -> {
                MediaControls.next(context)
                Result(true, "Skipping")
            }
            command.contains("previous song") || command.contains("go back a song") -> {
                MediaControls.previous(context)
                Result(true, "Going to the previous track")
            }
            command.contains("open chatgpt") || command.contains("start chatgpt") -> {
                Result(ChatGptLauncher.openVoice(context), "Opening ChatGPT Voice")
            }
            command.contains("open whatsapp") -> Result(MediaControls.launchPackage(context, "com.whatsapp"), "Opening WhatsApp")
            else -> {
                val launched = ChatGptLauncher.openPrompt(context, spoken, enableRelay = true)
                Result(launched, if (launched) "Sending to ChatGPT" else "ChatGPT is not installed")
            }
        }
    }
}
