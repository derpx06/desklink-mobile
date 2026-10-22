import com.github.jk1.license.LicenseReportExtension
import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

buildscript {
    dependencies {
        classpath(libs.android.gradlePlugin)
        classpath(libs.kotlin.gradlePlugin)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dependencyLicenseReport)
    alias(libs.plugins.compose.compiler)
}

val licenseResDir = "$projectDir/build/dependency-license-res"

fun releaseSigningValue(name: String): String? =
    (providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull)
        ?.takeIf { it.isNotBlank() }

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

android {
    namespace = "org.desklink.mobile"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.desklink.mobile"
        minSdk = 23
        targetSdk = 36
        versionCode = 20000
        versionName = "2.0.0"
        resConfigs("en")
        // The DeskLink relay (docs/RELAY.md): the deployed relay's wss://<host>/v1
        // from the DESKLINK_RELAY_URL Gradle property or environment variable,
        // else a placeholder that reaches nothing. Release builds must set it
        // (docs/RELEASE.md). Users may override it in settings.
        // No relay is deployed by default: without DESKLINK_RELAY_URL the app has none until the
        // person enters one (Settings → More → Advanced network), and never looks one up.
        val relayUrl = releaseSigningValue("DESKLINK_RELAY_URL") ?: ""
        buildConfigField("String", "DEFAULT_RELAY_URL", "\"$relayUrl\"")
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main") {
        res.directories += licenseResDir
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources {
            merges += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")
        }
        jniLibs {
            // Termux's PTY JNI library. Unused: the terminal runs on the desktop, only the
            // emulator/renderer classes are used. It is also not built for 16 KB pages.
            excludes += "**/libtermux.so"
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The upload key (docs/RELEASE.md). Never in the repository: read from a
        // Gradle property (~/.gradle/gradle.properties or -P) or the environment.
        // Without it the release build is unsigned, which is what F-Droid builds.
        val uploadKeystore = releaseSigningValue("DESKLINK_UPLOAD_KEYSTORE")
        if (uploadKeystore != null) {
            create("release") {
                storeFile = file(uploadKeystore)
                storePassword = releaseSigningValue("DESKLINK_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningValue("DESKLINK_UPLOAD_KEY_ALIAS")
                keyPassword = releaseSigningValue("DESKLINK_UPLOAD_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

ksp {
    arg("com.albertvaka.classindexksp.annotations", "org.desklink.mobile.plugins.PluginFactory.LoadablePlugin")
}

dependencies {
    coreLibraryDesugaring(libs.android.desugarJdkLibsNio)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.constraintlayout.compose)

    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.extensions)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.androidx.gridlayout)
    implementation(libs.google.android.material)
    implementation(libs.disklrucache) //For caching album art bitmaps. FIXME: Not updated in 10+ years. Replace with Kache.
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.handroid)

    implementation(libs.bcpkix.jdk15on) //For SSL certificate generation

    ksp(libs.classindexksp)

    implementation(libs.commons.io)
    implementation(libs.commons.collections4)
    implementation(libs.commons.lang3)

    implementation(libs.univocity.parsers)
    implementation(libs.okhttp)
    implementation(libs.zxing.android.embedded) { isTransitive = false }
    implementation(libs.zxing.core)
    implementation(libs.webrtc)
    // F3: on-device OCR, model bundled (owner-approved for "Insert from phone").
    implementation(libs.mlkit.text.recognition)

    // Terminal emulation (Apache-2.0). Only the emulator core and TerminalRenderer are
    // used: there is no local PTY, output comes from the desktop over WebRTC.
    implementation(libs.termux.terminal.emulator)
    implementation(libs.termux.terminal.view)

    // Kotlin
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.slf4j.simple) // do not try to use the Android logger backend in tests
    testImplementation(libs.jsonassert)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)

    // For device controls
    implementation(libs.reactive.streams)
    implementation(libs.rxjava)
}

licenseReport {
    configurations = LicenseReportExtension.ALL
    renderers = arrayOf<ReportRenderer>(TextReportRenderer())
}

tasks.named("generateLicenseReport") {
    val outputFile = file("$licenseResDir/raw/license")
    val inputFiles = files(
        layout.projectDirectory.file("COPYING"),
        layout.buildDirectory.file("reports/dependency-license/THIRD-PARTY-NOTICES.txt")
    )
    outputs.file(outputFile)
    doLast {
        outputFile.apply {
            parentFile.mkdirs()
            writeText(inputFiles.joinToString(separator = "\n") { it.readText() })
        }
    }
}

tasks.named("preBuild") {
    dependsOn("generateLicenseReport")
}
