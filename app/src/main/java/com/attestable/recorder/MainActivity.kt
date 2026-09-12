package com.attestable.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {
    private lateinit var attestationManager: AttestationManager
    private lateinit var audioRecorder: AttestableAudioRecorder

    private lateinit var btnGenerateKey: Button
    private lateinit var btnStartRecording: Button
    private lateinit var btnStopRecording: Button
    private lateinit var btnExportManifest: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvAttestationInfo: TextView

    private var currentManifest: RecordingManifest? = null

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()

        attestationManager = AttestationManager()

        val outputDir = File(getExternalFilesDir(null), "recordings")
        outputDir.mkdirs()

        audioRecorder = AttestableAudioRecorder(attestationManager, outputDir)

        checkPermissions()
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        tvStatus = TextView(this).apply {
            text = "Attestable Audio Recorder\n\nGrapheneOS + Titan M2 Hardware Attestation"
            textSize = 16f
            setPadding(0, 0, 0, 32)
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
            textSize = 12f
            setPadding(0, 32, 0, 0)
        }

        layout.addView(tvStatus)
        layout.addView(btnGenerateKey)
        layout.addView(btnStartRecording)
        layout.addView(btnStopRecording)
        layout.addView(btnExportManifest)

        val scrollView = ScrollView(this)
        scrollView.addView(tvAttestationInfo)
        layout.addView(scrollView)

        setContentView(layout)
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateStatus("Microphone permission granted")
            } else {
                updateStatus("ERROR: Microphone permission required")
            }
        }
    }

    private fun generateAttestationKey() {
        updateStatus("Generating hardware-backed attestation key...")

        // Generate random challenge
        val challenge = ByteArray(32)
        SecureRandom().nextBytes(challenge)

        when (val result = attestationManager.generateAttestationKey(challenge)) {
            is AttestationResult.Success -> {
                updateStatus("✓ Attestation key generated in Titan M2")
                tvAttestationInfo.text = attestationManager.getAttestationInfo()
                btnStartRecording.isEnabled = true
            }
            is AttestationResult.Error -> {
                updateStatus("ERROR: ${result.message}")
            }
        }
    }

    private fun startRecording() {
        when (val result = audioRecorder.startRecording()) {
            is RecordingResult.Success -> {
                updateStatus("⏺ Recording... (audio chunks are being signed)")
                btnStartRecording.isEnabled = false
                btnStopRecording.isEnabled = true
                btnGenerateKey.isEnabled = false
            }
            is RecordingResult.Error -> {
                updateStatus("ERROR: ${result.message}")
            }
        }
    }

    private fun stopRecording() {
        currentManifest = audioRecorder.stopRecording()

        if (currentManifest != null) {
            updateStatus("✓ Recording stopped\n${currentManifest!!.chunks.size} chunks captured and signed")
            btnStopRecording.isEnabled = false
            btnExportManifest.isEnabled = true
        }
    }

    private fun exportManifest() {
        currentManifest?.let { manifest ->
            val manifestJson = audioRecorder.exportManifest(manifest)

            // Save manifest to file
            val manifestFile = File(
                getExternalFilesDir(null),
                "recordings/${manifest.recordingId}_manifest.json"
            )
            manifestFile.writeText(manifestJson)

            updateStatus(
                "✓ Manifest exported to:\n${manifestFile.absolutePath}\n\n" +
                "Send this manifest + audio chunks to a Warden-based verifier"
            )

            // Show preview
            tvAttestationInfo.text = manifestJson
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            tvStatus.text = message
        }
    }
}
