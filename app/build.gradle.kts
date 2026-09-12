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

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // BouncyCastle for cryptographic operations
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // CBOR for attestation encoding
    implementation("co.nstant.in:cbor:0.9")
}
