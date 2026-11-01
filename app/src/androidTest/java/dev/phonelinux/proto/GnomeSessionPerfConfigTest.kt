package dev.phonelinux.proto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-launch performance config (2026-11-01, POCO 25062PC34G, d7fe421):
 * with the gate fixed and the desktop reachable, the phone showed severe
 * stutter. Evidence from the 12:02 diagnostic export: gnome-shell loaded
 * Mesa's libLLVM (llvmpipe software compositing; DRI3 unavailable) at
 * 1080x1957, frame-present intervals of 0.5-4.5s during interaction with a
 * 19.4s stall, Terminal startup 16.3s, tracker-miner-fs-3 autostart
 * enabled, and 1/3 of the 390 KB guest log was mutter STACK debug spam.
 * These tests pin the bounded mitigations: narrow default mutter debug
 * topics, animations off every launch, and per-user tracker miner masks -
 * all reachable on the EXISTING rootfs (per-launch, no reinstall).
 */
class GnomeSessionPerfConfigTest {

    private val script = GuestUbuntu.gnomeSessionScript()

    @Test
    fun mutterDebugDefaultExcludesPerFrameAndPerRestackTopics() {
        assertTrue(
            "MUTTER_DEBUG must default to dbus,sm,startup via the LDOA_MUTTER_DEBUG_TOPICS override",
            script.contains("MUTTER_DEBUG=\"\${LDOA_MUTTER_DEBUG_TOPICS:-dbus,sm,startup}\"")
        )
        // The old fixed value must be gone: backend logs every cogl swap
        // (per-frame) and stack dumps the tracker on every restack.
        assertFalse(
            "fixed 'MUTTER_DEBUG=backend,dbus,stack,sm,startup' must not ship as the default",
            script.contains("export MUTTER_DEBUG=backend,dbus,stack,sm,startup")
        )
    }

    @Test
    fun animationsDisabledEveryLaunch() {
        assertTrue(
            "the per-launch config must disable GNOME animations (llvmpipe compositing turns transitions into multi-second stalls)",
            script.contains("gsettings set org.gnome.desktop.interface enable-animations false")
        )
    }

    @Test
    fun trackerMinersMaskedPerUser() {
        assertTrue(
            "tracker-miner-fs-3 must be masked via a per-user autostart override",
            script.contains("tracker-miner-fs-3")
        )
        assertTrue(
            "tracker-extract-3 must be masked via a per-user autostart override",
            script.contains("tracker-extract-3")
        )
        assertTrue(
            "the mask must use Hidden=true (overrides /etc/xdg/autostart entries)",
            script.contains("Hidden=true")
        )
    }

    @Test
    fun probesSampleCpuAndMemoryPressure() {
        assertTrue("probes must record loadavg per sample", script.contains("/proc/loadavg"))
        assertTrue("probes must record MemAvailable per sample", script.contains("MemAvailable"))
        assertTrue("probes must record cumulative shell CPU jiffies per sample", script.contains("shell_cpu_jiffies="))
    }
}
