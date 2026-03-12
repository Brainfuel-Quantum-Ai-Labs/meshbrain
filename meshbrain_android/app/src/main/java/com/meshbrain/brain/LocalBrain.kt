package com.meshbrain.brain

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val TAG = "LocalBrain"

// ── Ollama API data classes ───────────────────────────────────────────

private data class OllamaMessage(
    @SerializedName("role")    val role: String,
    @SerializedName("content") val content: String
)

private data class OllamaChatRequest(
    @SerializedName("model")    val model: String,
    @SerializedName("messages") val messages: List<OllamaMessage>,
    @SerializedName("stream")   val stream: Boolean = true,
    @SerializedName("options")  val options: Map<String, Any> = mapOf(
        "temperature" to 0.7,
        "top_p" to 0.9,
        "num_predict" to 512
    )
)

private data class OllamaTagsResponse(
    @SerializedName("models") val models: List<OllamaModel>
)

private data class OllamaModel(
    @SerializedName("name") val name: String
)

// ── LocalBrain ────────────────────────────────────────────────────────

/**
 * Connects to the local Ollama server (running on this device or LAN).
 * All inference runs locally — no internet, no API key.
 *
 * Supports:
 * - Gemma 2 2B (recommended)
 * - Llama 3.2 1B/3B
 * - Mistral 7B
 * - Any Ollama-compatible model
 */
class LocalBrain(
    private var baseUrl: String = "http://localhost:11434",
    private var modelName: String = "gemma2:2b"
) {

    var isAvailable: Boolean = false
        private set

    var activeModel: String = modelName
        private set

    // In-memory only — cleared at session end, never persisted
    private val conversationHistory = mutableListOf<OllamaMessage>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ── Startup ───────────────────────────────────────────────────

    /**
     * Check if Ollama is running and find best available model.
     * Call this on app start.
     *
     * Returns true if ready.
     */
    suspend fun checkAvailability(): Boolean = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            baseUrl,                    // localhost (if Termux/UserLand running Ollama)
            "http://10.0.2.2:11434",   // Android emulator → host machine
            "http://192.168.1.1:11434" // common router IP — scan nearby
        )

        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url("$url/api/tags")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val tags = gson.fromJson(body, OllamaTagsResponse::class.java)
                    val models = tags.models.map { it.name }
                    Log.i(TAG, "Ollama found at $url. Models: $models")
                    baseUrl = url
                    isAvailable = true

                    // Pick best available model
                    val preferred = listOf(
                        "gemma2:2b", "gemma2", "llama3.2:3b", "llama3.2:1b",
                        "llama3.2", "mistral:7b", "mistral", "phi3:mini",
                        "gemma:2b", "tinyllama"
                    )
                    for (pref in preferred) {
                        if (models.any { it.startsWith(pref) }) {
                            activeModel = models.first { it.startsWith(pref) }
                            Log.i(TAG, "Using model: $activeModel")
                            break
                        }
                    }
                    if (activeModel == modelName && models.isNotEmpty()) {
                        activeModel = models.first()
                    }
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Not found at $url: ${e.message}")
            }
        }

        Log.w(TAG, "Ollama not found. Using fallback responses.")
        isAvailable = false
        false
    }

    // ── Core inference ────────────────────────────────────────────

    /**
     * Stream tokens from local LLM.
     * Emits tokens one by one as they're generated.
     * Conversation history kept in memory only.
     */
    fun thinkStream(userInput: String): Flow<String> = flow {
        conversationHistory.add(OllamaMessage("user", userInput))

        if (!isAvailable) {
            // Fallback mock for when Ollama not running
            val response = mockResponse(userInput)
            conversationHistory.add(OllamaMessage("assistant", response))
            for (word in response.split(" ")) {
                emit("$word ")
                kotlinx.coroutines.delay(40)
            }
            return@flow
        }

        val fullResponse = StringBuilder()

        try {
            val requestBody = gson.toJson(
                OllamaChatRequest(
                    model = activeModel,
                    messages = conversationHistory.toList(),
                    stream = true
                )
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(requestBody)
                .build()

            withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    emit("\n[Error: ${response.code}]")
                    return@withContext
                }
                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.isBlank()) continue
                    try {
                        val chunk = gson.fromJson(l, Map::class.java)
                        val msg = (chunk["message"] as? Map<*, *>)
                        val token = msg?.get("content") as? String ?: continue
                        if (token.isNotEmpty()) {
                            fullResponse.append(token)
                            emit(token)
                        }
                        if (chunk["done"] == true) break
                    } catch (e: Exception) {
                        // Skip malformed JSON lines
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            emit("\n[Brain error: ${e.message}]")
        }

        // Store response in history (in-memory, session only)
        if (fullResponse.isNotEmpty()) {
            conversationHistory.add(OllamaMessage("assistant", fullResponse.toString()))
        }
    }

    /**
     * Quick single-turn inference — no history.
     * Used for quality scoring, routing decisions.
     */
    suspend fun quickThink(prompt: String, maxTokens: Int = 20): String =
        withContext(Dispatchers.IO) {
            if (!isAvailable) return@withContext "0.7"
            try {
                val requestBody = gson.toJson(
                    OllamaChatRequest(
                        model = activeModel,
                        messages = listOf(OllamaMessage("user", prompt)),
                        stream = false,
                        options = mapOf("num_predict" to maxTokens, "temperature" to 0.1)
                    )
                ).toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl/api/chat")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext "0.7"
                val result = gson.fromJson(body, Map::class.java)
                val msg = result["message"] as? Map<*, *>
                msg?.get("content") as? String ?: "0.7"
            } catch (e: Exception) {
                "0.7"
            }
        }

    // ── Knowledge quality estimation ──────────────────────────────

    /**
     * Estimate response quality (0.0–1.0).
     * Used to decide whether to share with mesh.
     */
    suspend fun estimateQuality(query: String, response: String): Float {
        if (!isAvailable) {
            // Heuristic fallback
            val words = response.split(" ").size
            return when {
                words < 5  -> 0.3f
                words < 20 -> 0.6f
                else       -> 0.8f
            }
        }

        val prompt = "Rate the quality of this answer from 0.0 to 1.0. " +
                     "Return ONLY a decimal number like 0.8.\n" +
                     "Q: ${query.take(100)}\nA: ${response.take(200)}"

        val result = quickThink(prompt)
        return try {
            val num = Regex("\\d+\\.?\\d*").find(result)?.value?.toFloat() ?: 0.7f
            if (num > 1f) num / 100f else num.coerceIn(0f, 1f)
        } catch (e: Exception) {
            0.7f
        }
    }

    // ── Session management ────────────────────────────────────────

    /** Clear conversation history — call at end of session for privacy */
    fun clearHistory() {
        conversationHistory.clear()
        Log.i(TAG, "Conversation history cleared")
    }

    fun historySize(): Int = conversationHistory.size

    fun contextLength(): Int = conversationHistory.sumOf { it.content.length }

    // ── Fallback mock ─────────────────────────────────────────────

    private fun mockResponse(input: String): String {
        val q = input.lowercase()
        return when {
            "hello" in q || "hi" in q ->
                "Hello! I'm MeshBrain running locally on your device. " +
                "Install Ollama and run 'ollama pull gemma2:2b' to activate the real AI model."

            "mesh" in q || "network" in q ->
                "The MeshBrain network connects local AI nodes on nearby phones. " +
                "Each device runs its own model and shares compressed knowledge vectors — " +
                "not raw conversations. Privacy is mathematically guaranteed."

            "blockchain" in q || "crypto" in q ->
                "Blockchain cryptography means Ed25519 signatures verify every knowledge packet. " +
                "No central server — math enforces trust between nodes."

            "gemma" in q || "model" in q ->
                "Gemma 2 2B is Google's open-source model. It runs at ~15 tokens/sec " +
                "on a mid-range Android phone. Download it with: ollama pull gemma2:2b"

            else ->
                "[Mock Brain — Ollama not connected] I received: '${input.take(60)}'. " +
                "To activate real AI: install Ollama on your PC/Mac and run 'ollama pull gemma2:2b', " +
                "then ensure it's accessible on your local network."
        }
    }
}
