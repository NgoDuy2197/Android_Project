package com.dsoft.proxyspy

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** This device's LAN IPv4 addresses (for the other phone to point its proxy at). */
    fun lanIps(): List<String> {
        val out = ArrayList<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                // prefer wlan / ap / rndis-style interfaces; skip obvious virtual ones
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("rndis") ||
                            name.startsWith("swlan") || ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            ip.startsWith("172.")) out.add(0, ip) else out.add(ip)
                    }
                }
            }
        } catch (_: Exception) {}
        return out.distinct()
    }

    fun primaryIp(): String = lanIps().firstOrNull() ?: "0.0.0.0"
}
