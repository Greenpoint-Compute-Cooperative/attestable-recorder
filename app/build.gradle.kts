import java.net.URL
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: a keystore that is NOT the well-known Android debug key. Create one with
//   scripts/make-release-keystore.sh
// which writes keystore.properties (gitignored). Without it, assembleRelease produces an unsigned APK.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.attestable.recorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.attestable.recorder"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Not debuggable: a debugger cannot attach, and the attestation context reports debuggable=false.
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // `-PunsignedRelease` yields app-release-unsigned.apk: the reproducibility target.
            // Anyone can rebuild it and compare hashes; signing is a separate, non-deterministic step (ECDSA).
            if (!project.hasProperty("unsignedRelease")) {
                signingConfigs.findByName("release")?.let { signingConfig = it }
            }
            // Do not embed the git commit / dirty state into the APK: the build must depend only on the sources.
            vcsInfo { include = false }
        }
    }

    // Reproducibility: AGP's javac (BuildConfig etc.) and Kotlin must run on the same JDK major everywhere.
    // The pinned toolchain is JDK 17; see REPRODUCIBLE_BUILD.md and Dockerfile.

    buildFeatures {
        buildConfig = true
    }

    // Two products from one codebase:
    //   offline — the verifiable recorder. NO INTERNET permission: provably cannot exfiltrate.
    //   room    — the credible sensor for vibecode-room: streams signed audio / hand / gesture
    //             data to a room server on the LAN. Needs INTERNET, so it is a separate
    //             package (com.attestable.recorder.room) and the attestation says which one ran.
    flavorDimensions += "mode"
    productFlavors {
        create("offline") {
            dimension = "mode"
            isDefault = true
        }
        create("room") {
            dimension = "mode"
            applicationIdSuffix = ".room"
            versionNameSuffix = "-room"
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room flavor: on-device MediaPipe models, downloaded at build time and pinned by SHA-256 so the
// build depends only on these coordinates (the files are gitignored).
val roomModels = mapOf(
    "hand_landmarker.task" to Pair(
        "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task",
        "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1",
    ),
    "pose_landmarker_lite.task" to Pair(
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task",
        "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a",
    ),
)
val roomModelDir = layout.projectDirectory.dir("src/room/assets/models")
val downloadRoomModels by tasks.registering {
    outputs.dir(roomModelDir)
    doLast {
        val dir = roomModelDir.asFile.apply { mkdirs() }
        roomModels.forEach { (name, spec) ->
            val (url, sha) = spec
            val target = File(dir, name)
            fun digest(): String = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { b -> "%02x".format(b) }
            if (!target.exists() || digest() != sha) {
                logger.lifecycle("Downloading $name")
                URL(url).openStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                check(digest() == sha) { "$name: SHA-256 mismatch after download" }
            }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.contains("Room") && name.endsWith("Assets")) dependsOn(downloadRoomModels)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // BouncyCastle for cryptographic operations
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // CBOR for attestation encoding
    implementation("co.nstant.in:cbor:0.9")

    // Room flavor only: HTTP/WebSocket client and on-device hand / body-pose tracking.
    "roomImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
    "roomImplementation"("com.google.mediapipe:tasks-vision:0.10.35")
}
