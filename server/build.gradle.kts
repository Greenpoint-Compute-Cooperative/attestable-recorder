plugins {
    kotlin("jvm")
    application
}

group = "com.attestable"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // JSON parsing
    implementation("org.json:json:20231013")

    // BouncyCastle for crypto and X.509 verification
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

application {
    mainClass.set("com.attestable.verifier.VerifierKt")
}

kotlin {
    jvmToolchain(17)
}
