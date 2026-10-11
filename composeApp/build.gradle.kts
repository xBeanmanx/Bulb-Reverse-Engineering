import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.materialKolor)
            implementation(libs.kotlinx.datetime)
            implementation(libs.colorpicker.compose)
            implementation(libs.multiplatform.settings.noArg)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

android {
    namespace = "com.jbsmartbulb"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jbsmartbulb"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "3.0"
    }
    // Release signing comes from keystore.properties (not in git). Without it, release builds are
    // unsigned and the debug build is the one to install.
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val keystore = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // AGP 8.13's bundled lint reads Kotlin metadata only up to 2.2 and prints noisy errors for
        // this Kotlin 2.4 build; it never blocks it. Run `./gradlew lint` explicitly if wanted.
        checkReleaseBuilds = false
    }
}

compose.desktop {
    application {
        mainClass = "com.jbsmartbulb.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Smart Bulb app"
            packageVersion = "3.0.0"
        }
    }
}
