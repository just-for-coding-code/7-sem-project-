package com.example.bluetooth

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Splits and reassembles BLE GATT payloads across arbitrary ATT MTU sizes
 * with strict bounds to prevent memory-exhaustion attacks from malformed chunks.
 */
class MeshPacketFramer {
    companion object {
        const val MAGIC: Byte = 0xB1.toByte()
        const val HEADER_SIZE = 5
        const val MAX_CHUNKS = 64
        const val MAX_FRAME_BYTES = 512
        const val MAX_ASSEMBLED_BYTES = 32 * 1024
        const val MAX_PENDING_BUFFERS = 32
    }

    private data class ReassemblyBuffer(
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var accumulatedBytes: Int = 0,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val partialBuffers = ConcurrentHashMap<String, ReassemblyBuffer>()

    fun fragment(payload: ByteArray, maxCharacteristicBytes: Int): List<ByteArray> {
        val safeMtuPayload = (maxCharacteristicBytes - HEADER_SIZE).coerceIn(15, MAX_FRAME_BYTES - HEADER_SIZE)
        val totalChunks = ceil(payload.size.toDouble() / safeMtuPayload).toInt().coerceIn(1, 255)
        val tag = payload.contentHashCode() and 0xFFFF
        val tagHi = ((tag ushr 8) and 0xFF).toByte()
        val tagLo = (tag and 0xFF).toByte()

        val frames = ArrayList<ByteArray>(totalChunks)
        for (index in 0 until totalChunks) {
            val start = index * safeMtuPayload
            val end = minOf(start + safeMtuPayload, payload.size)
            val sliceLen = end - start
            val frame = ByteArray(HEADER_SIZE + sliceLen)
            frame[0] = MAGIC
            frame[1] = tagHi
            frame[2] = tagLo
            frame[3] = index.toByte()
            frame[4] = totalChunks.toByte()
            System.arraycopy(payload, start, frame, HEADER_SIZE, sliceLen)
            frames.add(frame)
        }
        return frames
    }

    fun onChunkReceived(sourceKey: String, frame: ByteArray): ByteArray? {
        if (frame.size <= HEADER_SIZE || frame.size > MAX_FRAME_BYTES || frame[0] != MAGIC) {
            return null
        }
        val tag = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        val chunkIndex = frame[3].toInt() and 0xFF
        val totalChunks = frame[4].toInt() and 0xFF
        if (totalChunks <= 0 || totalChunks > MAX_CHUNKS || chunkIndex >= totalChunks) {
            return null
        }

        val slice = frame.copyOfRange(HEADER_SIZE, frame.size)
        if (totalChunks == 1) {
            return if (slice.size <= MAX_ASSEMBLED_BYTES) slice else null
        }

        pruneBuffers()
        val bufferKey = "$sourceKey:$tag:$totalChunks"
        val buffer = partialBuffers.getOrPut(bufferKey) {
            ReassemblyBuffer(totalChunks = totalChunks)
        }
        synchronized(buffer) {
            val previous = buffer.chunks.put(chunkIndex, slice)
            buffer.accumulatedBytes += slice.size - (previous?.size ?: 0)
            if (buffer.accumulatedBytes > MAX_ASSEMBLED_BYTES) {
                partialBuffers.remove(bufferKey)
                return null
            }
            if (buffer.chunks.size == totalChunks) {
                partialBuffers.remove(bufferKey)
                val out = ByteArrayOutputStream(buffer.accumulatedBytes)
                for (i in 0 until totalChunks) {
                    val part = buffer.chunks[i] ?: return null
                    out.write(part)
                }
                return out.toByteArray()
            }
        }
        return null
    }

    fun clear() {
        partialBuffers.clear()
    }

    private fun pruneBuffers() {
        val now = System.currentTimeMillis()
        partialBuffers.entries.removeIf { now - it.value.createdAtMs > 15_000L }
        while (partialBuffers.size >= MAX_PENDING_BUFFERS) {
            val oldest = partialBuffers.entries.minByOrNull { it.value.createdAtMs } ?: break
            partialBuffers.remove(oldest.key)
        }
    }
}
