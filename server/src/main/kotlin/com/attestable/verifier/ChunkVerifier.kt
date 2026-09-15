package com.attestable.verifier

import java.io.File
import java.security.PublicKey
import java.security.Signature

/**
 * Everything that happens *after* Warden has handed back the attested public key: checking that
 * each chunk file matches its hash, that each hash was signed by that key, that the session
 * record covers the chunk list, and that the streams are continuous.
 */
object ChunkVerifier {

    sealed class ChunkStatus(val ok: Boolean) {
        object Valid : ChunkStatus(true)
        object FileMissing : ChunkStatus(false)
        object HashMismatch : ChunkStatus(false)
        object BadSignature : ChunkStatus(false)
        /** v4: the chunk's signed prev_hash does not equal the hash of the previous chunk in its stream. */
        object BrokenChain : ChunkStatus(false)
        data class Error(val message: String) : ChunkStatus(false)
    }

    data class ChunkResult(val chunk: RecordingManifest.ChunkEntry, val status: ChunkStatus)

    data class Report(
        val results: List<ChunkResult>,
        /** null = manifest has no session record (v≤2); true/false = record present and (in)valid. */
        val sessionValid: Boolean?,
        val warnings: List<String>,
    ) {
        val allChunksValid get() = results.isNotEmpty() && results.all { it.status.ok }
        fun valid(type: ChunkType) = results.count { it.chunk.type == type && it.status.ok }
        fun total(type: ChunkType) = results.count { it.chunk.type == type }
        val isFullyVerified get() = allChunksValid && sessionValid != false
    }

    fun verify(manifest: RecordingManifest, chunksDir: File, attestedKey: PublicKey): Report {
        val results = manifest.chunks.map { ChunkResult(it, verifyChunk(manifest, it, chunksDir, attestedKey)) }
            .let { if (manifest.manifestVersion >= 4) applyChainCheck(manifest, it) else it }
        val sessionValid = manifest.sessionPayload()?.let { payload ->
            val sig = manifest.sessionSignature
            sig != null && sig.isNotEmpty() && verifySignature(attestedKey, payload, sig)
        }
        return Report(results, sessionValid, continuityWarnings(manifest))
    }

    fun verifyChunk(manifest: RecordingManifest, chunk: RecordingManifest.ChunkEntry, chunksDir: File, key: PublicKey): ChunkStatus {
        val file = File(chunksDir, chunk.file)
        if (!file.isFile) return ChunkStatus.FileMissing
        val actualHash = SignedPayload.sha256(file.readBytes())
        if (!actualHash.contentEquals(chunk.hash)) return ChunkStatus.HashMismatch
        val payload = try {
            manifest.signedPayload(chunk)
        } catch (e: Exception) {
            return ChunkStatus.Error(e.message ?: "cannot build signed payload")
        }
        return if (verifySignature(key, payload, chunk.signature)) ChunkStatus.Valid else ChunkStatus.BadSignature
    }

    /**
     * v4 hash chain: each chunk's signed `prev_hash` must equal the previous same-stream chunk's hash
     * (zeros for index 0). Because `prev_hash` is inside the signed payload, a valid signature already
     * proves the *signer* chained it; this check proves the chain links to the chunks actually present.
     */
    private fun applyChainCheck(manifest: RecordingManifest, results: List<ChunkResult>): List<ChunkResult> {
        val byChunk = results.associateBy { it.chunk }
        val updated = HashMap(byChunk)
        for (type in listOf(ChunkType.AUDIO, ChunkType.VIDEO, ChunkType.HANDS, ChunkType.GESTURE)) {
            val stream = manifest.chunks(type)
            var expectedPrev = SignedPayload.ZERO_HASH
            for (c in stream) {
                val r = byChunk.getValue(c)
                val prev = c.prevHash
                if (r.status.ok && (prev == null || !prev.contentEquals(expectedPrev))) {
                    updated[c] = r.copy(status = ChunkStatus.BrokenChain)
                }
                expectedPrev = c.hash
            }
        }
        return results.map { updated.getValue(it.chunk) }
    }

    fun verifySignature(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(data); verify(signature)
        }
    } catch (e: Exception) {
        false
    }

    /** Gaps, overlaps, out-of-order or missing indexes per stream. Warnings, not failures: the signatures still hold. */
    fun continuityWarnings(manifest: RecordingManifest): List<String> {
        val warnings = mutableListOf<String>()
        for (type in listOf(ChunkType.AUDIO, ChunkType.VIDEO, ChunkType.HANDS, ChunkType.GESTURE)) {
            val chunks = manifest.chunks(type)
            if (chunks.isEmpty()) continue
            val indexes = chunks.map { it.index }
            val expected = (0 until chunks.size).toList()
            if (indexes != expected) warnings += "$type: chunk indexes are $indexes, expected $expected (missing or duplicated chunks?)"
            chunks.zipWithNext().forEach { (a, b) ->
                val aEnd = a.timestamp + (a.durationMs ?: 0)
                val gap = b.timestamp - aEnd
                if (a.durationMs != null && gap > 1500) warnings += "$type: ${gap} ms gap between chunk ${a.index} and ${b.index}"
                if (b.timestamp < a.timestamp) warnings += "$type: chunk ${b.index} starts before chunk ${a.index}"
            }
        }
        val audio = manifest.chunks(ChunkType.AUDIO)
        val video = manifest.chunks(ChunkType.VIDEO)
        if (audio.isNotEmpty() && video.isNotEmpty()) {
            val skew = video.first().timestamp - audio.first().timestamp
            if (kotlin.math.abs(skew) > 3000) warnings += "audio and video start $skew ms apart"
        }
        return warnings
    }
}
