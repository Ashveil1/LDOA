package dev.phonelinux.proto;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Preservation contract for the Phase A defaults flip (top tray,
 * maximized windows): the flipped defaults apply ONLY to keys that were
 * never set. An explicitly saved choice - above all bottom/floating
 * saved under the PREVIOUS defaults - must read back unchanged. Pure
 * JVM (gradle testDebugUnitTest; requires testImplementation junit in
 * app/build.gradle, same as SessionConfigTest).
 *
 * SharedPreferences is faked in memory, so no Android runtime, no
 * Robolectric and no build-file change. The android.jar unit-test stubs
 * throw "not mocked" from every constructor, so the single ContextWrapper
 * is allocated WITHOUT a constructor (sun.misc.Unsafe); only its
 * overridden getSharedPreferences ever executes.
 */
public class MobileModeSettingsTest {

    /** Fresh storage: every key unset -> the flipped product defaults. */
    @Test public void unsetKeysYieldProductDefaults() throws Exception {
        MobileUiPrefs ui = MobileModeSettings.INSTANCE.read(newFakeContext());
        assertEquals("top", ui.getPanelPosition());
        assertEquals("maximized", ui.getWindowMode());
        assertEquals("normal", ui.getPanelSize());
        assertEquals("#2E3440", ui.getBackground());
        assertEquals(1.0f, ui.getScale(), 1e-6f);
    }

    /** The load-bearing case: bottom/floating seeded directly into the
     *  store - the shape of a choice saved UNDER THE OLD defaults - must
     *  survive the flip exactly. */
    @Test public void explicitBottomFloatingSavedUnderOldDefaultsPreserved() throws Exception {
        Context ctx = newFakeContext();
        ctx.getSharedPreferences("plinux_mobile", Context.MODE_PRIVATE).edit()
            .putString(MobileModeSettings.KEY_PANEL_POSITION, "bottom")
            .putString(MobileModeSettings.KEY_WINDOW_MODE, "floating")
            .apply();
        MobileUiPrefs ui = MobileModeSettings.INSTANCE.read(ctx);
        assertEquals("bottom", ui.getPanelPosition());
        assertEquals("floating", ui.getWindowMode());
    }

    /** write() then read() preserves every explicit choice, in both
     *  directions around the new defaults. */
    @Test public void explicitChoicesRoundTripUnchanged() throws Exception {
        Context ctx = newFakeContext();
        MobileUiPrefs oldStyle = new MobileUiPrefs(1.5f, "large", "bottom", "#16324F", "floating");
        MobileModeSettings.INSTANCE.write(ctx, oldStyle);
        assertEquals(oldStyle, MobileModeSettings.INSTANCE.read(ctx));
        MobileUiPrefs newStyle = new MobileUiPrefs(0.75f, "normal", "top", "#3B2437", "maximized");
        MobileModeSettings.INSTANCE.write(ctx, newStyle);
        assertEquals(newStyle, MobileModeSettings.INSTANCE.read(ctx));
    }

    // ---- harness ----

    /** Only getSharedPreferences is ever called by read/write; any other
     *  ContextWrapper method would hit the null base. The prefs field is
     *  lazily built because Unsafe.allocateInstance skips field
     *  initializers together with the constructor. */
    private static final class FakeContext extends ContextWrapper {
        private FakePrefs prefs;
        FakeContext() { super(null); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (!"plinux_mobile".equals(name))
                throw new AssertionError("unexpected prefs store: " + name);
            if (prefs == null) prefs = new FakePrefs();
            return prefs;
        }
    }

    private static FakeContext newFakeContext() throws Exception {
        // sun.misc.Unsafe is NOT on the AGP unit-test COMPILE classpath
        // (javac: "package sun.misc does not exist"), so reference it by
        // name only; at RUNTIME the JDK's jdk.unsupported module supplies
        // it to the unnamed module.
        Class<?> uc = Class.forName("sun.misc.Unsafe");
        Field f = uc.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object u = f.get(null);
        return (FakeContext) uc.getMethod("allocateInstance", Class.class)
            .invoke(u, FakeContext.class);
    }

    /** In-memory SharedPreferences implementing the same contract:
     *  getX(key, def) returns the stored value when present and of the
     *  requested type, else def. */
    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> map = new HashMap<>();
        @Override public Map<String, ?> getAll() { return new HashMap<>(map); }
        @Override public String getString(String key, String def) { Object v = map.get(key); return v instanceof String ? (String) v : def; }
        @Override public Set<String> getStringSet(String key, Set<String> def) { throw new UnsupportedOperationException("unused by MobileModeSettings"); }
        @Override public int getInt(String key, int def) { Object v = map.get(key); return v instanceof Integer ? (Integer) v : def; }
        @Override public long getLong(String key, long def) { Object v = map.get(key); return v instanceof Long ? (Long) v : def; }
        @Override public float getFloat(String key, float def) { Object v = map.get(key); return v instanceof Float ? (Float) v : def; }
        @Override public boolean getBoolean(String key, boolean def) { Object v = map.get(key); return v instanceof Boolean ? (Boolean) v : def; }
        @Override public boolean contains(String key) { return map.containsKey(key); }
        @Override public Editor edit() { return new FakeEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        private final class FakeEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final List<String> removed = new ArrayList<>();
            private boolean cleared = false;
            @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); return this; }
            @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
            @Override public Editor remove(String k) { removed.add(k); return this; }
            @Override public Editor clear() { cleared = true; return this; }
            @Override public boolean commit() { applyNow(); return true; }
            @Override public void apply() { applyNow(); }
            private void applyNow() {
                if (cleared) map.clear();
                for (String k : removed) map.remove(k);
                map.putAll(pending);
            }
        }
    }
}
