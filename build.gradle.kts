buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.oss.licenses.plugin)
    }
}

plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("com.google.devtools.ksp") version "2.3.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0" apply false
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
    id("androidx.room") version "2.8.3" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.3" apply false
}

tasks.configureEach {
    if (name.contains("OssLicenses")) {
        notCompatibleWithConfigurationCache("OSS Licenses plugin doesn't support Gradle 9.2 config cache yet")
    }
}
