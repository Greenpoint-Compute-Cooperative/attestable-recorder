package com.attestable.recorder.room

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure cursor math, a port of vibecode-room's guest page (src/server/hands-page.ts) so a phone
 * behaves exactly like a laptop guest:
 *  - cursor = centroid of the palm landmarks (wrist + finger-base knuckles), x mirrored,
 *    mapped through the central "interaction zone" inset so a comfortable arm sweep reaches
 *    the whole wall;
 *  - engage = pinch ratio dist(thumb_tip, index_tip) / dist(wrist, middle_mcp) with the room's
 *    hysteresis (ON < 0.30 for 2 frames, OFF > 0.45 for 3 frames).
 */
object CursorMapper {
    const val INSET_X_MIN = 0.18f
    const val INSET_X_MAX = 0.82f
    const val INSET_Y_MIN = 0.12f
    const val INSET_Y_MAX = 0.70f
    const val PINCH_ON = 0.30f
    const val PINCH_OFF = 0.45f
    const val PINCH_ON_FRAMES = 2
    const val PINCH_OFF_FRAMES = 3
    val PALM = intArrayOf(0, 5, 9, 13, 17)

    data class Point(val x: Float, val y: Float)

    fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    /** Mirrors x, then maps the inset zone onto [0,1]². */
    fun toZone(x: Float, y: Float, mirror: Boolean = true): Point {
        val mx = if (mirror) 1f - x else x
        return Point(
            clamp01((mx - INSET_X_MIN) / (INSET_X_MAX - INSET_X_MIN)),
            clamp01((y - INSET_Y_MIN) / (INSET_Y_MAX - INSET_Y_MIN)),
        )
    }

    fun palmCentroid(landmarks: List<Point>): Point {
        var sx = 0f; var sy = 0f
        for (i in PALM) { sx += landmarks[i].x; sy += landmarks[i].y }
        return Point(sx / PALM.size, sy / PALM.size)
    }

    /** dist(4,8) / dist(0,9), aspect-corrected so a foreshortened palm does not fake a pinch. */
    fun pinchRatio(landmarks: List<Point>, aspect: Float): Float {
        fun d(a: Int, b: Int) = hypot((landmarks[a].x - landmarks[b].x) * aspect, landmarks[a].y - landmarks[b].y)
        val palm = d(0, 9)
        return if (palm < 1e-4f) 9f else d(4, 8) / palm
    }

    /** Per-hand hysteresis state, frame-debounced like the guest page. */
    class PinchState {
        var engaged = false; private set
        private var onVotes = 0
        private var offVotes = 0
        fun update(ratio: Float): Boolean {
            if (engaged) {
                offVotes = if (ratio > PINCH_OFF) offVotes + 1 else 0
                if (offVotes >= PINCH_OFF_FRAMES) { engaged = false; offVotes = 0; onVotes = 0 }
            } else {
                onVotes = if (ratio < PINCH_ON) onVotes + 1 else 0
                if (onVotes >= PINCH_ON_FRAMES) { engaged = true; onVotes = 0; offVotes = 0 }
            }
            return engaged
        }
    }

    /** Body-pose "gesture camera": a raised wrist is the cursor, engaged when clearly above the shoulder. */
    fun poseCursor(wrist: Point, shoulder: Point, mirror: Boolean = true): Pair<Point, Boolean> {
        val engaged = wrist.y < shoulder.y - 0.05f
        return toZone(wrist.x, wrist.y, mirror) to engaged
    }

    fun nearlyEqual(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) < eps
}
