package com.druvane.glasseshub

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DroidCamState {
    data class State(
        val running: Boolean = false,
        val address: String = "",
        val interfaceName: String = "",
        val videoClients: Int = 0,
        val audioClients: Int = 0,
        val audioSource: String = "Waiting for glasses microphone",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }

    fun replace(value: State) {
        _state.value = value
    }
}
