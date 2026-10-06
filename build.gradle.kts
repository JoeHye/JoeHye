// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so there is no org.jetbrains.kotlin.android plugin.
// The Compose compiler plugin's version sets the Kotlin version AGP uses; keep it on a stable Kotlin.
// KSP 2.3+ is versioned independently of Kotlin.
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.room") version "2.8.5" apply false
}
