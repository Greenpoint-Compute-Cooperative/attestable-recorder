package com.attestable.verifier

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID

/** Mirror of the app's `SignedChunk.kt`. Keep the two in sync. */
enum class ChunkType(val code: Byte, val wireName: String) {
    SESSION(0x00, "session"),
    AUDIO(0x01, "audio"),
    VIDEO(0x02, "video");

    companion object {
        fun fromWire(name: String) = entries.firstOrNull { it.wireName == name }
            ?: throw IllegalArgumentException("Unknown chunk type '$name'")
    }
}

object SignedPayload {
    const val MANIFEST_VERSION = 4
    private val MAGIC = "ATREC".toByteArray(Charsets.US_ASCII)
    val ZERO_HASH = ByteArray(32)

    /**
     * manifest_version 4: `"ATREC" | 0x04 | type | uuid[16] | index:i32 | timestamp_ms:i64 | duration_ms:i32 | sha256[32] | prev_sha256[32]`.
     * `prev` is the previous chunk's hash in the same stream (zeros for index 0); for the session record it is the context digest.
     */
    fun encode(type: ChunkType, recordingId: UUID, index: Int, timestampMs: Long, durationMs: Int, hash: ByteArray, prevHash: ByteArray): ByteArray {
        require(hash.size == 32 && prevHash.size == 32) { "hashes must be SHA-256" }
        return ByteBuffer.allocate(5 + 1 + 1 + 16 + 4 + 8 + 4 + 32 + 32).apply {
            put(MAGIC); put(MANIFEST_VERSION.toByte()); put(type.code)
            putLong(recordingId.mostSignificantBits); putLong(recordingId.leastSignificantBits)
            putInt(index); putLong(timestampMs); putInt(durationMs); put(hash); put(prevHash)
        }.array()
    }

    /** manifest_version 3: as v4 without the trailing prev hash, version byte 0x03. */
    fun encodeV3(type: ChunkType, recordingId: UUID, index: Int, timestampMs: Long, durationMs: Int, hash: ByteArray): ByteArray {
        require(hash.size == 32) { "hash must be SHA-256" }
        return ByteBuffer.allocate(5 + 1 + 1 + 16 + 4 + 8 + 4 + 32).apply {
            put(MAGIC); put(3.toByte()); put(type.code)
            putLong(recordingId.mostSignificantBits); putLong(recordingId.leastSignificantBits)
            putInt(index); putLong(timestampMs); putInt(durationMs); put(hash)
        }.array()
    }

    /** manifest_version ≤ 2 (audio only): `timestamp:i64 | index:i32 | sha256[32] | first 16 ASCII bytes of the recording id`. */
    fun encodeLegacy(recordingId: String, index: Int, timestampMs: Long, hash: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + 4 + 32 + 16).apply {
            putLong(timestampMs); putInt(index); put(hash)
            put(recordingId.toByteArray().take(16).toByteArray())
        }.array()

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Digest over the ordered chunk list: for each chunk `type | index:i32 | sha256[32]`. */
    fun chunkListDigest(chunks: List<RecordingManifest.ChunkEntry>): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        chunks.forEach { c ->
            md.update(c.type.code)
            md.update(ByteBuffer.allocate(4).putInt(c.index).array())
            md.update(c.hash)
        }
        return md.digest()
    }
}

/**
 * Canonical encoding of the app's OS-context snapshots (mirror of the app's RecordingContext).
 * `key=value` lines in this fixed order, joined by `\n`; snapshots joined by `\n\n`; SHA-256.
 */
object RecordingContext {
    val FIELDS = listOf(
        "sampled_at", "phase", "debuggable", "install_source", "accessibility_services",
        "other_active_recorders", "device_model", "os_release", "os_build", "security_patch",
    )

    fun canonical(snapshot: JSONObject): String =
        FIELDS.joinToString("\n") { k -> "$k=${snapshot.opt(k)?.takeIf { it != JSONObject.NULL } ?: ""}" }

    fun digest(snapshots: List<JSONObject>): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(snapshots.joinToString("\n\n") { canonical(it) }.toByteArray(Charsets.UTF_8))
}

/** Parsed `<recording_id>_manifest.json`, versions 1–4. */
data class RecordingManifest(
    val manifestVersion: Int,
    val recordingId: String,
    val attestationChain: List<X509Certificate>,
    val attestationChallenge: ByteArray?,
    /** "verifier" if the challenge came from outside, "app" if self-generated, null for old manifests. */
    val challengeSource: String?,
    val packageName: String?,
    val startedAt: Long?,
    val endedAt: Long?,
    /** Stream parameters as claimed by the app (codec, sample rate, resolution…). Not attested by hardware. */
    val streams: JSONObject?,
    /** v3 only: device/OS strings as claimed by the app. Not attested by hardware. */
    val appClaims: JSONObject?,
    /** v4: OS-context snapshots observed by the app (debuggable, install source, accessibility services, concurrent recorders…), bound into the session record. */
    val context: List<JSONObject>,
    val chunks: List<ChunkEntry>,
    /** v3+: signature over the session record (ordered chunk-list digest [+ v4 context digest]). */
    val sessionSignature: ByteArray?,
) {
    data class ChunkEntry(
        val type: ChunkType,
        val index: Int,
        val file: String,
        val timestamp: Long,
        val durationMs: Int?,
        val hash: ByteArray,
        /** v4: previous chunk's hash in the same stream (zeros for the first); null before v4. */
        val prevHash: ByteArray?,
        val signature: ByteArray,
        val size: Int,
    )

    val recordingUuid: UUID? get() = runCatching { UUID.fromString(recordingId) }.getOrNull()
    fun chunks(type: ChunkType) = chunks.filter { it.type == type }.sortedBy { it.index }
    val hasVideo get() = chunks.any { it.type == ChunkType.VIDEO }

    /** The bytes that the chunk's signature must cover, for this manifest version. */
    fun signedPayload(chunk: ChunkEntry): ByteArray = when {
        manifestVersion >= 4 -> SignedPayload.encode(
            chunk.type, recordingUuid ?: error("recording_id is not a UUID"),
            chunk.index, chunk.timestamp, chunk.durationMs ?: 0, chunk.hash,
            chunk.prevHash ?: error("v4 chunk without prev_hash")
        )
        manifestVersion == 3 -> SignedPayload.encodeV3(
            chunk.type, recordingUuid ?: error("recording_id is not a UUID"),
            chunk.index, chunk.timestamp, chunk.durationMs ?: 0, chunk.hash
        )
        else -> SignedPayload.encodeLegacy(recordingId, chunk.index, chunk.timestamp, chunk.hash)
    }

    fun sessionPayload(): ByteArray? {
        if (manifestVersion < 3 || startedAt == null || endedAt == null) return null
        val uuid = recordingUuid ?: return null
        val listDigest = SignedPayload.chunkListDigest(chunks)
        return if (manifestVersion >= 4)
            SignedPayload.encode(ChunkType.SESSION, uuid, chunks.size, endedAt, (endedAt - startedAt).toInt(), listDigest, RecordingContext.digest(context))
        else
            SignedPayload.encodeV3(ChunkType.SESSION, uuid, chunks.size, endedAt, (endedAt - startedAt).toInt(), listDigest)
    }

    companion object {
        fun parse(file: File): RecordingManifest = parse(JSONObject(file.readText()))

        fun parse(json: JSONObject): RecordingManifest {
            val b64 = Base64.getDecoder()
            val version = json.optInt("manifest_version", 1)
            val recordingId = json.getString("recording_id")
            fun optBytes(key: String) = json.optString(key, "").takeIf { it.isNotEmpty() && it != "null" }?.let(b64::decode)

            val chunks = json.getJSONArray("chunks").let { arr ->
                (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    val type = if (c.has("type")) ChunkType.fromWire(c.getString("type")) else ChunkType.AUDIO
                    val index = c.getInt("index")
                    ChunkEntry(
                        type = type,
                        index = index,
                        file = c.optString("file", "").ifEmpty { defaultFileName(recordingId, type, index) },
                        timestamp = c.getLong("timestamp"),
                        durationMs = if (c.has("duration_ms")) c.getInt("duration_ms") else null,
                        hash = b64.decode(if (c.has("hash")) c.getString("hash") else c.getString("audio_hash")),
                        prevHash = if (c.has("prev_hash")) b64.decode(c.getString("prev_hash")) else null,
                        signature = b64.decode(c.getString("signature")),
                        size = c.getInt("size"),
                    )
                }
            }
            return RecordingManifest(
                manifestVersion = version,
                recordingId = recordingId,
                attestationChain = decodeCertificateChain(json.get("attestation_chain")),
                attestationChallenge = optBytes("attestation_challenge"),
                challengeSource = json.optString("challenge_source", "").takeIf { it.isNotEmpty() && it != "null" },
                packageName = json.optString("package_name", "").takeIf { it.isNotEmpty() },
                startedAt = if (json.has("started_at")) json.getLong("started_at") else null,
                endedAt = if (json.has("ended_at")) json.getLong("ended_at") else null,
                streams = json.optJSONObject("streams"),
                appClaims = json.optJSONObject("app_claims"),
                context = json.optJSONArray("context")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } } ?: emptyList(),
                chunks = chunks,
                sessionSignature = optBytes("session_signature"),
            )
        }

        fun defaultFileName(recordingId: String, type: ChunkType, index: Int) = when (type) {
            ChunkType.AUDIO -> "${recordingId}_chunk_$index.pcm"
            ChunkType.VIDEO -> "${recordingId}_video_$index.mp4"
            ChunkType.SESSION -> error("session has no file")
        }

        /**
         * Accepts either a JSON array of base64 DER certificates (leaf first) or a single base64
         * string of concatenated DER certificates (what the app exports).
         */
        fun decodeCertificateChain(value: Any): List<X509Certificate> {
            val certFactory = CertificateFactory.getInstance("X.509")
            val b64 = Base64.getDecoder()
            if (value is JSONArray) {
                return (0 until value.length()).map { i ->
                    certFactory.generateCertificate(b64.decode(value.getString(i)).inputStream()) as X509Certificate
                }
            }
            val chainBytes = b64.decode(value.toString())
            val certs = mutableListOf<X509Certificate>()
            var offset = 0
            while (offset < chainBytes.size) {
                val cert = try {
                    certFactory.generateCertificate(chainBytes.inputStream(offset, chainBytes.size - offset)) as X509Certificate
                } catch (e: Exception) {
                    break
                }
                certs.add(cert)
                offset += cert.encoded.size
            }
            return certs
        }
    }
}
