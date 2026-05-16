plugins {
    id("com.android.application") version "9.1.0" apply false
    alias(libs.plugins.kotlin.serialization) apply false
    id("com.google.devtools.ksp") version "2.3.4" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("androidx.room") version "2.8.4" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.8" apply false
}
