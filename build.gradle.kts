// AGP >= 8.9.1 and compileSdk 36 are required by connect-client 1.1.0 (its AAR metadata enforces both).
// Versions pinned as a matched set: Compose compiler 1.5.8 (app/build.gradle.kts) requires Kotlin 1.9.22,
// and KSP versions are tied to the Kotlin version. Bump all three together.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
