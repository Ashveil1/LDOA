package dev.phonelinux.proto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for SessionConfig (pure JVM; run with gradle
 * testDebugUnitTest - requires testImplementation junit in
 * app/build.gradle). Every test asserts the non-negotiables: no
 * pre-existing path is ever replaced, deleted, renamed, or written
 * through, and unsafe paths fail closed instead of touching user data.
 */
public class SessionConfigTest {
    private static final String JWMRC = "<JWM><Tray/></JWM>\n";
    private static final String XRES = "Xft.dpi: 192\n";
    private static final String SCRIPT = "#!/bin/sh\nexit 0\n";
    private static final String MANAGED_PREFIX =
        "# ldoa-managed: per-session profile, never edited in place\n" +
        "[*]\nwallpaper_mode=color\nwallpaper_common=1\ndesktop_bg=";

    private Path base;
    private final List<String> warnings = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        base = Files.createTempDirectory("session-config-test");
        warnings.clear();
    }

    private Path newRootfs(String name) throws IOException {
        Path r = Files.createDirectory(base.resolve(name));
        Files.createDirectory(r.resolve("root"));
        return r;
    }

    private Path prepare(Path r) {
        return SessionConfig.prepare(r, "#2E3440", JWMRC, XRES, SCRIPT, warnings::add);
    }

    @Test
    public void freshPrepareCreatesUniqueManagedSessionDir() throws IOException {
        Path s = prepare(newRootfs("r1"));
        assertNotNull(s);
        assertTrue(s.getParent().toString().endsWith(".config-ldoa"));
        assertEquals(MANAGED_PREFIX + "#2E3440\n",
            new String(Files.readAllBytes(
                s.resolve("pcmanfm/default/desktop-items-0.conf")), StandardCharsets.UTF_8));
        assertEquals(JWMRC,
            new String(Files.readAllBytes(s.resolve("jwmrc")), StandardCharsets.UTF_8));
        assertEquals(XRES,
            new String(Files.readAllBytes(s.resolve("Xresources")), StandardCharsets.UTF_8));
        assertEquals(SCRIPT,
            new String(Files.readAllBytes(s.resolve("mobile-session.sh")), StandardCharsets.UTF_8));
    }

    @Test
    public void secondPrepareKeepsFirstSessionDirUntouched() throws IOException {
        // A first session may still be running from its dir; nothing may
        // delete or modify it (no cleanup in this change).
        Path r = newRootfs("r2");
        Path s1 = prepare(r);
        Path s2 = prepare(r);
        assertNotNull(s1);
        assertNotNull(s2);
        assertFalse(s1.equals(s2));
        for (String f : new String[] {"jwmrc", "Xresources", "mobile-session.sh",
                "pcmanfm/default/desktop-items-0.conf"}) {
            assertTrue(f + " of first session preserved", Files.exists(s1.resolve(f)));
        }
    }

    @Test
    public void userColorEditRetainingValidShapeIsPreservedByteForByte() throws IOException {
        // A user edit that keeps the managed header and a VALID managed
        // shape (only the hex color changed) must still survive
        // byte-for-byte: shape alone never proves "ours, unmodified".
        Path r = newRootfs("r3");
        Path s1 = prepare(r);
        Path f = s1.resolve("pcmanfm/default/desktop-items-0.conf");
        byte[] edited = (MANAGED_PREFIX + "#AABBCC\n").getBytes(StandardCharsets.UTF_8);
        Files.write(f, edited);
        prepare(r);
        assertEquals(MANAGED_PREFIX + "#AABBCC\n",
            new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
    }

    @Test
    public void nestedSymlinkInOldSessionDirIsUntouched() throws IOException {
        Path r = newRootfs("r4");
        Path s1 = prepare(r);
        Path target = Files.createDirectory(base.resolve("elsewhere"));
        Path link = s1.resolve("pcmanfm/linked");
        Files.createSymbolicLink(link, target);
        prepare(r);
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(0, target.toFile().list().length);
    }

    @Test
    public void rootfsItselfSymlinkIsBlocked() throws IOException {
        Path real = newRootfs("real5");
        Path link = base.resolve("rootfs-link");
        Files.createSymbolicLink(link, real);
        assertNull(prepare(link));
        assertFalse(warnings.isEmpty());
    }

    @Test
    public void configRootSymlinkIsBlockedAndNothingWrittenThrough() throws IOException {
        Path r = newRootfs("r6");
        Path target = Files.createDirectory(base.resolve("target6"));
        Files.createSymbolicLink(r.resolve("root/.config-ldoa"), target);
        assertNull(prepare(r));
        assertEquals(0, target.toFile().list().length);
    }

    @Test
    public void parentComponentSymlinkIsBlocked() throws IOException {
        Path r = Files.createDirectory(base.resolve("r7"));
        Files.createSymbolicLink(r.resolve("root"), newRootfs("real7"));
        assertNull(prepare(r));
    }

    @Test
    public void nonDirectoryComponentIsBlocked() throws IOException {
        Path r = newRootfs("r8");
        Files.write(r.resolve("root/.config-ldoa"), "x".getBytes(StandardCharsets.UTF_8));
        assertNull(prepare(r));
    }

    @Test
    public void statErrorIsRejectedNotTreatedAsAbsent() throws IOException {
        // Unreadable parent: lstat of the child fails EACCES; prepare
        // must block (never conclude "absent, safe to create"). The
        // permission mutation is verified to actually bite, or the test
        // SKIPS explicitly (privileged filesystem / root).
        Path r = newRootfs("r9");
        boolean m1 = r.resolve("root").toFile().setReadable(false, false);
        boolean m2 = r.resolve("root").toFile().setExecutable(false, false);
        try {
            Assume.assumeTrue("permission mutation not applied by filesystem", m1 && m2);
            boolean denied;
            try {
                Files.readAttributes(r.resolve("root/.config-ldoa"),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                denied = false;
            } catch (AccessDeniedException e) {
                denied = true;
            }
            Assume.assumeTrue("filesystem ignores permission bits (privileged)", denied);
            assertNull(prepare(r));
            assertFalse(warnings.isEmpty());
        } finally {
            r.resolve("root").toFile().setReadable(true, false);
            r.resolve("root").toFile().setExecutable(true, false);
        }
    }

    @Test
    public void invalidBackgroundIsRejectedBeforeAnyWrite() throws IOException {
        Path r = newRootfs("r10");
        for (String bad : new String[] {"red", "#2E344", "#2E34400", "#2E34GG"}) {
            assertNull(SessionConfig.prepare(r, bad, JWMRC, XRES, SCRIPT, warnings::add));
        }
        assertEquals(0, r.resolve("root").toFile().list().length);
    }
}
