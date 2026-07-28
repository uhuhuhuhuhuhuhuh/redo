package com.druvane.glasseshub

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var connectionStatus: TextView
    private lateinit var cameraStatus: TextView
    private lateinit var webStatus: TextView
    private lateinit var preview: ImageView
    private lateinit var promptInput: EditText
    private lateinit var relaySwitch: Switch
    private lateinit var commandStatus: TextView
    private var previewSequence = -1L

    private val androidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isNotEmpty()) toast("Some permissions were denied. Features will degrade gracefully.")
        }

    private val metaCameraPermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result.onSuccess { status -> toast("Glasses camera permission: $status") }
                .onFailure { error, _ -> toast("Camera permission failed: ${error.description}") }
        }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?: return@registerForActivityResult
            val routed = CommandRouter.route(this, text)
            commandStatus.text = "Heard: $text\n${routed.message}"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        setContentView(buildContent())
        GlassesForegroundService.ensureRunning(this)
        requestRuntimePermissions()
        observeState()
        mainHandler.post(previewUpdater)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(previewUpdater)
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(56))
            setBackgroundColor(COLOR_BACKGROUND)
        }

        root.addView(text("Glasses Hub", 31f, COLOR_TEXT, bold = true))
        root.addView(text("A clean-room companion for Meta AI glasses, ChatGPT, connected apps and local POV streaming.", 15f, COLOR_MUTED).withTop(6))

        root.addView(sectionTitle("Device"))
        val deviceCard = card()
        connectionStatus = text("Registration: checking\nConnection: starting", 16f, COLOR_TEXT)
        cameraStatus = text("Camera: stopped", 14f, COLOR_MUTED).withTop(8)
        deviceCard.addView(connectionStatus)
        deviceCard.addView(cameraStatus)
        deviceCard.addView(buttonRow(
            actionButton("Connect through Meta AI") { Wearables.startRegistration(this) },
            actionButton("Camera permission") { metaCameraPermissionLauncher.launch(Permission.CAMERA) },
        ).withTop(16))
        deviceCard.addView(buttonRow(
            actionButton("Reconnect") {
                GlassesForegroundService.sendAction(this, GlassesForegroundService.ACTION_RECONNECT)
            },
            secondaryButton("Bluetooth settings") {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            },
        ).withTop(10))
        root.addView(deviceCard)

        root.addView(sectionTitle("Camera to web"))
        val webCard = card()
        webStatus = text("Server stopped", 16f, COLOR_TEXT, bold = true)
        webCard.addView(webStatus)
        webCard.addView(text("Streams the highest DAT mode, 720 × 1280 at 30 FPS, from the glasses to an MJPEG browser endpoint on your local network.", 14f, COLOR_MUTED).withTop(6))
        preview = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = dp(180)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)).apply {
                topMargin = dp(14)
            }
        }
        webCard.addView(preview)
        webCard.addView(buttonRow(
            actionButton("Start glasses stream") {
                GlassesForegroundService.sendAction(this, GlassesForegroundService.ACTION_START_WEB)
            },
            secondaryButton("Test web viewer") {
                GlassesForegroundService.sendAction(this, GlassesForegroundService.ACTION_START_TEST)
            },
        ).withTop(14))
        webCard.addView(buttonRow(
            actionButton("Open viewer") { openViewer() },
            secondaryButton("Copy address") { copyViewerAddress() },
            secondaryButton("Stop") {
                GlassesForegroundService.sendAction(this, GlassesForegroundService.ACTION_STOP_WEB)
            },
        ).withTop(10))
        root.addView(webCard)

        root.addView(sectionTitle("Ask and control"))
        val commandCard = card()
        commandStatus = text("Tap the microphone and say a command, or ask a normal question for ChatGPT.", 15f, COLOR_MUTED)
        commandCard.addView(commandStatus)
        commandCard.addView(actionButton("Speak command") { startSpeechRecognition() }.withTop(14))
        commandCard.addView(text("Examples: “start web camera,” “open Spotify,” “next song,” “reconnect glasses,” or a question for ChatGPT.", 13f, COLOR_MUTED).withTop(8))
        root.addView(commandCard)

        root.addView(sectionTitle("ChatGPT"))
        val chatCard = card()
        chatCard.addView(text("Uses the official ChatGPT app and your existing signed-in account. No OpenAI API key is stored in Glasses Hub.", 14f, COLOR_MUTED))
        promptInput = EditText(this).apply {
            hint = "Message ChatGPT"
            setHintTextColor(Color.rgb(145, 148, 154))
            setTextColor(COLOR_TEXT)
            textSize = 16f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(31, 33, 37), 18f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            }
        }
        chatCard.addView(promptInput)
        relaySwitch = Switch(this).apply {
            text = "Read visible ChatGPT response aloud"
            setTextColor(COLOR_TEXT)
            isChecked = true
            setPadding(0, dp(8), 0, dp(2))
        }
        chatCard.addView(relaySwitch)
        chatCard.addView(buttonRow(
            actionButton("Send prompt") {
                val ok = ChatGptLauncher.openPrompt(this, promptInput.text.toString(), relaySwitch.isChecked)
                if (!ok) toast("Install or update the official ChatGPT app")
            },
            actionButton("ChatGPT Voice") {
                if (!ChatGptLauncher.openVoice(this)) toast("Unable to open ChatGPT Voice")
            },
        ).withTop(10))
        chatCard.addView(secondaryButton("Enable response relay accessibility") {
            ChatGptLauncher.openAccessibilitySettings(this)
        }.withTop(10))
        root.addView(chatCard)

        root.addView(sectionTitle("Music"))
        val spotifyCard = card()
        spotifyCard.addView(text("Spotify", 20f, COLOR_TEXT, bold = true))
        spotifyCard.addView(text("Launches the official Spotify app and uses Android media-session keys. No Spotify developer key is required.", 14f, COLOR_MUTED).withTop(4))
        spotifyCard.addView(buttonRow(
            actionButton("Open Spotify") { MediaControls.launchSpotify(this) },
            secondaryButton("Previous") { MediaControls.previous(this) },
            secondaryButton("Play / pause") { MediaControls.playPause(this) },
            secondaryButton("Next") { MediaControls.next(this) },
        ).withTop(14))
        root.addView(spotifyCard)

        root.addView(sectionTitle("Calling, messaging and sharing"))
        root.addView(connectedAppsCard(
            listOf(
                AppEntry("WhatsApp", "Open messages and calls", "com.whatsapp"),
                AppEntry("Messenger", "Open Messenger", "com.facebook.orca"),
                AppEntry("Phone", "Open the Android dialer", null, isDialer = true),
                AppEntry("Instagram", "Open Instagram", "com.instagram.android"),
                AppEntry("Facebook", "Open Facebook", "com.facebook.katana"),
            ),
        ))

        root.addView(sectionTitle("Connected services"))
        root.addView(connectedAppsCard(
            listOf(
                AppEntry("Gmail", "Open your connected Gmail account", "com.google.android.gm"),
                AppEntry("Google Calendar", "Open your calendar", "com.google.android.calendar"),
                AppEntry("Google Contacts", "Open contacts", "com.google.android.contacts"),
                AppEntry("Health Connect", "Open Health Connect", "com.google.android.apps.healthdata"),
                AppEntry("Shazam", "Identify music", "com.shazam.android"),
                AppEntry("Spotify", "Open and control playback", "com.spotify.music"),
                AppEntry("ChatGPT", "Voice and normal account chats", ChatGptLauncher.CHATGPT_PACKAGE),
            ),
        ))

        root.addView(sectionTitle("Limitations"))
        val limits = card()
        limits.addView(text("The official Meta AI app remains installed for ownership, firmware, app registration and permission approval. Glasses Hub cannot replace Meta’s private account backend or intercept the “Hey Meta” wake phrase. The web viewer runs only while you explicitly start it and displays an ongoing Android notification.", 14f, COLOR_MUTED))
        root.addView(limits)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            MetaGlassesController.status.collectLatest { status ->
                connectionStatus.text = "Registration: ${status.registration}\nConnection: ${status.connection}" +
                    (status.lastError?.let { "\nLast issue: $it" } ?: "")
                cameraStatus.text = "Camera: ${status.camera}"
            }
        }
        lifecycleScope.launch {
            WebCameraState.state.collectLatest { state ->
                webStatus.text = if (state.running) {
                    "${state.mode}\n${state.url}"
                } else {
                    state.error ?: "Server stopped"
                }
            }
        }
        lifecycleScope.launch {
            FrameHub.stats.collectLatest { stats ->
                if (stats.width > 0) {
                    cameraStatus.text = "Camera: ${MetaGlassesController.status.value.camera}\nFrames: ${stats.frames} · ${stats.width}×${stats.height} · viewers ${stats.viewers}"
                }
            }
        }
    }

    private val previewUpdater = object : Runnable {
        override fun run() {
            val frame = FrameHub.latestFrame()
            if (frame != null && frame.sequence != previewSequence) {
                previewSequence = frame.sequence
                val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                preview.setImageBitmap(bitmap)
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) androidPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a glasses command or ChatGPT question")
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: RuntimeException) {
            toast("No speech-recognition service is installed")
        }
    }

    private fun openViewer() {
        val url = WebCameraState.state.value.url
        if (url.isBlank()) {
            toast("Start the web camera first")
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun copyViewerAddress() {
        val url = WebCameraState.state.value.url
        if (url.isBlank()) {
            toast("Start the web camera first")
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Glasses Hub viewer", url))
        toast("Viewer address copied")
    }

    private fun connectedAppsCard(entries: List<AppEntry>): LinearLayout {
        val card = card()
        entries.forEachIndexed { index, entry ->
            if (index > 0) card.addView(divider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(13), 0, dp(13))
            }
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(entry.name, 18f, COLOR_TEXT, bold = true))
                addView(text(entry.subtitle, 14f, COLOR_MUTED).withTop(3))
            }
            row.addView(labels)
            row.addView(secondaryButton("Open") {
                val ok = if (entry.isDialer) MediaControls.dialer(this) else MediaControls.launchPackage(this, entry.packageName!!)
                if (!ok) toast("${entry.name} is not installed")
            })
            card.addView(row)
        }
        return card
    }

    private fun sectionTitle(value: String): TextView =
        text(value, 20f, COLOR_TEXT, bold = true).apply {
            setPadding(dp(4), dp(28), dp(4), dp(10))
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(COLOR_CARD, 26f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        }
    }

    private fun buttonRow(vararg buttons: Button): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            buttons.forEachIndexed { index, button ->
                if (index > 0) addView(Space(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
                addView(button)
            }
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = button(label, COLOR_ACCENT, Color.rgb(7, 20, 38), onClick)
    private fun secondaryButton(label: String, onClick: () -> Unit): Button = button(label, Color.rgb(61, 64, 70), COLOR_TEXT, onClick)

    private fun button(label: String, backgroundColor: Int, foregroundColor: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(foregroundColor)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(backgroundColor, 22f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minHeight = dp(44)
            setOnClickListener { onClick() }
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.08f)
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.rgb(69, 71, 76))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun <T : View> T.withTop(topDp: Int): T {
        layoutParams = (layoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)).also {
            if (it is ViewGroup.MarginLayoutParams) it.topMargin = dp(topDp)
        }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private data class AppEntry(
        val name: String,
        val subtitle: String,
        val packageName: String?,
        val isDialer: Boolean = false,
    )

    companion object {
        private val COLOR_BACKGROUND = Color.rgb(16, 17, 18)
        private val COLOR_CARD = Color.rgb(40, 42, 46)
        private val COLOR_TEXT = Color.rgb(245, 245, 245)
        private val COLOR_MUTED = Color.rgb(167, 169, 174)
        private val COLOR_ACCENT = Color.rgb(102, 168, 255)
    }
}
