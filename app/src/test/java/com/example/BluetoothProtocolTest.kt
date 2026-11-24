package com.example

import com.example.bluetooth.MeshCrypto
import com.example.bluetooth.MeshPacket
import com.example.bluetooth.MeshPacketFramer
import com.example.data.local.PeerEntity
import com.squareup.moshi.Moshi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothProtocolTest {
    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(MeshPacket::class.java)

    @Test
    fun `mesh packet round trip preserves fields`() {
        val original = MeshPacket(
            id = "pkt-101",
            type = "CHANNEL",
            senderId = "a3f91b04",
            senderNick = "alice",
            senderPubKey = "PUBKEY",
            targetId = null,
            channel = "#mesh",
            payload = "Hello BLE mesh!",
            ttl = 5,
            hopCount = 2,
            timestamp = 1700000000000L
        )
        val json = adapter.toJson(original)
        val decoded = adapter.fromJson(json)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `mesh packet framer fragments and reassembles across small MTU`() {
        val framer = MeshPacketFramer()
        val payload = ("BLE multi-hop payload ".repeat(12)).toByteArray(Charsets.UTF_8)
        val frames = framer.fragment(payload, maxCharacteristicBytes = 20)
        assertTrue(frames.size > 5)

        var reassembled: ByteArray? = null
        for (frame in frames) {
            reassembled = framer.onChunkReceived("AA:BB:CC:DD:EE:FF", frame)
        }
        assertNotNull(reassembled)
        assertArrayEquals(payload, reassembled)
    }

    @Test
    fun `mesh packet framer rejects excessive chunk counts to prevent memory exhaustion`() {
        val framer = MeshPacketFramer()
        val forgedChunk = byteArrayOf(
            MeshPacketFramer.MAGIC,
            0x01,
            0x02,
            0x00,
            (MeshPacketFramer.MAX_CHUNKS + 1).toByte(),
            0x41,
            0x42
        )
        assertNull(framer.onChunkReceived("AA:BB:CC:DD:EE:FF", forgedChunk))
    }

    @Test
    fun `ecdh aes-gcm encrypts and decrypts private dm with aad binding`() {
        val alice = MeshCrypto.generateIdentity()
        val bob = MeshCrypto.generateIdentity()
        val eve = MeshCrypto.generateIdentity()

        val aad = "${alice.peerId}->${bob.peerId}".toByteArray(Charsets.UTF_8)
        val secretMessage = "Meet at gate 4 in 10 mins"
        val ciphertext = MeshCrypto.encryptDm(
            plaintext = secretMessage,
            myPrivateKeyBase64 = alice.privateKeyBase64,
            peerPublicKeyBase64 = bob.publicKeyBase64,
            associatedData = aad
        )
        assertNotNull(ciphertext)

        val decryptedByBob = MeshCrypto.decryptDm(
            encryptedPayload = ciphertext!!,
            myPrivateKeyBase64 = bob.privateKeyBase64,
            peerPublicKeyBase64 = alice.publicKeyBase64,
            associatedData = aad
        )
        assertEquals(secretMessage, decryptedByBob)

        // Tampered routing metadata (AAD mismatch) must fail authentication
        val tamperedAad = "${alice.peerId}->${eve.peerId}".toByteArray(Charsets.UTF_8)
        assertNull(
            MeshCrypto.decryptDm(
                encryptedPayload = ciphertext,
                myPrivateKeyBase64 = bob.privateKeyBase64,
                peerPublicKeyBase64 = alice.publicKeyBase64,
                associatedData = tamperedAad
            )
        )

        // Third party cannot decrypt
        assertNull(
            MeshCrypto.decryptDm(
                encryptedPayload = ciphertext,
                myPrivateKeyBase64 = eve.privateKeyBase64,
                peerPublicKeyBase64 = alice.publicKeyBase64,
                associatedData = aad
            )
        )
    }

    @Test
    fun `verifyPeerPublicKey blocks peerId spoofing`() {
        val alice = MeshCrypto.generateIdentity()
        val attacker = MeshCrypto.generateIdentity()

        assertTrue(MeshCrypto.verifyPeerPublicKey(alice.peerId, alice.publicKeyBase64))
        assertFalse(MeshCrypto.verifyPeerPublicKey(alice.peerId, attacker.publicKeyBase64))
        assertFalse(MeshCrypto.verifyPeerPublicKey(alice.peerId, "not-a-valid-key"))
    }

    @Test
    fun `peer entity prefers custom alias when non-blank`() {
        val peerWithAlias = PeerEntity(
            peerId = "a3f91b04",
            nickname = "alice",
            publicKeyBase64 = "PUB",
            customAlias = "Field Unit A"
        )
        assertEquals("Field Unit A", peerWithAlias.displayName)

        val peerWithoutAlias = peerWithAlias.copy(customAlias = "   ")
        assertEquals("alice", peerWithoutAlias.displayName)
    }
}
