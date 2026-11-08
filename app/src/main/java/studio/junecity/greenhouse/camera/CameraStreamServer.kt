package studio.junecity.greenhouse.camera

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import studio.junecity.greenhouse.cloud.CloudRuntime

object CameraStreamServer {
    const val DEFAULT_PORT = 45891
    private const val SERVICE_TYPE = "_greenhousecam._tcp."
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var socket: ServerSocket? = null
    private var pairing: CameraPairing? = null
    private var appContext: Context? = null
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    data class Status(val running: Boolean, val localAddress: String?, val publicAddress: String?, val message: String)
    @Volatile var status = Status(false, null, null, "Camera server stopped")
        private set

    fun localIpv4(): String = NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress ?: "127.0.0.1"

    @Synchronized fun start(context: Context, allowPublicMapping: Boolean = runCatching { CloudRuntime.configurationOrNull() == null }.getOrDefault(false)): CameraPairing {
        appContext = context.applicationContext
        val host = localIpv4()
        val stored = CameraPairingStore(context).getOrCreateServer(host)
        pairing = stored
        if (running.compareAndSet(false, true)) {
            socket = ServerSocket(DEFAULT_PORT).also { server ->
                status = Status(true, host, null, "Available on local Wi-Fi")
                registerNsd(context, stored)
                pool.execute {
                    while (running.get()) runCatching { server.accept() }.onSuccess { client ->
                        pool.execute { handle(client) }
                    }
                }
                if (allowPublicMapping) {
                    pool.execute {
                        val result = UpnpPortMapper.open(context.applicationContext, DEFAULT_PORT, "Greenhouse camera")
                        val current = pairing ?: return@execute
                        val updated = current.copy(publicHost = result.publicAddress)
                        pairing = updated
                        CameraPairingStore(context).save(updated)
                        status = Status(true, host, result.publicAddress, result.message)
                    }
                } else {
                    val updated = stored.copy(publicHost = null)
                    pairing = updated
                    CameraPairingStore(context).save(updated)
                    status = Status(true, host, null, "Available on local Wi-Fi · Secure remote access")
                }
            }
        }
        return pairing ?: stored
    }

    @Synchronized fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        registration?.let { listener -> runCatching { nsdManager?.unregisterService(listener) } }
        registration = null
        appContext?.let { context -> pool.execute { UpnpPortMapper.close(context, DEFAULT_PORT) } }
        status = Status(false, null, null, "Camera server stopped")
    }

    private fun handle(client: Socket) = client.use { connection ->
        connection.soTimeout = 4_000
        val reader = BufferedReader(InputStreamReader(connection.getInputStream()))
        val request = reader.readLine().orEmpty()
        while (true) { if (reader.readLine().isNullOrEmpty()) break }
        val path = request.split(' ').getOrNull(1).orEmpty()
        val token = path.substringAfter('?', "").split('&').firstOrNull { it.startsWith("t=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
        val current = pairing
        val frame = CameraFrameHub.current()
        when {
            !request.startsWith("GET /frame") -> respond(connection, 404, "text/plain", "Not found".toByteArray())
            current == null || token != current.token -> respond(connection, 401, "text/plain", "Unauthorized".toByteArray())
            frame == null -> respond(connection, 503, "text/plain", "Camera starting".toByteArray())
            else -> respond(connection, 200, "application/vnd.greenhouse.encrypted-jpeg", CameraCrypto.encrypt(frame, current))
        }
    }

    private fun respond(socket: Socket, code: Int, contentType: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Unavailable" }
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
    }

    private fun registerNsd(context: Context, pairing: CameraPairing) {
        val manager = context.getSystemService(NsdManager::class.java) ?: return
        nsdManager = manager
        val service = NsdServiceInfo().apply {
            serviceName = "Greenhouse-${pairing.id.take(6)}"
            serviceType = SERVICE_TYPE
            port = DEFAULT_PORT
            setAttribute("pair", pairing.id)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        }
        registration = listener
        manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
    }
}
