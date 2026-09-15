package com.attestable.recorder.room

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.attestable.recorder.AttestationManager

/**
 * Credible sensor for vibecode-room. Automation-friendly: every field can be preset from the
 * launch intent and `--ez autostart true` starts the session immediately, e.g.
 *
 *   adb shell am start -n com.attestable.recorder.room/com.attestable.recorder.room.RoomActivity \
 *     --es room_url http://127.0.0.1:8787 --ez audio true --ez hands true --ez gesture true --ez autostart true
 */
class RoomActivity : AppCompatActivity(), RoomSession.Listener {
    private lateinit var attestationManager: AttestationManager
    private lateinit var etUrl: EditText
    private lateinit var etName: EditText
    private lateinit var etWall: EditText
    private lateinit var cbAudio: CheckBox
    private lateinit var cbHands: CheckBox
    private lateinit var cbGesture: CheckBox
    private lateinit var cbFront: CheckBox
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var preview: SurfaceView
    private var previewReady = false
    private var session: RoomSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attestationManager = AttestationManager(filesDir)
        buildUi()
        intent?.let {
            it.getStringExtra("room_url")?.let(etUrl::setText)
            it.getStringExtra("name")?.let(etName::setText)
            it.getStringExtra("wall")?.let(etWall::setText)
            if (it.hasExtra("audio")) cbAudio.isChecked = it.getBooleanExtra("audio", true)
            if (it.hasExtra("hands")) cbHands.isChecked = it.getBooleanExtra("hands", true)
            if (it.hasExtra("gesture")) cbGesture.isChecked = it.getBooleanExtra("gesture", false)
            if (it.hasExtra("front")) cbFront.isChecked = it.getBooleanExtra("front", false)
        }
        requestPermissionsIfNeeded()
        if (intent?.getBooleanExtra("autostart", false) == true) preview.postDelayed({ startSession() }, 800)
    }

    private fun buildUi() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        tvStatus = TextView(this).apply { text = "Credible sensor for vibecode-room\nStreams signed audio / hand / gesture data; the room verifies every chunk."; textSize = 15f; setPadding(0, 0, 0, 16) }
        etUrl = EditText(this).apply { hint = "Room URL"; setText("http://127.0.0.1:8787"); isSingleLine = true; textSize = 13f }
        etName = EditText(this).apply { hint = "Guest name shown on the wall"; setText("Pixel"); isSingleLine = true; textSize = 13f }
        etWall = EditText(this).apply { hint = "Wall (A/B)"; setText("A"); isSingleLine = true; textSize = 13f }
        cbAudio = CheckBox(this).apply { text = "Audio recorder → /api/mic (16 kHz PCM, signed 5 s chunks)"; isChecked = true }
        cbHands = CheckBox(this).apply { text = "Hand camera → /hands/ws (MediaPipe hands, signed 2 s windows)"; isChecked = true }
        cbGesture = CheckBox(this).apply { text = "Gesture camera → /hands/ws?stream=gesture (body pose)"; isChecked = false }
        cbFront = CheckBox(this).apply { text = "Use front camera"; isChecked = false }
        btnStart = Button(this).apply { text = "Start credible sensor"; setOnClickListener { startSession() } }
        btnStop = Button(this).apply { text = "Stop + seal session"; isEnabled = false; setOnClickListener { stopSession() } }
        tvStats = TextView(this).apply { textSize = 12f; setPadding(0, 16, 0, 0) }
        preview = SurfaceView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.widthPixels - 64) * 3 / 4)
            holder.setFixedSize(CameraFeed.WIDTH, CameraFeed.HEIGHT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { previewReady = true }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) { previewReady = false }
            })
        }
        listOf(tvStatus, etUrl, etName, etWall, cbAudio, cbHands, cbGesture, cbFront, btnStart, btnStop, preview).forEach(layout::addView)
        layout.addView(ScrollView(this).apply { addView(tvStats) })
        setContentView(layout)
    }

    private fun missing() = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun requestPermissionsIfNeeded() {
        val m = missing()
        if (m.isNotEmpty()) ActivityCompat.requestPermissions(this, m.toTypedArray(), 1)
    }

    private fun startSession() {
        if (session != null) return
        if (missing().isNotEmpty()) { onError("missing permissions: ${missing().joinToString()}"); requestPermissionsIfNeeded(); return }
        val cfg = RoomSession.Config(
            roomUrl = etUrl.text.toString().trim(), wall = etWall.text.toString().trim().ifEmpty { "A" },
            audio = cbAudio.isChecked, hands = cbHands.isChecked, gesture = cbGesture.isChecked,
            frontCamera = cbFront.isChecked, guestName = etName.text.toString().trim().ifEmpty { "Pixel" },
        )
        val surface = if ((cfg.hands || cfg.gesture) && previewReady) preview.holder.surface else null
        session = RoomSession(this, attestationManager, cfg, surface, this).also { it.start() }
        btnStart.isEnabled = false; btnStop.isEnabled = true
        listOf(etUrl, etName, etWall, cbAudio, cbHands, cbGesture, cbFront).forEach { it.isEnabled = false }
    }

    private fun stopSession() {
        val s = session ?: return
        btnStop.isEnabled = false
        onStatus("stopping — signing the session record…")
        Thread {
            s.stop()
            runOnUiThread {
                session = null
                btnStart.isEnabled = true
                listOf(etUrl, etName, etWall, cbAudio, cbHands, cbGesture, cbFront).forEach { it.isEnabled = true }
            }
        }.start()
    }

    override fun onStatus(message: String) = runOnUiThread { tvStatus.text = message }
    override fun onError(message: String) = runOnUiThread { tvStatus.text = "ERROR: $message" }
    override fun onStats(stats: RoomSession.Stats) = runOnUiThread {
        tvStats.text = buildString {
            appendLine("source: ${stats.sourceId ?: "—"}  verified: ${stats.verified ?: "—"}")
            appendLine("signed chunks — audio ${stats.audioChunks} · hands ${stats.handsChunks} · gesture ${stats.gestureChunks}")
            appendLine("room verdicts — accepted ${stats.accepted} · pending ${stats.pending} · rejected ${stats.rejected}")
            appendLine("tracking — hands seen ${stats.handsSeen} · people seen ${stats.peopleSeen}")
            stats.lastRejection?.let { appendLine("last rejection: $it") }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.let { s -> Thread { s.stop() }.start() }
    }
}
