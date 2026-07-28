package com.druvane.glasseshub

import android.graphics.Bitmap

object I420BitmapConverter {
    private const val BUFFER_COUNT = 4
    private var pixels = IntArray(0)
    private var bitmaps: Array<Bitmap?> = arrayOfNulls(BUFFER_COUNT)
    private var bitmapIndex = 0
    private var cachedWidth = 0
    private var cachedHeight = 0

    @Synchronized
    fun convert(i420: ByteArray, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || width and 1 != 0 || height and 1 != 0) return null
        val frameSize = width * height
        val expected = frameSize + frameSize / 2
        if (i420.size < expected) return null

        ensureBuffers(width, height)
        val uOffset = frameSize
        val vOffset = frameSize + frameSize / 4
        val halfWidth = width / 2
        var outputIndex = 0

        for (row in 0 until height) {
            val uvRow = (row shr 1) * halfWidth
            for (column in 0 until width) {
                val y = (i420[outputIndex].toInt() and 0xFF) - 16
                val uvIndex = uvRow + (column shr 1)
                val u = (i420[uOffset + uvIndex].toInt() and 0xFF) - 128
                val v = (i420[vOffset + uvIndex].toInt() and 0xFF) - 128
                val c = y.coerceAtLeast(0)

                val r = (298 * c + 409 * v + 128) shr 8
                val g = (298 * c - 100 * u - 208 * v + 128) shr 8
                val b = (298 * c + 516 * u + 128) shr 8

                pixels[outputIndex] =
                    0xFF000000.toInt() or
                        (clamp8(r) shl 16) or
                        (clamp8(g) shl 8) or
                        clamp8(b)
                outputIndex++
            }
        }

        val bitmap = bitmaps[bitmapIndex] ?: return null
        bitmapIndex = (bitmapIndex + 1) % BUFFER_COUNT
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun ensureBuffers(width: Int, height: Int) {
        val frameSize = width * height
        if (cachedWidth == width && cachedHeight == height && pixels.size >= frameSize) return
        bitmaps.forEach { bitmap ->
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
        pixels = IntArray(frameSize)
        bitmaps = Array(BUFFER_COUNT) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
        bitmapIndex = 0
        cachedWidth = width
        cachedHeight = height
    }

    private fun clamp8(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }
}
