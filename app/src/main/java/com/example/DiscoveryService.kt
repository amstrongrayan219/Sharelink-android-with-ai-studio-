package com.example

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

class DiscoveryService {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var socketListening: DatagramSocket? = null
    private var socketBroadcasting: DatagramSocket? = null

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val deviceAdapter = moshi.adapter(DiscoveredDevice::class.java)

    companion object {
        private const val TAG = "DiscoveryService"
        private const val DISCOVERY_PORT = 5000
    }

    fun startListening(onDeviceDiscovered: (DiscoveredDevice) -> Unit) {
        listenJob?.cancel()
        listenJob = scope.launch {
            while (isActive) {
                try {
                    val socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                    }
                    socketListening = socket
                    Log.d(TAG, "Démarrage de l'écoute UDP sur le port $DISCOVERY_PORT")

                    val buffer = ByteArray(2048)
                    while (isActive && !socket.isClosed) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                            val jsonStr = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                            Log.d(TAG, "Reçu paquet UDP de ${packet.address.hostAddress}: $jsonStr")
                            
                            val device = deviceAdapter.fromJson(jsonStr)
                            if (device != null) {
                                // Mettre à jour avec l'IP réelle du paquet au cas où elle diffère
                                val finalDevice = device.copy(
                                    ip = packet.address.hostAddress ?: device.ip,
                                    lastSeen = System.currentTimeMillis()
                                )
                                onDeviceDiscovered(finalDevice)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Erreur de réception de paquet UDP: ${e.message}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SocketException) {
                    Log.e(TAG, "Erreur d'ouverture du socket de réception, nouvelle tentative dans 5s...", e)
                    delay(5000)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur générale de listen: ${e.message}")
                    delay(5000)
                } finally {
                    socketListening?.close()
                }
            }
        }
    }

    fun startBroadcasting(deviceName: String, localIp: String, serverPort: Int) {
        broadcastJob?.cancel()
        broadcastJob = scope.launch {
            while (isActive) {
                try {
                    val socket = DatagramSocket().apply {
                        broadcast = true
                    }
                    socketBroadcasting = socket
                    val address = InetAddress.getByName("255.255.255.255")

                    while (isActive && !socket.isClosed) {
                        try {
                            val device = DiscoveredDevice(
                                name = deviceName,
                                ip = localIp,
                                port = serverPort
                            )
                            val jsonString = deviceAdapter.toJson(device)
                            val bytes = jsonString.toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(bytes, bytes.size, address, DISCOVERY_PORT)
                            
                            socket.send(packet)
                            Log.d(TAG, "Broadcast UDP envoyé: $jsonString")
                        } catch (e: Exception) {
                            Log.e(TAG, "Erreur d'envoi du broadcast UDP: ${e.message}")
                        }
                        delay(2000) // Envoyer toutes les 2 secondes
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur de socket broadcast, nouvelle tentative dans 5s...", e)
                    delay(5000)
                } finally {
                    socketBroadcasting?.close()
                }
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        broadcastJob?.cancel()
        listenJob = null
        broadcastJob = null
        try {
            socketListening?.close()
        } catch (e: Exception) {
            // Ignorer
        }
        try {
            socketBroadcasting?.close()
        } catch (e: Exception) {
            // Ignorer
        }
        socketListening = null
        socketBroadcasting = null
    }
}
