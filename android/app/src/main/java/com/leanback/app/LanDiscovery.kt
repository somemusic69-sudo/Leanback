package com.leanback.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class DiscoveredPc(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
)

object LanDiscovery {
    private const val DISCOVERY_PORT = 7891
    private const val MAGIC = "LEANBACK_DISCOVER_V1"

    suspend fun scan(onUpdate: (List<DiscoveredPc>) -> Unit): List<DiscoveredPc> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, DiscoveredPc>()
            val socket = DatagramSocket()
            try {
                socket.broadcast = true
                socket.soTimeout = 250

                val targets = collectBroadcastTargets()
                val reqBytes = MAGIC.toByteArray(Charsets.UTF_8)

                fun sendProbes() {
                    for (addr in targets) {
                        try {
                            socket.send(DatagramPacket(reqBytes, reqBytes.size, addr, DISCOVERY_PORT))
                        } catch (_: Exception) {
                            // Ignore individual unreachable interface broadcast errors.
                        }
                    }
                }

                sendProbes()
                var secondProbeSent = false
                val startedAt = System.currentTimeMillis()
                val buf = ByteArray(2048)

                while (System.currentTimeMillis() - startedAt < 1300L) {
                    if (!secondProbeSent && System.currentTimeMillis() - startedAt > 300L) {
                        secondProbeSent = true
                        sendProbes()
                    }
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(text)
                        if (json.optString("service") != "leanback") continue

                        val senderIp = packet.address?.hostAddress
                        val advertisedIp = json.optString("ip", "").takeIf { it.isNotEmpty() }
                        val host = senderIp ?: advertisedIp ?: continue
                        val port = json.optInt("port", Prefs.DEFAULT_PORT)
                        val name = json.optString("name", host)
                        val id = json.optString("id", "$host:$port")

                        if (!found.containsKey(id)) {
                            found[id] = DiscoveredPc(id = id, name = name, host = host, port = port)
                            val snapshot = found.values.toList()
                            withContext(Dispatchers.Main) {
                                onUpdate(snapshot)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Continue waiting until scan window completes.
                    } catch (_: Exception) {
                        // Ignore malformed UDP packets on the network.
                    }
                }
            } finally {
                socket.close()
            }
            found.values.toList()
        }

    private fun collectBroadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ifaceAddr in iface.interfaceAddresses) {
                    val bcast = ifaceAddr.broadcast
                    if (bcast is Inet4Address) {
                        targets.add(bcast)
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to global limited broadcast below.
        }
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return targets.toList()
    }
}
