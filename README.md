# Attestable Audio Recorder

A cryptographically verifiable audio recording system for GrapheneOS using hardware attestation (Titan M2) and the Warden framework.

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
- Records audio from microphone
- Signs 5-second audio chunks with hardware key
- Exports attestation manifest with certificate chain

**Key Files:**
- `AttestationManager.kt` - Hardware key generation and attestation
- `AttestableAudioRecorder.kt` - Audio recording with chunk signing
- `MainActivity.kt` - UI for recording and exporting

### 2. Server Verifier (`server/`)

Kotlin JVM application using Warden to:
- Verify Android key attestation certificate chains
- Validate keys are stored in hardware (not software)
- Verify audio chunk signatures
- Confirm recording authenticity

**Key Files:**
- `Verifier.kt` - Warden-based attestation verification

## Security Guarantees

When verification succeeds, you have cryptographic proof that:

1. **Hardware Origin**: The signing key was generated in Titan M2 hardware and never left the chip
2. **Device Identity**: The recording came from a specific Google Pixel device
3. **App Integrity**: The recording was made by the specified Android app
4. **Authenticity**: Each audio chunk is cryptographically signed and tamper-evident
5. **Timeliness**: Timestamps are included in signed data

## Setup

### Prerequisites

- GrapheneOS device (tested on Pixel 9 Pro XL)
- Android SDK 28+
- JDK 17+
- Gradle

### Build Android App

```bash
cd attestable-recorder
./gradlew :app:assembleDebug
```

Install on phone:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Build Server Verifier

```bash
./gradlew :server:build
```

## Usage

### Recording on Phone

1. **Generate Attestation Key**
   - Open app on GrapheneOS device
   - Tap "Generate Attestation Key"
   - Key is created in Titan M2 with attestation certificate

2. **Record Audio**
   - Tap "Start Recording"
   - Audio is captured in 5-second chunks
   - Each chunk is signed with hardware key

3. **Stop & Export**
   - Tap "Stop Recording"
   - Tap "Export Attestation Manifest"
   - Find manifest at: `/storage/emulated/0/Android/data/com.attestable.recorder/files/recordings/`

### Verification on Server

Transfer manifest + audio chunks to server, then verify:

```bash
./gradlew :server:run --args="path/to/manifest.json path/to/chunks_dir/"
```

Example output:
```
🔍 Verifying recording manifest: abc123_manifest.json
📋 Recording ID: abc123
📦 Chunks: 10

⚙️  Verifying Android key attestation with Warden...
✅ Attestation verified:
   - Hardware-backed: true
   - Package: com.attestable.recorder
   - Security level: STRONG_BOX

🎵 Verifying audio chunk signatures...
   ✅ Chunk 0: Valid (441000 bytes)
   ✅ Chunk 1: Valid (441000 bytes)
   ...

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
1. Verify certificate chain up to Google root CA
2. Check hardware backing (StrongBox = Titan M2)
3. Validate app identity
4. Verify each chunk signature against public key

## Resources

- [Warden](https://github.com/amiller/warden) - Android/iOS key attestation library
- [TEE Interop](https://teleport-computer.github.io/tee-interop/) - Decentralized TEE network
- [Android Key Attestation](https://developer.android.com/privacy-and-security/security-key-attestation)
- [GrapheneOS](https://grapheneos.org/)

## License

MIT

## Security Considerations

- **Bootloader**: GrapheneOS allows bootloader unlock. Warden verifier currently accepts this. For maximum security, relock bootloader after installation.
- **App Signature**: Current implementation doesn't verify app signature. For production, pin expected app signing certificate.
- **Root Detection**: Add root/integrity checks if needed
- **Replay Protection**: Verify timestamps are recent
- **Challenge Freshness**: Use server-provided challenges for key generation

## Future Enhancements

- [ ] TEE Interop on-chain registration
- [ ] Real-time streaming with live attestation
- [ ] Support for other hardware (iPhone AppAttest, etc.)
- [ ] WebRTC integration for remote attestation
- [ ] Biometric authentication requirement
