package com.attestable.recorder

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The OS state around the capture — the part of the evidence that the samples alone never carry.
 *
 * Everything here is *observed by the app* and therefore only as trustworthy as the app and the
 * OS, both of which are what the attestation pins. It is bound into the signed session record
 * via [digest], so it cannot be edited after the fact.
 *
 * Canonical encoding for the digest: `key=value` lines in the fixed order of [FIELDS], joined by
 * `\n`, UTF-8. Both sides re-derive it from the manifest's `context` object; JSON key order is
 * irrelevant.
 */
object RecordingContext {
    val FIELDS = listOf(
        "sampled_at",                 // epoch ms when this snapshot was taken
        "phase",                      // "start" | "stop"
        "debuggable",                 // is this a debuggable build (true = a debugger can attach)
        "install_source",             // installing package (null = adb / sideload)
        "accessibility_services",     // enabled accessibility services (can read the screen, inject input)
        "other_active_recorders",     // other clients recording audio at the same time
        "device_model",
        "os_release",
        "os_build",
        "security_patch",
    )

    fun snapshot(context: Context, phase: String): JSONObject {
        val pm = context.packageManager
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val installSource = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        }.getOrNull()
        val accessibility = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() }?.sorted() ?: emptyList()
        val otherRecorders = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Our own AudioRecord shows up too; count sessions that are not ours by client id when possible.
            am.activeRecordingConfigurations.count { it.clientAudioSessionId != ownAudioSessionId }
        }.getOrDefault(-1)

        return JSONObject().apply {
            put("sampled_at", System.currentTimeMillis())
            put("phase", phase)
            put("debuggable", debuggable)
            put("install_source", installSource ?: JSONObject.NULL)
            put("accessibility_services", accessibility.joinToString(":"))
            put("other_active_recorders", otherRecorders)
            put("device_model", Build.MODEL)
            put("os_release", Build.VERSION.RELEASE)
            put("os_build", Build.DISPLAY)
            put("security_patch", Build.VERSION.SECURITY_PATCH)
        }
    }

    /** Set by the audio recorder once its AudioRecord exists, so we do not count ourselves. */
    @Volatile var ownAudioSessionId: Int = -1

    fun canonical(snapshot: JSONObject): String =
        FIELDS.joinToString("\n") { k -> "$k=${snapshot.opt(k)?.takeIf { it != JSONObject.NULL } ?: ""}" }

    /** SHA-256 over the canonical encodings of all snapshots, in order, separated by `\n\n`. */
    fun digest(snapshots: List<JSONObject>): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(snapshots.joinToString("\n\n") { canonical(it) }.toByteArray(Charsets.UTF_8))
}
