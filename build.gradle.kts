plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}

// The vendored :lorie module declares androidx.lifecycle 2.11.0, whose
// version-alignment constraints drag EVERY lifecycle artifact (including our
// compose ones) to 2.11.0 - which requires AGP 9.1 + compileSdk 37. lorie only
// uses viewModelScope basics, so pin the lifecycle group to 2.8.7
// (compileSdk 35 / AGP 8.7 compatible) across the whole build.
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "androidx.lifecycle") useVersion("2.8.7")
            // lorie declares kotlin-stdlib 2.4.21; its metadata (2.4.0) is
            // unreadable by our Kotlin 2.0.20 compiler. lorie applies no
            // kotlin plugin, so 2.0.20 stdlib is sufficient.
            if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) useVersion("2.0.20")
        }
    }
}
