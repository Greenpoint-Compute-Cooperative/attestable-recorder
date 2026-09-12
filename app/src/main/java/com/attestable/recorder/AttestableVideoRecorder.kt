package com.attestable.recorder

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Verifiable camera: Camera2 → H.264 encoder (surface input) → MediaMuxer, cut into independent
 * MP4 segments of ~[SEGMENT_MS] that each start on a key frame. Every finished segment is hashed
 * and signed by the [ChunkSigner] with the attested hardware key.
 *
 * Segments are video-only; audio is captured in parallel by [AttestableAudioRecorder] and both
 * streams share the recording id and wall-clock timestamps, so the verifier can line them up.
 */
class AttestableVideoRecorder(
    private val context: Context,
    private val signer: ChunkSigner,
    private val recordingId: UUID,
    private val outputDir: File,
    private val previewSurface: Surface?,
    private val onSegment: (ChunkRecord) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "AttestableVideoRecorder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 30
        const val BITRATE = 4_000_000
        const val SEGMENT_MS = 5000
        private const val SEGMENT_US = SEGMENT_MS * 1000L
        /** Ask the encoder for a key frame a little before the target so the cut lands near SEGMENT_MS... */
        private const val REQUEST_SYNC_AFTER_US = SEGMENT_US - 400_000
        /** ...and accept any key frame from that window as the segment boundary. */
        private const val ROTATE_AFTER_US = SEGMENT_US - 500_000

        fun fileName(recordingId: UUID, index: Int) = "${recordingId}_video_$index.mp4"
    }

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val encoderThread = HandlerThread("encoder").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)
    private val cameraExecutor = Executor { cameraHandler.post(it) }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var outputFormat: MediaFormat? = null
    private var sensorOrientation = 0

    // Segment state (touched only on the encoder thread)
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var samplesInSegment = 0
    private var segmentIndex = 0
    private var segmentFile: File? = null
    private var segmentStartPtsUs = -1L
    private var segmentStartWallMs = 0L
    private var firstPtsUs = -1L
    private var firstWallMs = 0L
    private var lastPtsUs = 0L
    private var syncRequested = false
    private val eosLatch = CountDownLatch(1)
    @Volatile private var stopping = false

    /** @return null on success, otherwise an error message. */
    fun start(): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = try {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: return "No camera available"
        } catch (e: Exception) {
            return "Camera enumeration failed: ${e.message}"
        }
        sensorOrientation = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        try {
            setupEncoder()
        } catch (e: Exception) {
            Log.e(TAG, "Encoder setup failed", e)
            return "Encoder setup failed: ${e.message}"
        }

        return try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    onError("Camera error $error")
                }
            }, cameraHandler)
            null
        } catch (e: SecurityException) {
            "Camera permission not granted"
        } catch (e: Exception) {
            "Failed to open camera: ${e.message}"
        }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, SEGMENT_MS / 1000)
        }
        val encoder = MediaCodec.createEncoderByType(MIME)
        encoder.setCallback(encoderCallback, encoderHandler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
    }

    private fun createSession(camera: CameraDevice) {
        val targets = listOfNotNull(inputSurface, previewSurface)
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            targets.map { OutputConfiguration(it) },
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        targets.forEach { addTarget(it) }
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }
                    try {
                        session.setRepeatingRequest(request.build(), null, cameraHandler)
                    } catch (e: Exception) {
                        onError("Capture request failed: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError("Camera session configuration failed")
                }
            }
        )
        camera.createCaptureSession(config)
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputFormat = format
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder error", e)
            onError("Encoder error: ${e.message}")
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

            if (!isConfig && info.size > 0) {
                val buffer = codec.getOutputBuffer(index)!!
                val ptsUs = info.presentationTimeUs
                if (firstPtsUs < 0) {
                    firstPtsUs = ptsUs
                    firstWallMs = System.currentTimeMillis()
                }
                val elapsedUs = if (segmentStartPtsUs < 0) Long.MAX_VALUE else ptsUs - segmentStartPtsUs

                when {
                    muxer == null && !isKey -> {
                        // Cannot start a segment mid-GOP; the first encoder output is always a key frame.
                        Log.w(TAG, "Dropping non-key frame before first segment")
                    }
                    muxer == null || (isKey && elapsedUs >= ROTATE_AFTER_US) -> rotateSegment(ptsUs)
                    !isKey && !syncRequested && elapsedUs >= REQUEST_SYNC_AFTER_US -> {
                        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                        syncRequested = true
                    }
                }

                muxer?.let { m ->
                    val relative = MediaCodec.BufferInfo().apply {
                        set(info.offset, info.size, ptsUs - segmentStartPtsUs, info.flags)
                    }
                    m.writeSampleData(trackIndex, buffer, relative)
                    samplesInSegment++
                    lastPtsUs = ptsUs
                }
            }
            codec.releaseOutputBuffer(index, false)

            if (isEos) {
                finalizeSegment(lastPtsUs + 1_000_000L / FPS)
                eosLatch.countDown()
            }
        }
    }

    private fun rotateSegment(ptsUs: Long) {
        finalizeSegment(ptsUs)
        val format = outputFormat ?: run { onError("Encoder produced frames before its format"); return }
        val file = File(outputDir, fileName(recordingId, segmentIndex))
        val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        m.setOrientationHint(sensorOrientation)
        trackIndex = m.addTrack(format)
        m.start()
        muxer = m
        segmentFile = file
        samplesInSegment = 0
        segmentStartPtsUs = ptsUs
        segmentStartWallMs = firstWallMs + (ptsUs - firstPtsUs) / 1000
        syncRequested = false
    }

    private fun finalizeSegment(endPtsUs: Long) {
        val m = muxer ?: return
        muxer = null
        val file = segmentFile ?: return
        if (samplesInSegment == 0) {
            runCatching { m.release() }
            file.delete()
            return
        }
        try {
            m.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Muxer stop failed for segment $segmentIndex", e)
        } finally {
            m.release()
        }
        val bytes = file.readBytes()
        val durationMs = ((endPtsUs - segmentStartPtsUs) / 1000).toInt().coerceAtLeast(1)
        val record = signer.sign(ChunkType.VIDEO, segmentIndex, file.name, segmentStartWallMs, durationMs, bytes)
        onSegment(record)
        Log.d(TAG, "Signed video segment $segmentIndex (${bytes.size} bytes, $durationMs ms, $samplesInSegment frames)")
        segmentIndex++
    }

    /** Stops capture, drains the encoder, finalizes and signs the last segment. Blocks up to a few seconds. */
    fun stop() {
        if (stopping) return
        stopping = true
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null

        val encoder = codec
        if (encoder != null) {
            runCatching { encoder.signalEndOfInputStream() }
            if (!eosLatch.await(5, TimeUnit.SECONDS)) {
                Log.w(TAG, "Encoder did not signal EOS; finalizing anyway")
                encoderHandler.post { finalizeSegment(lastPtsUs + 1_000_000L / FPS) }
                val done = CountDownLatch(1)
                encoderHandler.post { done.countDown() }
                done.await(2, TimeUnit.SECONDS)
            }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
        runCatching { inputSurface?.release() }
        codec = null
        cameraThread.quitSafely()
        encoderThread.quitSafely()
    }
}
