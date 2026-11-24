package com.example.bluetooth

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class MeshIdentity(
    val peerId: String,
    val publicKeyBase64: String,
    val privateKeyBase64: String
)

object MeshCrypto {
    private const val EC_CURVE = "secp256r1"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private val KDF_CONTEXT = "BlueWave-ECDH-v1:".toByteArray(Charsets.UTF_8)
    private val secureRandom = SecureRandom()

    fun generateIdentity(): MeshIdentity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(EC_CURVE), secureRandom)
        val pair: KeyPair = generator.generateKeyPair()
        val pubBytes = pair.public.encoded
        val privBytes = pair.private.encoded
        val peerId = derivePeerId(pubBytes)
        return MeshIdentity(
            peerId = peerId,
            publicKeyBase64 = encodeBase64(pubBytes),
            privateKeyBase64 = encodeBase64(privBytes)
        )
    }

    fun derivePeerId(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun verifyPeerPublicKey(peerId: String, publicKeyBase64: String): Boolean {
        if (publicKeyBase64.isBlank() || peerId.isBlank()) return false
        return runCatching {
            val bytes = decodeBase64(publicKeyBase64)
            if (bytes.isEmpty() || bytes.size > 256) return false
            val kf = KeyFactory.getInstance("EC")
            val pub = kf.generatePublic(X509EncodedKeySpec(bytes)) as? ECPublicKey ?: return false
            if (pub.params.curve.field.fieldSize != 256) return false
            derivePeerId(bytes) == peerId
        }.getOrDefault(false)
    }

    fun encryptDm(
        plaintext: String,
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
        associatedData: ByteArray = ByteArray(0)
    ): String? {
        return runCatching {
            val aesKey = deriveSharedAesKey(myPrivateKeyBase64, peerPublicKeyBase64)
            val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            "${encodeBase64(iv)}:${encodeBase64(ciphertext)}"
        }.getOrNull()
    }

    fun decryptDm(
        encryptedPayload: String,
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
        associatedData: ByteArray = ByteArray(0)
    ): String? {
        return runCatching {
            val parts = encryptedPayload.split(":", limit = 2)
            if (parts.size != 2) return null
            val iv = decodeBase64(parts[0])
            val ciphertext = decodeBase64(parts[1])
            if (iv.size != GCM_IV_BYTES || ciphertext.size < 16) return null
            val aesKey = deriveSharedAesKey(myPrivateKeyBase64, peerPublicKeyBase64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun deriveSharedAesKey(
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String
    ): SecretKeySpec {
        val kf = KeyFactory.getInstance("EC")
        val priv: PrivateKey = kf.generatePrivate(
            PKCS8EncodedKeySpec(decodeBase64(myPrivateKeyBase64))
        )
        val pub: PublicKey = kf.generatePublic(
            X509EncodedKeySpec(decodeBase64(peerPublicKeyBase64))
        )
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(priv)
        agreement.doPhase(pub, true)
        val rawShared = agreement.generateSecret()
        require(rawShared.any { it != 0.toByte() }) { "Invalid shared secret" }
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(KDF_CONTEXT)
            update(rawShared)
        }.digest()
        return SecretKeySpec(digest, "AES")
    }

    private val B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val B64_INV = IntArray(128) { -1 }.apply {
        B64_CHARS.forEachIndexed { idx, c -> this[c.code] = idx }
    }

    internal fun encodeBase64(input: ByteArray): String {
        val sb = StringBuilder(((input.size + 2) / 3) * 4)
        var i = 0
        while (i < input.size) {
            val b0 = input[i++].toInt() and 0xFF
            val hasB1 = i < input.size
            val b1 = if (hasB1) input[i++].toInt() and 0xFF else 0
            val hasB2 = i < input.size
            val b2 = if (hasB2) input[i++].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(B64_CHARS[(triple ushr 18) and 0x3F])
            sb.append(B64_CHARS[(triple ushr 12) and 0x3F])
            sb.append(if (hasB1) B64_CHARS[(triple ushr 6) and 0x3F] else '=')
            sb.append(if (hasB2) B64_CHARS[triple and 0x3F] else '=')
        }
        return sb.toString()
    }

    internal fun decodeBase64(input: String): ByteArray {
        val clean = input.trim()
        val out = ByteArrayOutputStream((clean.length * 3) / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val code = ch.code
            if (code >= 128) continue
            val value = B64_INV[code]
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
