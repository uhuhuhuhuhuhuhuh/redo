package com.druvane.glasseshub

import org.xiph.speex.SpeexEncoder

class DroidCamSpeexEncoder {
    private val encoder: SpeexEncoder = newEncoder()
    private val output = ByteArray(MAX_ENCODED_BYTES)

    @Synchronized
    fun encode(samples: ShortArray): ByteArray? {
        if (samples.size != SAMPLES_PER_CHUNK) return null
        encoder.processData(samples, 0, samples.size)
        val size = encoder.getProcessedDataByteSize()
        if (size != ENCODED_CHUNK_BYTES || size > output.size) {
            if (size in 1..output.size) encoder.getProcessedData(output, 0)
            return null
        }
        encoder.getProcessedData(output, 0)
        return output.copyOf(size)
    }

    private fun newEncoder(): SpeexEncoder {
        val created = SpeexEncoder()
        check(created.init(MODE_WIDEBAND, QUALITY_28_KBPS, SAMPLE_RATE_HZ, CHANNELS)) {
            "Unable to initialize the Speex wideband encoder"
        }
        created.encoder.apply {
            setSamplingRate(SAMPLE_RATE_HZ)
            setVbr(false)
            setVad(false)
            setDtx(false)
            setComplexity(3)
        }
        return created
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNELS = 1
        const val CHUNK_DURATION_MS = 20L
        const val SAMPLES_PER_CHUNK = 320
        const val ENCODED_CHUNK_BYTES = 70
        const val CHUNKS_PER_PACKET = 2
        const val ENCODED_PACKET_BYTES = ENCODED_CHUNK_BYTES * CHUNKS_PER_PACKET
        private const val MODE_WIDEBAND = 1
        private const val QUALITY_28_KBPS = 8
        private const val MAX_ENCODED_BYTES = 256

        val silenceChunk: ByteArray by lazy {
            DroidCamSpeexEncoder().encode(ShortArray(SAMPLES_PER_CHUNK))
                ?: ByteArray(ENCODED_CHUNK_BYTES)
        }

        val silencePacket: ByteArray by lazy {
            ByteArray(ENCODED_PACKET_BYTES).also { packet ->
                System.arraycopy(silenceChunk, 0, packet, 0, ENCODED_CHUNK_BYTES)
                System.arraycopy(silenceChunk, 0, packet, ENCODED_CHUNK_BYTES, ENCODED_CHUNK_BYTES)
            }
        }
    }
}
