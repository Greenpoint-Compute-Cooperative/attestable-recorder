package com.attestable.recorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {
    private lateinit var attestationManager: AttestationManager
    private lateinit var outputDir: File

    private lateinit var etChallenge: EditText
    private lateinit var cbVideo: CheckBox
    private lateinit var preview: SurfaceView
    private lateinit var btnGenerateKey: Button
    private lateinit var btnStartRecording: Button
    private lateinit var btnStopRecording: Button
    private lateinit var btnExportManifest: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvAttestationInfo: TextView

    private var session: RecordingSession? = null
    private var previewReady = false

    companion object {
        private const val REQUEST_PERMISSIONS = 1001

        /** Intent extra carrying a verifier-issued challenge (base64), e.g. via `adb shell am start --es challenge ...`. */
        const val EXTRA_CHALLENGE = "challenge"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        attestationManager = AttestationManager(filesDir)
        outputDir = File(getExternalFilesDir(null), "recordings").also { it.mkdirs() }
        checkPermissions()
        applyChallengeFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyChallengeFromIntent(intent)
    }

    /** A verifier can hand the challenge to the app without typing: `am start --es challenge <base64>`. */
    private fun applyChallengeFromIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_CHALLENGE)?.takeIf { it.isNotBlank() }?.let {
            etChallenge.setText(it.trim())
            updateStatus("Verifier challenge received. Tap \"Generate Attestation Key\".")
        }
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        tvStatus = TextView(this).apply {
            text = "Attestable Recorder\n\nGrapheneOS + Titan M2 hardware attestation · audio + video"
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }

        etChallenge = EditText(this).apply {
            hint = "Verifier-issued challenge (base64) — leave empty to self-generate"
            textSize = 12f
            isSingleLine = true
        }

        cbVideo = CheckBox(this).apply {
            text = "Record video (camera) as signed MP4 segments"
            isChecked = true
        }

        // 16:9 preview; the camera session needs a fixed-size surface matching the encoder.
        preview = SurfaceView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
                // height = width * 9/16 is set once we know the width; start with a sane default
                height = (resources.displayMetrics.widthPixels - 64) * 9 / 16
            }
            holder.setFixedSize(AttestableVideoRecorder.WIDTH, AttestableVideoRecorder.HEIGHT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { previewReady = true }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) { previewReady = false }
            })
        }

        btnGenerateKey = Button(this).apply {
            text = "1. Generate Attestation Key"
            setOnClickListener { generateAttestationKey() }
        }

        btnStartRecording = Button(this).apply {
            text = "2. Start Recording"
            setOnClickListener { startRecording() }
            isEnabled = false
        }

        btnStopRecording = Button(this).apply {
            text = "3. Stop Recording"
            setOnClickListener { stopRecording() }
            isEnabled = false
        }

        btnExportManifest = Button(this).apply {
            text = "4. Export Attestation Manifest"
            setOnClickListener { exportManifest() }
            isEnabled = false
        }

        tvAttestationInfo = TextView(this).apply {
            text = ""
            textSize = 11f
            setPadding(0, 24, 0, 0)
        }

        layout.addView(tvStatus)
        layout.addView(etChallenge)
        layout.addView(cbVideo)
        layout.addView(btnGenerateKey)
        layout.addView(btnStartRecording)
        layout.addView(btnStopRecording)
        layout.addView(btnExportManifest)
        layout.addView(preview)

        val scrollView = ScrollView(this)
        scrollView.addView(tvAttestationInfo)
        layout.addView(scrollView)

        setContentView(layout)
    }

    private fun missingPermissions(): List<String> =
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun checkPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
            updateStatus(if (denied.isEmpty()) "Permissions granted" else "Missing permissions: ${denied.joinToString()}")
        }
    }

    private fun generateAttestationKey() {
        updateStatus("Generating hardware-backed attestation key...")

        // Prefer a verifier-issued challenge: only then does the attestation prove freshness.
        val typed = etChallenge.text.toString().trim()
        val (challenge, source) = if (typed.isNotEmpty()) {
            val decoded = try {
                Base64.decode(typed, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                updateStatus("ERROR: challenge is not valid base64")
                return
            }
            if (decoded.size < 16) {
                updateStatus("ERROR: challenge too short (${decoded.size} bytes)")
                return
            }
            decoded to "verifier"
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) } to "app"
        }

        when (val result = attestationManager.generateAttestationKey(challenge, source)) {
            is AttestationResult.Success -> {
                updateStatus(
                    if (source == "verifier") "✓ Attestation key generated in Titan M2 with the verifier's challenge"
                    else "✓ Attestation key generated in Titan M2 (self-generated challenge: freshness not provable)"
                )
                tvAttestationInfo.text = attestationManager.getAttestationInfo()
                btnStartRecording.isEnabled = true
            }
            is AttestationResult.Error -> updateStatus("ERROR: ${result.message}")
        }
    }

    private fun startRecording() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            updateStatus("ERROR: missing permissions: ${missing.joinToString()}")
            checkPermissions()
            return
        }
        val withVideo = cbVideo.isChecked
        val surface = if (withVideo && previewReady) preview.holder.surface else null
        val s = RecordingSession(this, attestationManager, outputDir, withVideo, surface) { err ->
            updateStatus("ERROR: $err")
        }
        val error = s.start()
        if (error != null) {
            updateStatus("ERROR: $error")
            return
        }
        session = s
        updateStatus("⏺ Recording ${if (withVideo) "audio + video" else "audio"}… every chunk is signed in the Titan M2")
        btnStartRecording.isEnabled = false
        btnStopRecording.isEnabled = true
        btnGenerateKey.isEnabled = false
        cbVideo.isEnabled = false
    }

    private fun stopRecording() {
        val s = session ?: return
        btnStopRecording.isEnabled = false
        updateStatus("Stopping… finalizing and signing the last segment")
        Thread {
            s.stop()
            runOnUiThread {
                updateStatus(
                    "✓ Recording stopped\n" +
                        "${s.chunkCount(ChunkType.AUDIO)} audio chunks, ${s.chunkCount(ChunkType.VIDEO)} video segments signed\n" +
                        "session record signed (${s.sessionSignature.size} bytes)"
                )
                btnExportManifest.isEnabled = true
                cbVideo.isEnabled = true
            }
        }.start()
    }

    private fun exportManifest() {
        val s = session ?: return
        val manifestJson = s.exportManifest()
        val manifestFile = File(outputDir, "${s.recordingId}_manifest.json")
        manifestFile.writeText(manifestJson)
        updateStatus(
            "✓ Manifest exported to:\n${manifestFile.absolutePath}\n\n" +
                "Verify with: server <manifest> <chunks_dir> --signer <apk-sha256>"
        )
        tvAttestationInfo.text = manifestJson
    }

    private fun updateStatus(message: String) {
        runOnUiThread { tvStatus.text = message }
    }
}
