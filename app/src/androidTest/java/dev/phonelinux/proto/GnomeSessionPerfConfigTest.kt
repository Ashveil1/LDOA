package dev.phonelinux.proto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stutter-mitigation scope (2026-11-01, POCO 25062PC34G, d7fe421):
 * with the gate fixed and the desktop reachable, the phone showed severe
 * stutter. Evidence from the 12:02 export: frame-present intervals of
 * 0.5-4.5s during interaction with a 19.4s stall (presentation-path
 * timings, not pure render cost), Terminal startup 16.3s, and 1/3 of the
 * 390 KB guest log was mutter STACK debug spam. Renderer identity is NOT
 * established (libLLVM loads for any gallium driver), so the probes now
 * capture X extensions and the full DRI/gallium module set before any
 * renderer claim.
 * Scope (main): reduced debug overhead + bounded read-only diagnostics
 * ONLY. No user-preference mutation: no animations write (any guard is
 * unauthoritative without dconf profile/backend grounding), no tracker
 * miner masks, user overrides/theme/dock survive.
 */
class GnomeSessionPerfConfigTest {

    private val script = GuestUbuntu.gnomeSessionScript()

    @Test
    fun mutterDebugDefaultExcludesPerFrameAndPerRestackTopics() {
        assertTrue(
            "MUTTER_DEBUG must default to dbus,sm,startup via the LDOA_MUTTER_DEBUG_TOPICS override",
            script.contains("MUTTER_DEBUG=\"\${LDOA_MUTTER_DEBUG_TOPICS:-dbus,sm,startup}\"")
        )
        assertFalse(
            "fixed 'MUTTER_DEBUG=backend,dbus,stack,sm,startup' must not ship as the default",
            script.contains("export MUTTER_DEBUG=backend,dbus,stack,sm,startup")
        )
    }

    @Test
    fun noUserPreferenceMutation() {
        assertFalse(
            "the script must never write the animations preference (no guard is authoritative without dconf profile/backend grounding)",
            script.contains("enable-animations")
        )
        assertFalse(
            "tracker miner masks are outside approved scope (main todo: no blanket Tracker masks)",
            script.contains("tracker-miner-fs-3")
        )
        assertFalse(
            "tracker extract masks are outside approved scope",
            script.contains("tracker-extract-3")
        )
        assertFalse("dconf must not be invoked (and never installed for this)", script.contains("dconf read"))
    }

    /**
     * Script-content coverage only (LIMITED - pins the script shape, not
     * runtime shell behavior): the dock recovery must be additive. The
     * pre-2026-11-01 full-array `gsettings set enabled-extensions` write
     * silently dropped user-added extensions every launch; recovery is
     * `gnome-extensions enable` alone, whose D-Bus and gsettings fallback
     * paths are both source-verified additive/no-op-when-present against
     * the exact guest package (gnome-shell 46.0-0ubuntu6~24.04.16;
     * shellDBus.js -> extensionManager.enableExtension pushes only when
     * missing; settings_list_add appends only when missing).
     */
    @Test
    fun dockRecoveryIsAdditiveOnly() {
        assertFalse(
            "no full-array enabled-extensions write (it silently dropped user extensions every launch)",
            script.contains("gsettings set org.gnome.shell enabled-extensions")
        )
        assertTrue(
            "the additive dock recovery must be retained",
            script.contains("gnome-extensions enable ubuntu-dock@ubuntu.com")
        )
        assertTrue(
            "the enabled-extensions evidence read must be retained",
            script.contains("gsettings get org.gnome.shell enabled-extensions")
        )
    }

    @Test
    fun probesCaptureRendererAndPressureEvidence() {
        assertTrue("probes must record loadavg per sample", script.contains("/proc/loadavg"))
        assertTrue("probes must record MemAvailable per sample", script.contains("MemAvailable"))
        assertTrue("probes must record cumulative shell CPU jiffies per sample", script.contains("shell_cpu_jiffies="))
        assertTrue(
            "the shell module scan must include gallium and per-driver *_dri.so so the ACTIVE driver is visible",
            script.contains("gallium|_dri")
        )
        assertTrue("probes must capture the X extension inventory once per session", script.contains("x_extensions_begin"))
    }
}
