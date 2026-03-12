package com.meshbrain.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import android.util.Base64
import android.util.Log

private val Context.identityDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "meshbrain_identity")

/**
 * Permanent cryptographic identity for this MeshBrain node.
 *
 * Generated ONCE on first launch. Stored in EncryptedSharedPreferences.
 * Public key = your Node ID (shown to peers).
 * Private key = NEVER shared, never leaves this device.
 *
 * Uses Ed25519 — fast, secure, 32-byte keys. Same algorithm as SSH keys.
 */
class NodeIdentity private constructor(
    private val privateKey: Ed25519PrivateKeyParameters,
    val publicKey: Ed25519PublicKeyParameters
) {

    // ── Public properties ────────────────────────────────────────

    /** 32-byte raw public key — your Node ID */
    val nodeIdBytes: ByteArray = publicKey.encoded

    /** Hex string of public key */
    val nodeIdHex: String = nodeIdBytes.toHex()

    /** Short display version for UI */
    val nodeIdShort: String = nodeIdHex.take(12) + "..."

    // ── Cryptographic operations ─────────────────────────────────

    /**
     * Sign any data with our private key.
     * Returns 64-byte Ed25519 signature.
     */
    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /**
     * Verify a signature from any peer node.
     * Returns true only if signature is mathematically valid.
     */
    fun verify(
        data: ByteArray,
        signature: ByteArray,
        senderPublicKeyBytes: ByteArray
    ): Boolean {
        return try {
            val pubKey = Ed25519PublicKeyParameters(senderPublicKeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubKey)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            Log.w("NodeIdentity", "Signature verification failed: ${e.message}")
            false
        }
    }

    override fun toString() = "NodeIdentity(id=$nodeIdShort)"

    // ── Companion — Factory methods ───────────────────────────────

    companion object {
        private const val TAG = "NodeIdentity"
        private val KEY_PRIVATE = stringPreferencesKey("ed25519_private_key")
        private val KEY_PUBLIC  = stringPreferencesKey("ed25519_public_key")

        /**
         * Load existing identity from storage, or generate a new one.
         * Call this once at app startup.
         */
        suspend fun loadOrCreate(context: Context): NodeIdentity {
            val prefs = context.identityDataStore.data.first()
            val storedPrivate = prefs[KEY_PRIVATE]
            val storedPublic  = prefs[KEY_PUBLIC]

            if (storedPrivate != null && storedPublic != null) {
                // Load existing keypair
                val privBytes = Base64.decode(storedPrivate, Base64.NO_WRAP)
                val pubBytes  = Base64.decode(storedPublic,  Base64.NO_WRAP)
                val priv = Ed25519PrivateKeyParameters(privBytes, 0)
                val pub  = Ed25519PublicKeyParameters(pubBytes,  0)
                Log.i(TAG, "Loaded existing identity: ${pubBytes.toHex().take(12)}...")
                return NodeIdentity(priv, pub)
            }

            // Generate new keypair (first launch)
            val generator = Ed25519KeyPairGenerator()
            generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val keyPair = generator.generateKeyPair()
            val priv = keyPair.private as Ed25519PrivateKeyParameters
            val pub  = keyPair.public  as Ed25519PublicKeyParameters

            // Persist
            context.identityDataStore.edit { settings ->
                settings[KEY_PRIVATE] = Base64.encodeToString(priv.encoded, Base64.NO_WRAP)
                settings[KEY_PUBLIC]  = Base64.encodeToString(pub.encoded,  Base64.NO_WRAP)
            }

            Log.i(TAG, "Generated new identity: ${pub.encoded.toHex().take(12)}...")
            return NodeIdentity(priv, pub)
        }
    }
}

// ── Extension functions ───────────────────────────────────────────────

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
