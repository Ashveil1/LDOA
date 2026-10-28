package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Ubuntu userland GUI: provisions GNOME inside the installed Ubuntu Base
 * rootfs and launches `gnome-shell --x11` against the embedded lorie X
 * server (B: standard Ubuntu layout).
 *
 * STATUS: feasibility UNPROVEN on our stack. Community evidence that
 * Ubuntu GNOME can run under proot + termux-x11 exists
 * (atamshkai/Ubuntu-Gnome-Desktop-On-Termux-x11, LinuxDroidMaster
 * startgnome_ubuntu.sh, docs.udroid.org termux-x11 page), all using the
 * workarounds applied in the session script below; one recipe warns the
 * desktop breaks after a system upgrade, and a Mali/VirGL crash report
 * exists. We run software rendering, so the first goal is "does
 * gnome-shell --x11 start and stay up", captured via guest-gnome.log.
 */
object GuestUbuntu {
    private const val TAG = "PhoneLinuxGnome"

    // Ubuntu-appearance set, tracked explicitly per directive: vanilla
    // GNOME alone is NOT "standard Ubuntu". The Ubuntu look = ubuntu-session
    // (session gsettings overrides via ubuntu-settings) + Yaru themes +
    // ubuntu-wallpapers + ubuntu-dock + the GNOME top bar. Verified by
    // real-render screenshot pixel inspection, not just shell liveness.
    // --no-install-recommends keeps snapd and the full ubuntu-desktop meta
    // out; snapd cannot work without systemd.
    private val GNOME_PACKAGES = listOf(
        "dbus-x11",
        "gnome-shell",
        "gnome-session",
        "ubuntu-session",
        "ubuntu-settings",
        "gnome-shell-extension-ubuntu-dock",
        "yaru-theme-gnome-shell",
        "yaru-theme-gtk",
        "yaru-theme-icon",
        "ubuntu-wallpapers",
        "gnome-terminal",
        "nautilus",
        "adwaita-icon-theme",
        "fonts-dejavu-core"
    )

    fun hasGnome(rootfs: File): Boolean = File(rootfs, "usr/bin/gnome-shell").isFile

    // fakeRoot: apt/dpkg refuse to install as a non-root euid; proot -0
    // fakes uid 0 inside the guest (same trick proot-distro uses).
    private fun apt(ctx: Context, rootfs: File, vararg args: String): Proot.Result =
        Proot.runGuest(ctx, rootfs, listOf("apt-get") + args.toList(),
            extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"), fakeRoot = true)

    /** apt-get update + install the GNOME set. One retry on failure: apt
     *  fetch hiccups under proot are transient. */
    fun provisionGnome(ctx: Context, rootfs: File): Proot.Result {
        var r = apt(ctx, rootfs, "update")
        if (r.exitCode != 0) {
            Thread.sleep(5_000)
            r = apt(ctx, rootfs, "update")
            if (r.exitCode != 0) return r
        }
        r = apt(ctx, rootfs, "install", "-y", "--no-install-recommends", *GNOME_PACKAGES.toTypedArray())
        if (r.exitCode != 0) {
            Thread.sleep(5_000)
            r = apt(ctx, rootfs, "install", "-y", "--no-install-recommends", *GNOME_PACKAGES.toTypedArray())
        }
        Log.i(TAG, "gnome provision exit=" + r.exitCode)
        return r
    }

    /** Launch the GNOME session: workarounds first, then gnome-shell --x11
     *  as the session leader (exec keeps it proot-root's direct child). */
    fun launchGnomeSession(ctx: Context, rootfs: File): Process {
        File(rootfs, "root/gnome-session.sh").writeText(
            """
            #!/bin/sh
            export DISPLAY=:0 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg XDG_CURRENT_DESKTOP=GNOME
            export PLINUX_SESSION=desktop
            mkdir -p /tmp/xdg
            chmod 0700 /tmp/xdg
            # Same identity fix as the Alpine session: GNOME components call
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
            # proot workarounds from the community GNOME recipes. Each is
            # logged verbosely so a failure can be tied to the workaround
            # that did/did not address it (directive: concrete failures/logs,
            # not cargo-culted changes).
            # - systemd-logind client libraries abort session startup under
            #   proot; rename them away (idempotent: *.back already moved).
            #   CAVEAT (consequential, keep): an apt upgrade of the owning
            #   packages restores the files and the session breaks again
            #   until this script re-runs.
            for f in ${'$'}(find /usr -type f -iname "*login1*" 2>/dev/null); do mv -v "${'$'}f" "${'$'}f.back"; done
            echo "workaround-login1: ${'$'}(find /usr -type f -iname '*login1*' 2>/dev/null | wc -l) remaining"
            # - the dbus launch helper must be setuid to spawn the session bus.
            #   CAVEAT (consequential, keep): this relaxes a guest file's
            #   permissions vs upstream packaging; scoped to the dbus helper
            #   only, no other setuid changes.
            chmod u+s /usr/lib/dbus-1.0/dbus-daemon-launch-helper 2>/dev/null
            mkdir -p /run/dbus
            dbus-daemon --system --fork 2>/dev/null || service dbus start 2>/dev/null
            echo "gnome-launch: trying gnome-session --session=ubuntu (top bar + ubuntu-dock + yaru)"
            gnome-session --session=ubuntu 2>&1 &
            GSPID=${'$'}!
            sleep 20
            if kill -0 ${'$'}GSPID 2>/dev/null; then
              echo "gnome-launch: gnome-session alive pid=${'$'}GSPID"
              wait ${'$'}GSPID
              echo "gnome-launch: gnome-session exited=${'$'}?"
            else
              wait ${'$'}GSPID 2>/dev/null
              echo "gnome-launch: gnome-session died exit=${'$'}? - falling back to bare gnome-shell --x11"
              exec gnome-shell --x11
            fi
            """.trimIndent() + "\n")
        val nd = ctx.applicationInfo.nativeLibraryDir
        val log = File(ctx.filesDir, "guest-gnome.log")
        val pb = ProcessBuilder(
            File(nd, "libproot.so").absolutePath,
            "--link2symlink",
            "-v", "5", // investigation: log syscall virtualization + failures
            "-0", // fake root: GNOME session components + dbus expect euid 0
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix",
            "-w", "/root",
            "/bin/sh", "/root/gnome-session.sh"
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
            "TMPDIR" to "/tmp"
        ))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        Log.i(TAG, "launching GNOME session, log=$log")
        return pb.start()
    }
}
