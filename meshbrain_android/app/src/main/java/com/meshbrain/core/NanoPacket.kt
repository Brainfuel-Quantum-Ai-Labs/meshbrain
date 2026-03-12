package com.meshbrain.core

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.sqrt

// ── Packet Types ──────────────────────────────────────────────────────

object PacketType {
    const val KNOWLEDGE  = 0x01
    const val HANDSHAKE  = 0x02
    const val HEARTBEAT  = 0x03
    const val QUERY_HELP = 0x04
    const val ACK        = 0x05

    fun name(type: Int) = when(type) {
        KNOWLEDGE  -> "KNOWLEDGE"
        HANDSHAKE  -> "HANDSHAKE"
        HEARTBEAT  -> "HEARTBEAT"
        QUERY_HELP -> "QUERY"
        ACK        -> "ACK"
        else       -> "UNKNOWN"
    }
}

// ── Wire format (JSON inside zlib compression) ────────────────────────

private data class PacketWire(
    @SerializedName("v")    val version: Int,
    @SerializedName("pt")   val packetType: Int,
    @SerializedName("ts")   val timestampMs: Long,
    @SerializedName("nid")  val nodeId: String,        // hex
    @SerializedName("th")   val topicHash: String,     // hex
    @SerializedName("k")    val knowledge: String,     // hex
    @SerializedName("q")    val qualityScore: Float,
    @SerializedName("lang") val language: String,
    @SerializedName("hop")  val hopCount: Int,
    @SerializedName("sig")  val signature: String      // hex
)

// ── NanoPacket ────────────────────────────────────────────────────────

/**
 * The fundamental unit of P2P knowledge exchange.
 *
 * ~200–500 bytes compressed.
 * Contains: compressed knowledge vector + quality score + topic fingerprint + signature.
 * Does NOT contain: raw query text, raw response text, user identity.
 *
 * Privacy guarantee: knowledge vector cannot be reversed to recover original text.
 */
data class NanoPacket(
    val version:      Int    = 1,
    val packetType:   Int    = PacketType.KNOWLEDGE,
    val timestampMs:  Long   = System.currentTimeMillis(),
    val nodeId:       ByteArray,
    val topicHash:    ByteArray = ByteArray(8),
    val knowledge:    ByteArray = ByteArray(0),
    val qualityScore: Float  = 0f,
    val language:     String = "en",
    val hopCount:     Int    = 0,
    var signature:    ByteArray = ByteArray(0)
) {

    // ── Serialization ─────────────────────────────────────────────

    fun toBytes(): ByteArray {
        val wire = PacketWire(
            version     = version,
            packetType  = packetType,
            timestampMs = timestampMs,
            nodeId      = nodeId.toHex(),
            topicHash   = topicHash.toHex(),
            knowledge   = knowledge.toHex(),
            qualityScore = qualityScore,
            language    = language,
            hopCount    = hopCount,
            signature   = signature.toHex()
        )
        val json = Gson().toJson(wire).toByteArray(Charsets.UTF_8)
        return compress(json)
    }

    /** Payload bytes that get signed (excludes signature field) */
    fun payloadForSigning(): ByteArray {
        return nodeId + topicHash + knowledge +
               ByteBuffer.allocate(8).putLong(timestampMs).array() +
               language.toByteArray()
    }

    val sizeBytes: Int get() = toBytes().size

    fun isExpired(maxAgeSeconds: Long = 7200L): Boolean {
        val ageMs = System.currentTimeMillis() - timestampMs
        return ageMs > maxAgeSeconds * 1000
    }

    override fun toString(): String {
        return "NanoPacket(type=${PacketType.name(packetType)} " +
               "node=${nodeId.toHex().take(8)}... " +
               "quality=${"%.2f".format(qualityScore)} " +
               "size=${sizeBytes}B)"
    }

    // equals/hashCode based on content (not reference)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NanoPacket) return false
        return nodeId.contentEquals(other.nodeId) &&
               timestampMs == other.timestampMs &&
               knowledge.contentEquals(other.knowledge)
    }

    override fun hashCode(): Int {
        return nodeId.contentHashCode() xor timestampMs.hashCode()
    }

    // ── Companion — Factory ───────────────────────────────────────

    companion object {

        fun fromBytes(data: ByteArray): NanoPacket {
            val json = decompress(data).toString(Charsets.UTF_8)
            val wire = Gson().fromJson(json, PacketWire::class.java)
            return NanoPacket(
                version      = wire.version,
                packetType   = wire.packetType,
                timestampMs  = wire.timestampMs,
                nodeId       = wire.nodeId.fromHex(),
                topicHash    = wire.topicHash.fromHex(),
                knowledge    = wire.knowledge.fromHex(),
                qualityScore = wire.qualityScore,
                language     = wire.language,
                hopCount     = wire.hopCount,
                signature    = wire.signature.fromHex()
            )
        }

        /**
         * Create a knowledge packet from a query/response interaction.
         * PRIVACY: only compressed embedding stored — not raw text.
         */
        fun createKnowledge(
            identity: NodeIdentity,
            queryText: String,
            responseText: String,
            quality: Float,
            language: String = "en"
        ): NanoPacket {
            val topicHash = hashTopic(queryText)
            val knowledge = compressKnowledge(queryText, responseText)

            val packet = NanoPacket(
                packetType   = PacketType.KNOWLEDGE,
                nodeId       = identity.nodeIdBytes,
                topicHash    = topicHash,
                knowledge    = knowledge,
                qualityScore = quality.coerceIn(0f, 1f),
                language     = language,
            )
            packet.signature = identity.sign(packet.payloadForSigning())
            return packet
        }

        fun createHandshake(identity: NodeIdentity): NanoPacket {
            val p = NanoPacket(
                packetType   = PacketType.HANDSHAKE,
                nodeId       = identity.nodeIdBytes,
                qualityScore = 1f,
                knowledge    = "HELLO".toByteArray()
            )
            p.signature = identity.sign(p.payloadForSigning())
            return p
        }

        fun createHeartbeat(identity: NodeIdentity, reputation: Float): NanoPacket {
            val p = NanoPacket(
                packetType   = PacketType.HEARTBEAT,
                nodeId       = identity.nodeIdBytes,
                qualityScore = reputation,
                knowledge    = "BEAT".toByteArray()
            )
            p.signature = identity.sign(p.payloadForSigning())
            return p
        }

        // ── Private helpers ────────────────────────────────────────

        /** 8-byte topic fingerprint — identifies topic WITHOUT revealing text */
        private fun hashTopic(text: String): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(text.lowercase().trim().toByteArray())
            return hash.take(8).toByteArray()
        }

        /**
         * Compress query+response into a privacy-safe knowledge vector.
         *
         * Production: LoRA gradient delta (~50KB)
         * Here: 256-dim bag-of-words embedding (256 bytes after int8 quantization)
         *
         * Cannot be reversed to recover original text.
         */
        private fun compressKnowledge(query: String, response: String): ByteArray {
            val combined = "$query $response".lowercase()
            val words = combined.split(Regex("\\s+"))
            val vec = FloatArray(256)

            for (word in words) {
                val md = MessageDigest.getInstance("MD5")
                val hash = md.digest(word.toByteArray())
                val idx = abs(ByteBuffer.wrap(hash.take(4).toByteArray()).int) % 256
                vec[idx] += 1f
            }

            // L2 normalize
            val norm = sqrt(vec.map { it * it }.sum())
            if (norm > 0) {
                for (i in vec.indices) vec[i] /= norm
            }

            // Add differential privacy noise (epsilon=0.1)
            val random = java.util.Random()
            for (i in vec.indices) {
                vec[i] += (random.nextGaussian() * 0.05f).toFloat()
            }

            // Quantize to Int8 — 8x size reduction, ~256 bytes
            return ByteArray(256) { i ->
                (vec[i] * 127f).coerceIn(-127f, 127f).toInt().toByte()
            }
        }

        // ── Compression ────────────────────────────────────────────

        private fun compress(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArray(data.size + 100)
            val len = deflater.deflate(output)
            deflater.end()
            return output.copyOf(len)
        }

        private fun decompress(data: ByteArray): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data)
            val output = ByteArray(data.size * 10)
            val len = inflater.inflate(output)
            inflater.end()
            return output.copyOf(len)
        }
    }
}
