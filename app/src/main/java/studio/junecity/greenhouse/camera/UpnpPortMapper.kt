package studio.junecity.greenhouse.camera

import android.content.Context
import android.net.wifi.WifiManager
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

object UpnpPortMapper {
    data class Result(val publicAddress: String?, val mapped: Boolean, val message: String)
    private data class Gateway(val controlUrl: String, val serviceType: String)

    fun open(context: Context, port: Int, description: String): Result = runCatching {
        val gateway = withMulticastLock(context) { discover() } ?: return Result(null, false, "Local streaming ready · router mapping unavailable")
        val public = soap(gateway, "GetExternalIPAddress", "")
            .substringAfter("<NewExternalIPAddress>", "").substringBefore("</NewExternalIPAddress>").ifBlank { null }
        val local = CameraStreamServer.localIpv4()
        val body = """
            <NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort>
            <NewProtocol>TCP</NewProtocol><NewInternalPort>$port</NewInternalPort>
            <NewInternalClient>$local</NewInternalClient><NewEnabled>1</NewEnabled>
            <NewPortMappingDescription>${xmlEscape(description)}</NewPortMappingDescription><NewLeaseDuration>0</NewLeaseDuration>
        """.trimIndent()
        soap(gateway, "AddPortMapping", body)
        Result(public, true, if (public == null) "Local streaming ready" else "Local and remote streaming ready")
    }.getOrElse { Result(null, false, "Local streaming ready · ${it.message ?: "UPnP unavailable"}") }

    fun close(context: Context, port: Int) {
        runCatching {
            val gateway = withMulticastLock(context) { discover() } ?: return@runCatching
            soap(gateway, "DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol>")
        }
    }

    private fun <T> withMulticastLock(context: Context, block: () -> T): T {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("greenhouse-upnp")?.apply { setReferenceCounted(false); acquire() }
        return try { block() } finally { runCatching { lock?.release() } }
    }

    private fun discover(): Gateway? {
        val query = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
            "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n").toByteArray()
        val locations = linkedSetOf<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 2_000
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName("239.255.255.250"), 1900))
            val end = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < end) {
                runCatching {
                    val data = ByteArray(8192)
                    val packet = DatagramPacket(data, data.size)
                    socket.receive(packet)
                    val response = String(data, 0, packet.length)
                    Regex("(?im)^location:\\s*(.+)$").find(response)?.groupValues?.get(1)?.trim()?.let(locations::add)
                }.onFailure { return@use }
            }
        }
        for (location in locations) {
            runCatching {
                val document = URL(location).openStream().use { DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it) }
                val services = document.getElementsByTagNameNS("*", "service")
                for (i in 0 until services.length) {
                    val element = services.item(i) as Element
                    val type = element.getElementsByTagNameNS("*", "serviceType").item(0)?.textContent.orEmpty()
                    if (type.contains("WANIPConnection") || type.contains("WANPPPConnection")) {
                        val path = element.getElementsByTagNameNS("*", "controlURL").item(0)?.textContent ?: continue
                        return Gateway(URL(URL(location), path).toString(), type)
                    }
                }
            }
        }
        return null
    }

    private fun soap(gateway: Gateway, action: String, body: String): String {
        val envelope = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${gateway.serviceType}">$body</u:$action></s:Body></s:Envelope>"""
        val connection = URL(gateway.controlUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPAction", "\"${gateway.serviceType}#$action\"")
        connection.outputStream.use { it.write(envelope.toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.use { input -> ByteArrayOutputStream().also { input.copyTo(it) }.toString("UTF-8") }.orEmpty()
        if (connection.responseCode !in 200..299) error("Router rejected $action (${connection.responseCode})")
        return response
    }

    private fun xmlEscape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
