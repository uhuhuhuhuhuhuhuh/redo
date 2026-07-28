package com.druvane.glasseshub

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WebCameraState {
    data class State(
        val running: Boolean = false,
        val url: String = "",
        val mode: String = "Stopped",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(value: State) {
        _state.value = value
    }
}
