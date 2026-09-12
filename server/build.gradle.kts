plugins {
    kotlin("jvm")
    application
}

group = "com.attestable"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // JSON parsing
    implementation("org.json:json:20231013")

    // Warden (A-SIT Plus) — server-side Android key attestation verification.
    // The former `at.asitplus:warden` artifact now lives on as Warden Supreme's
    // "makoto" module (Android + iOS). Use `at.asitplus.warden:roboto` instead if
    // you only ever need Android and want fewer transitive dependencies.
    implementation("at.asitplus.warden:makoto:1.1.4")

    // BouncyCastle for the ECDSA chunk-signature checks (version aligned with Warden)
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // Testing
    testImplementation(kotlin("test"))
}

application {
    applicationName = "attestable-verifier"
    mainClass.set("com.attestable.verifier.VerifierKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
