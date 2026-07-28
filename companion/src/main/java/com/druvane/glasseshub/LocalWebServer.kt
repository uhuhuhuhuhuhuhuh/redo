package com.druvane.glasseshub

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalWebServer(private val context: Context, private val port: Int = DEFAULT_PORT) {
    private val running = AtomicBoolean(false)
    private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "glasses-web-client").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var endpoint: WifiLanResolver.Endpoint? = null
    var lastError: String? = null
        private set

    val token: String
        get() = preferences().getString(KEY_TOKEN, null) ?: createToken()

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val resolved = WifiLanResolver.resolve(context)
        if (resolved == null) {
            running.set(false)
            lastError = "Connect the phone to Wi-Fi or enable its hotspot before starting the LAN viewer"
            return false
        }
        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(resolved.address, port))
            }
            endpoint = resolved
            serverSocket = socket
            acceptThread = Thread({ acceptLoop(socket) }, "glasses-web-server").apply {
                isDaemon = true
                start()
            }
            lastError = null
            true
        } catch (error: IOException) {
            running.set(false)
            endpoint = null
            lastError = "Unable to bind Wi-Fi LAN port $port: ${error.message}"
            Log.e(TAG, "Unable to start local web server", error)
            false
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        endpoint = null
    }

    fun isRunning(): Boolean = running.get()

    fun viewerUrl(): String {
        val ip = endpoint?.address?.hostAddress ?: return ""
        return "http://$ip:$port/?token=$token"
    }

    fun interfaceName(): String = endpoint?.interfaceName.orEmpty()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                pool.execute { handleClient(client) }
            } catch (error: SocketException) {
                if (running.get()) Log.w(TAG, "Web server socket interrupted", error)
            } catch (error: IOException) {
                if (running.get()) Log.w(TAG, "Failed to accept viewer", error)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.tcpNoDelay = true
                client.soTimeout = 8_000
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2 || parts[0] != "GET") {
                    sendText(output, 405, "Method Not Allowed", "GET only")
                    return
                }
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                }

                val target = parts[1]
                val path = target.substringBefore('?')
                val params = parseQuery(target.substringAfter('?', ""))
                if (params["token"] != token) {
                    sendText(output, 403, "Forbidden", "Access code required")
                    return
                }

                when (path) {
                    "/" -> sendViewer(output)
                    "/stream.mjpg" -> streamMjpeg(output)
                    "/snapshot.jpg" -> sendSnapshot(output)
                    "/status.json" -> sendStatus(output)
                    else -> sendText(output, 404, "Not Found", "Unknown route")
                }
            } catch (_: IOException) {
                // Browsers routinely close MJPEG sockets while navigating away.
            } catch (error: RuntimeException) {
                Log.w(TAG, "Viewer request failed", error)
            }
        }
    }

    private fun sendViewer(output: BufferedOutputStream) {
        val safeToken = token.replace("\"", "")
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <title>Glasses Hub Wi-Fi Camera</title>
              <style>
                :root{color-scheme:dark;font-family:system-ui,sans-serif;background:#101112;color:#f5f5f5}
                body{margin:0;padding:18px;max-width:920px;margin-inline:auto}
                .card{background:#282a2e;border-radius:24px;padding:18px;margin:14px 0;box-shadow:0 4px 28px #0008}
                h1{font-size:1.55rem;margin:0 0 6px}.muted{color:#a7a9ae}
                img{display:block;width:100%;max-height:78vh;object-fit:contain;background:#000;border-radius:18px}
                a,button{display:inline-block;background:#66a8ff;color:#08111e;border:0;border-radius:999px;padding:11px 17px;text-decoration:none;font-weight:700;margin:8px 8px 0 0}
                code{word-break:break-all}.status{white-space:pre-wrap;font-family:ui-monospace,monospace}
              </style>
            </head>
            <body>
              <div class="card"><h1>Glasses Hub camera</h1><div class="muted">Wi-Fi LAN playback with the fixed delay configured on the phone. The phone preview remains unbuffered.</div></div>
              <div class="card"><img src="/stream.mjpg?token=$safeToken" alt="Delayed glasses view"></div>
              <div class="card"><a href="/snapshot.jpg?token=$safeToken">Open delayed snapshot</a><button onclick="location.reload()">Reconnect viewer</button></div>
              <div class="card status" id="status">Loading status…</div>
              <div class="card muted">This endpoint is bound only to the phone's Wi-Fi or hotspot LAN. Do not port-forward it to the public internet.</div>
              <script>
                const token=${jsString(safeToken)};
                async function update(){
                  try{const r=await fetch('/status.json?token='+encodeURIComponent(token),{cache:'no-store'});document.getElementById('status').textContent=JSON.stringify(await r.json(),null,2)}catch(e){document.getElementById('status').textContent=String(e)}
                }
                update();setInterval(update,2000);
              </script>
            </body></html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        sendHeaders(output, 200, "OK", "text/html; charset=utf-8", html.size.toLong(), cache = false)
        output.write(html)
        output.flush()
    }

    private fun streamMjpeg(output: BufferedOutputStream) {
        output.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                .toByteArray(StandardCharsets.US_ASCII),
        )
        output.flush()
        FrameHub.viewerConnected()
        try {
            var nextFrameAtNs = System.nanoTime()
            while (running.get()) {
                val settings = LanStreamSettings.state.value
                val targetTimestamp = System.currentTimeMillis() - settings.delayMs
                val frame = FrameHub.playbackFrame(targetTimestamp, 50L)
                if (frame != null) {
                    output.write("--frame\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write("Content-Type: image/jpeg\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write("Content-Length: ${frame.jpeg.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write("X-Frame-Sequence: ${frame.sequence}\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write("X-Playback-Delay-Ms: ${settings.delayMs}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write(frame.jpeg)
                    output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                }
                nextFrameAtNs += 1_000_000_000L / settings.outputFps.coerceAtLeast(1)
                sleepUntil(nextFrameAtNs)
            }
        } finally {
            FrameHub.viewerDisconnected()
        }
    }

    private fun sendSnapshot(output: BufferedOutputStream) {
        val delayMs = LanStreamSettings.delayMs()
        val frame = FrameHub.playbackFrame(System.currentTimeMillis() - delayMs) ?: FrameHub.latestFrame()
        if (frame == null) {
            sendText(output, 503, "Service Unavailable", "No camera frame is available yet")
            return
        }
        sendHeaders(output, 200, "OK", "image/jpeg", frame.jpeg.size.toLong(), cache = false)
        output.write(frame.jpeg)
        output.flush()
    }

    private fun sendStatus(output: BufferedOutputStream) {
        val stats = FrameHub.stats.value
        val previewStats = PreviewFrameHub.stats.value
        val audioStats = AudioFrameHub.stats.value
        val age = if (stats.lastFrameAtMs == 0L) -1 else System.currentTimeMillis() - stats.lastFrameAtMs
        val endpointValue = endpoint
        val body = """{"source":${json(stats.source)},"resolution":${json(if (stats.width > 0) "${stats.width}x${stats.height}" else "none")},"directPreviewFps":${formatFloat(previewStats.fps)},"lanEncodedFps":${formatFloat(stats.encodedFps)},"frames":${stats.frames},"viewers":${stats.viewers},"fixedDelayMs":${LanStreamSettings.delayMs()},"bufferedFrames":${stats.bufferedFrames},"bufferedDurationMs":${stats.bufferedDurationMs},"audioSource":${json(audioStats.source)},"audioReady":${audioStats.ready},"audioChunks":${audioStats.chunks},"wifiInterface":${json(endpointValue?.interfaceName.orEmpty())},"lastFrameAgeMs":$age,"serverRunning":${running.get()}}"""
            .toByteArray(StandardCharsets.UTF_8)
        sendHeaders(output, 200, "OK", "application/json; charset=utf-8", body.size.toLong(), cache = false)
        output.write(body)
        output.flush()
    }

    private fun sendText(output: BufferedOutputStream, code: Int, reason: String, text: String) {
        val body = text.toByteArray(StandardCharsets.UTF_8)
        sendHeaders(output, code, reason, "text/plain; charset=utf-8", body.size.toLong(), cache = false)
        output.write(body)
        output.flush()
    }

    private fun sendHeaders(
        output: BufferedOutputStream,
        code: Int,
        reason: String,
        contentType: String,
        length: Long,
        cache: Boolean,
    ) {
        val cacheHeader = if (cache) "Cache-Control: private, max-age=60\r\n" else "Cache-Control: no-store\r\n"
        output.write(
            ("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: $length\r\n" +
                cacheHeader +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n")
                .toByteArray(StandardCharsets.US_ASCII),
        )
    }

    private fun readLine(input: BufferedInputStream): String? {
        val output = ByteArrayOutputStream(128)
        while (output.size() < 8_192) {
            val value = input.read()
            if (value == -1) return if (output.size() == 0) null else output.toString(StandardCharsets.UTF_8.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) output.write(value)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { item ->
            val key = item.substringBefore('=', "")
            if (key.isBlank()) return@mapNotNull null
            val value = item.substringAfter('=', "")
            URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.toMap()
    }

    private fun createToken(): String {
        val value = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
        preferences().edit().putString(KEY_TOKEN, value).apply()
        return value
    }

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sleepUntil(targetNs: Long) {
        val remaining = targetNs - System.nanoTime()
        if (remaining <= 0L) return
        try {
            Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun formatFloat(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun json(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun jsString(value: String): String = json(value)

    companion object {
        const val DEFAULT_PORT = 8080
        private const val TAG = "LocalWebServer"
        private const val PREFS = "web_server"
        private const val KEY_TOKEN = "access_token"
    }
}
