// Root build.gradle.kts
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Warden Supreme 1.1.x is compiled with Kotlin 2.4, so the Kotlin plugin
        // must be 2.4+ (which in turn needs AGP >= 8.5.2 and Gradle >= 7.6.3).
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
