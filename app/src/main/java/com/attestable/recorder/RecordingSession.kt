package com.attestable.recorder

import android.content.Context
import android.util.Base64
import android.util.Log
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One recording: audio (always) and video (optional) captured in parallel, every chunk signed by
 * the attested key, finished with a signed session record over the ordered chunk list.
 */
class RecordingSession(
    private val context: Context,
    private val attestationManager: AttestationManager,
    private val outputDir: File,
    private val includeVideo: Boolean,
    previewSurface: Surface?,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "RecordingSession"
    }

    val recordingId: UUID = UUID.randomUUID()
    private val signer = ChunkSigner(attestationManager, recordingId)
    private val chunks = CopyOnWriteArrayList<ChunkRecord>()
    private val audio = AttestableAudioRecorder(signer, recordingId, outputDir) { chunks += it }
    private val video = if (includeVideo)
        AttestableVideoRecorder(context, signer, recordingId, outputDir, previewSurface, { chunks += it }, onError)
    else null

    var startedAtMs = 0L; private set
    var endedAtMs = 0L; private set
    var sessionSignature: ByteArray = ByteArray(0); private set
    private val contextSnapshots = mutableListOf<JSONObject>()
    val chunkCount get() = chunks.size
    fun chunkCount(type: ChunkType) = chunks.count { it.type == type }

    fun start(): String? {
        startedAtMs = System.currentTimeMillis()
        audio.start()?.let { return "Audio: $it" }
        video?.start()?.let { err -> audio.stop(); return "Video: $err" }
        contextSnapshots += RecordingContext.snapshot(context, "start")
        return null
    }

    fun stop() {
        contextSnapshots += RecordingContext.snapshot(context, "stop")
        video?.stop()
        audio.stop()
        endedAtMs = System.currentTimeMillis()
        sessionSignature = signer.signSession(chunks.toList(), startedAtMs, endedAtMs, RecordingContext.digest(contextSnapshots))
        Log.d(TAG, "Session ${recordingId}: ${chunks.size} chunks, session signature ${sessionSignature.size} bytes")
    }

    /** manifest_version 4 — see server/.../Manifest.kt for the reader. */
    fun exportManifest(): String {
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        val json = JSONObject()
        json.put("manifest_version", SignedPayload.MANIFEST_VERSION)
        json.put("recording_id", recordingId.toString())
        json.put("started_at", startedAtMs)
        json.put("ended_at", endedAtMs)
        json.put("package_name", BuildConfig.APPLICATION_ID)
        json.put("attestation_chain", attestationManager.exportAttestationChain())
        json.put("attestation_challenge", attestationManager.exportAttestationChallenge() ?: JSONObject.NULL)
        json.put("challenge_source", attestationManager.challengeSource ?: JSONObject.NULL)

        // Observed by the app, NOT by hardware: only as trustworthy as the (attested) app + OS.
        // Bound into the session signature via RecordingContext.digest, so it cannot be edited later.
        json.put("context", JSONArray(contextSnapshots))

        val streams = JSONObject()
        streams.put("audio", JSONObject().apply {
            put("codec", "pcm_s16le")
            put("sample_rate", AttestableAudioRecorder.SAMPLE_RATE)
            put("channels", AttestableAudioRecorder.CHANNELS)
            put("chunk_ms", AttestableAudioRecorder.CHUNK_DURATION_MS)
        })
        if (video != null) streams.put("video", JSONObject().apply {
            put("codec", "h264")
            put("container", "mp4")
            put("width", AttestableVideoRecorder.WIDTH)
            put("height", AttestableVideoRecorder.HEIGHT)
            put("fps", AttestableVideoRecorder.FPS)
            put("segment_ms", AttestableVideoRecorder.SEGMENT_MS)
        })
        json.put("streams", streams)

        val arr = JSONArray()
        chunks.forEach { c ->
            arr.put(JSONObject().apply {
                put("type", c.type.wireName)
                put("index", c.index)
                put("file", c.file)
                put("timestamp", c.timestampMs)
                put("duration_ms", c.durationMs)
                put("size", c.size)
                put("hash", b64(c.hash))
                put("prev_hash", b64(c.prevHash))
                put("signature", b64(c.signature))
            })
        }
        json.put("chunks", arr)
        json.put("session_signature", b64(sessionSignature))
        return json.toString(2)
    }
}
