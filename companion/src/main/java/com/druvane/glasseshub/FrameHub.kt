package com.druvane.glasseshub

import java.util.ArrayDeque
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
        val encodedFps: Float = 0f,
        val viewers: Int = 0,
        val bufferedFrames: Int = 0,
        val bufferedDurationMs: Long = 0,
        val lastFrameAtMs: Long = 0,
    )

    private val latest = AtomicReference<Frame?>()
    private val sequence = AtomicLong(0)
    private val viewers = AtomicInteger(0)
    private val monitor = Object()
    private val buffer = ArrayDeque<Frame>()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private var fpsWindowStartedAtMs = 0L
    private var framesInWindow = 0

    fun publish(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        source: String = "Meta glasses",
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        if (jpeg.isEmpty()) return
        val next = sequence.incrementAndGet()
        val frame = Frame(jpeg, width, height, next, timestampMs)
        val now = System.currentTimeMillis()

        synchronized(monitor) {
            latest.set(frame)
            buffer.addLast(frame)
            while (buffer.size > MAX_BUFFERED_FRAMES) buffer.removeFirst()
            while (buffer.size > 1 && now - buffer.first.timestampMs > BUFFER_RETENTION_MS) {
                buffer.removeFirst()
            }

            if (fpsWindowStartedAtMs == 0L) fpsWindowStartedAtMs = now
            framesInWindow++
            val elapsed = now - fpsWindowStartedAtMs
            val previousFps = _stats.value.encodedFps
            val fps = if (elapsed >= 1_000L) {
                val measured = framesInWindow * 1_000f / elapsed.coerceAtLeast(1L)
                fpsWindowStartedAtMs = now
                framesInWindow = 0
                measured
            } else {
                previousFps
            }
            val duration = if (buffer.size > 1) {
                buffer.last.timestampMs - buffer.first.timestampMs
            } else {
                0L
            }
            _stats.value = Stats(
                source = source,
                width = width,
                height = height,
                frames = next,
                encodedFps = fps,
                viewers = viewers.get(),
                bufferedFrames = buffer.size,
                bufferedDurationMs = duration.coerceAtLeast(0L),
                lastFrameAtMs = now,
            )
            monitor.notifyAll()
        }
    }

    fun publishTestPattern(jpeg: ByteArray, width: Int, height: Int) {
        publish(jpeg, width, height, "Test pattern")
    }

    fun latestFrame(): Frame? = latest.get()

    fun playbackFrame(targetTimestampMs: Long, waitMs: Long = 0L): Frame? = synchronized(monitor) {
        selectPlaybackFrameLocked(targetTimestampMs)?.let { return@synchronized it }
        if (waitMs > 0L) {
            try {
                monitor.wait(waitMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        selectPlaybackFrameLocked(targetTimestampMs)
    }

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

    private fun selectPlaybackFrameLocked(targetTimestampMs: Long): Frame? {
        val iterator = buffer.descendingIterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.timestampMs <= targetTimestampMs) return candidate
        }
        return null
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
        synchronized(monitor) {
            latest.set(null)
            buffer.clear()
            fpsWindowStartedAtMs = 0L
            framesInWindow = 0
            _stats.value = _stats.value.copy(
                source = source,
                width = 0,
                height = 0,
                encodedFps = 0f,
                bufferedFrames = 0,
                bufferedDurationMs = 0,
                lastFrameAtMs = 0,
            )
            monitor.notifyAll()
        }
    }

    private const val BUFFER_RETENTION_MS = 3_000L
    private const val MAX_BUFFERED_FRAMES = 90
}
