@file:OptIn(ExperimentalTime::class)

package com.attestable.verifier

import at.asitplus.attestation.Makoto
import at.asitplus.attestation.android.AndroidAttestationConfiguration
import at.asitplus.attestation.android.AndroidRevocationList
import at.asitplus.attestation.android.VerifiedBootKey
import at.asitplus.attestation.android.parseHex
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * GrapheneOS verified-boot key hashes, one per supported device.
 *
 * GrapheneOS re-signs the OS with its own verified-boot key, so a GrapheneOS device reports
 * `verifiedBootState = SelfSigned` in its attestation record (never `Verified`, which is reserved
 * for the vendor key). Warden only accepts a self-signed boot state when the boot-key digest is
 * explicitly allow-listed, which is what this table is for.
 *
 * Source: https://grapheneos.org/install/web#verified-boot-key-hash
 */
object GrapheneOsBootKeys {
    val byDevice: Map<String, String> = linkedMapOf(
        "Pixel 10a" to "d8f879d10419eddc9fcda6280718be763f6bf12299e1f72df3ea8ad8a8eb7f80",
        "Pixel 10 Pro Fold" to "55a2d44103e56d5ec65496399c417987ba77730e6488fc60ba058d09fc3caee3",
        "Pixel 10 Pro XL" to "141d7fc32af7958a416f2661b37cf6f27bfb376fb5ce616aeaa27a82c7a04f74",
        "Pixel 10 Pro" to "4e8ee8f717754052198ca6d2d3aaa232e2461b4293c0d6f297e519cc778de093",
        "Pixel 10" to "3f7415ea26f5df5b14ea6d153256071a7a1af9ce7b0970b7311cc463c7ea02c7",
        "Pixel 9a" to "0508de44ee00bfb49ece32c418af1896391abde0f05b64f41bc9a2dfb589445b",
        "Pixel 9 Pro Fold" to "af4d2c6e62be0fec54f0271b9776ff061dd8392d9f51cf6ab1551d346679e24c",
        "Pixel 9 Pro XL" to "55d3c2323db91bb91f20d38d015e85112d038f6b6b5738fe352c1a80dba57023",
        "Pixel 9 Pro" to "f729cab861da1b83fdfab402fc9480758f2ae78ee0b61c1f2137dd1ab7076e86",
        "Pixel 9" to "9e6a8f3e0d761a780179f93acd5721ba1ab7c8c537c7761073c0a754b0e932de",
        "Pixel 8a" to "096b8bd6d44527a24ac1564b308839f67e78202185cbff9cfdcb10e63250bc5e",
        "Pixel 8 Pro" to "896db2d09d84e1d6bb747002b8a114950b946e5825772a9d48ba7eb01d118c1c",
        "Pixel 8" to "cd7479653aa88208f9f03034810ef9b7b0af8a9d41e2000e458ac403a2acb233",
        "Pixel Fold" to "ee0c9dfef6f55a878538b0dbf7e78e3bc3f1a13c8c44839b095fe26dd5fe2842",
        "Pixel Tablet" to "94df136e6c6aa08dc26580af46f36419b5f9baf46039db076f5295b91aaff230",
        "Pixel 7a" to "508d75dea10c5cbc3e7632260fc0b59f6055a8a49dd84e693b6d8899edbb01e4",
        "Pixel 7 Pro" to "bc1c0dd95664604382bb888412026422742eb333071ea0b2d19036217d49182f",
        "Pixel 7" to "3efe5392be3ac38afb894d13de639e521675e62571a8a9b3ef9fc8c44fd17fa1",
        "Pixel 6a" to "08c860350a9600692d10c8512f7b8e80707757468e8fbfeea2a870c0a83d6031",
        "Pixel 6 Pro" to "439b76524d94c40652ce1bf0d8243773c634d2f99ba3160d8d02aa5e29ff925c",
        "Pixel 6" to "f0a890375d1405e62ebfd87e8d3f475f948ef031bbf9ddd516d5f600a23677e8",
    )

    /** All GrapheneOS boot keys as Warden [VerifiedBootKey.Digest] entries. */
    val digests: Set<VerifiedBootKey> =
        byDevice.values.map { VerifiedBootKey.Digest(it.parseHex()) }.toSet()

    /** Human-readable device name for a verified-boot key digest, if it is a known GrapheneOS key. */
    fun deviceNameFor(digestHex: String): String? =
        byDevice.entries.firstOrNull { it.value.equals(digestHex, ignoreCase = true) }?.key
}

/**
 * The attestation policy the verifier enforces, expressed in terms of Warden's
 * [AndroidAttestationConfiguration].
 *
 * Every property here maps onto something Warden checks inside the Android Key Attestation
 * extension of the leaf certificate — this is the part the previous BouncyCastle-only
 * verifier never looked at.
 */
data class WardenPolicy(
    /** Package name the attestation record must carry. */
    val packageName: String = DEFAULT_PACKAGE_NAME,
    /** SHA-256 fingerprints of the APK signing certificate(s). At least one is required. */
    val signerFingerprints: Set<ByteArray>,
    /** Require the key to live in StrongBox (Titan M2 on Pixels) rather than the TEE. */
    val requireStrongBox: Boolean = true,
    /**
     * Accept devices with an unlocked bootloader. **Leave false for real verification**:
     * enabling this also disables the verified-boot-state and verified-boot-key checks.
     */
    val allowUnlockedBootloader: Boolean = false,
    /** Accepted verified-boot keys: GrapheneOS's published keys plus the vendor (OEM) key. */
    val verifiedBootKeys: Set<VerifiedBootKey> = GrapheneOsBootKeys.digests + VerifiedBootKey.OEM,
    /** Check the attestation chain against Google's published revocation list (needs network). */
    val checkRevocation: Boolean = true,
) {
    init {
        require(signerFingerprints.isNotEmpty()) { "At least one APK signer fingerprint is required" }
        require(signerFingerprints.all { it.size == 32 }) { "Signer fingerprints must be SHA-256 (32 bytes)" }
    }

    fun toAndroidConfiguration(): AndroidAttestationConfiguration = AndroidAttestationConfiguration(
        applications = listOf(
            AndroidAttestationConfiguration.AppData(
                packageName = packageName,
                signerFingerprints = signerFingerprints,
            )
        ),
        requireStrongBox = requireStrongBox,
        allowBootloaderUnlock = allowUnlockedBootloader,
        verifiedBootKeys = verifiedBootKeys,
        revocation = if (checkRevocation) listOf(AndroidRevocationList.GoogleDefaultLoaderConfig) else emptyList(),
        // Warden's own ASN.1 parser; recommended by upstream and required for some Android 16+ devices.
        supremeParser = true,
    )

    /** Build a Warden attestation service for this policy (Android-only, no iOS configuration). */
    fun makoto(clock: Clock = Clock.System): Makoto =
        Makoto(androidAttestationConfiguration = toAndroidConfiguration(), clock = clock)

    companion object {
        const val DEFAULT_PACKAGE_NAME = "com.attestable.recorder"

        /** Parse a SHA-256 fingerprint given as hex, with or without `:` separators or whitespace. */
        fun parseFingerprint(text: String): ByteArray {
            val bytes = text.parseHex()
            require(bytes.size == 32) { "Expected a SHA-256 fingerprint (64 hex chars), got ${bytes.size} bytes: $text" }
            return bytes
        }
    }
}
