package com.attestable.recorder.room

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One Camera2 stream shared by the trackers: YUV frames at a modest analysis size, converted to
 * RGB bitmaps for MediaPipe. A frame is skipped while the previous one is still being analysed.
 */
class CameraFeed(
    private val context: Context,
    private val previewSurface: Surface?,
    private val useFrontCamera: Boolean,
    private val onFrame: (bitmap: Bitmap, rotationDegrees: Int, timestampMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "CameraFeed"
        const val WIDTH = 640
        const val HEIGHT = 480
    }

    private val thread = HandlerThread("room-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val busy = AtomicBoolean(false)
    var sensorOrientation = 0; private set
    var facingFront = false; private set

    fun start(): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wanted = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted }
            ?: manager.cameraIdList.firstOrNull() ?: return "no camera"
        val chars = manager.getCameraCharacteristics(id)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        facingFront = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val r = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = r
        return try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; configure(camera) }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); device = null; onError("camera error $error") }
            }, handler)
            null
        } catch (e: SecurityException) {
            "camera permission missing"
        } catch (e: Exception) {
            "camera open failed: ${e.message}"
        }
    }

    private fun configure(camera: CameraDevice) {
        val targets = listOfNotNull(reader?.surface, previewSurface)
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, targets.map { OutputConfiguration(it) }, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { targets.forEach { addTarget(it) } }
                    runCatching { s.setRepeatingRequest(req.build(), null, handler) }.onFailure { onError("capture request failed: ${it.message}") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) = onError("camera session configuration failed")
            })
        camera.createCaptureSession(config)
    }

    private fun onImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (!busy.compareAndSet(false, true)) return
            val bitmap = YuvToBitmap.convert(image)
            val ts = SystemClock.uptimeMillis()
            try {
                onFrame(bitmap, sensorOrientation, ts)
            } finally {
                busy.set(false)
            }
        } catch (e: Exception) {
            busy.set(false)
            Log.w(TAG, "frame conversion failed", e)
        } finally {
            image.close()
        }
    }

    fun stop() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        thread.quitSafely()
    }
}

/** YUV_420_888 → ARGB_8888, honouring row and pixel strides (Pixels deliver semi-planar chroma). */
object YuvToBitmap {
    fun convert(image: Image): Bitmap {
        val w = image.width; val h = image.height
        val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride; val uvRow = uPlane.rowStride; val uvPix = uPlane.pixelStride
        val out = IntArray(w * h)
        val yBytes = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
        val uBytes = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
        val vBytes = ByteArray(vBuf.remaining()).also { vBuf.get(it) }
        var o = 0
        for (row in 0 until h) {
            val yBase = row * yRow
            val uvBase = (row / 2) * uvRow
            for (col in 0 until w) {
                val yv = (yBytes[yBase + col].toInt() and 0xff) - 16
                val uvIndex = uvBase + (col / 2) * uvPix
                val u = (uBytes.getOrElse(uvIndex) { 128.toByte() }.toInt() and 0xff) - 128
                val v = (vBytes.getOrElse(uvIndex) { 128.toByte() }.toInt() and 0xff) - 128
                val y1192 = 1192 * maxOf(yv, 0)
                var r = (y1192 + 1634 * v) shr 10
                var g = (y1192 - 833 * v - 400 * u) shr 10
                var b = (y1192 + 2066 * u) shr 10
                r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)
                out[o++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
