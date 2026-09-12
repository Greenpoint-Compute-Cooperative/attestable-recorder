@file:OptIn(ExperimentalTime::class)

package com.attestable.verifier

import at.asitplus.attestation.AttestationResult
import at.asitplus.attestation.android.VerifiedBootKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exercises the Warden integration against a real GrapheneOS attestation.
 *
 * The certificate chain, challenge and verification time are the Pixel 7a fixture from
 * Warden Supreme's own test-suite (supreme/verifier/src/jvmTest/kotlin/GrapheneOsVerifierTest.kt).
 * The key is TEE-backed (not StrongBox) and the app is A-SIT's test client, so the policy below
 * differs from the recorder's production policy in exactly those two respects.
 */
class WardenGrapheneOsTest {
    private val chain: List<X509Certificate> = run {
        val cf = CertificateFactory.getInstance("X.509")
        javaClass.getResourceAsStream("/grapheneos-pixel7a-chain.b64")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { cf.generateCertificate(Base64.getDecoder().decode(it).inputStream()) as X509Certificate }
    }
    private val challenge = Base64.getDecoder().decode("erlGxbI+3t23T2O9V7+Pvfmz3I2TRMMTIBrLxDI3M+4=")

    /** Fixture was captured 2026-03-23; intermediates in the chain are only valid around then. */
    private val fixtureClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1774256732076L)
    }

    private val policy = WardenPolicy(
        packageName = "at.asitplus.atttest",
        signerFingerprints = setOf(WardenPolicy.parseFingerprint("34b9762c4d6c90d48431940c57bde7314258b26420efe16ac7f7274f0d330ad5")),
        requireStrongBox = false,
        allowUnlockedBootloader = false,
        checkRevocation = false,
    )

    private fun verify(p: WardenPolicy, c: ByteArray = challenge) =
        AttestationVerifier(p, fixtureClock).verifyAttestation(chain, c)

    @Test
    fun `accepts a real GrapheneOS Pixel 7a attestation with a locked bootloader`() {
        val result = verify(policy)
        assertTrue(result.isSuccess, "expected success, got ${result.details}")
        val verified = assertIs<AttestationResult.Android.Verified>(result.details)
        val ext = verified.androidAttestationExtension
        printAttestationSummary(ext, "   ")

        assertEquals("TRUSTED_ENVIRONMENT", ext.keyMintSecurityLevel.name)
        assertTrue(ext.attestationChallenge.contentEquals(challenge))

        val rot = ext.hardwareEnforced.rootOfTrust?.getOrNull() ?: error("root of trust missing")
        assertTrue(rot.deviceLocked, "GrapheneOS fixture has a locked bootloader")
        assertEquals("SelfSigned", rot.verifiedBootState.name, "GrapheneOS signs the OS with its own key")
        assertEquals("Pixel 7a", GrapheneOsBootKeys.deviceNameFor(rot.verifiedBootKeyDigest.toHex()))

        val appId = ext.softwareEnforced.attestationApplicationId?.getOrNull() ?: error("app id missing")
        assertEquals(setOf("at.asitplus.atttest"), appId.packageInfos.map { it.packageName }.toSet())
    }

    @Test
    fun `rejects GrapheneOS boot key when only the vendor key is trusted`() {
        val result = verify(policy.copy(verifiedBootKeys = setOf(VerifiedBootKey.OEM)))
        assertFalse(result.isSuccess, "OEM-only boot policy must reject a self-signed GrapheneOS boot key")
        assertIs<AttestationResult.Error>(result.details)
    }

    @Test
    fun `rejects a wrong challenge`() {
        val result = verify(policy, ByteArray(32) { 0x42 })
        assertFalse(result.isSuccess)
    }

    @Test
    fun `rejects a wrong package name`() {
        assertFalse(verify(policy.copy(packageName = "com.attestable.recorder")).isSuccess)
    }

    @Test
    fun `rejects a wrong APK signer`() {
        assertFalse(verify(policy.copy(signerFingerprints = setOf(ByteArray(32) { 1 }))).isSuccess)
    }

    @Test
    fun `rejects a TEE key when StrongBox is required`() {
        assertFalse(verify(policy.copy(requireStrongBox = true)).isSuccess)
    }

    @Test
    fun `parses a manifest with a concatenated DER chain`() {
        val concatenated = Base64.getEncoder().encodeToString(chain.flatMap { it.encoded.toList() }.toByteArray())
        val parsed = RecordingManifest.decodeCertificateChain(concatenated)
        assertEquals(chain.size, parsed.size)
        assertEquals(chain.first().subjectX500Principal, parsed.first().subjectX500Principal)
    }
}
