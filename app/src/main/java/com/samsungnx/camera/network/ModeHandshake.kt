package com.samsungnx.camera.network

import android.util.Log
import com.samsungnx.camera.model.CameraConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

data class HandshakeResult(
    val isConnected: Boolean,
    val cameraIp: String,
    val detectedMode: String,
    val defaultUrl: String,
    val statusLog: String
)

class ModeHandshake {

    companion object {
        const val CAMERA_MODE_PORT = 7788
        const val PHONE_SERVER_PORT = 7789
        const val SELECTIVE_PUSH_PORT = 8100
        const val PHONE_SP_DATA_PORT = 18100
        const val TAG = "ModeHandshake"
    }

    private var serverSocket: ServerSocket? = null
    private var spServerSocket: ServerSocket? = null
    private var isRunning = false

    var onCameraModeEvent: ((String, String) -> Unit)? = null
    var onPushPhotoReceived: ((String, ByteArray) -> Unit)? = null

    suspend fun performHandshake(cameraIp: String, localIp: String): HandshakeResult = withContext(Dispatchers.IO) {
        val logBuilder = StringBuilder()
        val actualLocalIp = if (localIp != "0.0.0.0" && localIp.isNotEmpty()) localIp else "192.168.107.11"
        val macAddr = CameraConstants.SAMSUNG_MAC

        // 1. Start listener servers on 7789 (ModeServer) & 18100 (SelectivePush Data)
        startModeServer()
        startSelectivePushDataServer()

        // 2. Contact Port 7788 (ModeClient) on Camera
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(cameraIp, CAMERA_MODE_PORT), 3000)
                socket.soTimeout = 4500

                val request = "HEAD /mode/control HTTP/1.1\r\n" +
                        "User-Agent: SEC_MODE_$macAddr\r\n" +
                        "Connection: Close\r\n" +
                        "NTS : alive\r\n" +
                        "Content-Length: 0\r\n" +
                        "HOST-Mac : $macAddr\r\n" +
                        "HOST-Address : $actualLocalIp\r\n" +
                        "HOST-port : $PHONE_SERVER_PORT\r\n" +
                        "HOST-PNumber : none\r\n" +
                        "Access-Method : manual\r\n" +
                        "CALLBACK: <http://$actualLocalIp:$PHONE_SERVER_PORT/eventCallback>\r\n\r\n"

                val out = BufferedOutputStream(socket.getOutputStream())
                out.write(request.toByteArray(Charsets.UTF_8))
                out.flush()

                val ins = BufferedInputStream(socket.getInputStream())
                val buf = ByteArray(2048)
                val readLen = ins.read(buf)
                val response = if (readLen > 0) String(buf, 0, readLen, Charsets.UTF_8) else ""

                if (response.contains("200") || response.contains("100") || response.isNotEmpty()) {
                    var defaultUrl = ""
                    var mode = "mobilelink"
                    for (line in response.lines()) {
                        if (line.contains("Default-URL:", ignoreCase = true) || line.contains("default_url", ignoreCase = true)) {
                            defaultUrl = line.substringAfter(":").trim()
                            if (!defaultUrl.startsWith("http") && line.contains("http")) {
                                defaultUrl = "http" + line.substringAfter("http").trim()
                            }
                        }
                        val low = line.lowercase()
                        if (low.contains("rvf")) mode = "rvf"
                        else if (low.contains("autoshare")) mode = "autoshare"
                        else if (low.contains("selectivepush")) mode = "selectivepush"
                    }
                    logBuilder.append("Handshake 7788 OK (Mode: ${mode.uppercase()})")

                    if (mode == "selectivepush") {
                        trySelectivePushHandshake(cameraIp, actualLocalIp, macAddr, logBuilder)
                    }
                    return@withContext HandshakeResult(true, cameraIp, mode, defaultUrl, logBuilder.toString())
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Port 7788 note: ${e.message}")
        }

        // 3. Try SelectivePush on port 8100
        if (trySelectivePushHandshake(cameraIp, actualLocalIp, macAddr, logBuilder)) {
            return@withContext HandshakeResult(true, cameraIp, "selectivepush", "", logBuilder.toString())
        }

        // 4. Try UPnP port 7679 directly
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(cameraIp, 7679), 2500)
                logBuilder.append("Terhubung via UPnP Port 7679")
                return@withContext HandshakeResult(true, cameraIp, "upnp_direct", "", logBuilder.toString())
            }
        } catch (_: Exception) {}

        // 5. Try AutoShare S2L port 2000 or 1801
        for (p in listOf(2000, 1801)) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(cameraIp, p), 2000)
                    logBuilder.append("Terhubung ke AutoShare Port $p")
                    return@withContext HandshakeResult(true, cameraIp, "autoshare", "", logBuilder.toString())
                }
            } catch (_: Exception) {}
        }

        logBuilder.append("Kamera di $cameraIp belum merespon.")
        return@withContext HandshakeResult(false, cameraIp, "disconnected", "", logBuilder.toString())
    }

    private fun trySelectivePushHandshake(
        cameraIp: String,
        localIp: String,
        macAddr: String,
        logBuilder: StringBuilder
    ): Boolean {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(cameraIp, SELECTIVE_PUSH_PORT), 2000)
                socket.soTimeout = 3000

                val req = "HEAD /sp/control HTTP/1.1\r\n" +
                        "Host: $cameraIp:$SELECTIVE_PUSH_PORT\r\n" +
                        "User-Agent: SEC_SP_$macAddr\r\n" +
                        "Data-Server : $localIp:$PHONE_SP_DATA_PORT\r\n" +
                        "Data-Port : $PHONE_SP_DATA_PORT\r\n" +
                        "NTS : alive\r\n" +
                        "HOST-PNumber : none\r\n" +
                        "Access-Method : manual\r\n\r\n"

                val out = BufferedOutputStream(socket.getOutputStream())
                out.write(req.toByteArray(Charsets.UTF_8))
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val statusLine = reader.readLine() ?: ""
                if (statusLine.contains("200")) {
                    logBuilder.append("SelectivePush 8100 Aktif")
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun startModeServer() {
        if (serverSocket != null && !serverSocket!!.isClosed) return
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PHONE_SERVER_PORT))
            }
            isRunning = true
            Thread {
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        client.soTimeout = 4000
                        val ins = BufferedInputStream(client.getInputStream())
                        val buf = ByteArray(2048)
                        val len = ins.read(buf)
                        val reqStr = if (len > 0) String(buf, 0, len, Charsets.UTF_8) else ""

                        val resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                        val out = BufferedOutputStream(client.getOutputStream())
                        out.write(resp.toByteArray(Charsets.UTF_8))
                        out.flush()
                        client.close()

                        if (reqStr.isNotEmpty()) {
                            var mode = "mobilelink"
                            val low = reqStr.lowercase()
                            if (low.contains("<currentmode>rvf</currentmode>")) mode = "rvf"
                            else if (low.contains("<currentmode>autoshare</currentmode>")) mode = "autoshare"
                            else if (low.contains("<currentmode>selectivepush</currentmode>")) mode = "selectivepush"
                            onCameraModeEvent?.invoke(mode, reqStr)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }.start()
        } catch (_: Exception) {}
    }

    private fun startSelectivePushDataServer() {
        if (spServerSocket != null && !spServerSocket!!.isClosed) return
        try {
            spServerSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PHONE_SP_DATA_PORT))
            }
            Thread {
                while (isRunning) {
                    try {
                        val client = spServerSocket?.accept() ?: break
                        client.soTimeout = 15000
                        val ins = BufferedInputStream(client.getInputStream())
                        val out = BufferedOutputStream(client.getOutputStream())

                        val headerBuf = ByteArrayOutputStream()
                        var b: Int
                        var state = 0
                        while (ins.read().also { b = it } != -1) {
                            headerBuf.write(b)
                            if ((state == 0 || state == 2) && b == '\r'.code) state++
                            else if ((state == 1 || state == 3) && b == '\n'.code) state++
                            else state = 0
                            if (state == 4) break
                        }

                        val headerStr = String(headerBuf.toByteArray(), Charsets.UTF_8)
                        if (headerStr.contains("100-continue", ignoreCase = true) || headerStr.contains("Expect:", ignoreCase = true)) {
                            val cont = "HTTP/1.1 100 Continue\r\n\r\n"
                            out.write(cont.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }

                        var contentLength = 0
                        var fileName = "SAM_${System.currentTimeMillis()}.JPG"
                        for (line in headerStr.lines()) {
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                            if (line.contains("DCIM", ignoreCase = true) || line.contains(".JPG", ignoreCase = true) || line.contains(".MP4", ignoreCase = true)) {
                                val tokens = line.split(" ", "/", "\\")
                                val found = tokens.find { it.endsWith(".JPG", true) || it.endsWith(".MP4", true) }
                                if (found != null) fileName = found.trim()
                            }
                        }

                        if (contentLength > 0) {
                            val imgBytes = ByteArrayOutputStream(contentLength)
                            val buf = ByteArray(8192)
                            var totalRead = 0
                            while (totalRead < contentLength) {
                                val toRead = minOf(buf.size, contentLength - totalRead)
                                val r = ins.read(buf, 0, toRead)
                                if (r == -1) break
                                imgBytes.write(buf, 0, r)
                                totalRead += r
                            }

                            val okResp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nSub-ErrorCode: 0\r\n\r\n"
                            out.write(okResp.toByteArray(Charsets.UTF_8))
                            out.flush()

                            onPushPhotoReceived?.invoke(fileName, imgBytes.toByteArray())
                        }
                        client.close()
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }.start()
        } catch (_: Exception) {}
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close(); serverSocket = null } catch (_: Exception) {}
        try { spServerSocket?.close(); spServerSocket = null } catch (_: Exception) {}
    }
}
