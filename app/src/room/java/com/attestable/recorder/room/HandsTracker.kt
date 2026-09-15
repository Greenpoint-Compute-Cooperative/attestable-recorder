package com.attestable.recorder.room

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand camera: MediaPipe Hand Landmarker on device → the room's guest cursor protocol
 * ({"type":"cursors"} for dwell-to-click, {"type":"flyhands"} for the pinch camera), same
 * mapping as vibecode-room's own /hands page. Cursors are emitted from a 20 Hz timer so the
 * wall's liveness contract (a frame every tick, even when empty) holds between detections.
 */
class HandsTracker(context: Context, private val mirror: Boolean, private val onError: (String) -> Unit) {
    companion object {
        private const val TAG = "HandsTracker"
        const val MODEL = "models/hand_landmarker.task"
    }

    private val pinch = arrayOf(CursorMapper.PinchState(), CursorMapper.PinchState())
    @Volatile private var lastCursors: JSONArray = JSONArray()
    @Volatile private var lastFly: JSONObject? = null
    @Volatile var handsSeen = 0; private set
    @Volatile var framesAnalysed = 0L; private set

    private val landmarker: HandLandmarker = HandLandmarker.createFromOptions(
        context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e -> Log.w(TAG, "hand landmarker error", e); onError("hands: ${e.message}") }
            .build(),
    )

    fun analyse(bitmap: Bitmap, rotationDegrees: Int, timestampMs: Long) {
        val options = ImageProcessingOptions.builder().setRotationDegrees(rotationDegrees).build()
        landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), options, timestampMs)
    }

    private fun onResult(result: HandLandmarkerResult) {
        framesAnalysed++
        val cursors = JSONArray()
        val fly = JSONArray()
        val hands = result.landmarks()
        handsSeen = hands.size
        // Portrait frames: landmark x spans the narrow axis; aspect corrects the pinch ratio.
        val aspect = 3f / 4f
        hands.forEachIndexed { i, lm ->
            if (i >= 2) return@forEachIndexed
            val pts = lm.map { CursorMapper.Point(it.x(), it.y()) }
            val palm = CursorMapper.palmCentroid(pts)
            val zone = CursorMapper.toZone(palm.x, palm.y, mirror)
            val ratio = CursorMapper.pinchRatio(pts, aspect)
            val engaged = pinch[i].update(ratio)
            cursors.put(JSONObject().put("id", i).put("x", zone.x.toDouble()).put("y", zone.y.toDouble()).put("engaged", engaged))
            val handedness = result.handednesses().getOrNull(i)?.firstOrNull()?.categoryName()
            fly.put(JSONObject()
                .put("id", i + 1)
                .put("hand", handedness ?: JSONObject.NULL)
                .put("x", (if (mirror) 1.0 - palm.x else palm.x.toDouble()))
                .put("y", palm.y.toDouble())
                .put("pinch", Math.round(ratio * 10000.0) / 10000.0)
                .put("pinching", engaged)
                .put("conf", 1.0))
        }
        lastCursors = cursors
        lastFly = JSONObject().put("type", "flyhands").put("t", System.nanoTime() / 1e9).put("aspect", aspect.toDouble()).put("hands", fly)
    }

    /** The frames to send this tick: cursors always (liveness), flyhands when a detection exists. */
    fun framesForTick(): List<String> {
        val out = mutableListOf(JSONObject().put("type", "cursors").put("cursors", lastCursors).toString())
        lastFly?.let { out += it.toString() }
        return out
    }

    fun close() = runCatching { landmarker.close() }
}
