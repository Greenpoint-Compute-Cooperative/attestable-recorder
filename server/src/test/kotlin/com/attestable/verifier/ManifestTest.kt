package com.attestable.verifier

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the post-attestation half of the verifier (chunk hashes, chunk signatures, session
 * record, continuity) with a locally generated P-256 key standing in for the attested key.
 */
class ManifestTest {
    private val dir: File = Files.createTempDirectory("recording").toFile()
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val recordingId: UUID = UUID.randomUUID()
    private val b64 = Base64.getEncoder()

    private fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(keyPair.private); update(data); sign() }

    private fun contextSnapshot(phase: String, debuggable: Boolean = false) = JSONObject()
        .put("sampled_at", 1_789_000_000_000L).put("phase", phase).put("debuggable", debuggable)
        .put("install_source", JSONObject.NULL).put("accessibility_services", "").put("other_active_recorders", 0)
        .put("device_model", "Pixel 10a").put("os_release", "17").put("os_build", "2026091000").put("security_patch", "2026-09-01")

    /** Builds a v4 manifest with [audio] PCM chunks and [video] MP4 segments (hash-chained per stream), files included. */
    private fun buildManifest(audio: Int = 3, video: Int = 2, sessionSigned: Boolean = true): JSONObject {
        val started = 1_789_000_000_000L
        val chunks = JSONArray()
        val entries = mutableListOf<RecordingManifest.ChunkEntry>()
        val prev = HashMap<ChunkType, ByteArray>()
        fun add(type: ChunkType, index: Int, file: String, ts: Long, durationMs: Int, data: ByteArray) {
            File(dir, file).writeBytes(data)
            val hash = SignedPayload.sha256(data)
            val prevHash = prev[type] ?: SignedPayload.ZERO_HASH
            val sig = sign(SignedPayload.encode(type, recordingId, index, ts, durationMs, hash, prevHash))
            prev[type] = hash
            entries += RecordingManifest.ChunkEntry(type, index, file, ts, durationMs, hash, prevHash, sig, data.size)
            chunks.put(JSONObject().put("type", type.wireName).put("index", index).put("file", file).put("timestamp", ts)
                .put("duration_ms", durationMs).put("size", data.size).put("hash", b64.encodeToString(hash))
                .put("prev_hash", b64.encodeToString(prevHash)).put("signature", b64.encodeToString(sig)))
        }
        repeat(audio) { i -> add(ChunkType.AUDIO, i, "${recordingId}_chunk_$i.pcm", started + i * 5000L, 5000, Random.nextBytes(4410 * 2)) }
        repeat(video) { i -> add(ChunkType.VIDEO, i, "${recordingId}_video_$i.mp4", started + 120 + i * 5000L, 5000, Random.nextBytes(20_000)) }
        val ended = started + 5000L * maxOf(audio, video)
        val context = listOf(contextSnapshot("start"), contextSnapshot("stop"))
        val json = JSONObject()
            .put("manifest_version", 4).put("recording_id", recordingId.toString())
            .put("started_at", started).put("ended_at", ended)
            .put("package_name", "com.attestable.recorder")
            .put("attestation_chain", JSONArray()) // not needed for chunk verification
            .put("attestation_challenge", b64.encodeToString(ByteArray(32) { 7 })).put("challenge_source", "verifier")
            .put("streams", JSONObject().put("audio", JSONObject().put("codec", "pcm_s16le").put("sample_rate", 44100).put("channels", 1)))
            .put("context", JSONArray(context))
            .put("chunks", chunks)
        if (sessionSigned) {
            val payload = SignedPayload.encode(ChunkType.SESSION, recordingId, entries.size, ended, (ended - started).toInt(),
                SignedPayload.chunkListDigest(entries), RecordingContext.digest(context))
            json.put("session_signature", b64.encodeToString(sign(payload)))
        }
        return json
    }

    @Test
    fun `signed payload is 103 bytes and starts with the magic`() {
        val p = SignedPayload.encode(ChunkType.VIDEO, recordingId, 4, 1L, 5000, ByteArray(32), ByteArray(32))
        assertEquals(103, p.size)
        assertEquals("ATREC", String(p.copyOfRange(0, 5)))
        assertEquals(4, p[5].toInt())
        assertEquals(ChunkType.VIDEO.code, p[6])
    }

    @Test
    fun `context digest is stable across JSON key order`() {
        val a = contextSnapshot("start")
        val b = JSONObject(a.toString().let { JSONObject(it).toMap().toList().reversed().toMap() })
        assertTrue(RecordingContext.digest(listOf(a)).contentEquals(RecordingContext.digest(listOf(b))))
        val c = contextSnapshot("start", debuggable = true)
        assertFalse(RecordingContext.digest(listOf(a)).contentEquals(RecordingContext.digest(listOf(c))))
    }

    @Test
    fun `editing the sealed context breaks the session record`() {
        val json = buildManifest()
        json.getJSONArray("context").getJSONObject(0).put("debuggable", true)
        val report = ChunkVerifier.verify(RecordingManifest.parse(json), dir, keyPair.public)
        assertTrue(report.allChunksValid)
        assertEquals(false, report.sessionValid, "context is bound into the session signature")
    }

    @Test
    fun `removing a middle chunk breaks the hash chain even without a session record`() {
        val json = buildManifest(audio = 4, video = 0, sessionSigned = false)
        json.getJSONArray("chunks").remove(1) // drop audio 1; indexes now 0, 2, 3
        val manifest = RecordingManifest.parse(json)
        assertNull(manifest.sessionSignature)
        val report = ChunkVerifier.verify(manifest, dir, keyPair.public)
        val byIndex = report.results.associate { it.chunk.index to it.status }
        assertIs<ChunkVerifier.ChunkStatus.Valid>(byIndex[0])
        assertIs<ChunkVerifier.ChunkStatus.BrokenChain>(byIndex[2], "chunk 2's signed prev_hash is hash(1), but hash(0) precedes it now")
        assertIs<ChunkVerifier.ChunkStatus.Valid>(byIndex[3], "3 still chains to 2")
        assertFalse(report.allChunksValid)
        assertEquals(false, report.sessionValid)
        assertTrue(report.warnings.any { it.contains("indexes") })
    }

    @Test
    fun `a chunk with a forged prev_hash is caught by the chain check`() {
        val json = buildManifest(audio = 3, video = 0)
        // Re-sign audio 2 with a wrong prev_hash: signature valid, chain broken.
        val c = json.getJSONArray("chunks").getJSONObject(2)
        val hash = Base64.getDecoder().decode(c.getString("hash"))
        val wrongPrev = ByteArray(32) { 9 }
        val sig = sign(SignedPayload.encode(ChunkType.AUDIO, recordingId, 2, c.getLong("timestamp"), c.getInt("duration_ms"), hash, wrongPrev))
        c.put("prev_hash", b64.encodeToString(wrongPrev)).put("signature", b64.encodeToString(sig))
        val report = ChunkVerifier.verify(RecordingManifest.parse(json), dir, keyPair.public)
        assertIs<ChunkVerifier.ChunkStatus.BrokenChain>(report.results.first { it.chunk.index == 2 }.status)
    }

    @Test
    fun `v3 manifests without prev_hash still verify with the v3 payload`() {
        val started = 1_789_000_000_000L
        val id = UUID.randomUUID()
        val data = Random.nextBytes(1000)
        File(dir, "${id}_chunk_0.pcm").writeBytes(data)
        val hash = SignedPayload.sha256(data)
        val sig = sign(SignedPayload.encodeV3(ChunkType.AUDIO, id, 0, started, 1000, hash))
        val json = JSONObject().put("manifest_version", 3).put("recording_id", id.toString()).put("attestation_chain", JSONArray())
            .put("started_at", started).put("ended_at", started + 1000)
            .put("chunks", JSONArray().put(JSONObject().put("type", "audio").put("index", 0).put("timestamp", started).put("duration_ms", 1000)
                .put("hash", b64.encodeToString(hash)).put("signature", b64.encodeToString(sig)).put("size", data.size)))
        val manifest = RecordingManifest.parse(json)
        val report = ChunkVerifier.verify(manifest, dir, keyPair.public)
        assertTrue(report.allChunksValid)
        assertEquals(false, report.sessionValid, "no session_signature present → recorded as invalid for v3")
    }

    @Test
    fun `audio and video chunks and the session record verify`() {
        val manifest = RecordingManifest.parse(buildManifest())
        assertEquals(3, manifest.chunks(ChunkType.AUDIO).size)
        assertEquals(2, manifest.chunks(ChunkType.VIDEO).size)
        assertTrue(manifest.hasVideo)
        val report = ChunkVerifier.verify(manifest, dir, keyPair.public)
        assertTrue(report.allChunksValid, report.results.filter { !it.status.ok }.toString())
        assertEquals(true, report.sessionValid)
        assertTrue(report.isFullyVerified)
        assertTrue(report.warnings.isEmpty(), report.warnings.toString())
    }

    @Test
    fun `a modified video segment fails its hash and only that segment`() {
        val manifest = RecordingManifest.parse(buildManifest())
        val target = File(dir, manifest.chunks(ChunkType.VIDEO)[1].file)
        val bytes = target.readBytes(); bytes[100] = (bytes[100] + 1).toByte(); target.writeBytes(bytes)
        val report = ChunkVerifier.verify(manifest, dir, keyPair.public)
        assertFalse(report.allChunksValid)
        assertEquals(1, report.results.count { !it.status.ok })
        assertIs<ChunkVerifier.ChunkStatus.HashMismatch>(report.results.first { it.chunk.type == ChunkType.VIDEO && it.chunk.index == 1 }.status)
        assertEquals(true, report.sessionValid)
    }

    @Test
    fun `dropping the last chunk from the manifest breaks the session record`() {
        val json = buildManifest()
        json.getJSONArray("chunks").remove(json.getJSONArray("chunks").length() - 1)
        val manifest = RecordingManifest.parse(json)
        val report = ChunkVerifier.verify(manifest, dir, keyPair.public)
        assertTrue(report.allChunksValid, "remaining chunks are individually valid")
        assertEquals(false, report.sessionValid, "truncation must be detected")
        assertFalse(report.isFullyVerified)
    }

    @Test
    fun `reordering chunks breaks the session record`() {
        val json = buildManifest()
        val arr = json.getJSONArray("chunks")
        val a = arr.getJSONObject(0); val b = arr.getJSONObject(1)
        arr.put(0, b); arr.put(1, a)
        val report = ChunkVerifier.verify(RecordingManifest.parse(json), dir, keyPair.public)
        assertTrue(report.allChunksValid)
        assertEquals(false, report.sessionValid)
    }

    @Test
    fun `a signature from another key is rejected`() {
        val json = buildManifest()
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val report = ChunkVerifier.verify(RecordingManifest.parse(json), dir, other.public)
        assertTrue(report.results.all { it.status is ChunkVerifier.ChunkStatus.BadSignature })
        assertEquals(false, report.sessionValid)
    }

    @Test
    fun `legacy v2 manifests still parse and verify with the old payload`() {
        val started = 1_789_000_000_000L
        val data = Random.nextBytes(1000)
        val id = UUID.randomUUID().toString()
        File(dir, "${id}_chunk_0.pcm").writeBytes(data)
        val hash = SignedPayload.sha256(data)
        val sig = sign(SignedPayload.encodeLegacy(id, 0, started, hash))
        val json = JSONObject().put("manifest_version", 2).put("recording_id", id).put("attestation_chain", JSONArray())
            .put("attestation_challenge", b64.encodeToString(ByteArray(32)))
            .put("chunks", JSONArray().put(JSONObject().put("index", 0).put("timestamp", started).put("audio_hash", b64.encodeToString(hash))
                .put("signature", b64.encodeToString(sig)).put("size", data.size)))
        val manifest = RecordingManifest.parse(json)
        assertEquals(2, manifest.manifestVersion)
        assertEquals(ChunkType.AUDIO, manifest.chunks.single().type)
        val report = ChunkVerifier.verify(manifest, dir, keyPair.public)
        assertTrue(report.allChunksValid)
        assertNull(report.sessionValid)
    }

    @Test
    fun `continuity warnings flag gaps and missing indexes`() {
        val json = buildManifest(audio = 3, video = 0)
        val arr = json.getJSONArray("chunks")
        arr.getJSONObject(2).put("index", 5).put("timestamp", arr.getJSONObject(2).getLong("timestamp") + 20_000)
        val warnings = ChunkVerifier.continuityWarnings(RecordingManifest.parse(json))
        assertTrue(warnings.any { it.contains("indexes") }, warnings.toString())
        assertTrue(warnings.any { it.contains("gap") }, warnings.toString())
    }

    @Test
    fun `export writes a wav with a correct header`() {
        val manifest = RecordingManifest.parse(buildManifest(audio = 2, video = 0))
        val out = Export.export(manifest, dir, Files.createTempDirectory("export").toFile())
        val wav = out.wav!!.readBytes()
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals(44 + 2 * 4410 * 2, wav.size)
    }
}
