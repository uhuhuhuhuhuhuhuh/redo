package com.druvane.glasseshub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class TestPatternGenerator {
    private var executor: ScheduledExecutorService? = null
    private var tick = 0

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "glasses-test-pattern").apply { isDaemon = true }
        }.also { service ->
            service.scheduleAtFixedRate(::render, 0, 100, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    private fun render() {
        val width = 720
        val height = 1280
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(16, 17, 18))

        val bands = intArrayOf(
            Color.rgb(102, 168, 255),
            Color.rgb(56, 191, 137),
            Color.rgb(255, 184, 77),
            Color.rgb(235, 99, 116),
        )
        for (i in bands.indices) {
            paint.color = bands[(i + tick / 8) % bands.size]
            canvas.drawRect(0f, (i * 120 + 220).toFloat(), width.toFloat(), (i * 120 + 300).toFloat(), paint)
        }

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 58f
        paint.isFakeBoldText = true
        canvas.drawText("Glasses Hub", width / 2f, 130f, paint)
        paint.isFakeBoldText = false
        paint.textSize = 34f
        canvas.drawText("Local web camera test", width / 2f, 185f, paint)
        paint.textSize = 30f
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        canvas.drawText(time, width / 2f, 780f, paint)
        canvas.drawText("Replace with Meta glasses frames after registration", width / 2f, 870f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 12f
        paint.color = bands[tick % bands.size]
        canvas.drawCircle(width / 2f, 1040f, 115f, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 52f
        canvas.drawText((tick % 1000).toString(), width / 2f, 1058f, paint)

        val bytes = ByteArrayOutputStream(350_000).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        FrameHub.publishTestPattern(bytes, width, height)
        tick++
    }
}
