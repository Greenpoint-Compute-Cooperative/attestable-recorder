# Quick Start Guide

## Prerequisites

1. **GrapheneOS Device**
   - Pixel 9 Pro XL with GrapheneOS installed ✓ (just flashed!)
   - USB debugging enabled
   - Developer options enabled

2. **Development Tools**
   - Android SDK (install via Android Studio)
   - JDK 17+
   - Gradle (or use Android Studio)

## Step 1: Setup Development Environment

### Install Android Studio

```bash
# macOS
brew install --cask android-studio

# Or download from: https://developer.android.com/studio
```

### Configure SDK

```bash
# Set ANDROID_HOME
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

## Step 2: Build the App

### Option A: Using Android Studio

1. Open Android Studio
2. File → Open → Select `attestable-recorder/` directory
3. Wait for Gradle sync
4. Build → Build Bundle(s) / APK(s) → Build APK(s)

### Option B: Command Line

```bash
cd attestable-recorder

# Download Gradle wrapper (if not present)
# You need Gradle installed first: brew install gradle
gradle wrapper --gradle-version=8.5

# Build debug APK
./gradlew :app:assembleDebug
```

## Step 3: Install on Phone

Connect your GrapheneOS phone via USB:

```bash
# Check connection
adb devices

# Install app
adb install app/build/outputs/apk/debug/app-debug.apk

# Or if already installed:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Step 4: Record Audio with Attestation

On your GrapheneOS phone:

1. **Open "Attestable Recorder" app**

2. **Grant Permissions**
   - Allow microphone access

3. **Generate Attestation Key** (first time only)
   - Tap "1. Generate Attestation Key"
   - Key is created in Titan M2 hardware
   - View attestation certificate chain

4. **Start Recording**
   - Tap "2. Start Recording"
   - Speak or play audio
   - Audio is captured in 5-second chunks
   - Each chunk is cryptographically signed

5. **Stop Recording**
   - Tap "3. Stop Recording"
   - Recording finalized

6. **Export Manifest**
   - Tap "4. Export Attestation Manifest"
   - Manifest saved with all signed chunks

## Step 5: Transfer Files to Computer

```bash
# Find recordings directory
adb shell ls /sdcard/Android/data/com.attestable.recorder/files/recordings/

# Pull all files
adb pull /sdcard/Android/data/com.attestable.recorder/files/recordings/ ./recordings/
```

You should see:
- `{recording-id}_manifest.json` - Attestation manifest
- `{recording-id}_chunk_0.pcm` - Audio chunk 0
- `{recording-id}_chunk_1.pcm` - Audio chunk 1
- etc.

## Step 6: Verify Recording

### Build Server Verifier

```bash
cd attestable-recorder

# Build verifier
./gradlew :server:build

# Or create executable
./gradlew :server:installDist
```

### Run Verification

The verifier pins the APK signing certificate, so first get its SHA-256:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

Then verify (Warden needs internet once to fetch Google's attestation revocation list;
add `--skip-revocation-check` to run offline):

```bash
# Using gradle run
./gradlew :server:run --args="recordings/YOUR_RECORDING_ID_manifest.json recordings/ --signer <sha256-from-apksigner>"

# Or using built executable
./server/build/install/attestable-verifier/bin/attestable-verifier \
    recordings/YOUR_RECORDING_ID_manifest.json \
    recordings/ \
    --signer <sha256-from-apksigner>

# Just look at what the attestation record says, without applying any policy
./server/build/install/attestable-verifier/bin/attestable-verifier --inspect recordings/YOUR_RECORDING_ID_manifest.json
```

If your GrapheneOS device still has an **unlocked bootloader**, strict verification fails with
`Bootloader not locked`. Relock it (GrapheneOS install guide, "Locking the bootloader") or, for
a demo only, add `--allow-unlocked-bootloader`.

### Expected Output

```
🔍 Verifying recording: abc123-456-789
📦 Chunks: 5
🔐 Certificate chain: 5 certificates

⚙️  Verifying Android key attestation with Warden
   Policy: package=com.attestable.recorder, strongBox=true, unlockedBootloaderAllowed=false, revocationCheck=true
✅ Attestation verified
   Attestation version: 300, KeyMint version: 300
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

🎵 Verifying audio chunk signatures with the attested key...
   ✅ Chunk 0: valid (441000 bytes)
   ✅ Chunk 1: valid (441000 bytes)
   ✅ Chunk 2: valid (441000 bytes)
   ✅ Chunk 3: valid (441000 bytes)
   ✅ Chunk 4: valid (441000 bytes)

📊 Verification complete: 5/5 chunks valid

✅ VERIFICATION SUCCESSFUL
   Recording ID: abc123-456-789
   Valid chunks: 5/5
   Key security level: STRONGBOX
```

## Step 7: Play Back Audio (Optional)

Convert PCM to WAV for playback:

```bash
# Install ffmpeg
brew install ffmpeg

# Convert chunk to WAV
ffmpeg -f s16le -ar 44100 -ac 1 -i recordings/abc123_chunk_0.pcm chunk_0.wav

# Play
afplay chunk_0.wav
```

## Troubleshooting

### "Attestation verification failed"

**Issue**: Warden rejected the attestation. The verifier prints the exact reason and a hint.

| Reason | Fix |
|--------|-----|
| `Bootloader not locked` | Relock the bootloader; or `--allow-unlocked-bootloader` for demos only |
| verified boot key / state | Device runs an OS whose boot key is not trusted; add `--verified-boot-key <hex>` if you know it |
| signer / signature digest | APK signer differs: get it via `apksigner verify --print-certs` and pass `--signer` |
| StrongBox / security level | Key is TEE-backed; `--allow-tee` if acceptable |
| challenge | Manifest from an old app version without `attestation_challenge`; pass `--challenge` or re-record |
| revocation / network | Offline: add `--skip-revocation-check` |

Use `--inspect <manifest>` to print the raw attestation record.

### "No such file or directory" when pulling files

**Issue**: ADB cannot find recordings

**Solutions**:
```bash
# Check actual path on device
adb shell find /sdcard -name "*manifest.json"

# Use correct path
adb pull /storage/emulated/0/Android/data/com.attestable.recorder/files/recordings/ ./
```

### "Permission denied" on microphone

**Issue**: App doesn't have microphone permission

**Solutions**:
1. Go to Settings → Apps → Attestable Recorder → Permissions
2. Enable Microphone
3. Restart app

### Build fails with "SDK not found"

**Issue**: Android SDK not configured

**Solutions**:
```bash
# Create local.properties file
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

## Next Steps

1. **Read the full README**: `README.md`
2. **Understand TEE Interop**: `TEE_INTEROP.md`
3. **Customize the app**: Modify `app/src/main/java/com/attestable/recorder/`
4. **Deploy verifier server**: Set up remote verification service
5. **Integrate with your app**: Use attestation in your own projects

## Advanced: Deploy to Production

1. **Create release signing key**:
```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

2. **Configure signing in `app/build.gradle.kts`**

3. **Build release APK**:
```bash
./gradlew :app:assembleRelease
```

4. **Relock bootloader** (for maximum security):
```bash
# WARNING: This will wipe your device
adb reboot bootloader
fastboot flashing lock
```

## Support

For issues or questions:
- Check `README.md` for detailed documentation
- Review code comments in source files
- Open issue on GitHub (if applicable)
