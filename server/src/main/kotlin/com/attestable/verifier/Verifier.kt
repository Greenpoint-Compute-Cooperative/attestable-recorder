@file:OptIn(ExperimentalTime::class)

package com.attestable.verifier

import at.asitplus.attestation.AttestationResult
import at.asitplus.attestation.KeyAttestation
import at.asitplus.attestation.Makoto
import at.asitplus.attestation.android.AttestationKeyDescription
import at.asitplus.attestation.android.AuthorizationList
import at.asitplus.attestation.android.VerifiedBootKey
import at.asitplus.attestation.android.androidAttestationExtension
import at.asitplus.attestation.android.closestToRootOrNull
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.PublicKey
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Server-side verifier for attestable audio + video recordings.
 *
 * 1. Warden verifies the Android Key Attestation certificate chain *and* the attestation
 *    extension inside it: trust anchor (Google hardware root), revocation, challenge, package
 *    name, APK signer, security level (StrongBox), bootloader lock / verified-boot key.
 * 2. Only then is the attested public key used to check every chunk (audio PCM, video MP4
 *    segment) and the session record that seals the chunk list.
 */
class AttestationVerifier(
    private val policy: WardenPolicy,
    clock: Clock = Clock.System,
) {
    private val makoto: Makoto = policy.makoto(clock)

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    /** Run Warden's full Android key attestation check against [policy]. */
    fun verifyAttestation(chain: List<X509Certificate>, challenge: ByteArray): KeyAttestation<PublicKey> =
        makoto.android.verifyKeyAttestationBlocking(chain, challenge)

    fun verifyRecording(
        manifest: RecordingManifest,
        chunksDir: File,
        challengeOverride: ByteArray? = null,
        freshness: ChallengeFreshness = ChallengeFreshness.Unknown,
    ): VerificationResult {
        val audioCount = manifest.chunks(ChunkType.AUDIO).size
        val videoCount = manifest.chunks(ChunkType.VIDEO).size
        println("🔍 Verifying recording: ${manifest.recordingId} (manifest v${manifest.manifestVersion})")
        println("📦 Chunks: $audioCount audio" + (if (videoCount > 0) ", $videoCount video segments" else ""))
        println("🔐 Certificate chain: ${manifest.attestationChain.size} certificates")
        manifest.appClaims?.let { println("📱 App-reported (unattested): ${it.optString("device_model")} · ${it.optString("os_build")}") }
        printContext(manifest)

        if (manifest.attestationChain.isEmpty()) {
            return VerificationResult.Failed("Manifest contains no attestation certificate chain")
        }

        val challenge = challengeOverride ?: manifest.attestationChallenge
            ?: return VerificationResult.Failed(
                "Manifest has no attestation_challenge (recorded with an old app version?). " +
                    "Pass --challenge <base64> if you know it."
            )
        when (freshness) {
            is ChallengeFreshness.Issued ->
                println("🕐 Challenge was issued by this verifier at ${freshness.issuedAt} (valid until ${freshness.expiresAt}): " +
                    "a matching attestation proves the key was generated after that moment")
            is ChallengeFreshness.Unknown ->
                println("ℹ️  Challenge is not one this verifier issued (source: ${manifest.challengeSource ?: "unknown"}): freshness is not proven")
            is ChallengeFreshness.Rejected ->
                return VerificationResult.Failed("Challenge rejected: ${freshness.reason}")
        }

        println("\n⚙️  Verifying Android key attestation with Warden")
        println("   Policy: package=${policy.packageName}, strongBox=${policy.requireStrongBox}, " +
            "unlockedBootloaderAllowed=${policy.allowUnlockedBootloader}, revocationCheck=${policy.checkRevocation}")

        val attestation = verifyAttestation(manifest.attestationChain, challenge)
        val attestedKey = attestation.attestedPublicKey
        val details = attestation.details
        if (attestedKey == null || details !is AttestationResult.Android.Verified) {
            val error = details as? AttestationResult.Error
            println("❌ Attestation rejected by Warden")
            if (error != null) printAttestationError(error)
            return VerificationResult.Failed("Attestation rejected: ${error?.explanation ?: details}")
        }

        println("✅ Attestation verified")
        printAttestationSummary(details.androidAttestationExtension, "   ")

        println("\n🎬 Verifying chunks with the attested key...")
        val report = ChunkVerifier.verify(manifest, chunksDir, attestedKey)
        report.results.forEach { r ->
            val c = r.chunk
            val label = "${c.type.wireName} ${c.index}"
            val extra = c.durationMs?.let { " ${it} ms," } ?: ""
            when (val s = r.status) {
                ChunkVerifier.ChunkStatus.Valid -> println("   ✅ $label: valid ($extra ${c.size} bytes)")
                ChunkVerifier.ChunkStatus.FileMissing -> println("   ❌ $label: file not found (${c.file})")
                ChunkVerifier.ChunkStatus.HashMismatch -> println("   ❌ $label: content hash mismatch — file was modified")
                ChunkVerifier.ChunkStatus.BadSignature -> println("   ❌ $label: signature does not verify with the attested key")
                ChunkVerifier.ChunkStatus.BrokenChain -> println("   ❌ $label: hash chain broken — signed prev_hash does not match the previous ${c.type.wireName} chunk")
                is ChunkVerifier.ChunkStatus.Error -> println("   ❌ $label: ${s.message}")
            }
        }
        when (report.sessionValid) {
            true -> println("   ✅ session record: signed chunk list${if (manifest.manifestVersion >= 4) " + context" else ""} matches (${manifest.chunks.size} chunks, no truncation or reordering)")
            false -> println("   ❌ session record: signature does not cover this chunk list (truncated, reordered, or tampered manifest)")
            null -> println("   ⚠️  session record: none (manifest v${manifest.manifestVersion}); truncation of the chunk list is not detectable")
        }
        report.warnings.forEach { println("   ⚠️  $it") }

        val summary = buildString {
            append("${report.valid(ChunkType.AUDIO)}/${report.total(ChunkType.AUDIO)} audio")
            if (report.total(ChunkType.VIDEO) > 0) append(", ${report.valid(ChunkType.VIDEO)}/${report.total(ChunkType.VIDEO)} video")
        }
        println("\n📊 Verification complete: $summary chunks valid")

        // Chunk timestamps come from the (attested) OS clock. They are evidence of ORDER, not of time:
        // the verifier bounds them from both sides instead of trusting them.
        //   lower bound: the challenge issue time (only if this verifier issued the challenge)
        //   upper bound: the verifier's own clock now
        val now = Instant.now()
        val earliest = manifest.chunks.minOfOrNull { it.timestamp }?.let(Instant::ofEpochMilli)
        val latest = manifest.chunks.maxOfOrNull { it.timestamp + (it.durationMs ?: 0) }?.let(Instant::ofEpochMilli)
        if (latest != null && latest.isAfter(now.plus(CLOCK_SKEW))) {
            return VerificationResult.Failed("Chunk timestamp $latest is in the future (verifier clock $now)")
        }
        if (freshness is ChallengeFreshness.Issued) {
            if (earliest != null && earliest.isBefore(freshness.issuedAt.minus(CLOCK_SKEW))) {
                return VerificationResult.Failed("Chunk timestamp $earliest predates challenge issue time ${freshness.issuedAt}")
            }
            println("🕐 Capture window per phone clock: $earliest → $latest; bounded by challenge issue ${freshness.issuedAt} and verification $now")
        } else {
            println("🕐 Capture window per phone clock: $earliest → $latest (upper bound only: verified at $now)")
        }

        return if (report.isFullyVerified) {
            VerificationResult.Success(manifest.recordingId, report, details)
        } else if (!report.allChunksValid) {
            VerificationResult.Failed("Only $summary chunks are valid")
        } else {
            VerificationResult.Failed("Session record signature invalid: chunk list was truncated, reordered or tampered")
        }
    }

    companion object {
        /** Tolerated difference between the phone clock and the verifier clock. */
        val CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    }
}

sealed class VerificationResult {
    data class Success(
        val recordingId: String,
        val report: ChunkVerifier.Report,
        val attestation: AttestationResult.Android.Verified,
    ) : VerificationResult()

    data class Failed(val reason: String) : VerificationResult()
}

// ---------------------------------------------------------------------------------------------
// Reporting helpers
// ---------------------------------------------------------------------------------------------

/**
 * OS context the app observed around the capture (v4). Bound into the session signature, so it is
 * exactly as trustworthy as the app + OS the attestation pins — which is the point: the samples
 * alone never say who else held the microphone.
 */
fun printContext(manifest: RecordingManifest) {
    if (manifest.context.isEmpty()) return
    println("🧭 Recording context (app-observed, sealed by the session signature):")
    manifest.context.forEach { c ->
        val flags = buildList {
            if (c.optBoolean("debuggable")) add("DEBUGGABLE BUILD")
            c.optString("accessibility_services").takeIf { it.isNotEmpty() }?.let { add("accessibility services: $it") }
            val others = c.optInt("other_active_recorders", -1)
            if (others > 0) add("$others other app(s) recording audio")
        }
        println("   ${c.optString("phase")}: ${c.optString("device_model")} · ${c.optString("os_build")} · patch ${c.optString("security_patch")} · " +
            "installed by ${c.opt("install_source")?.takeIf { it != org.json.JSONObject.NULL } ?: "adb/sideload"}" +
            (if (flags.isEmpty()) "" else "\n      ⚠️  " + flags.joinToString("; ")))
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Print the attested facts humans care about. Everything here was parsed from the attestation extension. */
fun printAttestationSummary(ext: AttestationKeyDescription, indent: String = "") {
    val hw: AuthorizationList = ext.hardwareEnforced
    val sw: AuthorizationList = ext.softwareEnforced

    println("${indent}Attestation version: ${ext.attestationVersion}, KeyMint version: ${ext.keyMintVersion}")
    println("${indent}Attestation security level: ${ext.attestationSecurityLevel.name}")
    println("${indent}Key security level:         ${ext.keyMintSecurityLevel.name}")
    println("${indent}Challenge: ${Base64.getEncoder().encodeToString(ext.attestationChallenge)}")

    val appId = (sw.attestationApplicationId ?: hw.attestationApplicationId)?.getOrNull()
    if (appId != null) {
        appId.packageInfos.forEach { println("${indent}App: ${it.packageName} (versionCode ${it.version})") }
        appId.signatureDigests.forEach { println("${indent}APK signer SHA-256: ${it.toHex()}") }
    } else {
        println("${indent}App: <attestationApplicationId missing>")
    }

    val rot = (hw.rootOfTrust ?: sw.rootOfTrust)?.getOrNull()
    if (rot != null) {
        val keyHex = rot.verifiedBootKeyDigest.toHex()
        val known = GrapheneOsBootKeys.deviceNameFor(keyHex)?.let { " (GrapheneOS $it)" } ?: ""
        println("${indent}Bootloader locked: ${rot.deviceLocked}")
        println("${indent}Verified boot state: ${rot.verifiedBootState.name}")
        println("${indent}Verified boot key: $keyHex$known")
        rot.verifiedBootHash?.let { println("${indent}Verified boot hash: ${it.toHex()}") }
    } else {
        println("${indent}Root of trust: <missing>")
    }

    (hw.osVersion ?: sw.osVersion)?.getOrNull()?.let { println("${indent}OS version: ${it.major}.${it.minor}.${it.sub}") }
    (hw.osPatchLevel ?: sw.osPatchLevel)?.getOrNull()?.let { println("${indent}OS patch level: ${it.year}-${"%02d".format(it.month.ordinal + 1)}") }
    (hw.vendorPatchLevel ?: sw.vendorPatchLevel)?.getOrNull()?.let { println("${indent}Vendor patch level: ${it.year}-${"%02d".format(it.month.ordinal + 1)}") }
    (hw.bootPatchLevel ?: sw.bootPatchLevel)?.getOrNull()?.let { println("${indent}Boot patch level: ${it.year}-${"%02d".format(it.month.ordinal + 1)}") }
    (hw.origin ?: sw.origin)?.getOrNull()?.let { println("${indent}Key origin: ${it.name}") }
}

private fun printAttestationError(error: AttestationResult.Error) {
    println("   Reason: ${error.explanation}")
    generateSequence(error.cause as Throwable?) { it.cause }
        .drop(1)
        .forEach { println("   Caused by: ${it::class.simpleName}: ${it.message}") }

    val text = generateSequence(error.cause as Throwable?) { it.cause }
        .joinToString(" ") { it.message ?: "" } + " " + error.explanation
    val hints = buildList {
        if (Regex("bootloader|verified boot|device.?locked|boot key", RegexOption.IGNORE_CASE).containsMatchIn(text))
            add("Bootloader/verified-boot policy failed. Relock the bootloader for real attestation, or pass --allow-unlocked-bootloader for a demo run (this disables the boot checks).")
        if (Regex("strong.?box|security level", RegexOption.IGNORE_CASE).containsMatchIn(text))
            add("Key is not StrongBox-backed. Pass --allow-tee to accept TEE keys.")
        if (Regex("signature digest|signer|fingerprint|SIGNATURE", RegexOption.IGNORE_CASE).containsMatchIn(text))
            add("APK signer mismatch. Get the fingerprint with `apksigner verify --print-certs app.apk` and pass it via --signer.")
        if (Regex("package", RegexOption.IGNORE_CASE).containsMatchIn(text))
            add("Package name mismatch. Check --package.")
        if (Regex("challenge", RegexOption.IGNORE_CASE).containsMatchIn(text))
            add("Challenge mismatch. The manifest's attestation_challenge (or --challenge) must equal the one used at key generation.")
        if (Regex("revocation|revoked|connect|timeout|resolve", RegexOption.IGNORE_CASE).containsMatchIn(text))
            add("Revocation-list check failed (network?). Pass --skip-revocation-check to verify offline.")
    }
    hints.forEach { println("   💡 $it") }
    println("   💡 Run with --inspect <manifest> to print what the attestation record actually contains.")
}

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------

private const val USAGE = """
Usage:
  verifier issue-challenge [--ttl <minutes>] [--challenge-store <dir>]
  verifier <manifest.json> <chunks_dir> --signer <sha256-hex> [options]
  verifier --inspect <manifest.json>

Freshness flow:
  1. `verifier issue-challenge` prints a fresh challenge (and remembers it in the store).
  2. Enter it in the app before "Generate Attestation Key" (or inject it with the printed adb command).
  3. Verify as usual. A challenge from the store proves the key was generated after it was issued,
     and is consumed so it cannot validate a second recording.

Options:
  --signer <hex>                 SHA-256 of the APK signing certificate (repeatable, required)
                                 e.g. from: apksigner verify --print-certs app.apk
  --package <name>               Expected package name (default: com.attestable.recorder)
  --challenge <base64>           Override the challenge to check (default: attestation_challenge in the manifest)
  --challenge-store <dir>        Where issued challenges are kept (default: ./challenges)
  --require-issued-challenge     Fail unless the manifest's challenge came from this verifier's store
  --ttl <minutes>                issue-challenge: validity window (default: 30)
  --allow-tee                    Accept TEE-backed keys (default: StrongBox / Titan M2 required)
  --allow-unlocked-bootloader    DEMO ONLY: accept unlocked bootloaders (disables verified-boot checks)
  --verified-boot-key <hex>      Additional trusted SELF_SIGNED verified-boot key (repeatable)
  --no-grapheneos-keys           Do not pre-trust GrapheneOS's published verified-boot keys
  --skip-revocation-check        Do not fetch Google's attestation revocation list (offline)
  --export <dir>                 After a successful verification, write playable WAV / MP4 (uses ffmpeg if present)
  --inspect                      Only parse and print the attestation extension; apply no policy
"""

fun main(args: Array<String>) {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")

    val positional = mutableListOf<String>()
    val signers = mutableSetOf<ByteArray>()
    val extraBootKeys = mutableSetOf<VerifiedBootKey>()
    var packageName = WardenPolicy.DEFAULT_PACKAGE_NAME
    var challenge: ByteArray? = null
    var requireStrongBox = true
    var allowUnlockedBootloader = false
    var useGrapheneKeys = true
    var checkRevocation = true
    var inspect = false
    var challengeStoreDir = ChallengeStore.DEFAULT_DIR
    var requireIssuedChallenge = false
    var ttlMinutes = ChallengeStore.DEFAULT_TTL.toMinutes()
    var exportDir: File? = null

    fun fail(message: String): Nothing {
        System.err.println("ERROR: $message")
        System.err.println(USAGE)
        exitProcess(2)
    }

    var i = 0
    fun next(flag: String): String = args.getOrNull(++i) ?: fail("$flag requires a value")
    while (i < args.size) {
        when (val a = args[i]) {
            "--signer" -> signers += runCatching { WardenPolicy.parseFingerprint(next(a)) }.getOrElse { fail(it.message ?: "bad --signer") }
            "--package" -> packageName = next(a)
            "--challenge" -> challenge = runCatching { Base64.getDecoder().decode(next(a)) }.getOrElse { fail("--challenge must be base64") }
            "--allow-tee" -> requireStrongBox = false
            "--allow-unlocked-bootloader" -> allowUnlockedBootloader = true
            "--verified-boot-key" -> extraBootKeys += VerifiedBootKey.Digest(WardenPolicy.parseFingerprint(next(a)))
            "--no-grapheneos-keys" -> useGrapheneKeys = false
            "--skip-revocation-check" -> checkRevocation = false
            "--inspect" -> inspect = true
            "--challenge-store" -> challengeStoreDir = File(next(a))
            "--require-issued-challenge" -> requireIssuedChallenge = true
            "--ttl" -> ttlMinutes = next(a).toLongOrNull() ?: fail("--ttl must be a number of minutes")
            "--export" -> exportDir = File(next(a))
            "-h", "--help" -> { println(USAGE); exitProcess(0) }
            else -> if (a.startsWith("--")) fail("Unknown option $a") else positional += a
        }
        i++
    }

    if (positional.firstOrNull() == "issue-challenge") {
        val record = ChallengeStore(challengeStoreDir).issue(Duration.ofMinutes(ttlMinutes))
        println("🎲 Issued attestation challenge (valid ${ttlMinutes} min, until ${record.expiresAt}):")
        println()
        println("   ${record.base64}")
        println()
        println("   Enter it in the app's challenge field before tapping \"Generate Attestation Key\", or inject it:")
        println("   adb shell am start -n com.attestable.recorder/.MainActivity --es challenge '${record.base64}'")
        println()
        println("   Stored in ${challengeStoreDir.absolutePath}")
        exitProcess(0)
    }

    if (inspect) {
        val manifestFile = File(positional.getOrNull(0) ?: fail("--inspect needs a manifest path"))
        if (!manifestFile.exists()) fail("Manifest not found: ${manifestFile.absolutePath}")
        val manifest = RecordingManifest.parse(manifestFile)
        println("🔍 Manifest: ${manifestFile.name} (v${manifest.manifestVersion}, recording ${manifest.recordingId})")
        println("   ${manifest.chunks(ChunkType.AUDIO).size} audio chunks, ${manifest.chunks(ChunkType.VIDEO).size} video segments, " +
            "session record: ${if (manifest.sessionSignature != null) "present" else "absent"}")
        manifest.streams?.let { println("   streams (app-claimed): $it") }
        println("🔐 Certificate chain (${manifest.attestationChain.size} certificates):")
        manifest.attestationChain.forEachIndexed { idx, c -> println("   [$idx] ${c.subjectX500Principal.name}  ←  ${c.issuerX500Principal.name}") }
        println("📎 attestation_challenge in manifest: ${manifest.attestationChallenge?.let { Base64.getEncoder().encodeToString(it) } ?: "<absent>"} (source: ${manifest.challengeSource ?: "unknown"})")
        // Same selection rule Warden uses: the certificate closest to the root that carries the extension.
        val ext = manifest.attestationChain
            .closestToRootOrNull { it.androidAttestationExtension != null }
            ?.androidAttestationExtension
        if (ext == null) {
            println("❌ No Android Key Attestation extension found in the chain")
            exitProcess(1)
        }
        println("\n📄 Android Key Attestation extension (unverified — no policy applied):")
        printAttestationSummary(ext, "   ")
        ChunkVerifier.continuityWarnings(manifest).forEach { println("⚠️  $it") }
        exitProcess(0)
    }

    if (positional.size < 2) fail("manifest and chunks directory are required")
    val manifestFile = File(positional[0])
    val chunksDir = File(positional[1])
    if (!manifestFile.exists()) fail("Manifest not found: ${manifestFile.absolutePath}")
    if (!chunksDir.isDirectory) fail("Chunks directory not found: ${chunksDir.absolutePath}")
    if (signers.isEmpty()) fail("at least one --signer fingerprint is required (the APK signing certificate SHA-256)")

    val policy = WardenPolicy(
        packageName = packageName,
        signerFingerprints = signers,
        requireStrongBox = requireStrongBox,
        allowUnlockedBootloader = allowUnlockedBootloader,
        verifiedBootKeys = (if (useGrapheneKeys) GrapheneOsBootKeys.digests else emptySet()) + extraBootKeys + VerifiedBootKey.OEM,
        checkRevocation = checkRevocation,
    )
    if (allowUnlockedBootloader) {
        println("⚠️  --allow-unlocked-bootloader: bootloader lock, verified-boot state and boot-key checks are DISABLED. Demo use only.")
    }

    val manifest = RecordingManifest.parse(manifestFile)

    val store = ChallengeStore(challengeStoreDir)
    val effectiveChallenge = challenge ?: manifest.attestationChallenge
    val issued = effectiveChallenge?.let(store::lookup)
    val freshness: ChallengeFreshness = when {
        issued == null && requireIssuedChallenge ->
            ChallengeFreshness.Rejected("challenge is not in the verifier's store (${challengeStoreDir.absolutePath})")
        issued == null -> ChallengeFreshness.Unknown
        issued.isUsed -> ChallengeFreshness.Rejected("challenge was already used by recording ${issued.usedBy} at ${issued.usedAt}")
        issued.isExpired() -> ChallengeFreshness.Rejected("challenge expired at ${issued.expiresAt}")
        else -> ChallengeFreshness.Issued(issued.issuedAt, issued.expiresAt)
    }

    when (val result = AttestationVerifier(policy).verifyRecording(manifest, chunksDir, challenge, freshness)) {
        is VerificationResult.Success -> {
            if (issued != null) store.markUsed(issued, result.recordingId)
            println("\n✅ VERIFICATION SUCCESSFUL")
            println("   Recording ID: ${result.recordingId}")
            println("   Freshness: " + when (freshness) {
                is ChallengeFreshness.Issued -> "key generated after ${freshness.issuedAt} (verifier-issued challenge, now consumed)"
                else -> "NOT proven (challenge was not issued by this verifier)"
            })
            println("   Audio: ${result.report.valid(ChunkType.AUDIO)}/${result.report.total(ChunkType.AUDIO)} chunks valid")
            if (result.report.total(ChunkType.VIDEO) > 0)
                println("   Video: ${result.report.valid(ChunkType.VIDEO)}/${result.report.total(ChunkType.VIDEO)} segments valid")
            println("   Session record: ${when (result.report.sessionValid) { true -> "valid"; false -> "INVALID"; null -> "absent (v${manifest.manifestVersion})" }}")
            println("   Key security level: ${result.attestation.androidAttestationExtension.keyMintSecurityLevel.name}")
            exportDir?.let { dir ->
                println("\n📤 Exporting verified media to ${dir.absolutePath}")
                val out = Export.export(manifest, chunksDir, dir)
                out.wav?.let { println("   audio: ${it.name}") }
                out.concatList?.let { println("   video concat list: ${it.name}") }
                out.video?.let { println("   video: ${it.name}") }
                out.combined?.let { println("   combined: ${it.name}") }
                out.notes.forEach { println("   ℹ️  $it") }
            }
            exitProcess(0)
        }
        is VerificationResult.Failed -> {
            println("\n❌ VERIFICATION FAILED")
            println("   Reason: ${result.reason}")
            exitProcess(1)
        }
    }
}
