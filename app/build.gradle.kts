plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.phonelinux.proto"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.phonelinux.proto"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-spike"
        // Stamp the exact source the APK was built from so a phone log can
        // never again be read against the wrong code revision.
        buildConfigField("String", "GIT_SHA", "\"" + gitSha() + "\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Ship only ABIs with a complete native set (proot + loaders +
        // lorie); the armeabi-v7a/x86 builds lack proot and would install
        // fine but fail at runtime. Better: no install on those devices.
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // extractNativeLibs=true requires legacy (uncompressed) packaging so our
        // renamed executables land as real files in nativeLibraryDir with exec bit.
        // EXECUTION-CRITICAL: CI proved filesDir extraction is blocked by W^X
        // (error=13) at targetSdk 34; nativeLibraryDir is the only verified path.
        jniLibs { useLegacyPackaging = true }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation(project(":lorie"))
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.core:core-ktx:1.13.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

/** Best-effort source stamp; "unknown" when the build host has no git
 *  checkout metadata. Never fails the build. */
fun gitSha(): String = try {
    val p = ProcessBuilder("git", "rev-parse", "--short=12", "HEAD").directory(rootDir).start()
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
    if (p.waitFor() == 0 && out.isNotEmpty()) out else "unknown"
} catch (e: Exception) { "unknown" }
