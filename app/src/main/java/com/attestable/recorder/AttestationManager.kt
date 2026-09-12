package com.attestable.recorder

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Manages hardware-backed key attestation on GrapheneOS
 * Uses Android Keystore + Titan M2 for cryptographic proof
 */
class AttestationManager {
    companion object {
        private const val TAG = "AttestationManager"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "attestable_recorder_key"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    /**
     * Generate a hardware-backed EC key pair with attestation
     * This key will be stored in Titan M2 and cannot be extracted
     */
    fun generateAttestationKey(challenge: ByteArray): AttestationResult {
        try {
            // Delete existing key if present
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }

            // Generate EC key pair with attestation
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                KEYSTORE_PROVIDER
            )

            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).run {
                setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                setDigests(KeyProperties.DIGEST_SHA256)

                // Request hardware attestation with challenge
                setAttestationChallenge(challenge)

                // Require hardware-backed security
                setUserAuthenticationRequired(false) // Set to true for biometric
                setIsStrongBoxBacked(true) // Use Titan M2 if available

                build()
            }

            keyPairGenerator.initialize(parameterSpec)
            val keyPair = keyPairGenerator.generateKeyPair()

            // Get attestation certificate chain
            val certificateChain = keyStore.getCertificateChain(KEY_ALIAS)

            if (certificateChain == null || certificateChain.isEmpty()) {
                return AttestationResult.Error("Failed to retrieve attestation certificate chain")
            }

            // Extract public key
            val publicKey = keyPair.public.encoded

            Log.d(TAG, "Generated attestation key with ${certificateChain.size} certificates")

            return AttestationResult.Success(
                publicKey = publicKey,
                certificateChain = certificateChain,
                challenge = challenge
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate attestation key", e)
            return AttestationResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Sign data with the hardware-backed key
     * Returns signature that can be verified against attestation cert
     */
    fun signData(data: ByteArray): SignatureResult {
        return try {
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                return SignatureResult.Error("Attestation key not found. Call generateAttestationKey() first.")
            }

            val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initSign(privateKey)
            signature.update(data)

            val signatureBytes = signature.sign()

            Log.d(TAG, "Signed ${data.size} bytes")

            SignatureResult.Success(signatureBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign data", e)
            SignatureResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Export attestation certificate chain for verification
     * This can be sent to a server running Warden for validation
     */
    fun exportAttestationChain(): String {
        val certificateChain = keyStore.getCertificateChain(KEY_ALIAS) ?: return ""

        val output = ByteArrayOutputStream()
        certificateChain.forEach { cert ->
            output.write(cert.encoded)
        }

        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Get human-readable attestation info for debugging
     */
    fun getAttestationInfo(): String {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            return "No attestation key found"
        }

        val chain = keyStore.getCertificateChain(KEY_ALIAS)
        val sb = StringBuilder()

        sb.appendLine("Attestation Certificate Chain:")
        chain?.forEachIndexed { index, cert ->
            if (cert is X509Certificate) {
                sb.appendLine("  [$index] Subject: ${cert.subjectDN}")
                sb.appendLine("      Issuer: ${cert.issuerDN}")
                sb.appendLine("      Valid: ${cert.notBefore} to ${cert.notAfter}")
            }
        }

        return sb.toString()
    }
}

sealed class AttestationResult {
    data class Success(
        val publicKey: ByteArray,
        val certificateChain: Array<Certificate>,
        val challenge: ByteArray
    ) : AttestationResult()

    data class Error(val message: String) : AttestationResult()
}

sealed class SignatureResult {
    data class Success(val signature: ByteArray) : SignatureResult()
    data class Error(val message: String) : SignatureResult()
}
