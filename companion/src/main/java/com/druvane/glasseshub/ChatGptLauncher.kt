package com.druvane.glasseshub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ChatGptLauncher {
    const val PREFS = "chatgpt_relay"
    const val KEY_PENDING_PROMPT = "pending_prompt"

    fun openVoice(context: Context): Boolean {
        val assist = Intent(Intent.ACTION_ASSIST).apply {
            setPackage(CHATGPT_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launch(context, assist)) return true

        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://chatgpt.com/?mode=voice"),
        ).apply {
            setPackage(CHATGPT_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(context, webIntent)
    }

    fun openPrompt(context: Context, prompt: String, enableRelay: Boolean): Boolean {
        val cleaned = prompt.trim()
        if (cleaned.isEmpty()) return false
        if (enableRelay) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING_PROMPT, cleaned)
                .apply()
        }
        val encoded = URLEncoder.encode(cleaned, StandardCharsets.UTF_8.name())
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://chatgpt.com/?q=$encoded&skip_instant_query=1"),
        ).apply {
            setPackage(CHATGPT_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launch(context, intent)) return true

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, cleaned)
            setPackage(CHATGPT_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(context, share)
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(context.packageManager) == null) return false
            context.startActivity(intent)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    const val CHATGPT_PACKAGE = "com.openai.chatgpt"
}
