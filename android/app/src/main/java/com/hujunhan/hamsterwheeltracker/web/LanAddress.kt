package com.hujunhan.hamsterwheeltracker.web

import java.net.Inet4Address
import java.net.NetworkInterface

object LanAddress {
    fun dashboardUrl(port: Int): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!runCatching { network.isUp }.getOrDefault(false) || network.isLoopback) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address !is Inet4Address || address.isLoopbackAddress || !address.isSiteLocalAddress) continue
                val name = network.name.lowercase()
                val score = when {
                    name.startsWith("wlan") || name.contains("wifi") -> 3
                    name.startsWith("eth") -> 2
                    else -> 1
                }
                candidates += score to address.hostAddress.orEmpty()
            }
        }
        val host = candidates.maxByOrNull { it.first }?.second?.takeIf { it.isNotBlank() } ?: return null
        return "http://$host:$port/"
    }
}
