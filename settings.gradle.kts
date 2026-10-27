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
