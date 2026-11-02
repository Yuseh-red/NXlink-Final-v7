package com.samsungnx.camera.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

data class CameraWifiInfo(
    val network: Network?,
    val localIp: String,
    val gatewayIp: String,
    val ssid: String,
    val subnetClass: String
)

class CameraNetworkManager(context: Context) {

    companion object {
        val SAMSUNG_SUBNETS = listOf("101", "102", "103", "104", "107")
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var activeCameraNetwork: Network? = null
    var onWifiAvailableListener: ((CameraWifiInfo) -> Unit)? = null
    var onWifiLostListener: (() -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val info = extractWifiDetails(network)
                Log.d("CameraNetworkManager", "Wi-Fi available: local=${info.localIp}, gw=${info.gatewayIp}")
                if (isSamsungCameraNetwork(info)) {
                    connectivityManager.bindProcessToNetwork(network)
                    activeCameraNetwork = network
                }
                onWifiAvailableListener?.invoke(info)
            }
        }

        override fun onLost(network: Network) {
            if (network == activeCameraNetwork) {
                connectivityManager.bindProcessToNetwork(null)
                activeCameraNetwork = null
            }
            onWifiLostListener?.invoke()
        }
    }

    fun isSamsungCameraNetwork(info: CameraWifiInfo): Boolean {
        val ssid = info.ssid.uppercase()
        return info.subnetClass in SAMSUNG_SUBNETS ||
                ssid.contains("AP_SSC") ||
                ssid.contains("NX") ||
                ssid.contains("SAMSUNG")
    }

    fun forceBindToCurrentWifi(): CameraWifiInfo {
        val allNetworks = connectivityManager.allNetworks
        var fallbackWifi: CameraWifiInfo? = null

        for (net in allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val info = extractWifiDetails(net)
                if (isSamsungCameraNetwork(info)) {
                    connectivityManager.bindProcessToNetwork(net)
                    activeCameraNetwork = net
                    return info
                }
                if (fallbackWifi == null) {
                    fallbackWifi = info
                }
            }
        }

        if (fallbackWifi != null) {
            connectivityManager.bindProcessToNetwork(fallbackWifi.network)
            activeCameraNetwork = fallbackWifi.network
            return fallbackWifi
        }

        // Fallback via NetworkInterface enumeration if ConnectivityManager didn't return Wi-Fi
        val localIp = findLocalIpFromInterfaces() ?: "192.168.107.11"
        val parts = localIp.split(".")
        val gwIp = if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.1" else "192.168.107.1"
        val subnet = if (parts.size == 4) parts[2] else "107"
        return CameraWifiInfo(null, localIp, gwIp, getConnectedSsid(), subnet)
    }

    private fun extractWifiDetails(network: Network): CameraWifiInfo {
        var localIp = "0.0.0.0"
        var gatewayIp = "0.0.0.0"

        try {
            val linkProps = connectivityManager.getLinkProperties(network)
            if (linkProps != null) {
                for (linkAddr in linkProps.linkAddresses) {
                    val addr = linkAddr.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        localIp = addr.hostAddress ?: "0.0.0.0"
                        break
                    }
                }
                for (route in linkProps.routes) {
                    val gw = route.gateway
                    if (gw is Inet4Address && !gw.isLoopbackAddress) {
                        val gwHost = gw.hostAddress ?: ""
                        if (gwHost.isNotEmpty() && gwHost != "0.0.0.0") {
                            gatewayIp = gwHost
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (localIp == "0.0.0.0") {
            localIp = findLocalIpFromInterfaces() ?: "0.0.0.0"
        }

        var subnetClass = ""
        if (localIp != "0.0.0.0" && localIp.count { it == '.' } == 3) {
            val parts = localIp.split(".")
            subnetClass = parts[2]
            if (gatewayIp == "0.0.0.0" || !gatewayIp.startsWith("${parts[0]}.${parts[1]}.${parts[2]}.")) {
                gatewayIp = "${parts[0]}.${parts[1]}.${parts[2]}.1"
            }
        }

        if (gatewayIp == "0.0.0.0") {
            gatewayIp = "192.168.107.1"
        }

        return CameraWifiInfo(network, localIp, gatewayIp, getConnectedSsid(), subnetClass)
    }

    private fun findLocalIpFromInterfaces(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (nif.isLoopback || !nif.isUp) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.")) return host
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getConnectedSsid(): String {
        try {
            val wInfo = wifiManager.connectionInfo
            if (wInfo != null && wInfo.ssid != null) {
                val clean = wInfo.ssid.replace("\"", "").trim()
                if (clean != "<unknown ssid>") return clean
            }
        } catch (_: Exception) {}
        return ""
    }

    fun startListening() {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {}
    }

    fun stopListening() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            connectivityManager.bindProcessToNetwork(null)
        } catch (_: Exception) {}
    }
}
