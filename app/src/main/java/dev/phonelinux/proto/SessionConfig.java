package dev.phonelinux.proto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Random;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * App-owned, per-launch config directory for the mobile session:
 * jwmrc, Xresources, the session script and the pcmanfm desktop
 * profile live here.
 *
 * Every launch gets a FRESH uniquely named session dir under
 * /root/.config-ldoa; every directory and file inside is created
 * exclusively (createDirectory / CREATE_NEW), so no pre-existing path -
 * regular file, user edit, symlink, or dangling symlink - is ever
 * replaced, renamed, deleted, or written through. Old session dirs are
 * preserved untouched (bounded cleanup is tracked separately and is NOT
 * part of this change). The user's /root/.jwmrc, /root/.Xresources,
 * /root/mobile-session.sh and ~/.config/pcmanfm are never read or
 * written: the proot launch runs <session>/mobile-session.sh, jwm is
 * launched with -f <session>/jwmrc, xrdb -merge with
 * <session>/Xresources, and pcmanfm with XDG_CONFIG_HOME set to this
 * dir.
 *
 * pcmanfm format verified against the noble version's source (pcmanfm
 * 1.3.2, app-config.c/desktop.c): profile dir
 * <XDG_CONFIG_HOME>/pcmanfm/default/, desktop file
 * desktop-items-0.conf, group [*], keys wallpaper_mode (name "color")
 * and desktop_bg (#RRGGBB via gdk_color_parse). With NO config file the
 * loader leaves desktop_bg zeroed (#000000) and a live pcmanfm
 * --desktop paints solid black over the xsetroot color; whether pcmanfm
 * was the painter in the run-41449407107 frame remains HYPOTHESIS
 * pending the run log.
 *
 * Documented bounded limitation (NOT user-approved scope, local
 * same-uid threat model only): the component checks are
 * check-then-create, so a same-uid process that concurrently swaps an
 * ancestor between our lstat and our create could redirect the create.
 * The JVM exposes no dirfd/openat-handle API to close that window; the
 * exclusive CREATE_NEW write still guarantees no pre-existing PATH is
 * replaced at write time.
 */
final class SessionConfig {
    private static final Pattern HEX_BG = Pattern.compile("#[0-9A-Fa-f]{6}");

    private SessionConfig() {}

    /** lstat without following links; null = absent; errors propagate. */
    private static BasicFileAttributes lstat(Path p) throws IOException {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /**
     * Creates this launch's session config dir and writes the managed
     * pcmanfm profile into it; returns the session dir (pass it to
     * pcmanfm as XDG_CONFIG_HOME), or null when the path is unsafe or a
     * create/write fails. Null is a skip-desktop signal for the caller,
     * never a fallback to shared/user paths.
     */
    static Path prepare(Path rootfs, String background, String jwmrc,
                        String xresources, String sessionScript,
                        Consumer<String> warn) {
        if (!HEX_BG.matcher(background).matches()) {
            warn.accept("session config: background not #RRGGBB - desktop disabled");
            return null;
        }
        // rootfs itself and every component down must be a real,
        // non-symlink directory (the last may be absent; we create it).
        // lstat distinguishes absent from unknown/permission errors -
        // Files.exists() would conflate them and treat errors as absent.
        Path[] chain = { rootfs, rootfs.resolve("root"),
            rootfs.resolve("root/.config-ldoa") };
        for (int i = 0; i < chain.length; i++) {
            BasicFileAttributes at;
            try {
                at = lstat(chain[i]);
            } catch (IOException | SecurityException e) {
                warn.accept("session config: cannot stat " + chain[i] +
                    " (" + e.getClass().getSimpleName() + ") - desktop disabled");
                return null;
            }
            if (at == null) {
                if (i < chain.length - 1) {
                    warn.accept("session config: missing component " + chain[i] +
                        " - desktop disabled");
                    return null;
                }
            } else if (at.isSymbolicLink() || !at.isDirectory()) {
                warn.accept("session config: unsafe path component " + chain[i] +
                    " (symlink or non-dir) - desktop disabled");
                return null;
            }
        }
        Path appRoot = chain[chain.length - 1];
        try {
            Files.createDirectories(appRoot);
        } catch (IOException | SecurityException e) {
            warn.accept("session config: cannot create " + appRoot +
                " (" + e.getClass().getSimpleName() + ") - desktop disabled");
            return null;
        }
        byte[] desktopItems = (
            "# ldoa-managed: per-session profile, never edited in place\n" +
            "[*]\nwallpaper_mode=color\nwallpaper_common=1\n" +
            "desktop_bg=" + background + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] jwmrcB = jwmrc.getBytes(StandardCharsets.UTF_8);
        byte[] xresB = xresources.getBytes(StandardCharsets.UTF_8);
        byte[] scriptB = sessionScript.getBytes(StandardCharsets.UTF_8);
        Random rng = new Random();
        for (int attempt = 0; attempt < 5; attempt++) {
            Path session = appRoot.resolve("session-" + System.currentTimeMillis() +
                "-" + Integer.toHexString(rng.nextInt(0x10000)));
            try {
                Files.createDirectory(session);
                Files.write(session.resolve("jwmrc"), jwmrcB, StandardOpenOption.CREATE_NEW);
                Files.write(session.resolve("Xresources"), xresB, StandardOpenOption.CREATE_NEW);
                Files.write(session.resolve("mobile-session.sh"), scriptB, StandardOpenOption.CREATE_NEW);
                Files.createDirectory(session.resolve("pcmanfm"));
                Path def = Files.createDirectory(session.resolve("pcmanfm/default"));
                Files.write(def.resolve("desktop-items-0.conf"), desktopItems,
                    StandardOpenOption.CREATE_NEW);
                return session;
            } catch (FileAlreadyExistsException e) {
                // Name collision, including a planted dangling symlink:
                // nothing was replaced; retry with a fresh unique name.
            } catch (IOException | SecurityException e) {
                warn.accept("session config: write failed (" +
                    e.getClass().getSimpleName() + ") - desktop disabled");
                return null;
            }
        }
        warn.accept("session config: no unique session dir after 5 attempts - desktop disabled");
        return null;
    }
}
