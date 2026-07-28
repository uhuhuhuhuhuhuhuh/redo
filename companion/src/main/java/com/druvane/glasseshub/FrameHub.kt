package com.druvane.glasseshub

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FrameHub {
    data class Frame(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val sequence: Long,
        val timestampMs: Long,
    )

    data class Stats(
        val source: String = "Waiting for glasses",
        val width: Int = 0,
        val height: Int = 0,
        val frames: Long = 0,
        val viewers: Int = 0,
        val lastFrameAtMs: Long = 0,
    )

    private val latest = AtomicReference<Frame?>()
    private val sequence = AtomicLong(0)
    private val viewers = AtomicInteger(0)
    private val monitor = Object()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    fun publish(jpeg: ByteArray, width: Int, height: Int, source: String = "Meta glasses") {
        if (jpeg.isEmpty()) return
        val next = sequence.incrementAndGet()
        val now = System.currentTimeMillis()
        latest.set(Frame(jpeg, width, height, next, now))
        _stats.value = _stats.value.copy(
            source = source,
            width = width,
            height = height,
            frames = next,
            viewers = viewers.get(),
            lastFrameAtMs = now,
        )
        synchronized(monitor) { monitor.notifyAll() }
    }

    fun publishTestPattern(jpeg: ByteArray, width: Int, height: Int) {
        publish(jpeg, width, height, "Test pattern")
    }

    fun latestFrame(): Frame? = latest.get()

    fun awaitFrame(afterSequence: Long, timeoutMs: Long): Frame? {
        latest.get()?.let { if (it.sequence > afterSequence) return it }
        synchronized(monitor) {
            try {
                monitor.wait(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return latest.get()?.takeIf { it.sequence > afterSequence }
    }

    fun viewerConnected() {
        val count = viewers.incrementAndGet()
        _stats.value = _stats.value.copy(viewers = count)
    }

    fun viewerDisconnected() {
        val count = viewers.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        _stats.value = _stats.value.copy(viewers = count)
    }

    fun resetSource(source: String) {
        _stats.value = _stats.value.copy(source = source)
    }
}
