package com.druvane.glasseshub

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object I420JpegEncoder {
    private val lock = Any()
    private var copyBuffer = ByteArray(0)
    private var nv21 = ByteArray(0)
    private val output = ByteArrayOutputStream(512 * 1024)

    fun encode(i420: ByteArray, width: Int, height: Int, quality: Int = 76): ByteArray? {
        if (width <= 0 || height <= 0 || width and 1 != 0 || height and 1 != 0) return null
        val frameSize = width * height
        val chromaSize = frameSize / 4
        val expected = frameSize + chromaSize * 2
        if (i420.size < expected) return null

        synchronized(lock) {
            if (nv21.size < expected) nv21 = ByteArray(expected)
            System.arraycopy(i420, 0, nv21, 0, frameSize)
            val uOffset = frameSize
            val vOffset = frameSize + chromaSize
            var target = frameSize
            for (index in 0 until chromaSize) {
                nv21[target++] = i420[vOffset + index]
                nv21[target++] = i420[uOffset + index]
            }

            output.reset()
            val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val success = image.compressToJpeg(
                Rect(0, 0, width, height),
                quality.coerceIn(40, 95),
                output,
            )
            return if (success) output.toByteArray() else null
        }
    }

    fun encode(buffer: ByteBuffer, width: Int, height: Int, quality: Int = 76): ByteArray? {
        val expected = width * height * 3 / 2
        val duplicate = buffer.duplicate()
        if (expected <= 0 || duplicate.remaining() < expected) return null
        synchronized(lock) {
            if (copyBuffer.size < expected) copyBuffer = ByteArray(expected)
            duplicate.get(copyBuffer, 0, expected)
            return encode(copyBuffer, width, height, quality)
        }
    }
}
