# Honest Security Assessment

## Summary

This is a working prototype of hardware-attested audio + video recording. Since 2026-09-12 the
verifier uses **Warden** (`at.asitplus.warden:makoto`) to parse and enforce the Android Key
Attestation extension, which closes the biggest gap of the first version. It still has gaps that
must be addressed before relying on it for anything serious; they are listed below. An external
review of the pre-Warden commit and the point-by-point response are in `REVIEW_RESPONSE.md`.

## What We Built ✅

1. **Android App (Kotlin)** — no INTERNET permission
   - Hardware key generation in Titan M2 (StrongBox) with a verifier-issued attestation challenge
   - Audio recording in 5-second PCM chunks
   - Video recording (Camera2 → H.264) in independent ~5-second MP4 segments
   - ECDSA signature per chunk/segment over a typed, versioned payload (`manifest_version: 4`),
     hash-chained per stream (each payload includes the previous chunk's hash)
   - Signed session record over the ordered chunk list **and** the OS context digest
     (debuggable flag, install source, accessibility services, concurrent recorders, OS build/patch)
   - Release build: not debuggable, signed with a dedicated EC key (`scripts/make-release-keystore.sh`)
   - Manifest export: certificate chain, challenge + source, streams, context, chunk records

2. **Server Verifier (Kotlin/JVM, Warden)**
   - Certificate chain validation to Google's hardware attestation roots + Google revocation list
   - Attestation extension parsing and policy enforcement (see below)
   - Per-chunk hash + ECDSA verification (audio and video) with the *attested* public key
   - Hash-chain check per stream, session-record verification (chunk list + context),
     continuity warnings (gaps, missing indexes, A/V skew), timestamp bounds (challenge issue → verifier clock)
   - Verifier-issued single-use challenges; `--export` to WAV/MP4; `--inspect`

3. **Tests**
   - Warden integration test against a real GrapheneOS Pixel 7a attestation from Warden's
     own test-suite (accepts locked-bootloader GrapheneOS; rejects wrong challenge, wrong
     package, wrong signer, TEE-only key, and unknown boot key)

4. **Documentation**
   - Guides, interactive security slideshow, threat model

## What Is Cryptographically Verified ✅

Verified by Warden from the attestation extension in the leaf certificate, i.e. signed by the
device's hardware attestation key which chains to Google's root:

- ✅ **Challenge**: the key was generated for exactly the challenge in the manifest
- ✅ **StrongBox**: attestation and key security level are `STRONGBOX` (Titan M2)
- ✅ **App identity**: package name `com.attestable.recorder` and the APK signing certificate
  fingerprint (pinned via `--signer`)
- ✅ **Bootloader locked** and **verified boot state**: `Verified` with the vendor key, or
  `SelfSigned` with one of GrapheneOS's published verified-boot keys
- ✅ **Not revoked**: chain checked against Google's attestation revocation list
- ✅ **Signatures**: each audio chunk and video segment verified with the public key Warden attested
- ✅ **Tamper detection**: neither audio nor video can be modified, dropped, inserted or reordered after signing
  (per-chunk signatures + per-stream hash chain + session record)
- ✅ **Context sealed**: the app's OS-state snapshots are digested into the session signature

Observed on the test device (Pixel 10a, GrapheneOS 2026091000, Android 17, bootloader locked):
`STRONGBOX`, package + signer match, `SelfSigned` boot with the GrapheneOS Pixel 10a key,
OS/vendor/boot patch level 2026-09 — strict policy passes with a verifier-issued challenge.
Audio + video run (2026-09-12): 5/5 audio chunks, 6/6 video segments (1280×720 H.264, ~4.65 s
each), session record valid; exported to a playable combined MP4. Tamper tests on the same
recording: one flipped byte in a segment, an ffmpeg re-encode of a segment, a truncated
manifest, and swapped chunk files were each rejected.
Release-build run (manifest v4, signer `70e16aec…`, not debuggable): 4/4 audio, 5/5 video,
session record + context valid, freshness proven; the debug signer was rejected with
"Invalid Application Signature Digest".

## Remaining Gaps ❌

### 1. ~~Challenge freshness~~ (fixed 2026-09-12)

The verifier now issues challenges (`server issue-challenge`): 32 random bytes, time-limited,
stored locally, single-use. The app accepts a challenge in a text field or via an intent extra
(`am start --es challenge <base64>`) and records `challenge_source: "verifier"` in the manifest.
On verification the challenge is looked up in the store; a match proves the key was generated
after the issue time, chunk timestamps are checked against it, and the challenge is consumed.
`--require-issued-challenge` rejects anything else. Verified end to end on the Pixel 10a.

Residual: the store is a local directory, so "issued by the verifier" means "issued by this
verifier instance". A multi-verifier deployment needs a shared store or signed challenges.

### 2. ~~Test device has an unlocked bootloader~~ (fixed 2026-09-12)

The Pixel 10a's bootloader was relocked. It now attests `Bootloader locked: true`,
`Verified boot state: SelfSigned` with the GrapheneOS Pixel 10a verified-boot key, and a fresh
recording passes the **strict** policy end to end (3/3 chunks, STRONGBOX). The
`--allow-unlocked-bootloader` flag remains available for demos on other devices only.

### 3. Video: what a signed segment does and does not prove

A signed MP4 segment proves the encoded frames came out of *this* app on *this* device unmodified.
It does **not** prove the camera saw a real scene: pointing the phone at a screen, or (with far
more effort) physically replacing the sensor, still yields validly signed video. Compared with
the roadmap's Raspberry Pi base, the Pixel raises the cost of feed injection — the sensor is on
an internal MIPI link, not an exposed CSI connector — but the gap is named, not closed.

Segments are video-only; audio is a parallel signed stream aligned by signed timestamps
(typically within a few hundred ms). There is no signed cross-binding between the audio and
video streams beyond the shared recording id and the session record; that is sufficient to
detect substitution of either stream but not to prove lip-sync-level alignment.

### 4. No minimum patch level / OS version policy

Warden supports `patchLevel` and `androidVersion` requirements; the verifier does not set them
yet. Easy to add in `WardenPolicy`.

### 5. No protection against a malicious or modified app

**Problem**: Attestation proves WHERE the signature came from (this app, signed by this key, on
this hardware), not WHAT was signed. Because the APK signer is pinned, a *different* app cannot
produce a valid attestation, but the *legitimate* app could still:

- sign synthesized audio (TTS)
- sign silent audio
- ignore the microphone entirely

**Fix**:
- Reproducible builds + published source so the pinned signer is meaningful to third parties
- Consider Play Integrity / remote attestation of running code
- Multiple independent attestations (other sensors/devices)

### 6. ~~Debug signing key~~ → signer ≠ code (the wall)

Fixed as far as a key file can fix it: `assembleRelease` produces a non-debuggable APK signed by
a dedicated EC key that is not the world-readable Android debug key, and the verifier pins it.

What that does *not* fix (from the external review's evil-twin experiment): tag 709 proves who
holds the signing key, not what code ran. A build modified by whoever holds `release.jks` passes
every check. The route this project should take is an **encumbered signing key**: a key in a
TEE or HSM that only signs reproducible, canonical builds, with the fingerprint published next
to the source. Prerequisites: reproducible build; key moved out of the laptop keystore; Warden
`appVersion` floor so old builds under the same key are rejected. Until then, "the app" in every
claim above means "an APK signed by whoever holds the release keystore".

## Trust Model

Your trust in this system depends on:

1. **Titan M2 Hardware** ✅ (reasonable to trust)
2. **GrapheneOS Integrity** ✅ (verified-boot key checked when bootloader is locked)
3. **Google Attestation Root CA** ✅ (publicly published; revocation list checked)
4. **Certificate Chain + Attestation Extension** ✅ (validated and parsed by Warden)
5. **App Identity** ✅ (package + APK signer pinned)
6. **Challenge Freshness** ✅ (verifier-issued, single-use challenges; `--require-issued-challenge`)
7. **App Logic** ❌ (NOT verified)

## Threat Model

### Protected Against ✅

- **Post-recording tampering**: Can't modify audio without breaking signatures
- **Key extraction**: Private key physically cannot leave Titan M2
- **Software / TEE key substitution**: StrongBox security level is required
- **Impersonation by another app**: package name + signer are attested and pinned
- **Rooted / modified OS** (when bootloader is locked): verified-boot state and key are attested
- **Device impersonation**: Each device has a unique cert chain rooted at Google
- **Revoked devices**: Google revocation list is consulted

### NOT Protected Against ❌

- **Malicious behaviour by the legitimate app**: Could sign anything
- **Stale keys** if the verifier accepts app-chosen challenges (run with `--require-issued-challenge`)
- **Compromised build pipeline / key holder**: a modified APK signed with the release key passes (signer ≠ code)
- **Physical device compromise / firmware backdoors** below StrongBox
- **Supply chain attacks**: Compromised during manufacturing

## Production Checklist

### Done ✅

- [x] Add Warden library (`at.asitplus.warden:makoto:1.1.4`)
- [x] Parse attestation extension properly
- [x] Verify app package name in attestation
- [x] Pin APK signing certificate
- [x] Check StrongBox vs TEE vs software
- [x] Validate attestation challenge
- [x] Bootloader lock state + verified-boot key check (GrapheneOS keys trusted)
- [x] Revocation check against Google's list
- [x] Unit tests against a real GrapheneOS attestation
- [x] Bootloader relocked on the test device; strict policy passes end to end
- [x] Verifier-issued, single-use challenges (freshness) — app + CLI + store
- [x] Video capture as signed MP4 segments; session record over the chunk list; no INTERNET permission
- [x] Per-stream hash chaining and sealed OS context (manifest v4)
- [x] Release build: non-debuggable, dedicated signing key, pinned by the verifier

### Critical (Must Fix)

- [ ] Reproducible builds + encumbered (TEE/HSM) signing key that only signs canonical builds
- [ ] Publish the release signer fingerprint next to the source
- [ ] Gap records written by the app when capture stalls (verifier only infers gaps today)
- [ ] Per-chunk (not just per-session) OS context

### Important (Should Fix)

- [ ] Minimum security patch level policy
- [ ] Remote attestation of running code
- [ ] Biometric authentication for recording
- [ ] Rate limiting / abuse prevention
- [ ] Audit logging

### Nice to Have

- [ ] TEE Interop on-chain registration
- [ ] Multiple device cross-validation
- [ ] Real-time attestation streaming
- [ ] Mobile verifier app

## For Skeptics

**Q: Can I trust recordings from this system?**

A: You can trust that a recording was signed by a key that lives in the Titan M2 of a specific
Google device, was created by an app with the pinned package name and signing key, on an OS
booted with a trusted verified-boot key, and that the audio was not modified afterwards.

You CANNOT trust:
- That the app did the right thing with the microphone
- That the audio is real vs synthesized *before* signing

**Q: What prevents fake recordings?**

A: Nothing at the app level. Attestation proves signature authenticity and origin, not content
authenticity.

**Q: Should I use this for legal evidence?**

A: Not yet. Publish reproducible builds with an encumbered release key first.

## Bottom Line

**Current State**: Real hardware attestation, verified with Warden. Proof of concept with known,
documented gaps.

**Production Ready**: No

**Key Insight**: Attestation proves identity and integrity of the signer, but NOT the correctness
of what's being signed.

## Lessons Learned

- The Maven coordinates are `at.asitplus.warden:makoto` (Warden Supreme). The old
  `at.asitplus:warden` artifact (latest 2.4.3) is a relocation POM pointing there;
  `app.cash.warden` and "3.2.0" never existed.
- Warden requires Kotlin 2.4, which forced a Kotlin/AGP/Gradle upgrade.
- GrapheneOS reports `SelfSigned` verified boot, so the verifier must trust GrapheneOS's
  published boot keys explicitly (`verifiedBootKeys`) — Warden Supreme supports this directly.
- Warden's `allowBootloaderUnlock = true` disables *all* boot checks; never use it outside demos.

## License

MIT - Use at your own risk. Not suitable for production use without the improvements above.

---

**Last Updated**: 2026-09-12
**Status**: Prototype with Warden-verified attestation and documented gaps
**Recommended Use**: Education and demonstration; production only after the critical items
