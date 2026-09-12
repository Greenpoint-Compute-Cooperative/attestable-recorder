# Attestable Audio Recorder

A cryptographically verifiable **audio + video** recorder for GrapheneOS using hardware attestation (Titan M2) and the Warden framework. Every 5-second audio chunk and every video segment is hashed and signed inside the StrongBox; a signed session record seals the chunk list; the verifier proves which device, app and OS produced the bytes.

## Overview

This system provides **cryptographic proof** that audio was recorded on a specific GrapheneOS device, with each audio chunk signed by a hardware-backed key that cannot be extracted from the device.

### Architecture

```
┌──────────────────────────────────┐
│  GrapheneOS Phone (Pixel 9 Pro)  │
│  ┌────────────────────────────┐  │
│  │  Android App               │  │
│  │  1. Generate HW key        │  │
│  │  2. Record microphone      │  │
│  │  3. Sign audio chunks      │  │
│  │  4. Export attestation     │  │
│  └────────────────────────────┘  │
│         Titan M2 Chip            │
└──────────────────────────────────┘
           ↓ (export manifest)
┌──────────────────────────────────┐
│  Verifier Server (Warden)        │
│  1. Verify attestation chain     │
│  2. Validate Titan M2 hardware   │
│  3. Check app identity           │
│  4. Verify audio signatures      │
└──────────────────────────────────┘
```

## Components

### 1. Android App (`app/`)

Kotlin Android application that:
- Generates EC key pair in Android Keystore with hardware attestation
- Uses Titan M2 security chip to store private key (cannot be extracted)
- Records audio from the microphone (PCM, 5-second chunks) and, optionally, video from the camera
  (H.264 in independent ~5-second MP4 segments, each starting on a key frame)
- Signs every chunk / segment with the hardware key: `"ATREC"|4|type|recording_id|index|timestamp|duration|sha256|prev_sha256`
  (hash-chained per stream)
- Signs a session record over the ordered chunk list and the OS-context digest, so a manifest cannot be
  truncated, reordered or have its context edited
- Exports a `manifest_version: 4` JSON with the certificate chain, challenge, context and all chunk records
- Ships **without the INTERNET permission**: the app provably cannot exfiltrate
- Release build is **not debuggable** and signed with a dedicated key (`scripts/make-release-keystore.sh`)

**Key Files:**
- `AttestationManager.kt` - Hardware key generation and attestation
- `SignedChunk.kt` - Wire format (signed payload, chunk records, session record) shared with the verifier
- `AttestableAudioRecorder.kt` - PCM capture, chunked and signed
- `AttestableVideoRecorder.kt` - Camera2 → MediaCodec H.264 → MediaMuxer, cut into signed MP4 segments
- `RecordingSession.kt` - Runs both streams under one recording id, signs the session record, writes the manifest
- `MainActivity.kt` - UI: challenge field, video toggle, camera preview, record/export

### 2. Server Verifier (`server/`)

Kotlin JVM application using [Warden](https://github.com/a-sit-plus/warden-supreme)
(`at.asitplus.warden:makoto`) to:
- Verify the Android Key Attestation certificate chain up to Google's hardware attestation roots
  (including Google's certificate revocation list)
- Parse the attestation extension and enforce a policy on it:
  - attestation challenge matches the one embedded at key generation
  - key lives in **StrongBox** (Titan M2), not the TEE or software
  - package name is `com.attestable.recorder` and the APK signer fingerprint is pinned
  - bootloader is locked and the verified-boot key is either the vendor key or one of
    [GrapheneOS's published verified-boot keys](https://grapheneos.org/install/web#verified-boot-key-hash)
- Verify every audio chunk and video segment signature with the *attested* public key
- Verify the session record (ordered chunk-list digest) so truncation and reordering are detected
- Report stream continuity (missing indexes, gaps, audio/video skew) and export playable media

**Key Files:**
- `Verifier.kt` - Warden-based attestation verification + CLI
- `WardenPolicy.kt` - The policy (package, signer, StrongBox, boot keys) expressed as a Warden configuration
- `Manifest.kt` - Manifest reader (v1–v3) and the signed-payload format
- `ChunkVerifier.kt` - Chunk hashes, chunk signatures, session record, continuity checks
- `Export.kt` - WAV / MP4 export of verified media (uses ffmpeg when available)
- `ChallengeStore.kt` - Verifier-issued, single-use challenges
- `src/test/.../WardenGrapheneOsTest.kt` - Tests against a real GrapheneOS Pixel 7a attestation

## Security Guarantees

When verification succeeds, you have cryptographic proof that:

1. **Hardware Origin**: The signing key was generated in Titan M2 hardware and never left the chip
2. **Device Identity**: The recording came from a specific Google Pixel device
3. **App Integrity**: The recording was made by the specified Android app
4. **Authenticity**: Each audio chunk is cryptographically signed and tamper-evident
5. **Timeliness**: Timestamps are included in signed data

## Setup

### Prerequisites

- GrapheneOS device (tested on Pixel 10a; any Pixel 6 or newer with Titan M2 should work)
- Android SDK 28+
- JDK 17+
- Gradle 8.14 (wrapper included; Kotlin 2.4 and AGP 8.13 are pulled in automatically)

### Build Android App

Use the **release** build for anything you intend to verify. The debug build is debuggable and
signed with the well-known Android debug key; the verifier will pin whatever signer you give it,
but pinning the debug key proves nothing.

```bash
cd attestable-recorder
./scripts/make-release-keystore.sh      # once; writes release.jks + keystore.properties (gitignored) and prints the signer SHA-256
./gradlew :app:assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

For quick development only: `./gradlew :app:assembleDebug` and install `app-debug.apk`.

**Reproducible:** `./gradlew :app:assembleRelease -PunsignedRelease` yields `app-release-unsigned.apk`,
which is byte-for-byte reproducible from the sources (pinned toolchain in `Dockerfile`). See
`REPRODUCIBLE_BUILD.md` for how to check a published release against your own build.
Releases: https://github.com/Greenpoint-Compute-Cooperative/attestable-recorder/releases

### Build Server Verifier

```bash
./gradlew :server:test        # runs the Warden integration tests
./gradlew :server:installDist # creates server/build/install/attestable-verifier/bin/attestable-verifier
./gradlew :server:distZip     # server/build/distributions/attestable-verifier-1.0.0.zip (published with each release)
```

### Get the APK signer fingerprint

Warden pins the APK signing certificate, so the verifier needs its SHA-256:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
# Signer #1 certificate SHA-256 digest: 791f6c6f8303584eed777bf3b8c13664fb519e02eb96adc481c7ad8d791e7641
```

## Usage

### Recording on Phone

1. **Generate Attestation Key**
   - Open app on GrapheneOS device
   - Tap "Generate Attestation Key"
   - Key is created in Titan M2 with attestation certificate

2. **Record**
   - Leave "Record video" checked for audio + video, or untick it for audio only
   - Tap "Start Recording"; the camera preview shows what is being signed
   - Audio is captured in 5-second PCM chunks; video in ~5-second MP4 segments
   - Each chunk / segment is hashed and signed in the Titan M2 as soon as it is complete

3. **Stop & Export**
   - Tap "Stop Recording"
   - Tap "Export Attestation Manifest"
   - Find manifest at: `/storage/emulated/0/Android/data/com.attestable.recorder/files/recordings/`

### Verification on Server

Optional but recommended, before recording: issue a challenge so freshness can be proven.

```bash
server/build/install/attestable-verifier/bin/attestable-verifier issue-challenge --ttl 30
# prints a base64 challenge + an `adb shell am start ... --es challenge ...` one-liner
```

Enter the challenge in the app's text field (or run the printed adb command), then tap
"Generate Attestation Key". Transfer manifest + audio chunks to server, then verify:

```bash
./gradlew :server:run --args="path/to/manifest.json path/to/chunks_dir/ --signer <apk-sha256> --require-issued-challenge"
# or
server/build/install/attestable-verifier/bin/attestable-verifier path/to/manifest.json path/to/chunks_dir/ --signer <apk-sha256>
```

Options:

| Flag | Meaning |
|------|---------|
| `--signer <hex>` | SHA-256 of the APK signing certificate (required, repeatable) |
| `--package <name>` | Expected package name (default `com.attestable.recorder`) |
| `--challenge <base64>` | Override the challenge to check (defaults to the one stored in the manifest) |
| `--require-issued-challenge` | Fail unless the manifest's challenge was issued by this verifier's store |
| `--challenge-store <dir>` | Where issued challenges live (default `./challenges`) |
| `--allow-tee` | Accept TEE keys (default: StrongBox required) |
| `--allow-unlocked-bootloader` | **Demo only.** Disables bootloader-lock and verified-boot checks |
| `--verified-boot-key <hex>` | Trust an extra self-signed verified-boot key |
| `--skip-revocation-check` | Don't fetch Google's revocation list (offline) |
| `--export <dir>` | After success, write `<id>.wav`, a video concat list, and (with ffmpeg) `<id>_video.mp4` + `<id>_combined.mp4` |
| `--inspect <manifest>` | Just print what the attestation record contains, no policy |

Example output (Pixel 10a, GrapheneOS):
```
⚙️  Verifying Android key attestation with Warden
   Policy: package=com.attestable.recorder, strongBox=true, unlockedBootloaderAllowed=false, revocationCheck=true
✅ Attestation verified
   Attestation security level: STRONGBOX
   Key security level:         STRONGBOX
   Challenge: fhSl1bDLwSmgRXZhurmNTksB3CqbaB766+YXerD+HVg=
   App: com.attestable.recorder (versionCode 1)
   APK signer SHA-256: 791f6c6f8303584eed777bf3b8c13664fb519e02eb96adc481c7ad8d791e7641
   Bootloader locked: true
   Verified boot state: SelfSigned
   Verified boot key: d8f879d10419eddc9fcda6280718be763f6bf12299e1f72df3ea8ad8a8eb7f80 (GrapheneOS Pixel 10a)
   OS version: 17.0.0
   OS patch level: 2026-09
   Key origin: GENERATED

🎬 Verifying chunks with the attested key...
   ✅ video 0: valid ( 4662 ms, 2326995 bytes)
   ✅ audio 0: valid ( 5050 ms, 445440 bytes)
   ✅ video 1: valid ( 4654 ms, 2314543 bytes)
   ...
   ✅ session record: signed chunk list matches (11 chunks, no truncation or reordering)

📊 Verification complete: 5/5 audio, 6/6 video chunks valid

✅ VERIFICATION SUCCESSFUL
   Recording ID: abc123
   Valid chunks: 10/10
   Hardware-backed: true
   Package: com.attestable.recorder
```

## Manifest Format

```json
{
  "recording_id": "550e8400-e29b-41d4-a716-446655440000",
  "timestamp": 1726086000000,
  "attestation_chain": "MIICdDCCA...base64...",
  "chunks": [
    {
      "index": 0,
      "timestamp": 1726086005000,
      "audio_hash": "3a4b5c6d...base64...",
      "signature": "7f8e9d0a...base64...",
      "size": 441000
    }
  ]
}
```

## TEE Interop Integration (Optional)

For decentralized verification without trusting a single server, attestations can be registered on-chain using [TEE Interop](https://teleport-computer.github.io/tee-interop/).

This allows:
- No central authority
- Verifiable attestation registry
- Cross-platform TEE verification (Titan M2, SGX, SEV, etc.)

See `tee-interop/` directory for integration scripts (coming soon).

## How It Works

### 1. Key Generation

```kotlin
// Generate EC key in Android Keystore with attestation
val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, PURPOSE_SIGN)
    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
    .setAttestationChallenge(challenge)
    .setIsStrongBoxBacked(true) // Use Titan M2
    .build()

val keyPair = keyPairGenerator.generateKeyPair()
```

The attestation certificate chain proves:
- Key was created in hardware
- Device model and security patch level
- App package name and signature
- Bootloader state

### 2. Audio Chunk Signing

For each 5-second chunk:
```kotlin
val payload = timestamp || chunk_index || audio_hash || recording_id
val signature = sign_with_hardware_key(payload)
```

### 3. Verification

Server uses Warden to:
1. Verify certificate chain up to Google's hardware attestation root CA (+ revocation list)
2. Parse the attestation extension and check challenge, StrongBox, package name, APK signer,
   bootloader lock state and verified-boot key
3. Return the attested public key
4. Verify each audio chunk and video segment against that attested key, then the session record
5. Optionally export the verified media (`--export`) for playback

## Resources

- [Warden Supreme](https://github.com/a-sit-plus/warden-supreme) - Android/iOS key attestation library by A-SIT Plus (Maven: `at.asitplus.warden:makoto`)
- [TEE Interop](https://teleport-computer.github.io/tee-interop/) - Decentralized TEE network
- [Android Key Attestation](https://developer.android.com/privacy-and-security/security-key-attestation)
- [GrapheneOS](https://grapheneos.org/)

## License

MIT

## Security Considerations

- **Bootloader**: The verifier **rejects** unlocked bootloaders by default (Warden checks the root-of-trust in the attestation record). GrapheneOS's own verified-boot keys are trusted, so a relocked GrapheneOS device passes. `--allow-unlocked-bootloader` exists for demos only and disables all boot checks.
- **App Signature**: The APK signing certificate is pinned via `--signer`. Use the release build and its key. Even then, the signer proves *who holds the key*, not *what code ran* (an evil-twin APK with the same key passes); the intended fix is an encumbered TEE/HSM key that only signs reproducible builds. See `REVIEW_RESPONSE.md`.
- **Timestamps**: chunk timestamps come from the phone clock and are treated as ordering evidence. The verifier bounds them below by the challenge issue time and above by its own clock.
- **Challenge Freshness**: Warden proves the key was created with a given challenge; freshness needs the *verifier* to choose it. `server issue-challenge` prints a single-use, time-limited challenge and remembers it; enter it in the app (or inject it with the printed `adb ... --es challenge` command) before generating the key. On verification the challenge is looked up, its issue time is reported as a lower bound on key generation, and it is consumed so it cannot validate a second recording. Use `--require-issued-challenge` to reject recordings whose challenge the verifier did not issue. Self-generated challenges (manifest `challenge_source: "app"`) still verify but are reported as "freshness not proven".
- **Replay Protection**: Verify timestamps are recent
- **Root Detection**: Not needed beyond attestation: a rooted/modified OS shows up as an unlocked bootloader or an unknown verified-boot key.

## Future Enhancements

- [ ] TEE Interop on-chain registration
- [ ] Real-time streaming with live attestation
- [ ] Support for other hardware (iPhone AppAttest, etc.)
- [ ] WebRTC integration for remote attestation
- [ ] Biometric authentication requirement
