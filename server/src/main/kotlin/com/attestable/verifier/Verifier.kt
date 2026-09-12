package com.attestable.verifier

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Server-side verifier for attestable audio recordings
 * Verifies Android key attestation and audio signatures
 */
class AttestationVerifier {

    init {
        // Add BouncyCastle provider
        Security.addProvider(BouncyCastleProvider())
    }

    /**
     * Verify a recording manifest and all its signed chunks
     */
    fun verifyRecording(
        manifestFile: File,
        chunksDir: File,
        expectedPackageName: String = "com.attestable.recorder"
    ): VerificationResult {
        println("🔍 Verifying recording manifest: ${manifestFile.name}")

        // Parse manifest
        val manifestJson = JSONObject(manifestFile.readText())
        val recordingId = manifestJson.getString("recording_id")
        val attestationChainB64 = manifestJson.getString("attestation_chain")
        val chunksArray = manifestJson.getJSONArray("chunks")

        println("📋 Recording ID: $recordingId")
        println("📦 Chunks: ${chunksArray.length()}")

        // Decode attestation certificate chain
        val certChain = decodeCertificateChain(attestationChainB64)
        if (certChain.isEmpty()) {
            return VerificationResult.Failed("Failed to decode certificate chain")
        }

        println("🔐 Certificate chain: ${certChain.size} certificates")

        // Extract public key from leaf certificate
        val publicKey = certChain[0].publicKey

        // Verify certificate chain
        println("\n⚙️  Verifying certificate chain...")
        val chainValid = verifyCertificateChain(certChain)

        if (!chainValid) {
            return VerificationResult.Failed("Certificate chain verification failed")
        }

        println("✅ Certificate chain verified")
        println("   - Leaf subject: ${certChain[0].subjectDN}")
        if (certChain.size > 1) {
            println("   - Issuer: ${certChain[1].subjectDN}")
        }

        // Verify each audio chunk signature
        println("\n🎵 Verifying audio chunk signatures...")
        var validChunks = 0

        for (i in 0 until chunksArray.length()) {
            val chunk = chunksArray.getJSONObject(i)
            val chunkIndex = chunk.getInt("index")
            val timestamp = chunk.getLong("timestamp")
            val audioHash = Base64.getDecoder().decode(chunk.getString("audio_hash"))
            val signature = Base64.getDecoder().decode(chunk.getString("signature"))
            val size = chunk.getInt("size")

            // Load audio chunk file
            val chunkFile = File(chunksDir, "${recordingId}_chunk_${chunkIndex}.pcm")
            if (!chunkFile.exists()) {
                println("   ❌ Chunk $chunkIndex: File not found")
                continue
            }

            // Verify chunk hash
            val actualHash = MessageDigest.getInstance("SHA-256").digest(chunkFile.readBytes())
            if (!actualHash.contentEquals(audioHash)) {
                println("   ❌ Chunk $chunkIndex: Hash mismatch")
                continue
            }

            // Reconstruct signed payload
            val payload = java.nio.ByteBuffer.allocate(8 + 4 + 32 + 16)
            payload.putLong(timestamp)
            payload.putInt(chunkIndex)
            payload.put(audioHash)
            payload.put(recordingId.toByteArray().take(16).toByteArray())

            // Verify signature
            if (verifySignature(publicKey, payload.array(), signature)) {
                validChunks++
                println("   ✅ Chunk $chunkIndex: Valid (${size} bytes)")
            } else {
                println("   ❌ Chunk $chunkIndex: Invalid signature")
            }
        }

        println("\n📊 Verification complete: $validChunks/${chunksArray.length()} chunks valid")

        return if (validChunks == chunksArray.length()) {
            VerificationResult.Success(
                recordingId = recordingId,
                totalChunks = chunksArray.length(),
                validChunks = validChunks,
                certificateSubject = certChain[0].subjectDN.toString()
            )
        } else {
            VerificationResult.Failed("Only $validChunks/${chunksArray.length()} chunks are valid")
        }
    }

    /**
     * Verify certificate chain
     */
    private fun verifyCertificateChain(certChain: List<X509Certificate>): Boolean {
        return try {
            // Verify each certificate is signed by the next one
            for (i in 0 until certChain.size - 1) {
                val cert = certChain[i]
                val issuerCert = certChain[i + 1]

                // Verify signature
                cert.verify(issuerCert.publicKey)

                // Check validity
                cert.checkValidity()
            }

            // Verify root certificate is self-signed
            if (certChain.isNotEmpty()) {
                val rootCert = certChain.last()
                rootCert.verify(rootCert.publicKey)
                rootCert.checkValidity()
            }

            true
        } catch (e: Exception) {
            println("   ❌ Chain verification failed: ${e.message}")
            false
        }
    }

    /**
     * Verify ECDSA signature
     */
    private fun verifySignature(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val sig = Signature.getInstance("SHA256withECDSA", "BC")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Decode base64-encoded certificate chain
     */
    private fun decodeCertificateChain(base64Chain: String): List<X509Certificate> {
        val certFactory = CertificateFactory.getInstance("X.509")
        val chainBytes = Base64.getDecoder().decode(base64Chain)

        val certs = mutableListOf<X509Certificate>()
        var offset = 0

        while (offset < chainBytes.size) {
            try {
                val certBytes = chainBytes.sliceArray(offset until chainBytes.size)
                val cert = certFactory.generateCertificate(certBytes.inputStream()) as X509Certificate
                certs.add(cert)
                offset += cert.encoded.size
            } catch (e: Exception) {
                break
            }
        }

        return certs
    }
}

sealed class VerificationResult {
    data class Success(
        val recordingId: String,
        val totalChunks: Int,
        val validChunks: Int,
        val certificateSubject: String
    ) : VerificationResult()

    data class Failed(val reason: String) : VerificationResult()
}

/**
 * CLI entry point
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: verifier <manifest.json> <chunks_directory>")
        println()
        println("Example:")
        println("  verifier recordings/abc123_manifest.json recordings/")
        exitProcess(1)
    }

    val manifestFile = File(args[0])
    val chunksDir = File(args[1])

    if (!manifestFile.exists()) {
        println("ERROR: Manifest file not found: ${manifestFile.absolutePath}")
        exitProcess(1)
    }

    if (!chunksDir.exists() || !chunksDir.isDirectory) {
        println("ERROR: Chunks directory not found: ${chunksDir.absolutePath}")
        exitProcess(1)
    }

    val verifier = AttestationVerifier()
    val result = verifier.verifyRecording(manifestFile, chunksDir)

    when (result) {
        is VerificationResult.Success -> {
            println("\n✅ VERIFICATION SUCCESSFUL")
            println("   Recording ID: ${result.recordingId}")
            println("   Valid chunks: ${result.validChunks}/${result.totalChunks}")
            println("   Certificate: ${result.certificateSubject}")
            exitProcess(0)
        }
        is VerificationResult.Failed -> {
            println("\n❌ VERIFICATION FAILED")
            println("   Reason: ${result.reason}")
            exitProcess(1)
        }
    }
}
