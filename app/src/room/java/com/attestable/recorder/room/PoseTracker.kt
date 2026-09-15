package com.attestable.recorder.room

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gesture camera: MediaPipe Pose Landmarker on device, standing in for the room's depth-camera
 * gesture wall. Each tracked person becomes one cursor: their higher wrist, engaged when raised
 * above the shoulder — the same "point at the wall" grammar the fusion server produces.
 */
class PoseTracker(context: Context, private val mirror: Boolean, private val onError: (String) -> Unit) {
    companion object {
        private const val TAG = "PoseTracker"
        const val MODEL = "models/pose_landmarker_lite.task"
        private const val L_SHOULDER = 11; private const val R_SHOULDER = 12
        private const val L_WRIST = 15; private const val R_WRIST = 16
    }

    @Volatile private var lastCursors: JSONArray = JSONArray()
    @Volatile var peopleSeen = 0; private set
    @Volatile var framesAnalysed = 0L; private set

    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(2)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e -> Log.w(TAG, "pose landmarker error", e); onError("gesture: ${e.message}") }
            .build(),
    )

    fun analyse(bitmap: Bitmap, rotationDegrees: Int, timestampMs: Long) {
        val options = ImageProcessingOptions.builder().setRotationDegrees(rotationDegrees).build()
        landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), options, timestampMs)
    }

    private fun onResult(result: PoseLandmarkerResult) {
        framesAnalysed++
        val cursors = JSONArray()
        val poses = result.landmarks()
        peopleSeen = poses.size
        poses.forEachIndexed { i, lm ->
            if (i >= 2 || lm.size <= R_WRIST) return@forEachIndexed
            fun p(idx: Int) = CursorMapper.Point(lm[idx].x(), lm[idx].y())
            fun vis(idx: Int) = lm[idx].visibility().orElse(1f)
            // The wrist that is higher on screen (smaller y) is the pointing hand.
            val useLeft = vis(L_WRIST) >= 0.5f && (vis(R_WRIST) < 0.5f || lm[L_WRIST].y() < lm[R_WRIST].y())
            val wrist = if (useLeft) p(L_WRIST) else p(R_WRIST)
            val shoulder = if (useLeft) p(L_SHOULDER) else p(R_SHOULDER)
            if ((if (useLeft) vis(L_WRIST) else vis(R_WRIST)) < 0.5f) return@forEachIndexed
            val (zone, engaged) = CursorMapper.poseCursor(wrist, shoulder, mirror)
            cursors.put(JSONObject().put("id", i).put("x", zone.x.toDouble()).put("y", zone.y.toDouble()).put("engaged", engaged))
        }
        lastCursors = cursors
    }

    fun framesForTick(): List<String> = listOf(JSONObject().put("type", "cursors").put("cursors", lastCursors).toString())

    fun close() = runCatching { landmarker.close() }
}
