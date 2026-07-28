package com.druvane.glasseshub

import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import java.lang.reflect.Method
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object MetaAudioBridge {
    data class Format(
        val sampleRate: Int,
        val channels: Int,
        val description: String,
    )

    @Suppress("UNCHECKED_CAST")
    fun start(stream: Stream, configuration: StreamConfiguration, scope: CoroutineScope): Job? {
        val audioMethod = findZeroArgumentMethod(stream.javaClass, "getAudioStreamInternal")
        if (audioMethod == null) {
            AudioFrameHub.markUnavailable("This DAT build does not expose its decoded audio flow")
            return null
        }
        val audioFlow = runCatching {
            audioMethod.isAccessible = true
            audioMethod.invoke(stream) as? Flow<Any?>
        }.getOrNull()
        if (audioFlow == null) {
            AudioFrameHub.markUnavailable("Unable to open the glasses audio flow")
            return null
        }

        val format = readFormat(configuration)
        AudioFrameHub.configure(format.sampleRate, format.channels, format.description)
        return scope.launch {
            var getBuffer: Method? = null
            var getPresentationTime: Method? = null
            runCatching {
                audioFlow.collect { value ->
                    if (value == null) return@collect
                    if (getBuffer == null) {
                        getBuffer = findZeroArgumentMethod(value.javaClass, "getBuffer")
                        getPresentationTime = findZeroArgumentMethod(value.javaClass, "getPresentationTimeUs")
                        getBuffer?.isAccessible = true
                        getPresentationTime?.isAccessible = true
                    }
                    val buffer = getBuffer?.invoke(value) as? ByteBuffer ?: return@collect
                    val presentationTimeUs = (getPresentationTime?.invoke(value) as? Long) ?: 0L
                    AudioFrameHub.submit(buffer, presentationTimeUs)
                }
            }.onFailure { error ->
                AudioFrameHub.markUnavailable("Glasses audio stream stopped: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun readFormat(configuration: StreamConfiguration): Format {
        return runCatching {
            val codecMethod = findZeroArgumentMethod(configuration.javaClass, "getAudioCodec")
                ?: return@runCatching null
            codecMethod.isAccessible = true
            val codec = codecMethod.invoke(configuration) ?: return@runCatching null
            val sampleRateMethod = findZeroArgumentMethod(codec.javaClass, "getSampleRate")
                ?: return@runCatching null
            val channelsMethod = findZeroArgumentMethod(codec.javaClass, "getNumberOfChannels")
                ?: return@runCatching null
            sampleRateMethod.isAccessible = true
            channelsMethod.isAccessible = true
            val sampleRateObject = sampleRateMethod.invoke(codec) ?: return@runCatching null
            val hertzMethod = findZeroArgumentMethod(sampleRateObject.javaClass, "getHertz")
                ?: return@runCatching null
            hertzMethod.isAccessible = true
            val sampleRate = (hertzMethod.invoke(sampleRateObject) as Number).toInt()
            val channels = (channelsMethod.invoke(codec) as Number).toInt()
            Format(
                sampleRate = sampleRate,
                channels = channels,
                description = "Meta glasses microphones · PCM ${sampleRate} Hz · $channels channel${if (channels == 1) "" else "s"}",
            )
        }.getOrNull() ?: Format(
            sampleRate = 16_000,
            channels = 1,
            description = "Meta glasses microphones · PCM format inferred as 16000 Hz mono",
        )
    }

    private fun findZeroArgumentMethod(type: Class<*>, prefix: String): Method? {
        return (type.methods.asSequence() + type.declaredMethods.asSequence())
            .firstOrNull { it.parameterCount == 0 && it.name.startsWith(prefix) }
    }
}
