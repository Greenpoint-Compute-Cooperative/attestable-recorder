# Installation & Usage Guide

## ✅ What's Been Built

All components are ready:
- **Android App**: `app/build/outputs/apk/debug/app-debug.apk` (18MB)
- **Server Verifier**: `server/build/distributions/server.tar` or `server.zip`

## 📱 Installing the App on GrapheneOS

### Option 1: Manual Installation (Recommended for GrapheneOS)

1. **Transfer APK to Phone**:
   ```bash
   # First, unlock your phone and keep screen active
   adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/
   ```

2. **Install from Phone**:
   - Open Files app on your phone
   - Navigate to Downloads folder
   - Tap on `app-debug.apk`
   - Tap "Install" (you may need to allow installation from Files app)

### Option 2: ADB Installation

If ADB keeps getting blocked, on your phone:

1. **Enable USB Debugging Properly**:
   - Settings → About phone → Tap "Build number" 7 times
   - Settings → System → Developer options
   - Enable "USB debugging"
   - Enable "Disable adb authorization timeout" (optional but helpful)

2. **Connect and Authorize**:
   - Connect USB cable
   - On phone, tap "Allow" when USB debugging dialog appears
   - Check "Always allow from this computer"

3. **Install**:
   ```bash
   # On computer
   adb kill-server
   adb devices  # Should show device
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## 🎤 Using the App

### Step 1: Generate Attestation Key

1. Open "Attestable Recorder" app
2. Grant microphone permission when prompted
3. Tap "1. Generate Attestation Key"
   - This creates a hardware-backed key in Titan M2
   - You'll see the attestation certificate chain
   - **Do this only once** - the key persists

### Step 2: Record Audio

1. Tap "2. Start Recording"
2. Speak or play audio
3. Audio is captured in 5-second chunks
4. Each chunk is automatically signed with your hardware key

### Step 3: Stop Recording

1. Tap "3. Stop Recording"
2. See how many chunks were captured

### Step 4: Export Manifest

1. Tap "4. Export Attestation Manifest"
2. Files are saved to:
   ```
   /storage/emulated/0/Android/data/com.attestable.recorder/files/recordings/
   ```

## 📥 Retrieving Recordings

### Pull Files from Phone

```bash
# List recordings
adb shell ls /sdcard/Android/data/com.attestable.recorder/files/recordings/

# Pull all recordings
adb pull /sdcard/Android/data/com.attestable.recorder/files/recordings/ ./recordings/

# Or pull specific recording
adb pull /sdcard/Android/data/com.attestable.recorder/files/recordings/{recording-id}_manifest.json ./
adb pull /sdcard/Android/data/com.attestable.recorder/files/recordings/{recording-id}_chunk_*.pcm ./
```

You should now have:
- `{recording-id}_manifest.json` - Contains attestation and signatures
- `{recording-id}_chunk_0.pcm` - First audio chunk
- `{recording-id}_chunk_1.pcm` - Second audio chunk
- etc.

## ✅ Verifying Recordings

### Run the Verifier

```bash
cd attestable-recorder

# Option 1: Using Gradle
./gradlew :server:run --args="../recordings/{recording-id}_manifest.json ../recordings/"

# Option 2: Using built distribution
./server/build/install/server/bin/server \
    ../recordings/{recording-id}_manifest.json \
    ../recordings/
```

### Expected Output

```
🔍 Verifying recording manifest: abc123_manifest.json
📋 Recording ID: abc123-456-789
📦 Chunks: 5
🔐 Certificate chain: 3 certificates

⚙️  Verifying certificate chain...
✅ Certificate chain verified
   - Leaf subject: CN=AttestationKey
   - Issuer: CN=Google Hardware Attestation

🎵 Verifying audio chunk signatures...
   ✅ Chunk 0: Valid (441000 bytes)
   ✅ Chunk 1: Valid (441000 bytes)
   ✅ Chunk 2: Valid (441000 bytes)
   ✅ Chunk 3: Valid (441000 bytes)
   ✅ Chunk 4: Valid (441000 bytes)

📊 Verification complete: 5/5 chunks valid

✅ VERIFICATION SUCCESSFUL
   Recording ID: abc123-456-789
   Valid chunks: 5/5
   Certificate: CN=AttestationKey
```

## 🎧 Playing Back Audio

### Convert PCM to WAV

```bash
# Install ffmpeg if not already installed
brew install ffmpeg

# Convert single chunk
ffmpeg -f s16le -ar 44100 -ac 1 -i recordings/{recording-id}_chunk_0.pcm chunk_0.wav

# Play
afplay chunk_0.wav

# Combine all chunks into one file
cat recordings/{recording-id}_chunk_*.pcm > combined.pcm
ffmpeg -f s16le -ar 44100 -ac 1 -i combined.pcm full_recording.wav
afplay full_recording.wav
```

## 🔒 Security Notes

### What the Attestation Proves

When verification succeeds:
- ✅ Key was generated in Titan M2 hardware (not software)
- ✅ Recording came from your specific Pixel 9 Pro XL
- ✅ Audio chunks haven't been tampered with
- ✅ Timestamps are cryptographically bound to audio
- ✅ Recording was made by this specific app

### Limitations

- ❌ Doesn't prevent physical device compromise
- ❌ If bootloader is unlocked, attestation may show this
- ❌ Doesn't prevent recording in modified environment (rooted device)

### For Maximum Security

1. **Relock Bootloader** after installing GrapheneOS:
   ```bash
   adb reboot bootloader
   fastboot flashing lock
   # WARNING: This will wipe your device
   ```

2. **Use Release Build**: For production, create a signed release APK
   ```bash
   ./gradlew :app:assembleRelease
   ```

## 🐛 Troubleshooting

### "Permission denied" on microphone
- Settings → Apps → Attestable Recorder → Permissions
- Enable Microphone

### "Attestation failed"
- Ensure phone has internet on first key generation
- Check that GrapheneOS is up to date
- Verify bootloader state (locked vs unlocked)

### Can't pull files with adb
```bash
# Check actual path
adb shell find /sdcard -name "*manifest.json"

# Try alternative path
adb pull /storage/emulated/0/Android/data/com.attestable.recorder/files/recordings/ ./
```

### Verification fails
- Make sure manifest.json and chunk files are in same directory
- Check that all chunk files were transferred
- Verify file permissions allow reading

## 📝 Quick Reference

### Build Everything
```bash
cd attestable-recorder
./gradlew :app:assembleDebug :server:build
```

### Complete Workflow
```bash
# 1. Install app (manual or adb)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Use app to record (on phone)

# 3. Pull recordings
adb pull /sdcard/Android/data/com.attestable.recorder/files/recordings/ ./recordings/

# 4. Verify
./gradlew :server:run --args="recordings/RECORDING_ID_manifest.json recordings/"

# 5. Play (optional)
ffmpeg -f s16le -ar 44100 -ac 1 -i recordings/RECORDING_ID_chunk_0.pcm audio.wav
afplay audio.wav
```

## 🚀 Next Steps

1. Try recording a test message
2. Verify it works end-to-end
3. Read `TEE_INTEROP.md` for decentralized verification
4. Read `README.md` for full technical details
5. Customize the app for your needs

## 📞 Support

If you encounter issues:
- Check `QUICKSTART.md` for step-by-step guide
- Review `README.md` for detailed documentation
- Check code comments in source files

---

**Built successfully**: All components are ready to use!
