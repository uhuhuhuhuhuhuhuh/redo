package com.druvane.glasseshub

import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

object RawFramePipeline {
    private data class SharedRawFrame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val timestampMs: Long,
        val generation: Long,
        private val references: AtomicInteger = AtomicInteger(2),
    ) {
        fun release() {
            if (references.decrementAndGet() == 0) RawFramePool.release(bytes)
        }
    }

    private object RawFramePool {
        private const val MAX_RETAINED = 10
        private val lock = Any()
        private val buffers = ArrayDeque<ByteArray>()

        fun acquire(minimumSize: Int): ByteArray = synchronized(lock) {
            val iterator = buffers.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.size >= minimumSize) {
                    iterator.remove()
                    return@synchronized candidate
                }
            }
            ByteArray(minimumSize)
        }

        fun release(buffer: ByteArray) = synchronized(lock) {
            if (buffers.size < MAX_RETAINED) buffers.addLast(buffer)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicLong(0L)
    private val previewChannel = Channel<SharedRawFrame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { it.release() },
    )
    private val webChannel = Channel<SharedRawFrame>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { it.release() },
    )

    init {
        scope.launch {
            for (frame in previewChannel) {
                try {
                    val bitmap = I420BitmapConverter.convert(frame.bytes, frame.width, frame.height)
                    if (bitmap != null && frame.generation == generation.get()) {
                        PreviewFrameHub.publish(bitmap, frame.width, frame.height, frame.timestampMs)
                    }
                } finally {
                    frame.release()
                }
            }
        }
        scope.launch {
            for (frame in webChannel) {
                try {
                    val jpeg = I420JpegEncoder.encode(frame.bytes, frame.width, frame.height, quality = 76)
                    if (jpeg != null && frame.generation == generation.get()) {
                        FrameHub.publish(
                            jpeg = jpeg,
                            width = frame.width,
                            height = frame.height,
                            source = "Meta glasses buffered Wi-Fi feed",
                            timestampMs = frame.timestampMs,
                        )
                    }
                } finally {
                    frame.release()
                }
            }
        }
    }

    fun submit(buffer: ByteBuffer, width: Int, height: Int, presentationTimeUs: Long): Boolean {
        if (width <= 0 || height <= 0 || width and 1 != 0 || height and 1 != 0) return false
        val expected = width * height * 3 / 2
        val duplicate = buffer.duplicate()
        if (duplicate.remaining() < expected) return false

        val bytes = RawFramePool.acquire(expected)
        duplicate.get(bytes, 0, expected)
        val timestampMs = MediaTimeline.map(presentationTimeUs)
        val shared = SharedRawFrame(bytes, width, height, timestampMs, generation.get())

        val previewResult = previewChannel.trySend(shared)
        if (previewResult.isFailure) shared.release()
        val webResult = webChannel.trySend(shared)
        if (webResult.isFailure) shared.release()
        return previewResult.isSuccess || webResult.isSuccess
    }

    fun clear(source: String) {
        generation.incrementAndGet()
        PreviewFrameHub.clear(source)
        FrameHub.resetSource(source)
    }
}
