package com.attestable.verifier

import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Verifier-issued attestation challenges.
 *
 * Warden proves that a key was generated *with* a given challenge. Only if the verifier itself
 * chose that challenge — unpredictably, and shortly before the recording — does that also prove
 * the key (and therefore every chunk signed with it) is *fresh*. This store is the verifier's
 * memory of which challenges it issued, when, and whether they have been consumed.
 *
 * One JSON file per challenge, keyed by the URL-safe base64 of the challenge bytes.
 */
class ChallengeStore(val dir: File) {

    data class Record(
        val challenge: ByteArray,
        val issuedAt: Instant,
        val expiresAt: Instant,
        val usedAt: Instant?,
        val usedBy: String?,
    ) {
        val isUsed get() = usedAt != null
        fun isExpired(now: Instant = Instant.now()) = now.isAfter(expiresAt)
        val base64: String get() = Base64.getEncoder().encodeToString(challenge)
    }

    private val random = SecureRandom()

    /** Create and persist a fresh 32-byte challenge valid for [ttl]. */
    fun issue(ttl: Duration, now: Instant = Instant.now()): Record {
        val bytes = ByteArray(32).also(random::nextBytes)
        val record = Record(bytes, now, now.plus(ttl), null, null)
        write(record)
        return record
    }

    fun lookup(challenge: ByteArray): Record? {
        val f = fileFor(challenge)
        if (!f.exists()) return null
        val j = JSONObject(f.readText())
        return Record(
            challenge = Base64.getDecoder().decode(j.getString("challenge")),
            issuedAt = Instant.parse(j.getString("issued_at")),
            expiresAt = Instant.parse(j.getString("expires_at")),
            usedAt = j.optString("used_at", "").takeIf { it.isNotEmpty() }?.let(Instant::parse),
            usedBy = j.optString("used_by", "").takeIf { it.isNotEmpty() },
        )
    }

    /** Consume a challenge so it can never validate a second recording. */
    fun markUsed(record: Record, recordingId: String, now: Instant = Instant.now()): Record =
        record.copy(usedAt = now, usedBy = recordingId).also(::write)

    private fun write(record: Record) {
        dir.mkdirs()
        val j = JSONObject()
            .put("challenge", record.base64)
            .put("issued_at", record.issuedAt.toString())
            .put("expires_at", record.expiresAt.toString())
        record.usedAt?.let { j.put("used_at", it.toString()) }
        record.usedBy?.let { j.put("used_by", it) }
        fileFor(record.challenge).writeText(j.toString(2))
    }

    private fun fileFor(challenge: ByteArray) =
        File(dir, Base64.getUrlEncoder().withoutPadding().encodeToString(challenge) + ".json")

    companion object {
        val DEFAULT_DIR = File("challenges")
        val DEFAULT_TTL: Duration = Duration.ofMinutes(30)
    }
}

/** Outcome of matching a manifest's challenge against the verifier's store. */
sealed class ChallengeFreshness {
    /** Issued by this verifier, unexpired and unused: key generation happened after [issuedAt]. */
    data class Issued(val issuedAt: Instant, val expiresAt: Instant) : ChallengeFreshness()

    /** Not in the store: the app chose it, or another verifier issued it. Freshness unknown. */
    object Unknown : ChallengeFreshness()

    data class Rejected(val reason: String) : ChallengeFreshness()
}
