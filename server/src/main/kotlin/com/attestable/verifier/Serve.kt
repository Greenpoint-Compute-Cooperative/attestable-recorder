@file:OptIn(ExperimentalTime::class)

package com.attestable.verifier

import at.asitplus.attestation.AttestationResult
import at.asitplus.attestation.android.AttestationKeyDescription
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * `attestable-verifier serve`: the attestation half of the verifier as a small HTTP service, so a
 * non-JVM room (vibecode-room, Bun/TypeScript) can delegate the hardware-attestation check —
 * Warden's chain + policy verification — and do the cheap per-chunk ECDSA checks itself.
 *
 *   GET  /health                → { ok, policy }
 *   POST /challenge             → { challenge, issued_at, expires_at }         (single-use, TTL)
 *   POST /attest  { attestation_chain, attestation_challenge, recording_id? }
 *                               → { ok, attested_public_key_spki, facts, freshness } | { ok:false, error }
 *
 * Binds to 127.0.0.1 by default: the room server is expected to sit on the same machine.
 */
class Serve(
    private val policy: WardenPolicy,
    private val store: ChallengeStore,
    private val challengeTtl: Duration,
    clock: Clock = Clock.System,
) {
    private val verifier = AttestationVerifier(policy, clock)

    fun start(host: String, port: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.createContext("/health") { ex -> json(ex, 200, JSONObject().put("ok", true).put("policy", policyJson())) }
        server.createContext("/challenge") { ex ->
            if (ex.requestMethod != "POST") return@createContext json(ex, 405, err("POST only"))
            val r = store.issue(challengeTtl)
            json(ex, 200, JSONObject().put("challenge", r.base64).put("issued_at", r.issuedAt.toString()).put("expires_at", r.expiresAt.toString()))
        }
        server.createContext("/attest") { ex ->
            if (ex.requestMethod != "POST") return@createContext json(ex, 405, err("POST only"))
            val body = runCatching { JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8)) }.getOrElse {
                return@createContext json(ex, 400, err("body must be JSON"))
            }
            json(ex, 200, attest(body))
        }
        server.executor = null
        server.start()
        return server
    }

    /** Pure request handler, exposed for tests. */
    fun attest(body: JSONObject): JSONObject {
        val chain = runCatching { RecordingManifest.decodeCertificateChain(body.get("attestation_chain")) }.getOrElse {
            return err("attestation_chain: ${it.message}")
        }
        if (chain.isEmpty()) return err("attestation_chain is empty")
        val challenge = runCatching { Base64.getDecoder().decode(body.getString("attestation_challenge")) }.getOrElse {
            return err("attestation_challenge must be base64")
        }
        val recordingId = body.optString("recording_id", "").ifEmpty { "unknown" }

        val issued = store.lookup(challenge)
        val freshness: JSONObject? = when {
            issued == null -> null
            issued.isUsed -> return err("challenge was already used by recording ${issued.usedBy} at ${issued.usedAt}")
            issued.isExpired() -> return err("challenge expired at ${issued.expiresAt}")
            else -> JSONObject().put("issued_at", issued.issuedAt.toString()).put("expires_at", issued.expiresAt.toString())
        }
        if (freshness == null && body.optBoolean("require_issued_challenge", false)) {
            return err("challenge was not issued by this verifier")
        }

        val result = verifier.verifyAttestation(chain, challenge)
        val key = result.attestedPublicKey
        val details = result.details
        if (key == null || details !is AttestationResult.Android.Verified) {
            val e = details as? AttestationResult.Error
            return err("attestation rejected: ${e?.explanation ?: details}")
        }
        if (issued != null) store.markUsed(issued, recordingId)
        return JSONObject()
            .put("ok", true)
            .put("attested_public_key_spki", Base64.getEncoder().encodeToString(key.encoded))
            .put("facts", facts(details.androidAttestationExtension))
            .put("freshness", freshness ?: JSONObject.NULL)
    }

    private fun policyJson() = JSONObject()
        .put("package", policy.packageName)
        .put("signers", JSONArray(policy.signerFingerprints.map { it.toHex() }))
        .put("require_strongbox", policy.requireStrongBox)
        .put("allow_unlocked_bootloader", policy.allowUnlockedBootloader)
        .put("check_revocation", policy.checkRevocation)

    companion object {
        fun err(message: String) = JSONObject().put("ok", false).put("error", message)

        /** The attested facts a UI wants to show, from the attestation extension. Mirrors [printAttestationSummary]. */
        fun facts(ext: AttestationKeyDescription): JSONObject {
            val hw = ext.hardwareEnforced
            val sw = ext.softwareEnforced
            val j = JSONObject()
                .put("attestation_version", ext.attestationVersion)
                .put("attestation_security_level", ext.attestationSecurityLevel.name)
                .put("key_security_level", ext.keyMintSecurityLevel.name)
                .put("challenge", Base64.getEncoder().encodeToString(ext.attestationChallenge))
            (sw.attestationApplicationId ?: hw.attestationApplicationId)?.getOrNull()?.let { app ->
                j.put("packages", JSONArray(app.packageInfos.map { JSONObject().put("name", it.packageName).put("version_code", it.version.toLong()) }))
                j.put("signer_digests", JSONArray(app.signatureDigests.map { it.toHex() }))
            }
            (hw.rootOfTrust ?: sw.rootOfTrust)?.getOrNull()?.let { rot ->
                val keyHex = rot.verifiedBootKeyDigest.toHex()
                j.put("bootloader_locked", rot.deviceLocked)
                j.put("verified_boot_state", rot.verifiedBootState.name)
                j.put("verified_boot_key", keyHex)
                j.put("verified_boot_device", GrapheneOsBootKeys.deviceNameFor(keyHex)?.let { "GrapheneOS $it" } ?: JSONObject.NULL)
                rot.verifiedBootHash?.let { j.put("verified_boot_hash", it.toHex()) }
            }
            (hw.osVersion ?: sw.osVersion)?.getOrNull()?.let { j.put("os_version", "${it.major}.${it.minor}.${it.sub}") }
            (hw.osPatchLevel ?: sw.osPatchLevel)?.getOrNull()?.let { j.put("os_patch_level", "${it.year}-${"%02d".format(it.month.ordinal + 1)}") }
            return j
        }

        private fun json(ex: HttpExchange, status: Int, body: JSONObject) {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("content-type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }
}
