package com.attestable.recorder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Records raw PCM from the microphone and hands every 5-second chunk to the [ChunkSigner].
 * Each chunk is written to `<recordingId>_chunk_<n>.pcm` (16-bit little-endian mono, 44.1 kHz).
 */
class AttestableAudioRecorder(
    private val signer: ChunkSigner,
    private val recordingId: UUID,
    private val outputDir: File,
    private val onChunk: (ChunkRecord) -> Unit,
) {
    companion object {
        private const val TAG = "AttestableAudioRecorder"
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val CHUNK_DURATION_MS = 5000
        private const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)
        private const val BYTES_PER_CHUNK = BYTES_PER_SECOND * CHUNK_DURATION_MS / 1000

        fun fileName(recordingId: UUID, index: Int) = "${recordingId}_chunk_$index.pcm"
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ) * 2

    fun start(): String? {
        if (isRecording) return "Already recording"
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) return "Failed to initialize AudioRecord"
            audioRecord = record
            RecordingContext.ownAudioSessionId = record.audioSessionId
            record.startRecording()
            isRecording = true
            recordingThread = thread(start = true, name = "audio-capture") { recordAudioLoop() }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio", e)
            e.message ?: "Unknown error"
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        recordingThread?.join(5000)
        audioRecord?.apply { stop(); release() }
        audioRecord = null
    }

    private fun recordAudioLoop() {
        val buffer = ByteArray(bufferSize)
        val chunk = ByteArrayOutputStream(BYTES_PER_CHUNK)
        var chunkIndex = 0
        var chunkStartMs = 0L

        fun flush() {
            val data = chunk.toByteArray()
            if (data.isEmpty()) return
            val durationMs = (data.size.toLong() * 1000 / BYTES_PER_SECOND).toInt()
            val name = fileName(recordingId, chunkIndex)
            File(outputDir, name).writeBytes(data)
            onChunk(signer.sign(ChunkType.AUDIO, chunkIndex, name, chunkStartMs, durationMs, data))
            Log.d(TAG, "Signed audio chunk $chunkIndex (${data.size} bytes, $durationMs ms)")
            chunk.reset()
            chunkIndex++
        }

        while (isRecording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read <= 0) continue
            if (chunk.size() == 0) {
                // Chunk starts when its first samples arrive; subtract the buffer's own duration.
                chunkStartMs = System.currentTimeMillis() - read * 1000L / BYTES_PER_SECOND
            }
            chunk.write(buffer, 0, read)
            if (chunk.size() >= BYTES_PER_CHUNK) flush()
        }
        flush()
    }
}
