pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "phonelinux-proto"
include(":app")

// Vendored termux-x11 (GPLv3), pinned commit: see third_party/termux-x11 gitlink
// and THIRD_PARTY_NOTICES.md. :lorie is an Android library module; our app
// depends on it and launches its MainActivity for the X desktop.
include(":lorie")
project(":lorie").projectDir = file("third_party/termux-x11/lorie")
include(":shell-loader:stub")
project(":shell-loader:stub").projectDir = file("third_party/termux-x11/shell-loader/stub")

// Gradle 8.9 compat patch for the vendored tree (upstream expects Gradle 9's
// ProjectDependency.getPath). Applied at configuration time so a clean
// recursive checkout builds identically; the gitlink stays at the pristine
// upstream pin. Recorded in THIRD_PARTY_NOTICES.md.
run {
    val f = File(rootDir, "third_party/termux-x11/lorie/build.gradle")
    if (f.exists()) {
        val orig = "it instanceof ProjectDependency && it.path == project.path"
        val patched = "it instanceof ProjectDependency && it.dependencyProject.path == project.path"
        val text = f.readText()
        if (orig in text) f.writeText(text.replace(orig, patched))
        check(patched in f.readText()) { "lorie Gradle 8.9 compat patch failed to apply" }
    }
}
