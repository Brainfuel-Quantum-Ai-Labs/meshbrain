package com.meshbrain.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshbrain.brain.LocalBrain
import com.meshbrain.core.NanoPacket
import com.meshbrain.core.NodeIdentity
import com.meshbrain.mesh.MeshEvent
import com.meshbrain.mesh.MeshNetwork
import com.meshbrain.mesh.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "MainViewModel"

// ── Message model ─────────────────────────────────────────────────────

enum class MessageRole { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val sharedToMesh: Boolean = false,
    val qualityScore: Float = 0f
) {
    val timeStr: String
        get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

// ── UI State ──────────────────────────────────────────────────────────

data class MeshBrainUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val isInitializing: Boolean = true,
    val nodeIdShort: String = "...",
    val modelName: String = "Loading...",
    val isModelReady: Boolean = false,
    val peerCount: Int = 0,
    val knowledgeAbsorbed: Int = 0,
    val recentMeshEvents: List<String> = emptyList(),
    val peers: List<Peer> = emptyList(),
    val showMeshPanel: Boolean = false,
    val merkleRoot: String = "empty",
    val inputText: String = ""
)

// ── ViewModel ─────────────────────────────────────────────────────────

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(MeshBrainUiState())
    val uiState = _uiState.asStateFlow()

    // Core components
    private lateinit var identity: NodeIdentity
    private lateinit var brain: LocalBrain
    private lateinit var mesh: MeshNetwork

    // Knowledge Merkle tree (simple hash chain for prototype)
    private val knowledgeHashes = mutableListOf<String>()

    init {
        viewModelScope.launch { initialize() }
    }

    // ── Initialization ────────────────────────────────────────────

    private suspend fun initialize() {
        try {
            addSystemMessage("🔄 Initializing MeshBrain node...")

            // 1. Load/create cryptographic identity
            identity = NodeIdentity.loadOrCreate(getApplication())
            _uiState.update { it.copy(nodeIdShort = identity.nodeIdShort) }
            addSystemMessage("🔑 Node ID: ${identity.nodeIdShort}")

            // 2. Start mesh network
            mesh = MeshNetwork(
                context  = getApplication(),
                identity = identity,
                scope    = viewModelScope
            )
            mesh.start()
            observeMeshEvents()
            observeMeshStats()
            addSystemMessage("🌐 Mesh network started · LAN discovery active")

            // 3. Check local LLM
            brain = LocalBrain()
            val modelReady = brain.checkAvailability()
            _uiState.update {
                it.copy(
                    modelName   = if (modelReady) brain.activeModel else "Fallback (no Ollama)",
                    isModelReady = modelReady,
                    isInitializing = false
                )
            }

            if (modelReady) {
                addSystemMessage("🧠 Model ready: ${brain.activeModel}")
                addSystemMessage("✅ MeshBrain ONLINE — type anything to start")
            } else {
                addSystemMessage("⚠️  Ollama not found — using mock responses")
                addSystemMessage("💡 To activate: install Ollama, run 'ollama pull gemma2:2b'")
                addSystemMessage("✅ Mesh networking active — connect peers on your LAN")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}", e)
            addSystemMessage("❌ Error: ${e.message}")
            _uiState.update { it.copy(isInitializing = false) }
        }
    }

    // ── Chat ──────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        _uiState.update { it.copy(inputText = "") }

        viewModelScope.launch {
            val userMsg = ChatMessage(role = MessageRole.USER, content = text.trim())
            addMessage(userMsg)
            _uiState.update { it.copy(isThinking = true) }

            // Placeholder streaming message
            val assistantMsgId = UUID.randomUUID().toString()
            val streamingMsg = ChatMessage(
                id          = assistantMsgId,
                role        = MessageRole.ASSISTANT,
                content     = "",
                isStreaming = true
            )
            addMessage(streamingMsg)

            // Stream from local LLM
            val fullResponse = StringBuilder()
            try {
                brain.thinkStream(text).collect { token ->
                    fullResponse.append(token)
                    updateStreamingMessage(assistantMsgId, fullResponse.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming error: ${e.message}")
                fullResponse.append("\n[Error: ${e.message}]")
            }

            val finalResponse = fullResponse.toString()

            // Estimate quality + decide to share
            val quality = brain.estimateQuality(text, finalResponse)
            val shared = mesh.isConnected() && quality >= 0.55f

            if (shared) {
                shareToMesh(text, finalResponse, quality)
            }

            // Finalize message
            finalizeMessage(
                id       = assistantMsgId,
                content  = finalResponse,
                quality  = quality,
                shared   = shared
            )
            _uiState.update { it.copy(isThinking = false) }
        }
    }

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    // ── Mesh actions ──────────────────────────────────────────────

    fun connectToPeer(wsUrl: String) {
        if (wsUrl.isBlank()) return
        mesh.connectToPeer(wsUrl.trim())
        addMeshEvent("📡 Connecting to $wsUrl...")
    }

    fun toggleMeshPanel() {
        _uiState.update { it.copy(showMeshPanel = !it.showMeshPanel) }
    }

    fun clearHistory() {
        brain.clearHistory()
        _uiState.update {
            it.copy(
                messages = listOf(
                    ChatMessage(
                        role    = MessageRole.SYSTEM,
                        content = "🗑️ Conversation cleared — privacy ✅"
                    )
                )
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────

    private suspend fun shareToMesh(query: String, response: String, quality: Float) {
        withContext(Dispatchers.Default) {
            try {
                val packet = NanoPacket.createKnowledge(
                    identity     = identity,
                    queryText    = query,
                    responseText = response,
                    quality      = quality,
                    language     = "en"
                )
                val sentTo = mesh.broadcast(packet)
                if (sentTo > 0) {
                    addMeshEvent("📤 Knowledge shared to $sentTo peer(s) " +
                                 "(q=${"%.2f".format(quality)}, ${packet.sizeBytes}B, " +
                                 "raw text NOT sent ✅)")
                    // Update Merkle
                    knowledgeHashes.add(packet.nodeId.toHex() + packet.timestampMs)
                    val merkleRoot = computeMerkleRoot(knowledgeHashes)
                    _uiState.update { it.copy(merkleRoot = merkleRoot.take(16) + "...") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mesh share error: ${e.message}")
            }
        }
    }

    private fun observeMeshEvents() {
        viewModelScope.launch {
            mesh.events.collect { event ->
                when (event) {
                    is MeshEvent.PeerConnected -> {
                        addMeshEvent("🤝 Peer connected: ${event.nodeId.take(12)}...")
                        _uiState.update { it.copy(peers = mesh.getPeerList()) }
                    }
                    is MeshEvent.PeerDisconnected -> {
                        addMeshEvent("👋 Peer left: ${event.nodeId.take(12)}...")
                        _uiState.update { it.copy(peers = mesh.getPeerList()) }
                    }
                    is MeshEvent.KnowledgeReceived -> {
                        val p = event.packet
                        addMeshEvent("✨ Knowledge from ${p.nodeId.toHex().take(8)}... " +
                                     "q=${"%.2f".format(p.qualityScore)} ${p.sizeBytes}B")
                        knowledgeHashes.add(p.nodeId.toHex() + p.timestampMs)
                        val root = computeMerkleRoot(knowledgeHashes)
                        _uiState.update {
                            it.copy(
                                knowledgeAbsorbed = it.knowledgeAbsorbed + 1,
                                merkleRoot = root.take(16) + "..."
                            )
                        }
                    }
                    is MeshEvent.PacketRejected ->
                        addMeshEvent("🚫 Packet rejected: ${event.reason}")
                    is MeshEvent.NetworkStarted ->
                        addMeshEvent("🌐 Mesh network online")
                    is MeshEvent.Error ->
                        addMeshEvent("⚠️ ${event.message}")
                }
            }
        }
    }

    private fun observeMeshStats() {
        viewModelScope.launch {
            mesh.peerCount.collect { count ->
                _uiState.update { it.copy(peerCount = count) }
            }
        }
    }

    private fun addMessage(msg: ChatMessage) {
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    private fun updateStreamingMessage(id: String, content: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id == id) msg.copy(content = content) else msg
                }
            )
        }
    }

    private fun finalizeMessage(id: String, content: String, quality: Float, shared: Boolean) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id == id) msg.copy(
                        content     = content,
                        isStreaming = false,
                        qualityScore = quality,
                        sharedToMesh = shared
                    ) else msg
                }
            )
        }
    }

    private fun addSystemMessage(text: String) {
        addMessage(ChatMessage(role = MessageRole.SYSTEM, content = text))
    }

    private fun addMeshEvent(text: String) {
        val events = (_uiState.value.recentMeshEvents + text).takeLast(20)
        _uiState.update { it.copy(recentMeshEvents = events) }
    }

    private fun computeMerkleRoot(hashes: List<String>): String {
        if (hashes.isEmpty()) return "empty"
        var nodes = hashes.map { it.take(16) }
        while (nodes.size > 1) {
            if (nodes.size % 2 != 0) nodes = nodes + nodes.last()
            nodes = nodes.chunked(2).map { (a, b) ->
                (a + b).hashCode().toString(16)
            }
        }
        return nodes.first()
    }

    override fun onCleared() {
        super.onCleared()
        mesh.stop()
        brain.clearHistory()
    }
}
