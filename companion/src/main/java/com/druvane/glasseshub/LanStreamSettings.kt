package com.druvane.glasseshub

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LanStreamSettings {
    data class State(
        val delayMs: Int = DEFAULT_DELAY_MS,
        val outputFps: Int = DEFAULT_OUTPUT_FPS,
    )

    private val initialized = AtomicBoolean(false)
    private lateinit var appContext: Context
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        _state.value = State(
            delayMs = preferences.getInt(KEY_DELAY_MS, DEFAULT_DELAY_MS).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS),
            outputFps = DEFAULT_OUTPUT_FPS,
        )
    }

    fun setDelayMs(value: Int): Int {
        val clamped = value.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        check(initialized.get()) { "LanStreamSettings is not initialized" }
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DELAY_MS, clamped)
            .apply()
        _state.value = _state.value.copy(delayMs = clamped)
        return clamped
    }

    fun delayMs(): Int = _state.value.delayMs

    const val DEFAULT_DELAY_MS = 1_000
    const val MIN_DELAY_MS = 0
    const val MAX_DELAY_MS = 5_000
    const val DEFAULT_OUTPUT_FPS = 30
    private const val PREFERENCES = "lan_stream_settings"
    private const val KEY_DELAY_MS = "fixed_delay_ms"
}
