# Credible Sensors Roadmap — What the Pixel Work Already Covers

**Scope of this evaluation.** The Greenpoint Compute Cooperative "Credible Sensors" R&D roadmap
(Jul 10 → early Oct 2026; 12 epics, 73 tasks, 666 h; GitHub project
`Greenpoint-Compute-Cooperative/credible-sensors-draft`) against what exists in this repo today:
a GrapheneOS Pixel 10a with a Titan M2, an app that records audio and signs every 5-second chunk
with a StrongBox key, and a Warden-based verifier that enforces a full attestation policy plus
verifier-issued, single-use challenges. The question asked: **how much of "verifiable video +
audio capture" can be delivered on the Pixel, targeting the vibecode-room codebase but built as
generalized tooling first.**

Source of truth: the July 15 plaintext export (`~/credible-sensors-slideshow.html`) and the
live GitHub issues #3–#87. The September 12 HTML attachment is StatiCrypt-encrypted and I do
not have its passphrase, so if the roadmap was re-scoped after July, this needs a re-read.

---

## 1. Bottom line

| Track / epic | Roadmap hours | Pixel + Warden status | Remaining on Pixel |
|---|---|---|---|
| **A1** Transcriber on a stock phone (7 tasks) | 62–76 h | Attestation core (#18) done and exceeds spec; QR verifier (#19) partly blocked by Warden being JVM-only | ~30 h |
| **A2** Hardened OS (5 tasks) | 54 h | GrapheneOS + locked bootloader + verified-boot-key pinning covers the *verification* half; custom-key OS build and kiosk are not started | ~35 h |
| **A3** Minimal open device (6 tasks) | 56 h | Different hardware (STSAFE/MCU). Pixel work is the *comparison baseline* #32 asks for | 0 h on Pixel; write-up only |
| **A4** Video camera (5 tasks) | 34 h | **Signing camera built** (#35 equivalent): Camera2 → H.264 → signed MP4 segments, verified by the same verifier (#36 equivalent, tamper-tested). The Pixel is a better base than the Pi 5 for the injected-feed problem | ~10 h (threat write-up #37, C2PA wrapper if wanted) |
| **C1** #60 two cameras + two mics | 8 h | Pixel can be one attested mic/camera source; not the dual-webcam capture | partial |
| **C2** #66 per-device audio attribution | 12 h | Attested channel identity for free (key = device = participant) | partial |
| **C4** Credible room (6 tasks) | 46 h | Sensor-to-room seam (#76) is the real work: attested phone mic → `/api/mic` + attestation sidecar | ~20 h |
| **B1–B3** enclosures, indicator | 198 h | Out of scope for a phone; the phone's indicator is software | 0 h |

**Roughly 45% of Track A's hours (≈ 100 of 226 h) are either done or made materially cheaper by
what already runs on the Pixel**, and the C4 integration seam is well defined. The two things
the Pixel cannot deliver are Track B (physical enclosure and hardware indicator) and A3 (open
MCU device). The single biggest technical gap is a **bystander verifier that runs without a JVM**
(A1 #19), because Warden is a Kotlin/JVM library.

---

## 2. Track A, task by task

### A1 — Transcriber on a stock phone (#3)

The epic's trust root is exactly ours: "hardware key attestation alone, verified offline".
Play Integrity is dropped by design, which matches this repo's choice.

| Task | Status on Pixel | Notes |
|---|---|---|
| #15 Validate VoxTerm ASR (4 h) | Not started | ASR is orthogonal to attestation. VoxTerm's `tauri-plugin-voxasr` (sherpa-onnx Whisper) can run on this phone unchanged. |
| #16 Fork VoxTerm, strip to a1 scope (8 h) | Not started | Decision point: **VoxTerm (Tauri + Kotlin plugin) vs. this Kotlin app.** The attestation, chunk signing and challenge flow here are ~600 lines of Kotlin and could be dropped into VoxTerm's plugin layer, or VoxTerm's ASR could be dropped into this app. Either way the no-INTERNET manifest is trivial: this app already has no network code. |
| #17 On-device PII/name redaction (14 h) | Not started | Pure ASR/NLP work; unaffected by attestation. |
| **#18 Bind proof to redacted output via key attestation (12 h)** | **Done, exceeds spec** | Spec: sign the transcript hash with a StrongBox/TEE key, export the chain, verify offline to Google's root. Delivered: per-chunk StrongBox signatures, chain verified with Warden (root, revocation, challenge, package, APK signer, bootloader, GrapheneOS boot key), verifier-issued single-use challenges. To meet the letter of #18, the *redacted transcript hash* must be added to the signed payload alongside the audio hash (≈ 2 h: one more field in `signedPayload`, one more manifest key). |
| #19 One-tap QR verify + bystander verifier (12 h) | **Blocked in its "static page, no backend" form** | Warden is JVM-only. Options, cheapest first: (a) *second-phone path* — the verifier as an Android app (Warden roboto is plain Kotlin + BouncyCastle + ktor; likely runs on Android, needs a spike); (b) a static page that POSTs to a tiny local verifier service on the room LAN (acceptable for the C4 demo, which is LAN-only anyway); (c) a from-scratch JS/WASM attestation verifier — do not do this in-sprint. Recommend (a) + (b), ~12–16 h. |
| #20 Layperson test (6 h) | Not started | Needs #19. |
| #21 Write-up: what attestation does/doesn't cover (6 h) | **~80% done** | `HONEST_ASSESSMENT.md` and `warden-trust-model.html` already cover the seam, residual gaps, and why Play Integrity is unnecessary. Needs the no-INTERNET framing and the layperson findings. |

### A2 — Hardened OS (#4)

| Task | Status | Notes |
|---|---|---|
| #22 Procure Pixel 8a (4 h) | Done (Pixel 10a) | Any Titan M2 Pixel works; all 21 GrapheneOS boot keys are pinned in `WardenPolicy`. |
| #23 Reproducibly build GrapheneOS with custom keys (22 h) | Not started | The verifier is *ready* for it: pass the custom verified-boot key digest with `--verified-boot-key` and drop `OEM`/GrapheneOS keys. Attestation then proves "booted *our* OS image". |
| #24 Kiosk-lock the device (10 h) | Not started | Device-owner mode via `adb shell dpm set-device-owner`; unaffected by attestation. |
| #25 Running-image match + attestation script (12 h) | **Mostly done** | The verifier already extracts and checks verified-boot state, boot key, boot hash and OS/vendor/boot patch levels. Remaining: compare `verifiedBootHash` against the built image's expected hash (≈ 3 h once #23 exists). |
| #26 Update-key threat memo (6 h) | Not started | |

### A3 — Minimal open device (#5)

Different hardware (I2S mic → MCU → STSAFE secure element). Nothing transfers except the
manifest/verifier design, which is deliberately generic (chain + signed chunk manifest). The
verdict memo #32 ("open device vs stock phone") should cite the Pixel numbers: zero hardware
cost, StrongBox, revocation, and the injected-feed comparison below.

### A4 — Video camera (#6)

The roadmap picks Raspberry Pi 5 + Camera Module 3 + c2pa-rs, and names the TC358743
HDMI-to-CSI-2 injection attack as the gap it cannot close. **The Pixel changes that calculus:**

- On a Pixel the image sensor talks to the SoC ISP over an internal MIPI link on the board;
  there is no exposed CSI connector to plug an injector into. Feeding synthetic frames requires
  either a modified OS (caught by verified boot) or physically re-working the phone.
  This does not *close* the gap (a determined attacker can still desolder or point the camera
  at a screen), but it raises the cost far above "buy a $30 adapter", which is precisely what
  #37 asks the write-up to quantify.
- The audio pipeline ports directly: CameraX → MediaCodec H.264 → fragmented MP4 segments of
  2–5 s → SHA-256 → sign with the same StrongBox key → manifest. Same verifier, one new
  chunk type. Estimated **12–16 h** for capture + signing (#35 equivalent), **5 h** for the
  verifier extension (#36), and the C2PA wrapper (#34's "wire format" decision) can be a
  post-processing step with c2patool on the verifier side rather than on-device signing,
  because the attestation-signed segment manifest is already the stronger claim.
- Throughput: signing a 32-byte hash in StrongBox takes tens of milliseconds; the earlier
  audio run signed each chunk in well under a second. Per-segment signing at 1080p is not the
  bottleneck; encoding and storage are (measure per #35).

**Status (2026-09-12): the signing camera and the segment verifier are implemented** in this repo
(`AttestableVideoRecorder.kt`, `ChunkVerifier.kt`): 1280×720 H.264 at 30 fps, ~5 s segments
cut on key frames, each hashed and signed in StrongBox, plus a signed session record. Unit
tests cover tampered segments, truncation and reordering. What remains of A4 on the Pixel is
the threat write-up (#37) and, if C2PA as a wire format matters, a c2patool post-processing
step on the verifier side. Keep the Pi track only if the "open hardware" narrative matters
more than the injection-resistance one.

---

## 3. Track C: the integration seam into vibecode-room

### What vibecode-room has today (from `docs/CURRENT-STATE.md`, `docs/phone-mic.md`, `src/server/index.ts`)

- The room mic is a WebSocket at `/api/mic`: the client streams **16 kHz mono little-endian
  Int16 PCM in 4096-sample frames**; the server answers `{type:"ready", mode, sessionId}` and
  refuses sessions while muted (close code 1008). `public/mic.html` lets any phone be *the*
  room mic through the same socket; the server does not distinguish sources.
- ASR is cloud (Deepgram) or a replay fixture; there is no local inference wired, which
  conflicts with C4's "WAN cable pulled" demo beat. That is C1 #58/#59 work, not ours.
- There is no notion of provenance, attestation, or per-source identity in `src/types.ts` or
  the UI. The `onDevice`/`onSwitch` callbacks in `src/ui/mic.ts` are the closest thing: the
  wall already wants to "SAY which physical mic is feeding the room instead of leaving it to
  faith". That sentence is the product hook for an attested source.

### The contract C4 #76 asks for, satisfied by the Pixel

C4's key decision: "credible mic presents as a standard audio capture device or LAN stream,
with its attestation/verification log exposed as a separate feed the room UI can display."

1. **Audio path (unchanged for the room):** the Pixel app streams the same linear16 PCM frames
   to `/api/mic` that `mic.html` does. Nothing in the c1–c3 stack changes.
2. **Attestation sidecar (new, generic):** a second channel — `/api/attestation` WebSocket or
   HTTP POST per chunk — carrying `{recording_id, index, timestamp, audio_hash, signature}`
   plus, once per session, the certificate chain and challenge. This is exactly the manifest
   this repo already emits, streamed instead of exported at the end.
3. **Verifier as a room service:** the Kotlin verifier wrapped in a small HTTP API
   (`POST /challenge`, `POST /verify-session`, `POST /verify-chunk`), running on the room node.
   It hashes the PCM frames it received on `/api/mic` for that session and checks them against
   the signed chunk hashes — so the room can prove the audio it transcribed is the audio the
   Titan M2 signed, not merely that *some* signed audio exists.
4. **Room UI:** a per-source badge fed by the sidecar: "StrongBox · GrapheneOS Pixel 10a ·
   bootloader locked · challenge fresh · 47/47 chunks verified". This is C4's "what the sensor
   claims, what you can check" display and B3's software analogue of the indicator light.

Clock alignment within 100 ms (#76 AC) comes for free if the room hashes what it receives:
alignment is by content hash, not by clock.

### Effort for the seam

| Piece | Hours |
|---|---|
| Streaming mode in the Android app (PCM to `/api/mic`, chunk sidecar to `/api/attestation`) | 8 |
| Verifier HTTP service (challenge issue, session verify, per-chunk verify over received PCM) | 6 |
| vibecode-room: sidecar ingest + `SourceAttestation` in `src/types.ts` + badge in `src/ui/App.tsx` | 6 |
| End-to-end on the travel-router LAN, WAN unplugged | 2 |
| **Total** | **~22 h** — inside C4's 46 h budget, and it *is* #76 |

---

## 4. Generalized tooling to build now (target-agnostic)

Build these as three separable pieces so vibecode-room is one consumer, not the design centre:

1. **`attested-capture` Android library** — extract `AttestationManager` and the chunk signer
   from the app into a library module with two sources (`AudioRecord` PCM, CameraX fMP4
   segments) and two sinks (file manifest, live stream). API: `openSession(challenge) →
   attestationBundle`, `sign(chunk) → ChunkAttestation`. Optional redacted-transcript binding
   for A1 #18.
2. **`attested-capture-verifier`** — the existing Warden verifier plus an HTTP surface and a
   pluggable policy file (package, signers, StrongBox, boot keys, patch level, RKP). Ships as a
   JVM jar for room nodes and, after a spike, as an Android APK for the second-phone bystander
   path (#19).
3. **A wire spec** — `manifest_version: 3`: session header (chain, challenge, challenge source,
   device claims) + chunk records (type audio|video, index, timestamp, hash, signature). Same
   document whether written to a file, streamed over a WebSocket, or embedded in a C2PA
   assertion for A4.

Priority order if the October demo is the goal: (3) spec → (1) streaming audio → (2) HTTP
verifier → vibecode-room badge → video segments → second-phone verifier.

---

## 5. What the Pixel does not solve

- **Track B entirely.** A phone in a room has no tamper-evident enclosure and no hardware
  indicator; its recording light is software (attested software, but software).
- **Sensor authenticity.** Attestation proves *which device and app* signed the bytes, never
  that the microphone heard a live human or the camera saw a real scene. The roadmap's
  own framing ("the gap is named, not closed") applies unchanged.
- **Static, backend-free bystander verification (#19).** Needs either an Android verifier app
  or a LAN service until someone ports attestation verification to JS/WASM.
- **App behaviour.** The release build is signed with a dedicated key, but a signer proves who
  holds the key, not what ran (see `REVIEW_RESPONSE.md`). Reproducible builds and an encumbered
  TEE/HSM signing key are still required for a third party to trust the *app*, not just the chip.

---

## 6. Suggested re-plan of Track A hours (for the go/no-go)

| Item | Roadmap | With Pixel work |
|---|---|---|
| A1 #18 attestation binding | 12 h | 2 h (add transcript hash to payload) |
| A1 #19 bystander verifier | 12 h | 14 h (Android verifier app spike + LAN page) |
| A1 #21 write-up | 6 h | 2 h |
| A2 #25 image match script | 12 h | 3 h |
| A4 build + verifier (#35, #36) | 17 h | 20 h on Pixel, no Pi |
| A4 threat write-up (#37) | 6 h | 6 h, stronger conclusion |
| C4 #76 integration | 10 h | 22 h (the seam is real work; budget it honestly) |

Net effect: Track A drops from ~226 h to roughly 160 h of remaining work, C4's integration
grows but becomes concrete, and the October demo can show a **verified-fresh, StrongBox-signed
phone channel** inside the room rather than a side station.
