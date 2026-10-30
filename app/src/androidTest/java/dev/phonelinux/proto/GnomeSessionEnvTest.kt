package dev.phonelinux.proto

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the POCO GNOME startup failure (user guest log 2026-10-30):
 * gnome-shell exited 1 with "Failed to configure: Unsupported session type"
 * and gnome-session stayed alive shell-less, so no fallback ever ran.
 * Cause: the session env lacked XDG_SESSION_TYPE while logind is renamed
 * away, and mutter's DISPLAY legacy fallback is gated on a logind "tty"
 * session (mutter 46.0 src/core/meta-context-main.c find_session_type).
 * The default gnome-session path must therefore carry the session type in
 * its env; the bare `gnome-shell --x11` fallback forces the backend and was
 * masking this gap on x86, where gnome-session dies early.
 */
class GnomeSessionEnvTest {

    @Test
    fun sessionScriptExportsX11SessionType() {
        val script = GuestUbuntu.gnomeSessionScript()
        assertTrue(
            "gnome-session.sh must export XDG_SESSION_TYPE=x11 so the default gnome-session path selects the X11 backend",
            script.contains("XDG_SESSION_TYPE=x11")
        )
    }

    @Test
    fun sessionTypeExportPrecedesGnomeSessionLaunch() {
        val script = GuestUbuntu.gnomeSessionScript()
        val exportAt = script.indexOf("XDG_SESSION_TYPE=x11")
        val launchAt = script.indexOf("gnome-session --session=ubuntu")
        assertTrue("session-type export must exist", exportAt >= 0)
        assertTrue("gnome-session launch must exist in the script", launchAt >= 0)
        assertTrue(
            "XDG_SESSION_TYPE must be exported before gnome-session starts (env propagates to gnome-session and the shell it spawns)",
            exportAt < launchAt
        )
    }

    @Test
    fun sessionScriptExportsUtf8LocaleBeforeLaunch() {
        val script = GuestUbuntu.gnomeSessionScript()
        val langAt = script.indexOf("LANG=C.UTF-8")
        val lcallAt = script.indexOf("LC_ALL=C.UTF-8")
        val launchAt = script.indexOf("gnome-session --session=ubuntu")
        assertTrue("gnome-session.sh must export LANG=C.UTF-8 (gnome-terminal-server exits status 8 under a non-UTF-8 locale)", langAt >= 0)
        assertTrue("gnome-session.sh must export LC_ALL=C.UTF-8", lcallAt >= 0)
        assertTrue("gnome-session launch must exist in the script", launchAt >= 0)
        assertTrue(
            "locale exports must precede the gnome-session launch (env propagates to the session bus daemon and D-Bus-activated servers)",
            langAt in 0 until launchAt && lcallAt in 0 until launchAt
        )
    }
}
