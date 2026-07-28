package com.druvane.glasseshub

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object WifiLanResolver {
    data class Endpoint(
        val address: Inet4Address,
        val interfaceName: String,
        val description: String,
    )

    fun resolve(context: Context): Endpoint? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager != null) {
            val candidates = manager.allNetworks.mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
                val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
                val interfaceName = properties.interfaceName.orEmpty()
                if (isExcludedInterface(interfaceName)) return@mapNotNull null
                val address = properties.linkAddresses
                    .asSequence()
                    .map { it.address }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull(::isPrivateLanAddress)
                    ?: return@mapNotNull null
                Endpoint(address, interfaceName.ifBlank { "Wi-Fi" }, "Android Wi-Fi network")
            }
            candidates.firstOrNull()?.let { return it }
        }

        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !isExcludedInterface(it.name) }
                .sortedBy { interfacePriority(it.name) }
                .mapNotNull { networkInterface ->
                    val address = Collections.list(networkInterface.inetAddresses)
                        .asSequence()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull(::isPrivateLanAddress)
                        ?: return@mapNotNull null
                    Endpoint(address, networkInterface.name, "Wi-Fi or phone hotspot interface")
                }
                .firstOrNull()
        }.getOrNull()
    }

    private fun isPrivateLanAddress(address: Inet4Address): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || !address.isSiteLocalAddress) return false
        val bytes = address.address.map { it.toInt() and 0xFF }
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168)
    }

    private fun isExcludedInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("rmnet") ||
            lower.startsWith("ccmni") ||
            lower.startsWith("pdp") ||
            lower.startsWith("wwan") ||
            lower.startsWith("tun") ||
            lower.startsWith("tap") ||
            lower.startsWith("wg") ||
            lower.contains("p2p") ||
            lower.contains("aware") ||
            lower.contains("dummy")
    }

    private fun interfacePriority(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.startsWith("wlan") -> 0
            lower.startsWith("swlan") -> 1
            lower.startsWith("ap") || lower.contains("softap") -> 2
            lower.startsWith("wifi") -> 3
            else -> 20
        }
    }
}
