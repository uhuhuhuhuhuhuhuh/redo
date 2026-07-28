package com.druvane.glasseshub

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PreviewFrameHub {
    data class Frame(
        val bitmap: Bitmap,
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
        val fps: Float = 0f,
        val lastFrameAtMs: Long = 0,
    )

    private val sequence = AtomicLong(0)
    private val _latest = MutableStateFlow<Frame?>(null)
    val latest: StateFlow<Frame?> = _latest.asStateFlow()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private var windowStartedAtMs = 0L
    private var framesInWindow = 0

    @Synchronized
    fun publish(bitmap: Bitmap, width: Int, height: Int, timestampMs: Long) {
        val next = sequence.incrementAndGet()
        val now = System.currentTimeMillis()
        if (windowStartedAtMs == 0L) windowStartedAtMs = now
        framesInWindow++
        val elapsed = now - windowStartedAtMs
        val previousFps = _stats.value.fps
        val fps = if (elapsed >= 1_000L) {
            val measured = framesInWindow * 1_000f / elapsed.coerceAtLeast(1L)
            windowStartedAtMs = now
            framesInWindow = 0
            measured
        } else {
            previousFps
        }

        _latest.value = Frame(bitmap, width, height, next, timestampMs)
        _stats.value = Stats(
            source = "Direct I420 preview",
            width = width,
            height = height,
            frames = next,
            fps = fps,
            lastFrameAtMs = now,
        )
    }

    @Synchronized
    fun clear(source: String) {
        _latest.value = null
        _stats.value = _stats.value.copy(
            source = source,
            width = 0,
            height = 0,
            fps = 0f,
            lastFrameAtMs = 0,
        )
        windowStartedAtMs = 0L
        framesInWindow = 0
    }
}
