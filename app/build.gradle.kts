import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jlleitschuh.gradle.ktlint")
    id("androidx.room")
    id("org.jetbrains.kotlinx.kover")
}

apply(plugin = "com.google.android.gms.oss-licenses-plugin")

android {
    namespace = "com.urik.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.urik.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.2.2-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            isDebuggable = false
            isJniDebuggable = false
            isShrinkResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true

            all {
                it.jvmArgs(
                    "-XX:+EnableDynamicAgentLoading",
                    "-Djdk.instrument.traceUsage",
                )
            }
        }
    }
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*Fragment",
                    "*Fragment$*",
                    "*Activity",
                    "*Activity$*",
                    "*.databinding.*",
                    "*.BuildConfig",
                    "*_Factory",
                    "*_HiltModules*",
                    "*Hilt_*",
                    "dagger.hilt.*",
                )
            }
        }

        total {
            html {
                onCheck = true
            }
            xml {
                onCheck = true
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.core.ktx)
    ksp(libs.hilt.android.compiler)

    implementation(libs.icu4j)

    implementation(libs.symspellkt)

    implementation(libs.androidx.preference.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.emoji2.emojipicker)
    implementation(libs.androidx.emoji2)

    implementation(libs.android.database.sqlcipher)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.play.services.oss.licenses)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.core.ktx)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.runner)
}
