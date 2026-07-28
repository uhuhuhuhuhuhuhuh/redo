package com.druvane.glasseshub

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object MetaGlassesController {
    data class Status(
        val registration: String = "Not registered",
        val connection: String = "Idle",
        val camera: String = "Stopped",
        val lastError: String? = null,
        val reconnectAttempt: Int = 0,
    )

    private lateinit var application: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val initialized = AtomicBoolean(false)
    private val desiredConnection = AtomicBoolean(false)
    private val desiredCamera = AtomicBoolean(false)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var registrationJob: Job? = null
    private var deviceJob: Job? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var frameJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    fun initialize(app: Application) {
        if (!initialized.compareAndSet(false, true)) return
        application = app
        registrationJob = scope.launch {
            Wearables.registrationState.collect { state ->
                _status.value = _status.value.copy(registration = state.toString())
                if (state == RegistrationState.REGISTERED && desiredConnection.get()) {
                    ensureSession()
                } else if (state != RegistrationState.REGISTERED) {
                    closeSession("Waiting for Meta AI registration")
                }
            }
        }
        deviceJob = scope.launch {
            Wearables.devices.collect { devices ->
                if (devices.isNotEmpty() && desiredConnection.get() && Wearables.registrationState.value == RegistrationState.REGISTERED) {
                    ensureSession()
                }
            }
        }
    }

    fun startConnection() {
        desiredConnection.set(true)
        scope.launch { ensureSession() }
    }

    fun stopConnection() {
        desiredConnection.set(false)
        desiredCamera.set(false)
        reconnectJob?.cancel()
        scope.launch { closeSession("Stopped by user") }
    }

    fun setCameraRequested(enabled: Boolean) {
        desiredCamera.set(enabled)
        if (enabled) {
            desiredConnection.set(true)
            scope.launch {
                ensureSession()
                ensureStream()
            }
        } else {
            scope.launch { closeStream("Stopped") }
        }
    }

    fun forceReconnect() {
        desiredConnection.set(true)
        reconnectJob?.cancel()
        reconnectAttempt = 0
        scope.launch {
            closeSession("Reconnecting")
            delay(350)
            ensureSession()
        }
    }

    private suspend fun ensureSession() {
        lock.withLock {
            if (!desiredConnection.get()) return
            if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
                _status.value = _status.value.copy(connection = "Register through Meta AI first")
                return
            }
            if (session != null) return
            _status.value = _status.value.copy(connection = "Connecting", lastError = null)
            Wearables.createSession(AutoDeviceSelector())
                .onSuccess { created ->
                    session = created
                    observeSession(created)
                    created.start()
                }
                .onFailure { error, _ ->
                    val message = error.description
                    _status.value = _status.value.copy(connection = "Connection failed", lastError = message)
                    scheduleReconnect(message)
                }
        }
    }

    private fun observeSession(created: DeviceSession) {
        sessionStateJob?.cancel()
        sessionErrorJob?.cancel()
        sessionStateJob = scope.launch {
            created.state.collect { state ->
                Log.d(TAG, "Session state: $state")
                when (state) {
                    DeviceSessionState.STARTED -> {
                        reconnectAttempt = 0
                        _status.value = _status.value.copy(
                            connection = "Connected",
                            reconnectAttempt = 0,
                            lastError = null,
                        )
                        if (desiredCamera.get()) ensureStream()
                    }
                    DeviceSessionState.PAUSED -> {
                        _status.value = _status.value.copy(connection = "Paused by glasses")
                    }
                    DeviceSessionState.STOPPED -> {
                        if (session === created) {
                            clearSessionReferences()
                            _status.value = _status.value.copy(connection = "Disconnected")
                            if (desiredConnection.get()) scheduleReconnect("Session stopped")
                        }
                    }
                    else -> {
                        _status.value = _status.value.copy(connection = state.toString())
                    }
                }
            }
        }
        sessionErrorJob = scope.launch {
            created.errors.collect { error ->
                val message = error.description
                Log.w(TAG, "Session error: $message")
                _status.value = _status.value.copy(lastError = message)
                if (desiredConnection.get()) {
                    closeSession("Session error")
                    scheduleReconnect(message)
                }
            }
        }
    }

    private suspend fun ensureStream() {
        lock.withLock {
            if (!desiredCamera.get()) return
            val activeSession = session ?: return
            if (activeSession.state.value != DeviceSessionState.STARTED) return
            if (stream != null) return

            _status.value = _status.value.copy(camera = "Starting 720x1280 at 30 FPS", lastError = null)
            activeSession.addStream(
                StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 30),
            ).onSuccess { added ->
                stream = added
                observeStream(added)
                added.start()
            }.onFailure { error, _ ->
                val message = error.description
                _status.value = _status.value.copy(camera = "Camera unavailable", lastError = message)
                scheduleStreamRetry(message)
            }
        }
    }

    private fun observeStream(added: Stream) {
        streamStateJob?.cancel()
        streamErrorJob?.cancel()
        frameJob?.cancel()

        streamStateJob = scope.launch {
            added.state.collect { state ->
                Log.d(TAG, "Stream state: $state")
                _status.value = _status.value.copy(camera = state.toString())
                if (state == StreamState.CLOSED && stream === added) {
                    clearStreamReferences()
                    FrameHub.resetSource("Camera stream closed")
                    if (desiredCamera.get()) scheduleStreamRetry("Camera stream closed")
                }
            }
        }
        streamErrorJob = scope.launch {
            added.errorStream.collect { error ->
                val message = error.description
                Log.w(TAG, "Stream error: $message")
                _status.value = _status.value.copy(lastError = message)
            }
        }
        frameJob = scope.launch {
            added.videoStream.collect { frame ->
                val jpeg = I420JpegEncoder.encode(frame.buffer, frame.width, frame.height)
                if (jpeg != null) {
                    FrameHub.publish(jpeg, frame.width, frame.height)
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!desiredConnection.get() || reconnectJob?.isActive == true) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(20)
        val delayMs = (1_000L shl (reconnectAttempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
        _status.value = _status.value.copy(
            connection = "Retrying in ${delayMs / 1_000}s",
            lastError = reason,
            reconnectAttempt = reconnectAttempt,
        )
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJob = null
            ensureSession()
        }
    }

    private fun scheduleStreamRetry(reason: String) {
        if (!desiredCamera.get()) return
        _status.value = _status.value.copy(camera = "Retrying camera", lastError = reason)
        scope.launch {
            delay(2_000)
            closeStream("Restarting")
            ensureStream()
        }
    }

    private suspend fun closeStream(label: String) {
        lock.withLock {
            val current = stream
            clearStreamReferences()
            try {
                current?.stop()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Failed to stop stream", error)
            }
            _status.value = _status.value.copy(camera = label)
            FrameHub.resetSource("$label - no glasses frames")
        }
    }

    private suspend fun closeSession(label: String) {
        lock.withLock {
            val currentStream = stream
            val currentSession = session
            clearStreamReferences()
            clearSessionReferences()
            try {
                currentStream?.stop()
            } catch (_: RuntimeException) {
            }
            try {
                currentSession?.stop()
            } catch (_: RuntimeException) {
            }
            _status.value = _status.value.copy(connection = label, camera = "Stopped")
        }
    }

    private fun clearStreamReferences() {
        frameJob?.cancel()
        frameJob = null
        streamStateJob?.cancel()
        streamStateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
        stream = null
    }

    private fun clearSessionReferences() {
        clearStreamReferences()
        sessionStateJob?.cancel()
        sessionStateJob = null
        sessionErrorJob?.cancel()
        sessionErrorJob = null
        session = null
    }

    fun shutdown() {
        desiredConnection.set(false)
        desiredCamera.set(false)
        scope.launch { closeSession("Stopped") }
    }

    private const val TAG = "MetaGlassesController"
}
