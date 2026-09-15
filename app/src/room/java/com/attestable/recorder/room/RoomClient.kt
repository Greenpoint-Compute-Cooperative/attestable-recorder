package com.attestable.recorder.room

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP + WebSocket client for a vibecode-room server's credible-sensor endpoints
 * (docs/credible-sensors.md in that repo). Every byte sent on a socket is also handed to
 * [onSent] so the caller can sign exactly what the room received.
 */
class RoomClient(baseUrl: String) {
    companion object {
        private const val TAG = "RoomClient"
        private val JSON = "application/json".toMediaType()
    }

    val baseUrl = baseUrl.trimEnd('/')
    private val wsBase = this.baseUrl.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    data class HttpResult(val code: Int, val body: JSONObject)

    fun post(path: String, body: JSONObject?): HttpResult {
        val req = Request.Builder().url("$baseUrl$path")
            .post((body?.toString() ?: "{}").toRequestBody(JSON)).build()
        http.newCall(req).execute().use { res -> return HttpResult(res.code, parse(res)) }
    }

    private fun parse(res: Response): JSONObject =
        runCatching { JSONObject(res.body?.string() ?: "{}") }.getOrElse { JSONObject().put("error", "non-JSON response ${res.code}") }

    /**
     * A socket that reconnects, and on the room's "muted" refusal (close 1008) un-mutes and
     * retries — the same contract as the room's own mic.html. [onSent] sees every frame that
     * actually went out, in order, so the caller's chunk bytes equal the room's ledger.
     */
    inner class ReconnectingSocket(
        private val path: String,
        private val onOpen: (ReconnectingSocket) -> Unit,
        private val onSent: (ByteArray) -> Unit,
        private val onStatus: (String) -> Unit,
        /** Binary sockets wait for the room's {"type":"ready"} before sending. */
        private val waitForReady: Boolean,
    ) {
        @Volatile private var socket: WebSocket? = null
        @Volatile private var ready = false
        @Volatile private var closed = false
        private var backoffMs = 1000L

        fun connect() {
            if (closed) return
            val req = Request.Builder().url("$wsBase$path").build()
            http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    backoffMs = 1000
                    ready = !waitForReady
                    onStatus("connected")
                    onOpen(this@ReconnectingSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (msg.optString("type")) {
                        "ready" -> { ready = true; onStatus("ready") }
                        "error" -> onStatus("room: ${msg.optString("reason")}")
                        else -> Unit
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null; ready = false
                    if (closed) return
                    if (code == 1008 && reason == "muted") {
                        onStatus("room is muted — unmuting")
                        runCatching { post("/api/unmute", null) }.onFailure { Log.w(TAG, "unmute failed", it) }
                    }
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null; ready = false
                    if (closed) return
                    onStatus("socket error: ${t.message}")
                    scheduleReconnect()
                }
            })
        }

        private fun scheduleReconnect() {
            val delay = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(10_000)
            Thread {
                Thread.sleep(delay)
                if (!closed) connect()
            }.start()
        }

        val isReady get() = ready && socket != null

        /** Sends if the socket is ready; returns whether the bytes went out (and were recorded). */
        fun sendText(text: String): Boolean {
            val s = socket ?: return false
            if (!ready) return false
            if (!s.send(text)) return false
            onSent((text + "\n").toByteArray(Charsets.UTF_8))
            return true
        }

        fun sendBinary(bytes: ByteArray): Boolean {
            val s = socket ?: return false
            if (!ready) return false
            if (!s.send(bytes.toByteString())) return false
            onSent(bytes)
            return true
        }

        fun close() {
            closed = true
            socket?.close(1000, "done")
            socket = null
        }
    }

    fun openSocket(
        path: String, waitForReady: Boolean, onOpen: (ReconnectingSocket) -> Unit,
        onSent: (ByteArray) -> Unit, onStatus: (String) -> Unit,
    ): ReconnectingSocket = ReconnectingSocket(path, onOpen, onSent, onStatus, waitForReady).also { it.connect() }

    fun shutdown() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

@Suppress("unused")
private fun ByteString.unused() = Unit
