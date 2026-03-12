package com.meshbrain.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.meshbrain.core.NanoPacket
import com.meshbrain.core.NodeIdentity
import com.meshbrain.core.PacketType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "MeshNetwork"
private const val SERVICE_TYPE = "_meshbrain._tcp."
private const val SERVICE_NAME = "MeshBrainNode"

// ── Events ────────────────────────────────────────────────────────────

sealed class MeshEvent {
    data class PeerConnected(val nodeId: String, val address: String) : MeshEvent()
    data class PeerDisconnected(val nodeId: String) : MeshEvent()
    data class KnowledgeReceived(val packet: NanoPacket) : MeshEvent()
    data class PacketRejected(val reason: String) : MeshEvent()
    object NetworkStarted : MeshEvent()
    data class Error(val message: String) : MeshEvent()
}

// ── Peer record ───────────────────────────────────────────────────────

data class Peer(
    val nodeId: String,
    val address: String,
    val ws: WebSocket,
    var reputation: Float = 0.5f,
    var packetsReceived: Int = 0,
    var packetsAccepted: Int = 0
)

// ── MeshNetwork ───────────────────────────────────────────────────────

/**
 * Manages the P2P mesh network on Android.
 *
 * Features:
 * - WebSocket connections to peers (OkHttp)
 * - NSD (Network Service Discovery) for automatic LAN peer finding
 * - Packet verification + trust scoring
 * - Knowledge gossip protocol
 */
class MeshNetwork(
    private val context: Context,
    private val identity: NodeIdentity,
    private val scope: CoroutineScope,
    private val wsPort: Int = 8765
) {

    // Connected peers
    private val peers = ConcurrentHashMap<String, Peer>()

    // Event flow — UI observes this
    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    // Stats
    private val _peerCount = MutableStateFlow(0)
    val peerCount = _peerCount.asStateFlow()

    private val _knowledgeCount = MutableStateFlow(0)
    val knowledgeCount = _knowledgeCount.asStateFlow()

    var packetsSent = 0
    var packetsReceived = 0
    var bytesSent = 0L
    var bytesReceived = 0L

    // Seen packet hashes — deduplication
    private val seenPackets = mutableSetOf<String>()

    // OkHttp client for WebSocket connections
    private val okClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // no timeout for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // ── Start ──────────────────────────────────────────────────────

    fun start() {
        Log.i(TAG, "Starting MeshNetwork, node=${identity.nodeIdShort}")
        registerNsdService()
        discoverNsdPeers()
        scope.launch { _events.emit(MeshEvent.NetworkStarted) }
    }

    // ── Connect to specific peer ───────────────────────────────────

    fun connectToPeer(wsUrl: String) {
        Log.i(TAG, "Connecting to $wsUrl")
        val request = Request.Builder().url(wsUrl).build()
        okClient.newWebSocket(request, createWebSocketListener(wsUrl))
    }

    // ── Broadcast knowledge to all peers ──────────────────────────

    fun broadcast(packet: NanoPacket): Int {
        val data = packet.toBytes()
        var sent = 0
        peers.values.forEach { peer ->
            try {
                peer.ws.send(okio.ByteString.of(*data))
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send to ${peer.nodeId.take(8)}: ${e.message}")
            }
        }
        if (sent > 0) {
            packetsSent++
            bytesSent += data.size
            Log.i(TAG, "Broadcast ${data.size}B to $sent peers")
        }
        return sent
    }

    // ── WebSocket listener ─────────────────────────────────────────

    private fun createWebSocketListener(peerUrl: String) = object : WebSocketListener() {

        var peerNodeId: String? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket opened to $peerUrl")
            // Send our handshake immediately
            val hs = NanoPacket.createHandshake(identity)
            webSocket.send(okio.ByteString.of(*hs.toBytes()))
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            scope.launch {
                try {
                    val data = bytes.toByteArray()
                    bytesReceived += data.size
                    packetsReceived++

                    val packet = NanoPacket.fromBytes(data)

                    when (packet.packetType) {
                        PacketType.HANDSHAKE -> {
                            peerNodeId = packet.nodeId.toHex()
                            val peer = Peer(
                                nodeId  = peerNodeId!!,
                                address = peerUrl,
                                ws      = webSocket
                            )
                            peers[peerNodeId!!] = peer
                            _peerCount.value = peers.size

                            Log.i(TAG, "Peer handshake: ${peerNodeId!!.take(12)}...")
                            _events.emit(MeshEvent.PeerConnected(peerNodeId!!, peerUrl))

                            // Respond with our handshake if they initiated
                            val hs = NanoPacket.createHandshake(identity)
                            webSocket.send(okio.ByteString.of(*hs.toBytes()))
                        }

                        PacketType.KNOWLEDGE -> handleKnowledgePacket(packet, webSocket)

                        PacketType.HEARTBEAT -> {
                            peerNodeId?.let { id ->
                                peers[id]?.reputation = minOf(1f, (peers[id]?.reputation ?: 0.5f) + 0.005f)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing packet: ${e.message}")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Handle text frames (shouldn't happen in our protocol, but just in case)
            Log.d(TAG, "Text message received (unexpected): ${text.take(50)}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            peerNodeId?.let { removeAndNotify(it) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            peerNodeId?.let { removeAndNotify(it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure to $peerUrl: ${t.message}")
            peerNodeId?.let { removeAndNotify(it) }
            scope.launch { _events.emit(MeshEvent.Error("Connection failed: ${t.message}")) }
        }

        private fun removeAndNotify(nodeId: String) {
            peers.remove(nodeId)
            _peerCount.value = peers.size
            scope.launch { _events.emit(MeshEvent.PeerDisconnected(nodeId)) }
        }
    }

    // ── Knowledge packet handling ──────────────────────────────────

    private suspend fun handleKnowledgePacket(packet: NanoPacket, senderWs: WebSocket) {
        // Deduplication
        val packetHash = packet.nodeId.toHex() + packet.timestampMs.toString()
        if (packetHash in seenPackets) return
        seenPackets.add(packetHash)
        if (seenPackets.size > 5000) {
            val toRemove = seenPackets.take(2500).toSet()
            seenPackets.removeAll(toRemove)
        }

        // Don't process our own packets
        if (packet.nodeId.contentEquals(identity.nodeIdBytes)) return

        // Hop count limit
        if (packet.hopCount > 5) return

        // Expiry check
        if (packet.isExpired()) return

        // Cryptographic signature verification
        val sigValid = identity.verify(
            packet.payloadForSigning(),
            packet.signature,
            packet.nodeId
        )
        if (!sigValid) {
            Log.w(TAG, "Invalid signature from ${packet.nodeId.toHex().take(8)}")
            _events.emit(MeshEvent.PacketRejected("invalid-signature"))
            // Punish reputation
            peers[packet.nodeId.toHex()]?.reputation =
                maxOf(0f, (peers[packet.nodeId.toHex()]?.reputation ?: 0.5f) - 0.08f)
            return
        }

        // Quality threshold
        if (packet.qualityScore < 0.55f) {
            _events.emit(MeshEvent.PacketRejected("low-quality:${packet.qualityScore}"))
            return
        }

        // ✅ Verified — integrate and notify
        _knowledgeCount.value++
        peers[packet.nodeId.toHex()]?.let {
            it.packetsAccepted++
            it.reputation = minOf(1f, it.reputation + 0.02f)
        }

        Log.i(TAG, "Knowledge integrated from ${packet.nodeId.toHex().take(8)}... " +
                   "quality=${packet.qualityScore} size=${packet.sizeBytes}B")
        _events.emit(MeshEvent.KnowledgeReceived(packet))

        // Gossip relay — forward to other peers
        if (packet.hopCount < 3) {
            val relayPacket = packet.copy(hopCount = packet.hopCount + 1)
            val data = relayPacket.toBytes()
            peers.values
                .filter { it.ws != senderWs }
                .forEach { peer ->
                    try { peer.ws.send(okio.ByteString.of(*data)) }
                    catch (e: Exception) { /* peer disconnected */ }
                }
        }
    }

    // ── NSD — Automatic LAN Discovery ─────────────────────────────

    private fun registerNsdService() {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "${SERVICE_NAME}_${identity.nodeIdHex.take(8)}"
                serviceType = SERVICE_TYPE
                port = wsPort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "NSD registered: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "NSD registration failed: $code")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }

            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD registration error: ${e.message}")
        }
    }

    private fun discoverNsdPeers() {
        try {
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.i(TAG, "NSD discovery started")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains("meshbrain") &&
                        !serviceInfo.serviceName.contains(identity.nodeIdHex.take(8))) {
                        Log.i(TAG, "Found peer: ${serviceInfo.serviceName}")
                        nsdManager?.resolveService(serviceInfo, createResolveListener())
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Peer lost: ${serviceInfo.serviceName}")
                }

                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "Discovery failed: $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD discovery error: ${e.message}")
        }
    }

    private fun createResolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "NSD resolve failed: $errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            val wsUrl = "ws://$host:$port/mesh"
            Log.i(TAG, "Resolved peer at $wsUrl")

            // Connect in background
            scope.launch(Dispatchers.IO) {
                connectToPeer(wsUrl)
            }
        }
    }

    // ── Heartbeat ──────────────────────────────────────────────────

    fun sendHeartbeat() {
        if (peers.isEmpty()) return
        val rep = minOf(1f, knowledgeCount.value / 100f)
        val hb = NanoPacket.createHeartbeat(identity, rep)
        broadcast(hb)
    }

    // ── Status ─────────────────────────────────────────────────────

    fun getPeerList(): List<Peer> = peers.values.toList()

    fun isConnected(): Boolean = peers.isNotEmpty()

    // ── Cleanup ────────────────────────────────────────────────────

    fun stop() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
            peers.values.forEach { it.ws.close(1000, "Shutdown") }
            peers.clear()
            okClient.dispatcher.executorService.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Shutdown error: ${e.message}")
        }
    }
}

// Extension for Peer
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
