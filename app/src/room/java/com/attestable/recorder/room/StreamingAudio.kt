package com.attestable.recorder.room

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.attestable.recorder.RecordingContext
import kotlin.concurrent.thread

/**
 * Microphone → the room's `/api/mic` socket in the format its own browser mic uses: 16 kHz mono
 * little-endian Int16 PCM, 4096-sample frames. Every frame that goes out is also appended to the
 * audio [FrameChunker], so the signed 5-second chunks are exactly the bytes the room consumed.
 */
class StreamingAudio(
    private val socket: RoomClient.ReconnectingSocket,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "StreamingAudio"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 4096
        const val CHUNK_MS = 5000L
        const val BYTES_PER_CHUNK = SAMPLE_RATE * 2 * (CHUNK_MS / 1000).toInt()
    }

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(): String? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, FRAME_SAMPLES * 4))
        } catch (e: SecurityException) {
            return "microphone permission missing"
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) return "AudioRecord failed to initialize"
        record = r
        RecordingContext.ownAudioSessionId = r.audioSessionId
        r.startRecording()
        running = true
        thread = thread(name = "room-audio") { loop() }
        return null
    }

    private fun loop() {
        val frame = ByteArray(FRAME_SAMPLES * 2)
        var dropped = 0
        while (running) {
            val n = record?.read(frame, 0, frame.size) ?: break
            if (n <= 0) continue
            val bytes = if (n == frame.size) frame.copyOf() else frame.copyOf(n)
            if (!socket.sendBinary(bytes)) {
                // Not connected / not "ready" yet: the room cannot have received these bytes, so
                // they must not be signed either. Dropping keeps sent == signed.
                dropped++
                if (dropped % 50 == 1) Log.w(TAG, "mic socket not ready; dropped $dropped frames")
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        record?.runCatching { stop(); release() }
        record = null
    }
}
