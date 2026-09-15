package com.attestable.recorder

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Wire format shared by the app and the verifier (manifest_version 4).
 *
 * Every chunk of captured media — an audio PCM chunk or a video MP4 segment — is hashed and the
 * hash is signed inside the hardware keystore together with what the chunk *is*: which recording,
 * which stream, which position, when, how long, and **which chunk came before it** in the same
 * stream (hash chaining). A final "session" record signs the ordered chunk list and a digest of
 * the OS context the app observed, so a manifest cannot be truncated or re-ordered without
 * detection even if the session record is missing (the chain breaks) — and vice versa.
 *
 * Layout of the signed payload (103 bytes, big-endian):
 *
 *     "ATREC" | version=0x04 | type | recording_id[16] | index:i32 | timestamp_ms:i64 | duration_ms:i32 | sha256[32] | prev_sha256[32]
 *
 * `prev_sha256` is the previous chunk's hash in the same stream (all zero for index 0). For the
 * session record it is the digest of the recording context (see [RecordingContext]).
 *
 * The verifier's copy lives in server/src/main/kotlin/com/attestable/verifier/Manifest.kt.
 */
object SignedPayload {
    const val MANIFEST_VERSION = 4
    private val MAGIC = "ATREC".toByteArray(Charsets.US_ASCII)
    const val SIZE = 5 + 1 + 1 + 16 + 4 + 8 + 4 + 32 + 32
    val ZERO_HASH = ByteArray(32)

    fun encode(
        type: ChunkType, recordingId: UUID, index: Int, timestampMs: Long, durationMs: Int,
        hash: ByteArray, prevHash: ByteArray,
    ): ByteArray {
        require(hash.size == 32 && prevHash.size == 32) { "hashes must be SHA-256" }
        return ByteBuffer.allocate(SIZE).apply {
            put(MAGIC)
            put(MANIFEST_VERSION.toByte())
            put(type.code)
            putLong(recordingId.mostSignificantBits)
            putLong(recordingId.leastSignificantBits)
            putInt(index)
            putLong(timestampMs)
            putInt(durationMs)
            put(hash)
            put(prevHash)
        }.array()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Digest over the ordered chunk list: for each chunk `type | index:i32 | sha256[32]`. */
    fun chunkListDigest(chunks: List<ChunkRecord>): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        chunks.forEach { c ->
            md.update(c.type.code)
            md.update(ByteBuffer.allocate(4).putInt(c.index).array())
            md.update(c.hash)
        }
        return md.digest()
    }
}

enum class ChunkType(val code: Byte, val wireName: String) {
    SESSION(0x00, "session"),
    AUDIO(0x01, "audio"),
    VIDEO(0x02, "video"),
    /** Hand-camera frames: newline-terminated JSON cursor/flyhands frames exactly as sent to the room. */
    HANDS(0x03, "hands"),
    /** Gesture-camera frames: newline-terminated JSON cursor frames from body-pose tracking, as sent. */
    GESTURE(0x04, "gesture");
}

/** One signed chunk as it appears in the manifest. */
data class ChunkRecord(
    val type: ChunkType,
    val index: Int,
    /** File name relative to the recordings directory. */
    val file: String,
    /** Wall-clock start of the chunk, epoch milliseconds (from the attested OS's clock). */
    val timestampMs: Long,
    val durationMs: Int,
    val size: Int,
    val hash: ByteArray,
    /** Hash of the previous chunk in the same stream; zeros for the first. */
    val prevHash: ByteArray,
    val signature: ByteArray,
)

/** Hashes and signs chunks with the attested hardware key for one recording. */
class ChunkSigner(private val attestationManager: AttestationManager, val recordingId: UUID) {
    private val lastHash = HashMap<ChunkType, ByteArray>()

    @Synchronized
    fun sign(type: ChunkType, index: Int, file: String, timestampMs: Long, durationMs: Int, data: ByteArray): ChunkRecord {
        val hash = SignedPayload.sha256(data)
        val prev = lastHash[type] ?: SignedPayload.ZERO_HASH
        val payload = SignedPayload.encode(type, recordingId, index, timestampMs, durationMs, hash, prev)
        val record = ChunkRecord(type, index, file, timestampMs, durationMs, data.size, hash, prev, signOrEmpty(payload))
        lastHash[type] = hash
        return record
    }

    /**
     * Session record: index = chunk count, timestamp = end, duration = whole session,
     * hash = ordered chunk-list digest, prev = digest of the recording context.
     */
    fun signSession(chunks: List<ChunkRecord>, startedAtMs: Long, endedAtMs: Long, contextDigest: ByteArray): ByteArray {
        val digest = SignedPayload.chunkListDigest(chunks)
        val payload = SignedPayload.encode(
            ChunkType.SESSION, recordingId, chunks.size, endedAtMs, (endedAtMs - startedAtMs).toInt(), digest, contextDigest
        )
        return signOrEmpty(payload)
    }

    private fun signOrEmpty(payload: ByteArray): ByteArray =
        when (val r = attestationManager.signData(payload)) {
            is SignatureResult.Success -> r.signature
            is SignatureResult.Error -> ByteArray(0)
        }
}
