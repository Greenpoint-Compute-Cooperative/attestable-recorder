package com.attestable.recorder.room

import android.content.Context
import android.util.Base64
import android.util.Log
import android.view.Surface
import com.attestable.recorder.AttestationManager
import com.attestable.recorder.AttestationResult
import com.attestable.recorder.BuildConfig
import com.attestable.recorder.ChunkRecord
import com.attestable.recorder.ChunkSigner
import com.attestable.recorder.ChunkType
import com.attestable.recorder.RecordingContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One credible-sensor session against a vibecode-room server:
 *
 *  1. ask the room for a challenge, generate the StrongBox key with it, send the attestation
 *     chain → the room answers with a `source_id` (and whether it verified the chain via Warden);
 *  2. stream: audio → /api/mic, hand cursors → /hands/ws?stream=hands, pose cursors →
 *     /hands/ws?stream=gesture, each socket tagged with the source id;
 *  3. sign what was sent, per stream, in windows, and post the records to /api/attest/chunk;
 *  4. on stop, post the signed session record (chunk list + OS context digest).
 */
class RoomSession(
    private val context: Context,
    private val attestationManager: AttestationManager,
    private val config: Config,
    private val previewSurface: Surface?,
    private val listener: Listener,
) {
    data class Config(
        val roomUrl: String,
        val wall: String,
        val audio: Boolean,
        val hands: Boolean,
        val gesture: Boolean,
        val frontCamera: Boolean,
        val guestName: String,
    )

    interface Listener {
        fun onStatus(message: String)
        fun onError(message: String)
        fun onStats(stats: Stats)
    }

    data class Stats(
        val sourceId: String?, val verified: String?, val audioChunks: Int, val handsChunks: Int, val gestureChunks: Int,
        val accepted: Int, val rejected: Int, val pending: Int, val handsSeen: Int, val peopleSeen: Int, val lastRejection: String?,
    )

    companion object {
        private const val TAG = "RoomSession"
        private const val FRAME_CHUNK_MS = 2000L
        private const val CURSOR_HZ = 20L
    }

    val recordingId: UUID = UUID.randomUUID()
    private val client = RoomClient(config.roomUrl)
    private val signer = ChunkSigner(attestationManager, recordingId)
    private val chunks = CopyOnWriteArrayList<ChunkRecord>()
    private val contextSnapshots = mutableListOf<JSONObject>()
    private val exec: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    @Volatile var sourceId: String? = null; private set
    @Volatile var verified: String? = null; private set
    private var startedAtMs = 0L
    private var accepted = 0; private var rejected = 0; private var pending = 0
    @Volatile private var lastRejection: String? = null

    private var audio: StreamingAudio? = null
    private var camera: CameraFeed? = null
    private var handsTracker: HandsTracker? = null
    private var poseTracker: PoseTracker? = null
    private var micSocket: RoomClient.ReconnectingSocket? = null
    private var handsSocket: RoomClient.ReconnectingSocket? = null
    private var gestureSocket: RoomClient.ReconnectingSocket? = null
    private var audioChunker: FrameChunker? = null
    private var handsChunker: FrameChunker? = null
    private var gestureChunker: FrameChunker? = null

    /** Runs the whole handshake on a worker thread; reports through [listener]. */
    fun start() {
        exec.execute {
            try {
                startBlocking()
            } catch (e: Exception) {
                Log.e(TAG, "session start failed", e)
                listener.onError("start failed: ${e.message}")
            }
        }
    }

    private fun startBlocking() {
        startedAtMs = System.currentTimeMillis()
        listener.onStatus("asking the room for a challenge…")
        val ch = client.post("/api/attest/challenge", null)
        if (ch.code != 200 || !ch.body.has("challenge")) throw IllegalStateException("challenge: HTTP ${ch.code} ${ch.body}")
        val challenge = Base64.decode(ch.body.getString("challenge"), Base64.DEFAULT)

        listener.onStatus("generating a StrongBox key for the room's challenge…")
        when (val r = attestationManager.generateAttestationKey(challenge, "verifier")) {
            is AttestationResult.Error -> throw IllegalStateException("key generation: ${r.message}")
            is AttestationResult.Success -> Unit
        }

        listener.onStatus("sending the attestation chain…")
        val streams = JSONObject()
        if (config.audio) streams.put("audio", JSONObject().put("codec", "pcm_s16le").put("sample_rate", StreamingAudio.SAMPLE_RATE).put("channels", 1).put("chunk_ms", StreamingAudio.CHUNK_MS))
        if (config.hands) streams.put("hands", JSONObject().put("protocol", "vibersyn-guest").put("chunk_ms", FRAME_CHUNK_MS))
        if (config.gesture) streams.put("gesture", JSONObject().put("protocol", "vibersyn-guest").put("chunk_ms", FRAME_CHUNK_MS))
        val session = client.post("/api/attest/session", JSONObject()
            .put("recording_id", recordingId.toString())
            .put("attestation_chain", attestationManager.exportAttestationChain())
            .put("attestation_challenge", attestationManager.exportAttestationChallenge())
            .put("package_name", BuildConfig.APPLICATION_ID)
            .put("streams", streams)
            .put("label", config.guestName))
        if (session.code != 200) throw IllegalStateException("room rejected the attestation: ${session.body.optString("error", session.body.toString())}")
        sourceId = session.body.getString("source_id")
        verified = session.body.optString("verified", "?")
        contextSnapshots += RecordingContext.snapshot(context, "start")
        listener.onStatus("room accepted this phone as source $sourceId (${verified})")
        publishStats()

        val sid = sourceId!!
        if (config.audio) {
            audioChunker = FrameChunker(signer, ChunkType.AUDIO, StreamingAudio.CHUNK_MS, StreamingAudio.BYTES_PER_CHUNK) { post(it) }
            micSocket = client.openSocket("/api/mic?source=$sid", waitForReady = true, onOpen = {}, onSent = { audioChunker!!.append(it) }) { listener.onStatus("mic: $it") }
            audio = StreamingAudio(micSocket!!) { listener.onError(it) }.also { a -> a.start()?.let { throw IllegalStateException("audio: $it") } }
        }
        if (config.hands) {
            handsChunker = FrameChunker(signer, ChunkType.HANDS, FRAME_CHUNK_MS, 256 * 1024) { post(it) }
            handsTracker = HandsTracker(context, mirror = !config.frontCamera || true) { listener.onError(it) }
            handsSocket = client.openSocket("/hands/ws?source=$sid&stream=hands", waitForReady = false,
                onOpen = { it.sendText(hello("hand camera")) }, onSent = { handsChunker!!.append(it) }) { listener.onStatus("hands: $it") }
        }
        if (config.gesture) {
            gestureChunker = FrameChunker(signer, ChunkType.GESTURE, FRAME_CHUNK_MS, 256 * 1024) { post(it) }
            poseTracker = PoseTracker(context, mirror = true) { listener.onError(it) }
            gestureSocket = client.openSocket("/hands/ws?source=$sid&stream=gesture", waitForReady = false,
                onOpen = { it.sendText(hello("gesture camera")) }, onSent = { gestureChunker!!.append(it) }) { listener.onStatus("gesture: $it") }
        }
        if (config.hands || config.gesture) {
            camera = CameraFeed(context, previewSurface, config.frontCamera, { bmp, rot, ts ->
                handsTracker?.analyse(bmp, rot, ts)
                poseTracker?.analyse(bmp, rot, ts)
            }) { listener.onError(it) }
            camera!!.start()?.let { throw IllegalStateException("camera: $it") }
        }

        // Liveness: cursor frames every tick on each guest socket; chunk windows flushed by time.
        exec.scheduleAtFixedRate({
            runCatching {
                handsTracker?.let { t -> handsSocket?.let { s -> t.framesForTick().forEach { s.sendText(it) } } }
                poseTracker?.let { t -> gestureSocket?.let { s -> t.framesForTick().forEach { s.sendText(it) } } }
            }.onFailure { Log.w(TAG, "tick failed", it) }
        }, 0, 1000 / CURSOR_HZ, TimeUnit.MILLISECONDS)
        exec.scheduleAtFixedRate({
            runCatching { audioChunker?.tick(); handsChunker?.tick(); gestureChunker?.tick(); publishStats() }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    private fun hello(name: String) =
        JSONObject().put("type", "hello").put("wall", config.wall).put("name", "${config.guestName} · $name").toString()

    private fun post(record: ChunkRecord) {
        chunks += record
        val sid = sourceId ?: return
        val task = Runnable {
            val body = JSONObject()
                .put("source_id", sid).put("type", record.type.wireName).put("index", record.index)
                .put("timestamp", record.timestampMs).put("duration_ms", record.durationMs).put("size", record.size)
                .put("hash", b64(record.hash)).put("prev_hash", b64(record.prevHash)).put("signature", b64(record.signature))
            val res = runCatching { client.post("/api/attest/chunk", body) }.getOrElse { RoomClient.HttpResult(0, JSONObject().put("reason", it.message)) }
            when (res.code) {
                200 -> accepted++
                202 -> pending++
                else -> { rejected++; lastRejection = "${record.type.wireName} ${record.index}: ${res.body.optString("reason", "HTTP ${res.code}")}" }
            }
            publishStats()
        }
        // After shutdown (final flush racing a stop) post inline rather than lose the record.
        try { exec.execute(task) } catch (e: java.util.concurrent.RejectedExecutionException) { task.run() }
    }

    private fun publishStats() {
        listener.onStats(Stats(
            sourceId, verified,
            audioChunker?.chunksSigned ?: 0, handsChunker?.chunksSigned ?: 0, gestureChunker?.chunksSigned ?: 0,
            accepted, rejected, pending, handsTracker?.handsSeen ?: 0, poseTracker?.peopleSeen ?: 0, lastRejection,
        ))
    }

    /** Stops capture, flushes the last chunks, seals the session with the room. Blocking. */
    fun stop() {
        // Order matters: stop capture → flush the last windows (their records are posted through
        // `exec`) → only then shut the executor down and wait for the posts to land.
        runCatching { audio?.stop() }
        runCatching { camera?.stop() }
        runCatching { handsTracker?.close() }
        runCatching { poseTracker?.close() }
        audioChunker?.flush(); handsChunker?.flush(); gestureChunker?.flush()
        contextSnapshots += RecordingContext.snapshot(context, "stop")
        val endedAt = System.currentTimeMillis()
        val list = chunks.toList()
        val sessionSig = signer.signSession(list, startedAtMs, endedAt, RecordingContext.digest(contextSnapshots))
        exec.shutdown()
        // Let in-flight chunk posts land before the close (the room checks the count).
        runCatching { exec.awaitTermination(8, TimeUnit.SECONDS) }
        sourceId?.let { sid ->
            val body = JSONObject()
                .put("started_at", startedAtMs).put("ended_at", endedAt)
                .put("context", JSONArray(contextSnapshots))
                .put("chunks", JSONArray(list.map { JSONObject().put("type", it.type.wireName).put("index", it.index).put("hash", b64(it.hash)) }))
                .put("session_signature", b64(sessionSig))
            val res = runCatching { client.post("/api/attest/session/$sid/close", body) }.getOrNull()
            listener.onStatus(if (res?.code == 200) "session sealed by the room (${list.size} chunks)" else "session close rejected: ${res?.body ?: "no response"}")
        }
        runCatching { micSocket?.close(); handsSocket?.close(); gestureSocket?.close() }
        client.shutdown()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
