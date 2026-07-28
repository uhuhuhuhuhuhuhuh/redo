package com.druvane.glasseshub

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DroidCamCompatServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT,
) {
    private data class UdpAudioClient(
        @Volatile var lastSeenMs: Long,
        @Volatile var lastSequence: Long = 0L,
    )

    private val running = AtomicBoolean(false)
    private val videoClients = AtomicInteger(0)
    private val tcpAudioClients = AtomicInteger(0)
    private val udpAudioClients = ConcurrentHashMap<SocketAddress, UdpAudioClient>()
    private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "droidcam-compat-client").apply { isDaemon = true }
    }

    private var endpoint: WifiLanResolver.Endpoint? = null
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var acceptThread: Thread? = null
    private var udpReceiveThread: Thread? = null
    private var udpSendThread: Thread? = null
    var lastError: String? = null
        private set

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val resolved = WifiLanResolver.resolve(context)
        if (resolved == null) {
            running.set(false)
            lastError = "Connect the phone to Wi-Fi or enable its hotspot before starting DroidCam LAN mode"
            publishState()
            return false
        }

        return try {
            endpoint = resolved
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(resolved.address, port))
            }
            udpSocket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(resolved.address, port + 1))
                    soTimeout = 1_000
                }
            }.onFailure { error ->
                Log.w(TAG, "DroidCam UDP audio unavailable; TCP fallback remains active", error)
            }.getOrNull()

            acceptThread = Thread(::acceptLoop, "droidcam-compat-accept").apply {
                isDaemon = true
                start()
            }
            if (udpSocket != null) {
                udpReceiveThread = Thread(::udpReceiveLoop, "droidcam-compat-udp-rx").apply {
                    isDaemon = true
                    start()
                }
                udpSendThread = Thread(::udpSendLoop, "droidcam-compat-udp-tx").apply {
                    isDaemon = true
                    start()
                }
            }
            lastError = null
            publishState()
            true
        } catch (error: IOException) {
            lastError = "Unable to bind DroidCam port $port on ${resolved.address.hostAddress}: ${error.message}"
            Log.e(TAG, lastError.orEmpty(), error)
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        serverSocket = null
        udpSocket = null
        acceptThread?.interrupt()
        udpReceiveThread?.interrupt()
        udpSendThread?.interrupt()
        acceptThread = null
        udpReceiveThread = null
        udpSendThread = null
        udpAudioClients.clear()
        endpoint = null
        videoClients.set(0)
        tcpAudioClients.set(0)
        publishState()
    }

    fun isRunning(): Boolean = running.get()

    fun address(): String {
        val address = endpoint?.address?.hostAddress ?: return ""
        return "$address:$port"
    }

    private fun acceptLoop() {
        val listener = serverSocket ?: return
        while (running.get()) {
            try {
                val client = listener.accept()
                pool.execute { handleTcpClient(client) }
            } catch (error: SocketException) {
                if (running.get()) Log.w(TAG, "DroidCam listener interrupted", error)
            } catch (error: IOException) {
                if (running.get()) Log.w(TAG, "DroidCam accept failed", error)
            }
        }
    }

    private fun handleTcpClient(socket: Socket) {
        socket.use { client ->
            try {
                client.tcpNoDelay = true
                client.soTimeout = 2_000
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val request = readRequest(input)
                client.soTimeout = 0
                when {
                    request.startsWith(VIDEO_REQUEST_PREFIX) -> streamVideo(output)
                    request.startsWith(AUDIO_REQUEST) -> streamAudioTcp(output)
                    request.startsWith(BATTERY_REQUEST_PREFIX) -> sendBattery(output)
                    request.startsWith(PING_REQUEST) -> {
                        output.write("OK".toByteArray(StandardCharsets.US_ASCII))
                        output.flush()
                    }
                    request.startsWith(STOP_REQUEST) -> Unit
                    else -> Log.d(TAG, "Ignoring unknown DroidCam request: $request")
                }
            } catch (_: IOException) {
                // The desktop client closed the stream.
            } catch (error: RuntimeException) {
                Log.w(TAG, "DroidCam TCP client failed", error)
            }
        }
    }

    private fun streamVideo(output: BufferedOutputStream) {
        var sourceFrame: FrameHub.Frame? = null
        val waitDeadline = System.currentTimeMillis() + 10_000L
        while (running.get() && sourceFrame == null && System.currentTimeMillis() < waitDeadline) {
            sourceFrame = FrameHub.latestFrame() ?: FrameHub.awaitFrame(0L, 250L)
        }
        val initial = sourceFrame ?: return
        val header = ByteArray(9)
        header[0] = (initial.width ushr 8).toByte()
        header[1] = initial.width.toByte()
        header[2] = (initial.height ushr 8).toByte()
        header[3] = initial.height.toByte()
        output.write(header)
        output.flush()

        videoClients.incrementAndGet()
        FrameHub.viewerConnected()
        publishState()
        try {
            var nextFrameAtNs = System.nanoTime()
            while (running.get()) {
                val settings = LanStreamSettings.state.value
                val targetTimestamp = System.currentTimeMillis() - settings.delayMs
                val frame = FrameHub.playbackFrame(targetTimestamp, 50L)
                if (frame != null) {
                    writeLittleEndianInt(output, frame.jpeg.size)
                    output.write(frame.jpeg)
                    output.flush()
                }
                nextFrameAtNs += 1_000_000_000L / settings.outputFps.coerceAtLeast(1)
                sleepUntil(nextFrameAtNs)
            }
        } finally {
            videoClients.updateAndGet { (it - 1).coerceAtLeast(0) }
            FrameHub.viewerDisconnected()
            publishState()
        }
    }

    private fun streamAudioTcp(output: BufferedOutputStream) {
        output.write(byteArrayOf('-'.code.toByte(), '@'.code.toByte(), 'v'.code.toByte(), '0'.code.toByte(), '2'.code.toByte(), DroidCamSpeexEncoder.CHUNKS_PER_PACKET.toByte()))
        output.flush()
        tcpAudioClients.incrementAndGet()
        publishState()
        try {
            var lastSequence = 0L
            var nextPacketAtNs = System.nanoTime()
            while (running.get()) {
                val targetTimestamp = System.currentTimeMillis() - LanStreamSettings.delayMs()
                val packet = AudioFrameHub.packetFor(targetTimestamp, lastSequence)
                val bytes = packet?.bytes ?: DroidCamSpeexEncoder.silencePacket
                if (packet != null) lastSequence = packet.lastSequence
                output.write(bytes)
                output.flush()
                nextPacketAtNs += 40_000_000L
                sleepUntil(nextPacketAtNs)
            }
        } finally {
            tcpAudioClients.updateAndGet { (it - 1).coerceAtLeast(0) }
            publishState()
        }
    }

    private fun udpReceiveLoop() {
        val socket = udpSocket ?: return
        val buffer = ByteArray(256)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val request = String(packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII)
                val address = packet.socketAddress
                when {
                    request.startsWith(AUDIO_REQUEST) -> {
                        udpAudioClients.compute(address) { _, current ->
                            (current ?: UdpAudioClient(System.currentTimeMillis())).apply {
                                lastSeenMs = System.currentTimeMillis()
                            }
                        }
                        publishState()
                    }
                    request.startsWith(STOP_REQUEST) -> {
                        udpAudioClients.remove(address)
                        publishState()
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
                removeExpiredUdpClients()
            } catch (error: SocketException) {
                if (running.get()) Log.w(TAG, "DroidCam UDP audio socket stopped", error)
            } catch (error: IOException) {
                if (running.get()) Log.w(TAG, "DroidCam UDP receive failed", error)
            }
        }
    }

    private fun udpSendLoop() {
        val socket = udpSocket ?: return
        var nextPacketAtNs = System.nanoTime()
        while (running.get()) {
            val targetTimestamp = System.currentTimeMillis() - LanStreamSettings.delayMs()
            udpAudioClients.forEach { (address, client) ->
                val packet = AudioFrameHub.packetFor(targetTimestamp, client.lastSequence)
                val bytes = packet?.bytes ?: DroidCamSpeexEncoder.silencePacket
                if (packet != null) client.lastSequence = packet.lastSequence
                runCatching {
                    socket.send(DatagramPacket(bytes, bytes.size, address))
                }.onFailure { error ->
                    if (running.get()) Log.d(TAG, "Unable to send DroidCam UDP audio to $address", error)
                }
            }
            removeExpiredUdpClients()
            nextPacketAtNs += 40_000_000L
            sleepUntil(nextPacketAtNs)
        }
    }

    private fun removeExpiredUdpClients() {
        val cutoff = System.currentTimeMillis() - UDP_CLIENT_TIMEOUT_MS
        val removed = udpAudioClients.entries.removeIf { it.value.lastSeenMs < cutoff }
        if (removed) publishState()
    }

    private fun sendBattery(output: BufferedOutputStream) {
        val body = "100"
        output.write(
            ("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body")
                .toByteArray(StandardCharsets.US_ASCII),
        )
        output.flush()
    }

    private fun readRequest(input: BufferedInputStream): String {
        val bytes = ByteArray(128)
        val length = input.read(bytes)
        if (length <= 0) return ""
        return String(bytes, 0, length, StandardCharsets.US_ASCII).trim('\u0000', '\r', '\n', ' ')
    }

    private fun writeLittleEndianInt(output: BufferedOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write(value ushr 8 and 0xFF)
        output.write(value ushr 16 and 0xFF)
        output.write(value ushr 24 and 0xFF)
    }

    private fun sleepUntil(targetNs: Long) {
        val remaining = targetNs - System.nanoTime()
        if (remaining <= 0L) return
        try {
            val millis = remaining / 1_000_000L
            val nanos = (remaining % 1_000_000L).toInt()
            Thread.sleep(millis, nanos)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun publishState() {
        val resolved = endpoint
        DroidCamState.replace(
            DroidCamState.State(
                running = running.get() && serverSocket?.isClosed == false,
                address = address(),
                interfaceName = resolved?.interfaceName.orEmpty(),
                videoClients = videoClients.get(),
                audioClients = tcpAudioClients.get() + udpAudioClients.size,
                audioSource = AudioFrameHub.stats.value.source,
                error = lastError,
            ),
        )
    }

    companion object {
        const val DEFAULT_PORT = 4747
        private const val VIDEO_REQUEST_PREFIX = "CMD /v3/video/"
        private const val AUDIO_REQUEST = "CMD /v2/audio"
        private const val STOP_REQUEST = "CMD /v1/stop"
        private const val PING_REQUEST = "CMD /ping"
        private const val BATTERY_REQUEST_PREFIX = "GET /battery"
        private const val UDP_CLIENT_TIMEOUT_MS = 10_000L
        private const val TAG = "DroidCamCompat"
    }
}
