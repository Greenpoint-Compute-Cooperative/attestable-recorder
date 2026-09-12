package com.attestable.recorder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*
import kotlin.concurrent.thread

/**
 * Records audio from microphone and creates cryptographic attestations
 * Each audio chunk is signed with hardware-backed key
 */
class AttestableAudioRecorder(
    private val attestationManager: AttestationManager,
    private val outputDir: File
) {
    companion object {
        private const val TAG = "AttestableAudioRecorder"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 5000 // 5 second chunks
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private val recordingId = UUID.randomUUID().toString()
    private val chunks = mutableListOf<AudioChunkAttestation>()

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * 2

    /**
     * Start recording with attestation
     */
    fun startRecording(): RecordingResult {
        if (isRecording) {
            return RecordingResult.Error("Already recording")
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return RecordingResult.Error("Failed to initialize AudioRecord")
            }

            audioRecord?.startRecording()
            isRecording = true

            // Start recording thread
            recordingThread = thread(start = true) {
                recordAudioLoop()
            }

            Log.d(TAG, "Started recording session: $recordingId")
            return RecordingResult.Success("Recording started")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            return RecordingResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Stop recording and finalize attestation manifest
     */
    fun stopRecording(): RecordingManifest? {
        if (!isRecording) {
            return null
        }

        isRecording = false
        recordingThread?.join(5000)

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        Log.d(TAG, "Stopped recording. Captured ${chunks.size} chunks")

        return RecordingManifest(
            recordingId = recordingId,
            chunks = chunks.toList(),
            attestationInfo = attestationManager.getAttestationInfo(),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Main recording loop - captures and signs chunks
     */
    private fun recordAudioLoop() {
        val buffer = ByteArray(bufferSize)
        val chunkBuffer = mutableListOf<Byte>()
        val samplesPerChunk = (SAMPLE_RATE * CHUNK_DURATION_MS / 1000) * 2 // 16-bit = 2 bytes
        var chunkIndex = 0

        while (isRecording) {
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

            if (bytesRead > 0) {
                // Add to chunk buffer
                chunkBuffer.addAll(buffer.take(bytesRead))

                // If chunk is complete, sign and save it
                if (chunkBuffer.size >= samplesPerChunk) {
                    val chunkData = chunkBuffer.toByteArray()
                    val attestation = createChunkAttestation(chunkData, chunkIndex)
                    chunks.add(attestation)

                    // Save chunk to file
                    saveChunk(chunkData, chunkIndex)

                    chunkBuffer.clear()
                    chunkIndex++
                }
            }
        }

        // Save remaining data
        if (chunkBuffer.isNotEmpty()) {
            val chunkData = chunkBuffer.toByteArray()
            val attestation = createChunkAttestation(chunkData, chunkIndex)
            chunks.add(attestation)
            saveChunk(chunkData, chunkIndex)
        }
    }

    /**
     * Create cryptographic attestation for an audio chunk
     */
    private fun createChunkAttestation(
        audioData: ByteArray,
        chunkIndex: Int
    ): AudioChunkAttestation {
        val timestamp = System.currentTimeMillis()

        // Hash the audio data
        val sha256 = MessageDigest.getInstance("SHA-256")
        val audioHash = sha256.digest(audioData)

        // Create attestation payload
        val payload = ByteBuffer.allocate(
            8 + // timestamp
            4 + // chunk index
            32 + // audio hash
            16   // recording ID (UUID bytes)
        )
        payload.putLong(timestamp)
        payload.putInt(chunkIndex)
        payload.put(audioHash)
        payload.put(recordingId.toByteArray().take(16).toByteArray())

        // Sign with hardware key
        val signatureResult = attestationManager.signData(payload.array())

        val signature = when (signatureResult) {
            is SignatureResult.Success -> signatureResult.signature
            is SignatureResult.Error -> {
                Log.e(TAG, "Failed to sign chunk: ${signatureResult.message}")
                ByteArray(0)
            }
        }

        return AudioChunkAttestation(
            chunkIndex = chunkIndex,
            timestamp = timestamp,
            audioHash = audioHash,
            signature = signature,
            audioDataSize = audioData.size
        )
    }

    /**
     * Save audio chunk to file
     */
    private fun saveChunk(audioData: ByteArray, chunkIndex: Int) {
        try {
            val chunkFile = File(outputDir, "${recordingId}_chunk_${chunkIndex}.pcm")
            FileOutputStream(chunkFile).use { it.write(audioData) }
            Log.d(TAG, "Saved chunk $chunkIndex: ${audioData.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chunk $chunkIndex", e)
        }
    }

    /**
     * Export recording manifest with all attestations
     */
    fun exportManifest(manifest: RecordingManifest): String {
        val json = JSONObject()
        json.put("recording_id", manifest.recordingId)
        json.put("timestamp", manifest.timestamp)
        json.put("attestation_chain", attestationManager.exportAttestationChain())

        val chunksArray = JSONArray()
        manifest.chunks.forEach { chunk ->
            val chunkJson = JSONObject()
            chunkJson.put("index", chunk.chunkIndex)
            chunkJson.put("timestamp", chunk.timestamp)
            chunkJson.put("audio_hash", Base64.encodeToString(chunk.audioHash, Base64.NO_WRAP))
            chunkJson.put("signature", Base64.encodeToString(chunk.signature, Base64.NO_WRAP))
            chunkJson.put("size", chunk.audioDataSize)
            chunksArray.put(chunkJson)
        }
        json.put("chunks", chunksArray)

        return json.toString(2)
    }
}

data class AudioChunkAttestation(
    val chunkIndex: Int,
    val timestamp: Long,
    val audioHash: ByteArray,
    val signature: ByteArray,
    val audioDataSize: Int
)

data class RecordingManifest(
    val recordingId: String,
    val chunks: List<AudioChunkAttestation>,
    val attestationInfo: String,
    val timestamp: Long
)

sealed class RecordingResult {
    data class Success(val message: String) : RecordingResult()
    data class Error(val message: String) : RecordingResult()
}
