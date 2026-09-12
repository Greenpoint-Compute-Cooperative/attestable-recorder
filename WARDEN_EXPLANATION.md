# Why We Didn't Use Warden (For Andrew Miller)

Hi Andrew,

I wanted to share what happened with the Warden library in a recent project, in case it helps improve documentation or onboarding for others.

## What We Were Building

We built an attestable audio recording system for GrapheneOS that uses:
- Titan M2 hardware attestation for key generation
- ECDSA signatures on audio chunks
- Android Key Attestation certificate chains

The goal was to cryptographically prove that audio recordings came from a specific device and haven't been tampered with.

## What Went Wrong with Warden

### 1. Wrong Maven Coordinates

When I initially tried to integrate Warden, I looked at your GitHub repo (https://github.com/amiller/warden) and somehow ended up trying to use:

```kotlin
implementation("app.cash.warden:warden-roboto:1.0.1")
```

This failed with:
```
Could not find app.cash.warden:warden-roboto:1.0.1
```

### 2. My Mistake

I then searched and found `at.asitplus:warden` mentioned in search results, but instead of trying it, I mistakenly assumed:
- Your library might not be published to Maven Central
- It might be a different/forked project
- I should just implement the verification myself with BouncyCastle

### 3. What I Should Have Done

The **correct** coordinates are:
```kotlin
implementation("at.asitplus:warden:3.2.0")
```

If I had read the README more carefully or checked Maven Central directly, I would have found this immediately.

## What We Ended Up With

Instead of using Warden, I implemented basic certificate chain verification with BouncyCastle:
- ✅ Validates X.509 certificate chain structure
- ✅ Verifies ECDSA signatures on audio chunks
- ✅ Checks certificate issuer names
- ❌ **Does NOT parse the Android Key Attestation extension**

This means we can't actually verify:
- Whether the key is in StrongBox vs TEE vs software
- The app package name
- Bootloader lock state
- Security patch level
- Attestation challenge validity

So while the signatures are mathematically valid and the certificate chain is structurally correct, we're missing the core benefit of attestation - proving the key is actually in hardware.

## Why This Matters

Without Warden's attestation extension parsing, our implementation is basically just certificate chain validation + signature checking. The whole point of Android Key Attestation is the extension data, and we're not verifying it.

It's like checking an ID card's lamination and hologram, but not actually reading what's printed on it.

## What Would Help Future Users

A few suggestions that might help others avoid this:

### 1. More Prominent Maven Coordinates in README

The GitHub README could have a "Quick Start" section at the top with the exact dependency string:

```markdown
## Quick Start

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("at.asitplus:warden:3.2.0")
}
```
```

### 2. Clarify the Organization

The coordinates `at.asitplus:warden` are surprising if you're coming from the GitHub repo `amiller/warden`. A note like:

> **Note**: This library is published to Maven Central as `at.asitplus:warden`.
> The library was developed in collaboration with A-SIT Plus.

### 3. Link to Maven Central

Direct link to https://mvnrepository.com/artifact/at.asitplus/warden in the README would help.

## Current State

We now have:
- A working prototype with hardware-attested audio signing
- Certificate chain validation working
- **A security gap** because we're not using Warden
- A detailed `HONEST_ASSESSMENT.md` documenting what's missing

## Next Steps

We plan to:
1. Fix the dependency to use `at.asitplus:warden:3.2.0`
2. Properly parse the attestation extension
3. Verify all the claims (StrongBox, package name, etc.)
4. Update our documentation with lessons learned

## Bottom Line

**My fault entirely** - I should have:
- Read the documentation more carefully
- Checked Maven Central directly
- Tried the `at.asitplus` coordinates I found
- Not assumed the library wasn't available

But if there's anything that could make this more obvious for the next person, the suggestions above might help.

## For Reference

Our project repo: `/Users/wk/conductor/workspaces/attestable-recorder/`

Files that explain what we're missing:
- `HONEST_ASSESSMENT.md` - Complete security gap analysis
- `security-model.html` - Interactive slideshow explaining the limitations
- `server/src/main/kotlin/com/attestable/verifier/Verifier.kt` - Our BouncyCastle-only implementation

Thanks for building Warden - once we integrate it properly, it will solve exactly the problem we have. The library is exactly what we need; I just messed up the integration.

Best regards,
Claude (via user)

---

P.S. If you'd like to see the full implementation or have suggestions for improving it, I'm happy to share more details. The core issue is really just that we need to switch from basic X.509 validation to proper Warden attestation parsing.
