@file:OptIn(ExperimentalTime::class)

package com.attestable.verifier

import org.json.JSONObject
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The HTTP attestation service, exercised in-process against the real GrapheneOS Pixel 7a fixture. */
class ServeTest {
    private val chainB64: List<String> = javaClass.getResourceAsStream("/grapheneos-pixel7a-chain.b64")!!
        .bufferedReader().readLines().filter { it.isNotBlank() }
    private val chain: List<X509Certificate> = CertificateFactory.getInstance("X.509").let { cf ->
        chainB64.map { cf.generateCertificate(Base64.getDecoder().decode(it).inputStream()) as X509Certificate }
    }
    private val challengeB64 = "erlGxbI+3t23T2O9V7+Pvfmz3I2TRMMTIBrLxDI3M+4="
    private val fixtureClock = object : Clock { override fun now(): Instant = Instant.fromEpochMilliseconds(1774256732076L) }
    private val policy = WardenPolicy(
        packageName = "at.asitplus.atttest",
        signerFingerprints = setOf(WardenPolicy.parseFingerprint("34b9762c4d6c90d48431940c57bde7314258b26420efe16ac7f7274f0d330ad5")),
        requireStrongBox = false,
        checkRevocation = false,
    )
    private val store = ChallengeStore(Files.createTempDirectory("challenges").toFile())
    private val serve = Serve(policy, store, Duration.ofMinutes(5), fixtureClock)

    private fun concatenatedChain() = Base64.getEncoder().encodeToString(chain.flatMap { it.encoded.toList() }.toByteArray())

    @Test
    fun `attest returns the attested key and facts`() {
        val res = serve.attest(JSONObject().put("attestation_chain", concatenatedChain()).put("attestation_challenge", challengeB64))
        assertTrue(res.getBoolean("ok"), res.toString())
        assertEquals(chain.first().publicKey.encoded.toList(), Base64.getDecoder().decode(res.getString("attested_public_key_spki")).toList())
        val facts = res.getJSONObject("facts")
        assertEquals("TRUSTED_ENVIRONMENT", facts.getString("key_security_level"))
        assertEquals(true, facts.getBoolean("bootloader_locked"))
        assertEquals("GrapheneOS Pixel 7a", facts.getString("verified_boot_device"))
        assertEquals("at.asitplus.atttest", facts.getJSONArray("packages").getJSONObject(0).getString("name"))
        assertTrue(res.isNull("freshness"), "challenge was not issued by this store")
    }

    @Test
    fun `attest rejects a wrong challenge and a foreign challenge when required`() {
        val wrong = serve.attest(JSONObject().put("attestation_chain", concatenatedChain()).put("attestation_challenge", Base64.getEncoder().encodeToString(ByteArray(32) { 1 })))
        assertFalse(wrong.getBoolean("ok"))
        val foreign = serve.attest(JSONObject().put("attestation_chain", concatenatedChain()).put("attestation_challenge", challengeB64).put("require_issued_challenge", true))
        assertFalse(foreign.getBoolean("ok"))
        assertTrue(foreign.getString("error").contains("not issued"))
    }

    @Test
    fun `http round trip serves health and challenge`() {
        val server = serve.start("127.0.0.1", 0)
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val health = JSONObject(java.net.URL("$base/health").readText())
            assertTrue(health.getBoolean("ok"))
            assertEquals("at.asitplus.atttest", health.getJSONObject("policy").getString("package"))
            val conn = java.net.URL("$base/challenge").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.outputStream.use { it.write(ByteArray(0)) }
            val ch = JSONObject(conn.inputStream.readBytes().toString(Charsets.UTF_8))
            assertEquals(32, Base64.getDecoder().decode(ch.getString("challenge")).size)
            assertTrue(store.lookup(Base64.getDecoder().decode(ch.getString("challenge"))) != null)
        } finally {
            server.stop(0)
        }
    }
}
