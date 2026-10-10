package com.shilapi.xcertplay

import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Serves the EZ-60 probe report read-only to a phone on the same Wi-Fi as the car (the car hotspot, Wi-Fi
 * Direct, or the phone's own hotspot), so the text can be copied in Safari. It listens only on private IPv4
 * addresses of Wi-Fi interfaces, answers only a random path, and stops when the probe viewer closes.
 */
internal class MazdaProbeShare(private val report: () -> String) : Closeable {
    private val token = ByteArray(9).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val sockets = CopyOnWriteArrayList<ServerSocket>()
    @Volatile private var closed = false

    /** Starts listening and returns the addresses to open; empty without a private Wi-Fi address. Not on main. */
    fun start(): List<String> {
        val urls = mutableListOf<String>()
        for (address in candidateAddresses()) {
            if (closed) break
            val socket = runCatching { ServerSocket(0, 4, address) }.getOrNull() ?: continue
            sockets += socket
            urls += "http://${address.hostAddress}:${socket.localPort}/$token"
            Thread({ serve(socket) }, "diplay-ez60-share").apply { isDaemon = true }.start()
        }
        return urls
    }

    private fun serve(server: ServerSocket) {
        while (!closed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { client.use(::respond) }
        }
    }

    private fun respond(client: Socket) {
        client.soTimeout = 5_000
        val requestLine = client.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty()
        val (status, type, body) = response(requestLine)
        val bytes = body.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    internal fun response(requestLine: String): Triple<String, String, String> {
        val parts = requestLine.split(' ')
        val path = parts.getOrNull(1)
        return when {
            parts.firstOrNull() != "GET" -> Triple("405 Method Not Allowed", "text/plain", "")
            path == "/$token" -> Triple("200 OK", "text/html; charset=utf-8", page(report()))
            path == "/$token.txt" -> Triple("200 OK", "text/plain; charset=utf-8", report())
            else -> Triple("404 Not Found", "text/plain", "")
        }
    }

    internal val path: String get() = "/$token"

    override fun close() {
        closed = true
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    companion object {
        // Wi-Fi only: the EZ-60 also has private addresses on its in-vehicle Ethernet (eth0, wt_eth2_*).
        private val wifiInterface = Regex("^(wlan|swlan|ap|softap|p2p)")

        internal fun isWifiInterface(name: String): Boolean = wifiInterface.containsMatchIn(name)

        private fun candidateAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) && isWifiInterface(it.name) }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrDefault(emptyList())

        internal fun page(text: String): String {
            val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>DiPlay EZ-60 probe</title></head>" +
                "<body style=\"font-family:-apple-system,sans-serif;margin:12px\">" +
                "<p>Tap the text, choose Select All, then Copy.</p>" +
                "<textarea readonly style=\"width:100%;height:80vh;font:12px ui-monospace,monospace\">$escaped</textarea>" +
                "</body></html>"
        }
    }
}
