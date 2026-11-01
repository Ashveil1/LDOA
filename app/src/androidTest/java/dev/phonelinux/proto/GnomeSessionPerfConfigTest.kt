package dev.phonelinux.proto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-launch performance config (2026-11-01, POCO 25062PC34G, d7fe421):
 * with the gate fixed and the desktop reachable, the phone showed severe
 * stutter. Evidence from the 12:02 export: frame-present intervals of
 * 0.5-4.5s during interaction with a 19.4s stall (presentation-path
 * timings, not pure render cost), Terminal startup 16.3s, and 1/3 of the
 * 390 KB guest log was mutter STACK debug spam. Renderer identity is NOT
 * established: libLLVM loading does not distinguish llvmpipe from other
 * gallium drivers, so the probes now capture X extensions and the full
 * DRI/gallium module set before any renderer claim.
 * Scope (main todo): user overrides/theme/dock must survive; no blanket
 * Tracker/Color/Keyboard masks. Animations get a reversible default only
 * when the user has no explicit setting.
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
    fun animationsDefaultIsGuardedAndReversible() {
        assertTrue(
            "the dconf guard must read the explicit user value before any default is written",
            script.contains("dconf read /org/gnome/desktop/interface/enable-animations")
        )
        assertTrue(
            "an explicit user setting must be preserved and logged",
            script.contains("animations preserved=")
        )
        assertTrue(
            "the reversible default path must be logged as applied only when no explicit setting exists",
            script.contains("animations default-applied=false")
        )
    }

    @Test
    fun noTrackerMinerMasking() {
        assertFalse(
            "tracker miner masks are outside approved scope (main todo: no blanket Tracker masks)",
            script.contains("tracker-miner-fs-3")
        )
        assertFalse(
            "tracker extract masks are outside approved scope",
            script.contains("tracker-extract-3")
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
