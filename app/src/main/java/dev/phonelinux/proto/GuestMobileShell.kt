package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.roundToInt

/**
 * LDOA mobile desktop mode (Phase A, user-approved 2026-10-29): an
 * additive session on the SAME Ubuntu rootfs - a touch-sized light-WM
 * session instead of GNOME/mutter. The WM is transparently REUSED (jwm
 * from Ubuntu noble universe); ours is the session design: tall touch
 * tray, large fonts and Xft.dpi from the device's real density, and the
 * panel/background choices from MobileModeSettings.
 *
 * Coexistence contract (brokered with the GNOME worker): one shared
 * lorie X server (:0), exactly one WM session at a time. A mode switch
 * is a VERIFIED stop of the current session tree, THEN the launch of
 * the other; a failed stop must fail closed (no second WM ever). This
 * session's processes are tagged PLINUX_SESSION=mobile; the GNOME and
 * Alpine lanes currently share PLINUX_SESSION=desktop over a host-bound
 * /proc, which is a latent cross-kill hazard for any guest-global tag
 * scan - teardown must walk host parentage from the tracked proot root
 * (XServer.stopDesktopMode, owned by the GNOME worker), never a
 * guest-wide environ scan.
 *
 * Integration seam (GNOME worker's shared files, sequenced after the
 * trial APK path): XServer.openDesktop(..., mode), stopDesktopMode and
 * the picker entry with the mode-switch warning. Until then this object
 * is exercised only by the dispatch-only CI lane (mobile-desktop.yml).
 */
object GuestMobileShell {
    private const val TAG = "PhoneLinuxMobile"

    /** noble main/universe, all verified on packages.ubuntu.com
     *  2026-10-29. Independent of the GNOME package set: mobile mode must
     *  not require a prior GNOME provision. */
    private val MOBILE_PACKAGES = listOf(
        "dbus-x11",
        "jwm",
        "xterm",
        "x11-utils",         // xprop, xwininfo, xdpyinfo, xsetroot
        "x11-xserver-utils", // xrdb for Xft.dpi
        "xdotool",
        "pcmanfm",
        "fonts-dejavu-core",
        "hicolor-icon-theme"
    )

    fun hasMobileShell(rootfs: File): Boolean = File(rootfs, "usr/bin/jwm").isFile

    private fun apt(ctx: Context, rootfs: File, vararg args: String): Proot.Result =
        Proot.runGuest(ctx, rootfs, listOf("apt-get") + args.toList(),
            extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"), fakeRoot = true)

    /** apt-get update + install the mobile set. One retry on failure:
     *  apt fetch hiccups under proot are transient (same pattern as the
     *  GNOME provision). */
    fun provisionMobileShell(ctx: Context, rootfs: File): Proot.Result {
        var r = apt(ctx, rootfs, "update")
        if (r.exitCode != 0) {
            Thread.sleep(5_000)
            r = apt(ctx, rootfs, "update")
            if (r.exitCode != 0) return r
        }
        r = apt(ctx, rootfs, "install", "-y", "--no-install-recommends", *MOBILE_PACKAGES.toTypedArray())
        if (r.exitCode != 0) {
            Thread.sleep(5_000)
            r = apt(ctx, rootfs, "install", "-y", "--no-install-recommends", *MOBILE_PACKAGES.toTypedArray())
        }
        Log.i(TAG, "mobile shell provision exit=" + r.exitCode)
        return r
    }

    private fun xftDpi(ctx: Context, ui: MobileUiPrefs): Int =
        (ctx.resources.displayMetrics.densityDpi * ui.scale).roundToInt().coerceIn(96, 768)

    private fun termFontSize(ui: MobileUiPrefs): Int =
        (13 * ui.scale).roundToInt().coerceIn(10, 26)

    private fun jwmrc(ui: MobileUiPrefs): String {
        val trayH = if (ui.panelSize == "large") 64 else 48
        val trayY = if (ui.panelPosition == "top") "0" else "-1"
        val menuFont = (13 * ui.scale).roundToInt().coerceIn(10, 30)
        val titleFont = (12 * ui.scale).roundToInt().coerceIn(10, 26)
        val titleH = (30 * ui.scale).roundToInt().coerceIn(24, 60)
        val termFont = termFontSize(ui)
        return """
            <JWM>
              <!-- LDOA mobile mode: touch-sized layout on a transparently
                   reused jwm. Font/tray sizes derive from MobileModeSettings
                   and the device's real density. -->
              <RootMenu onroot="1">
                <Program label="Terminal">xterm -fa "DejaVu Sans Mono" -fs $termFont</Program>
                <Program label="Files">pcmanfm /root</Program>
                <Separator/>
                <Restart label="Restart"/>
                <Exit label="Exit"/>
              </RootMenu>
              <MenuStyle><Font>DejaVu Sans-$menuFont</Font></MenuStyle>
              <WindowStyle><Font>DejaVu Sans-$titleFont</Font><Width>5</Width><Height>$titleH</Height></WindowStyle>
              <TrayStyle><Font>DejaVu Sans-$menuFont</Font></TrayStyle>
              <TaskListStyle><Font>DejaVu Sans-$menuFont</Font></TaskListStyle>
              <PagerStyle><Font>DejaVu Sans-8</Font></PagerStyle>
              <Tray x="0" y="$trayY" height="$trayH">
                <TrayButton label="LDOA">root:1</TrayButton>
                <Pager/>
                <TaskList/>
                <Clock format="%H:%M"/>
              </Tray>
            </JWM>
            """.trimIndent() + "\n"
    }

    /** Launch the mobile session against the embedded X server. The proot
     *  invocation is cloned from GuestUbuntu.launchGnomeSession (fake root,
     *  loaders, binds, TMPDIR) - keep the two in sync when that changes. */
    fun launchMobileSession(ctx: Context, rootfs: File): Process {
        val ui = MobileModeSettings.read(ctx)
        val dpi = xftDpi(ctx, ui)
        val trayH = if (ui.panelSize == "large") 64 else 48
        File(rootfs, "root/.jwmrc").writeText(jwmrc(ui))
        File(rootfs, "root/.Xresources").writeText(
            "Xft.dpi: $dpi\nXft.antialias: 1\nXft.hinting: 1\nXft.rgba: rgb\n")
        File(rootfs, "root/mobile-session.sh").writeText(
            """
            #!/bin/sh
            export DISPLAY=:0 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg
            # Session identity: mobile lane. Teardown walks host parentage
            # from the tracked proot root - never a guest-global tag scan
            # (GNOME/Alpine share the host /proc through the proot bind).
            export PLINUX_SESSION=mobile
            mkdir -p /tmp/xdg
            chmod 0700 /tmp/xdg
            # Identity fix (cloned from GuestUbuntu): GTK components call
            # getpwuid(getuid()) and fail for the Android app uid.
            UID_NOW=${'$'}(id -u); GID_NOW=${'$'}(id -g)
            echo "guest-identity: uid=${'$'}UID_NOW gid=${'$'}GID_NOW"
            if ! awk -F: '${'$'}3 == u {f=1} END {exit !f}' u="${'$'}UID_NOW" /etc/passwd; then
              echo "plinux:x:${'$'}UID_NOW:${'$'}GID_NOW:Phone Linux:/root:/bin/sh" >> /etc/passwd
              echo "guest-identity: added passwd entry for uid ${'$'}UID_NOW"
            fi
            if ! awk -F: '${'$'}3 == g {f=1} END {exit !f}' g="${'$'}GID_NOW" /etc/group; then
              echo "plinux:x:${'$'}GID_NOW:" >> /etc/group
            fi
            # Fresh session bus per launch (load-bearing, run 40729917335):
            # a killed session's bus address lingers in the X root window
            # while the shared X server keeps running.
            xprop -root -remove _DBUS_SESSION_BUS_ADDRESS 2>/dev/null
            xprop -root -remove _DBUS_SESSION_BUS_PID 2>/dev/null
            eval ${'$'}(dbus-launch --sh-syntax) 2>&1
            echo "mobile-launch: session bus ${'$'}DBUS_SESSION_BUS_ADDRESS"
            echo ${'$'}DBUS_SESSION_BUS_PID > /root/.mobile-bus.pid 2>/dev/null
            xrdb -merge /root/.Xresources 2>&1
            xsetroot -solid "${ui.background}"
            echo "mobile-launch: starting jwm (tray=${trayH}px dpi=$dpi scale=${ui.scale})"
            jwm &
            WMPID=${'$'}!
            sleep 5
            if kill -0 ${'$'}WMPID 2>/dev/null; then
              echo "mobile-launch: jwm alive pid=${'$'}WMPID"
            else
              wait ${'$'}WMPID 2>/dev/null
              echo "mobile-launch: jwm died exit=${'$'}? - NO FALLBACK (fail closed: exactly one WM may own :0)"
              exit 1
            fi
            fc-cache -f >/dev/null 2>&1
            pcmanfm --desktop &
            xterm -fa "DejaVu Sans Mono" -fs ${'$'}{TERM_FONT:-13} -geometry 100x30+40+120 &
            wait
            """.trimIndent() + "\n")
        val nd = ctx.applicationInfo.nativeLibraryDir
        val log = File(ctx.filesDir, "mobile-desktop.log")
        val pb = ProcessBuilder(
            File(nd, "libproot.so").absolutePath,
            "--link2symlink",
            "-0", // fake root: session components + dbus expect euid 0
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix",
            "-w", "/root",
            "/bin/sh", "/root/mobile-session.sh"
        )
        pb.environment().putAll(mapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd,
            "HOME" to "/root",
            "DISPLAY" to ":0",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to Proot.prootTmpDir(ctx).absolutePath,
            // Same TMPDIR leak as Proot.exec (see there).
            "TMPDIR" to "/tmp",
            // Passed through proot into the session script's xterm launch.
            "TERM_FONT" to termFontSize(ui).toString()
        ))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        Log.i(TAG, "launching mobile session, log=$log")
        return pb.start()
    }
}
