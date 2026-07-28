package com.druvane.glasseshub

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AudioFrameHub {
    data class Chunk(
        val speex: ByteArray,
        val sequence: Long,
        val timestampMs: Long,
    )

    data class Packet(
        val bytes: ByteArray,
        val lastSequence: Long,
        val timestampMs: Long,
    )

    data class Stats(
        val source: String = "Waiting for glasses microphone",
        val sourceSampleRate: Int = 0,
        val sourceChannels: Int = 0,
        val chunks: Long = 0,
        val encodedFps: Float = 0f,
        val bufferedChunks: Int = 0,
        val bufferedDurationMs: Long = 0L,
        val lastChunkAtMs: Long = 0L,
        val ready: Boolean = false,
        val error: String? = null,
    )

    private val lock = Object()
    private val sequence = AtomicLong(0L)
    private val chunks = ArrayDeque<Chunk>()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private var encoder: DroidCamSpeexEncoder? = null
    private var converter: PcmDownsampler? = null
    private var formatRate = 0
    private var formatChannels = 0
    private var sourceDescription = "Waiting for glasses microphone"
    private var fpsWindowStartedAtMs = 0L
    private var chunksInWindow = 0

    fun configure(sampleRate: Int, channels: Int, source: String) = synchronized(lock) {
        if (sampleRate <= 0 || channels <= 0) {
            markUnavailable("Invalid glasses audio format: ${sampleRate} Hz, $channels channels")
            return@synchronized
        }
        if (formatRate != sampleRate || formatChannels != channels || converter == null) {
            formatRate = sampleRate
            formatChannels = channels
            converter = PcmDownsampler(sampleRate, channels)
            chunks.clear()
            sequence.set(0L)
        }
        sourceDescription = source
        if (encoder == null) {
            encoder = runCatching { DroidCamSpeexEncoder() }
                .onFailure { error ->
                    _stats.value = _stats.value.copy(error = "Speex encoder failed: ${error.message}")
                }
                .getOrNull()
        }
        _stats.value = _stats.value.copy(
            source = sourceDescription,
            sourceSampleRate = sampleRate,
            sourceChannels = channels,
            error = if (encoder == null) _stats.value.error else null,
        )
        DroidCamState.update { it.copy(audioSource = sourceDescription) }
    }

    fun submit(buffer: ByteBuffer, presentationTimeUs: Long) = synchronized(lock) {
        val activeConverter = converter ?: return@synchronized
        val activeEncoder = encoder ?: return@synchronized
        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameStartMs = MediaTimeline.map(presentationTimeUs)
        activeConverter.consume(duplicate, frameStartMs) { pcm, timestampMs ->
            val encoded = activeEncoder.encode(pcm)
            if (encoded == null || encoded.size != DroidCamSpeexEncoder.ENCODED_CHUNK_BYTES) {
                _stats.value = _stats.value.copy(
                    ready = false,
                    error = "Speex output did not match DroidCam's 70-byte wideband frame",
                )
                return@consume
            }
            publishEncoded(encoded, timestampMs)
        }
    }

    fun packetFor(targetTimestampMs: Long, afterSequence: Long): Packet? = synchronized(lock) {
        if (chunks.isEmpty()) return@synchronized null
        val eligible = chunks.filter { it.sequence > afterSequence && it.timestampMs <= targetTimestampMs }
        if (eligible.isEmpty()) return@synchronized null

        val selected = when {
            eligible.size >= 6 -> eligible.takeLast(2)
            eligible.size >= 2 -> eligible.take(2)
            else -> eligible
        }
        val packet = ByteArray(DroidCamSpeexEncoder.ENCODED_PACKET_BYTES)
        System.arraycopy(selected[0].speex, 0, packet, 0, DroidCamSpeexEncoder.ENCODED_CHUNK_BYTES)
        val second = selected.getOrNull(1)
        if (second != null) {
            System.arraycopy(
                second.speex,
                0,
                packet,
                DroidCamSpeexEncoder.ENCODED_CHUNK_BYTES,
                DroidCamSpeexEncoder.ENCODED_CHUNK_BYTES,
            )
        } else {
            System.arraycopy(
                DroidCamSpeexEncoder.silenceChunk,
                0,
                packet,
                DroidCamSpeexEncoder.ENCODED_CHUNK_BYTES,
                DroidCamSpeexEncoder.ENCODED_CHUNK_BYTES,
            )
        }
        val last = second ?: selected[0]
        Packet(packet, last.sequence, selected[0].timestampMs)
    }

    fun markUnavailable(message: String) = synchronized(lock) {
        _stats.value = _stats.value.copy(
            source = "Glasses microphone unavailable",
            ready = false,
            error = message,
        )
        DroidCamState.update { it.copy(audioSource = "Unavailable: $message") }
    }

    fun reset(source: String) = synchronized(lock) {
        chunks.clear()
        converter?.reset()
        sequence.set(0L)
        fpsWindowStartedAtMs = 0L
        chunksInWindow = 0
        _stats.value = Stats(source = source)
        DroidCamState.update { it.copy(audioSource = source) }
        lock.notifyAll()
    }

    private fun publishEncoded(encoded: ByteArray, timestampMs: Long) {
        val next = sequence.incrementAndGet()
        val now = System.currentTimeMillis()
        chunks.addLast(Chunk(encoded, next, timestampMs))
        while (chunks.size > MAX_BUFFERED_CHUNKS) chunks.removeFirst()
        while (chunks.size > 1 && now - chunks.first.timestampMs > BUFFER_RETENTION_MS) {
            chunks.removeFirst()
        }

        if (fpsWindowStartedAtMs == 0L) fpsWindowStartedAtMs = now
        chunksInWindow++
        val elapsed = now - fpsWindowStartedAtMs
        val previousFps = _stats.value.encodedFps
        val fps = if (elapsed >= 1_000L) {
            val measured = chunksInWindow * 1_000f / elapsed.coerceAtLeast(1L)
            fpsWindowStartedAtMs = now
            chunksInWindow = 0
            measured
        } else {
            previousFps
        }
        val duration = if (chunks.size > 1) chunks.last.timestampMs - chunks.first.timestampMs else 0L
        _stats.value = Stats(
            source = sourceDescription,
            sourceSampleRate = formatRate,
            sourceChannels = formatChannels,
            chunks = next,
            encodedFps = fps,
            bufferedChunks = chunks.size,
            bufferedDurationMs = duration.coerceAtLeast(0L),
            lastChunkAtMs = now,
            ready = true,
            error = null,
        )
        lock.notifyAll()
    }

    private class PcmDownsampler(
        private val sampleRate: Int,
        private val channels: Int,
    ) {
        private val output = ShortArray(DroidCamSpeexEncoder.SAMPLES_PER_CHUNK)
        private var outputPosition = 0
        private var phase = 0L
        private var averageSum = 0L
        private var averageCount = 0
        private var currentChunkTimestampMs = Long.MIN_VALUE
        private var lastInputEndMs = Long.MIN_VALUE

        fun consume(buffer: ByteBuffer, frameStartMs: Long, onChunk: (ShortArray, Long) -> Unit) {
            val bytesPerFrame = channels * 2
            val inputFrames = buffer.remaining() / bytesPerFrame
            if (inputFrames <= 0) return
            val calculatedEnd = frameStartMs + inputFrames * 1_000L / sampleRate
            if (lastInputEndMs != Long.MIN_VALUE && kotlin.math.abs(frameStartMs - lastInputEndMs) > 300L) {
                reset()
            }
            if (currentChunkTimestampMs == Long.MIN_VALUE) currentChunkTimestampMs = frameStartMs

            repeat(inputFrames) {
                var channelSum = 0L
                repeat(channels) {
                    channelSum += buffer.short.toLong()
                }
                val mono = (channelSum / channels).toInt()
                averageSum += mono
                averageCount++
                phase += DroidCamSpeexEncoder.SAMPLE_RATE_HZ
                if (phase >= sampleRate) {
                    phase -= sampleRate
                    val averaged = (averageSum / averageCount.coerceAtLeast(1)).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                    output[outputPosition++] = averaged.toShort()
                    averageSum = 0L
                    averageCount = 0
                    if (outputPosition == output.size) {
                        onChunk(output.copyOf(), currentChunkTimestampMs)
                        outputPosition = 0
                        currentChunkTimestampMs += DroidCamSpeexEncoder.CHUNK_DURATION_MS
                    }
                }
            }
            lastInputEndMs = calculatedEnd
        }

        fun reset() {
            outputPosition = 0
            phase = 0L
            averageSum = 0L
            averageCount = 0
            currentChunkTimestampMs = Long.MIN_VALUE
            lastInputEndMs = Long.MIN_VALUE
        }
    }

    private const val BUFFER_RETENTION_MS = 7_000L
    private const val MAX_BUFFERED_CHUNKS = 400
}
