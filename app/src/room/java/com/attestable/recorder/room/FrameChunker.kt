package com.attestable.recorder.room

import com.attestable.recorder.ChunkRecord
import com.attestable.recorder.ChunkSigner
import com.attestable.recorder.ChunkType
import java.io.ByteArrayOutputStream

/**
 * Accumulates exactly the bytes that went out on one stream and, every [chunkMs] (or when the
 * buffer reaches [maxBytes]), hashes + signs them as the next chunk of that stream and hands the
 * record to [onChunk]. Chunk N's bytes are the concatenation of everything sent between the
 * previous flush and this one, so the room's ledger — which appends the same bytes in the same
 * order — can consume `size` bytes and compare hashes.
 */
class FrameChunker(
    private val signer: ChunkSigner,
    private val type: ChunkType,
    private val chunkMs: Long,
    private val maxBytes: Int,
    private val onChunk: (ChunkRecord) -> Unit,
) {
    private val buffer = ByteArrayOutputStream()
    private var index = 0
    private var chunkStartMs = 0L
    private var lastFlushMs = System.currentTimeMillis()
    val chunksSigned get() = index

    @Synchronized
    fun append(bytes: ByteArray) {
        if (buffer.size() == 0) chunkStartMs = System.currentTimeMillis()
        buffer.write(bytes)
        if (buffer.size() >= maxBytes) flush()
    }

    /** Called from a timer: flushes when the window has elapsed. */
    @Synchronized
    fun tick() {
        if (buffer.size() > 0 && System.currentTimeMillis() - lastFlushMs >= chunkMs) flush()
    }

    @Synchronized
    fun flush() {
        lastFlushMs = System.currentTimeMillis()
        if (buffer.size() == 0) return
        val data = buffer.toByteArray()
        buffer.reset()
        val durationMs = (System.currentTimeMillis() - chunkStartMs).toInt().coerceAtLeast(1)
        val record = signer.sign(type, index, "${signer.recordingId}_${type.wireName}_$index", chunkStartMs, durationMs, data)
        index++
        onChunk(record)
    }
}
