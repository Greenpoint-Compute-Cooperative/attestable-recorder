# Reproducible Build

The attestation pins the APK's **signing certificate** (tag 709). That proves who holds the signing
key, not what code ran. A reproducible build closes half of that gap: anyone can rebuild the APK
from the tagged sources and check, byte for byte, that the published APK contains exactly that
code. (The other half — a signing key that *only* signs such builds — is future work; see
`REVIEW_RESPONSE.md`.)

## What is reproducible

The artifact is **`app-release-unsigned.apk`** — the **offline** flavor (`assembleOfflineRelease -PunsignedRelease`, written as `app-offline-release-unsigned.apk` and published under the shorter name). The `room` flavor (credible sensor for vibecode-room) adds INTERNET, OkHttp and MediaPipe and is a separate package; it is built the same way but is not the reproducibility target of the releases. Signing is a separate, non-deterministic step
(ECDSA signatures are randomized), so the signed APK's hash differs on every signing run. What
must match is the *content*: every zip entry of the signed APK equals the unsigned build's entry.
`scripts/apk-compare.py` checks exactly that, ignoring only the signature block.

Each GitHub release publishes:

| Asset | Meaning |
|---|---|
| `app-release.apk` | Signed with the release key; install this |
| `app-release-unsigned.apk` | The reproducibility target |
| `SHA256SUMS` | Hashes of both, plus the verifier distribution |
| release notes | The release signer's certificate SHA-256 to pass to the verifier as `--signer` |

## Reproduce it

### Option A — pinned Docker environment (recommended)

```bash
git clone https://github.com/Greenpoint-Compute-Cooperative/attestable-recorder
cd attestable-recorder && git checkout v1.0.0
docker build --platform linux/amd64 -t attestable-recorder-build .
mkdir -p out && docker run --rm --platform linux/amd64 -v "$PWD/out:/out" attestable-recorder-build
cat out/SHA256SUMS                         # compare with the release's SHA256SUMS
scripts/apk-compare.py out/app-release-unsigned.apk path/to/downloaded/app-release.apk
```

The image pins everything that influences the bytes: Temurin JDK 17.0.16, Android platform 34,
build-tools 36.0.0, Gradle 8.14.3 (wrapper), AGP 8.13.2, Kotlin 2.4.20, and exact dependency
versions from `build.gradle.kts`. Signing material is deleted inside the image.

### Option B — your own machine

Needs JDK 17 and an Android SDK with platform 34 + build-tools 36.0.0.

```bash
scripts/reproduce.sh path/to/downloaded/app-release.apk
```

This does a clean build of the unsigned APK, prints its SHA-256, and compares it with the
downloaded signed APK by content. Exit code 0 means the published APK is exactly this source.

## What makes it deterministic

- AGP writes fixed zip timestamps (1981-01-01) and orders entries deterministically.
- VCS info is disabled (`vcsInfo { include = false }`), so the APK does not depend on the git
  commit or a dirty tree.
- No R8/ProGuard (`isMinifyEnabled = false`), no build-time timestamps, no `BuildConfig` fields
  beyond the defaults.
- `kotlin { jvmToolchain(17) }` and `compileOptions` at 17 pin the compiler target; the Docker
  image pins the JDK itself.
- Dependency versions are exact, not ranges.

Evidence on 2026-09-12: two consecutive clean builds on macOS (Temurin 21 host JDK, toolchain 17)
produced byte-identical `app-release-unsigned.apk` (`6aebe796…`), and the signed APK compared
equal entry-for-entry. The Docker build's hash is recorded in the v1.0.0 release notes.

## Verify a device's attestation against a published build

1. Get the release signer fingerprint from the release notes (or from the APK:
   `apksigner verify --print-certs app-release.apk`).
2. Run the verifier with `--signer <that fingerprint>`. The attestation record on every recording
   carries the signer digest of the APK that created the key; Warden rejects any other signer.
3. Reproduce the build as above so you know that signer was applied to *this* source.

Together: the recording was made by an APK whose contents you can rebuild yourself, signed by the
published key, on a locked GrapheneOS device with a StrongBox key. Still unproven: that the key
holder never signed anything else — the encumbered-key step.
