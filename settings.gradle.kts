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

// Gradle 8.9 compat patch for the vendored tree (upstream expects Gradle 9's
// ProjectDependency.getPath). Applied at configuration time so a clean
// recursive checkout builds identically; the gitlink stays at the pristine
// upstream pin. Recorded in THIRD_PARTY_NOTICES.md.
run {
    val f = File(rootDir, "third_party/termux-x11/lorie/build.gradle")
    if (f.exists()) {
        val orig = "it instanceof ProjectDependency && it.path == project.path"
        val patched = "it instanceof ProjectDependency && it.dependencyProject.path == project.path"
        val text = f.readText()
        if (orig in text) f.writeText(text.replace(orig, patched))
        check(patched in f.readText()) { "lorie Gradle 8.9 compat patch failed to apply" }
    }
}
// Vendored patch (c): CmdEntryPoint.initEntryPoint loads libXlorie via a
// classloader resource URL, which only works with extractNativeLibs=false.
// We must keep extractNativeLibs=true (W^X blocks filesDir exec at
// targetSdk 34, CI-verified error=13), so add an XSERVER_LIB env preload.
run {
    val f = File(rootDir, "third_party/termux-x11/lorie/src/main/java/com/termux/x11/CmdEntryPoint.java")
    if (f.exists()) {
        val orig = "        ctx = createContext();\n"
        val patched = "        ctx = createContext();\n\n        String xserverLib = System.getenv(\"XSERVER_LIB\");\n        if (xserverLib != null && !xserverLib.isEmpty()) { System.load(xserverLib); return; }\n"
        val text = f.readText()
        if (orig in text && "XSERVER_LIB" !in text) f.writeText(text.replace(orig, patched, ignoreCase = false))
        check("XSERVER_LIB" in f.readText()) { "CmdEntryPoint XSERVER_LIB preload patch failed to apply" }
    }
}
// Vendored modules compile at SDK 34, but their current androidx deps
// (transition 1.6.0, compose-ui 1.11.0) require compileSdk 35. Bump the
// vendored compileSdkVersion the same way; targetSdk/minSdk untouched.
for (rel in listOf("third_party/termux-x11/lorie/build.gradle", "third_party/termux-x11/shell-loader/stub/build.gradle")) {
    val f = File(rootDir, rel)
    if (f.exists()) {
        val text = f.readText()
        if ("compileSdkVersion 34" in text) f.writeText(text.replace("compileSdkVersion 34", "compileSdkVersion 35"))
        check("compileSdkVersion 35" in f.readText()) { "compileSdk 35 bump failed for $rel" }
    }
}

// Vendored patch (LDOA gestures, native-first model 2026-10-29):
// one-finger gestures stay fully native (tap = click, drag = scroll /
// window-move / selection, hold = toolkit press-and-hold where supported);
// a two-finger TAP (second finger down+up within 350ms, both fingers under
// 30px slop) injects a right-click at the midpoint, and two-finger drag =
// wheel scroll for legacy clients, each
// behind an app-level toggle read from the app's default SharedPreferences
// (defaults on, user-approved gesture map 2026-10-29). InputEventSender
// gains endAllTouches(x, y) so a scroll takeover ends the touch sequence at
// the finger point instead of dangling - and never at (0,0), which a WM
// reads as a drag endpoint and collapses the window (run 40820196500).
run {
    val tih = File(rootDir, "third_party/termux-x11/lorie/src/main/java/com/termux/x11/input/TouchInputHandler.java")
    val ies = File(rootDir, "third_party/termux-x11/lorie/src/main/java/com/termux/x11/input/InputEventSender.java")
    if (tih.exists() && ies.exists()) {
        run {
            val f = ies
            val orig = """    private final boolean[] pointers = new boolean[32];
"""
            val patched = """    private final boolean[] pointers = new boolean[32];

    /**
     * LDOA: end every currently tracked touch pointer. Used when an
     * Android-side gesture (two-finger-tap right-click, two-finger scroll)
     * takes over a direct-touch sequence, so the X client sees a clean
     * sequence end instead of a stuck pointer.
     */
    public void endAllTouches(int x, int y) {
        for (int id = 0; id < pointers.length; id++) {
            if (pointers[id]) {
                pointers[id] = false;
                mInjector.sendTouchEvent(XI_TouchEnd, id, x, y);
            }
        }
    }
"""
            val text = f.readText()
            if (orig in text && patched !in text) f.writeText(text.replace(orig, patched))
            check(patched in f.readText()) { "LDOA gesture patch 0 failed for ${f.name}" }
        }
        run {
            val f = tih
            val orig = """    private boolean mSuppressCursorMovement;
"""
            val patched = """    private boolean mSuppressCursorMovement;
    /** LDOA: true while an Android-side gesture (two-finger scroll) has
     * taken over the current direct-touch sequence. */
    private boolean mSuppressDirectTouch;
    /** LDOA two-finger-tap right-click bookkeeping: a two-finger tap
     * (second finger down+up within 350ms, both fingers under 30px slop)
     * injects a right-click at the midpoint; movement or a longer hold
     * keeps the sequence native (scroll/pinch) and never fires. One-finger
     * hold is deliberately NOT injected: GTK press-and-hold owns it where
     * supported, and duplicating it raced the native menu (run 40844558720). */
    private long mTwoTapDownTime;
    private float mTwoTapX1, mTwoTapY1, mTwoTapX2, mTwoTapY2;
    private boolean mTwoTapMoved;
"""
            val text = f.readText()
            if (orig in text && patched !in text) f.writeText(text.replace(orig, patched))
            check(patched in f.readText()) { "LDOA gesture patch 1 failed for ${f.name}" }
        }
        run {
            val f = tih
            val orig = """            if (mInputStrategy instanceof InputStrategyInterface.NullInputStrategy)
                mInjector.sendTouchEvent(event, mRenderData);
            else
"""
            val patched = """            if (mInputStrategy instanceof InputStrategyInterface.NullInputStrategy) {
                if (!mSuppressDirectTouch)
                    mInjector.sendTouchEvent(event, mRenderData);
            } else
"""
            val text = f.readText()
            if (orig in text && patched !in text) f.writeText(text.replace(orig, patched))
            check(patched in f.readText()) { "LDOA gesture patch 2 failed for ${f.name}" }
        }
        run {
            val f = tih
            val orig = """                case MotionEvent.ACTION_DOWN:
                    mSuppressCursorMovement = false;
"""
            val patched = """                case MotionEvent.ACTION_DOWN:
                    mSuppressCursorMovement = false;
                    mSuppressDirectTouch = false;
"""
            val text = f.readText()
            if (orig in text && patched !in text) f.writeText(text.replace(orig, patched))
            check(patched in f.readText()) { "LDOA gesture patch 3 failed for ${f.name}" }
        }
        run {
            val f = tih
            val orig = """            if (pointerCount == 2 && mSwipePinchDetector.isSwiping()) {
                mInputStrategy.onScroll(distanceX, distanceY);
"""
            val patched = """            if (pointerCount == 2 && mSwipePinchDetector.isSwiping()) {
                if (mInputStrategy instanceof InputStrategyInterface.NullInputStrategy) {
                    // LDOA direct touch: two-finger drag is wheel scroll. The X
                    // server turns the smooth valuators into legacy button 4/5
                    // steps for clients without XI2 smooth scrolling itself
                    // (SetScrollValuator, InitInput.c).
                    if (directTouchPrefs(mActivity).getBoolean("ldoa_twofinger_scroll", true)) {
                        if (!mSuppressDirectTouch) {
                            mSuppressDirectTouch = true;
                            mRenderData.mapScreenPoint(e2.getX(), e2.getY(), mappedPoint);
                            mInjector.endAllTouches((int) mappedPoint[0], (int) mappedPoint[1]);
                            mInjector.sendCursorMove(mappedPoint[0], mappedPoint[1], false);
                        }
                        mInjector.sendMouseWheelEvent(distanceX, distanceY);
                    }
                    mSuppressCursorMovement = true;
                    return true;
                }
                mInputStrategy.onScroll(distanceX, distanceY);
"""
            val text = f.readText()
            if (orig in text && patched !in text) f.writeText(text.replace(orig, patched))
            check(patched in f.readText()) { "LDOA gesture patch 4 failed for ${f.name}" }
        }
        run {
            val f = tih
            val orig = """    public boolean shouldInterceptKeys() {
"""
            val patched = """    /** LDOA: app-level gesture toggles (default on; see PickerScreen). */
    private static android.content.SharedPreferences directTouchPrefs(android.content.Context ctx) {
        return ctx.getSharedPreferences(ctx.getPackageName() + "_preferences", android.content.Context.MODE_PRIVATE);
    }

    public boolean shouldInterceptKeys() {
"""
            val text = f.readText()
            if (orig in text && patched !in text) f.writeText(text.replace(orig, patched))
            check(patched in f.readText()) { "LDOA gesture patch 6 failed for ${f.name}" }
        }
        run {
            val f = tih
            val orig = """                case MotionEvent.ACTION_POINTER_DOWN:
                    mTotalMotionY = 0;
                    break;
"""
            val patched = """                case MotionEvent.ACTION_POINTER_DOWN:
                    mTotalMotionY = 0;
                    // LDOA: a second finger's down opens the two-finger-tap
                    // window; anything but exactly two fingers cancels it.
                    if (event.getPointerCount() == 2) {
                        int tapAi = event.getActionIndex();
                        int tapOi = tapAi == 0 ? 1 : 0;
                        mTwoTapDownTime = event.getEventTime();
                        mTwoTapX1 = event.getX(tapOi);
                        mTwoTapY1 = event.getY(tapOi);
                        mTwoTapX2 = event.getX(tapAi);
                        mTwoTapY2 = event.getY(tapAi);
                        mTwoTapMoved = false;
                    } else {
                        mTwoTapDownTime = 0L;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    // LDOA two-finger-tap tracking: either finger moving
                    // beyond slop makes this a native scroll/pinch; no
                    // right-click fires on lift.
                    if (mTwoTapDownTime != 0L && event.getPointerCount() >= 2
                            && (Math.abs(event.getX(0) - mTwoTapX1) > 30f
                                || Math.abs(event.getY(0) - mTwoTapY1) > 30f
                                || Math.abs(event.getX(1) - mTwoTapX2) > 30f
                                || Math.abs(event.getY(1) - mTwoTapY2) > 30f))
                        mTwoTapMoved = true;
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    // LDOA direct touch: a two-finger tap (second finger
                    // lifted within 350ms, both fingers under 30px slop)
                    // becomes a right-click at the midpoint. Moved or held
                    // two-finger sequences stay native (scroll/pinch) and
                    // never fire. One-finger hold is NOT injected: GTK's
                    // native press-and-hold owns that gesture where the
                    // toolkit supports it, and duplicating it raced the
                    // native menu (run 40844558720 arm3). The raw taps are
                    // forwarded all along; their focus/select side effect
                    // mirrors a real touchscreen's.
                    if (mTwoTapDownTime != 0L) {
                        if (!mTwoTapMoved && event.getEventTime() - mTwoTapDownTime <= 350
                                && mInputStrategy instanceof InputStrategyInterface.NullInputStrategy
                                && directTouchPrefs(mActivity).getBoolean("ldoa_twofinger_tap_rightclick", true)) {
                            mRenderData.mapScreenPoint((mTwoTapX1 + mTwoTapX2) / 2f, (mTwoTapY1 + mTwoTapY2) / 2f, mappedPoint);
                            mInjector.sendCursorMove(mappedPoint[0], mappedPoint[1], false);
                            mInjector.sendMouseClick(InputStub.BUTTON_RIGHT, false);
                            android.util.Log.i("LorieTouch", "LDOA-2FTAP-FIRE right-click guest("
                                + (int) mappedPoint[0] + "," + (int) mappedPoint[1] + ") dtMs="
                                + (event.getEventTime() - mTwoTapDownTime));
                        }
                        mTwoTapDownTime = 0L;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    mTwoTapDownTime = 0L;
                    break;
"""
            val text = f.readText()
            if (orig in text && patched !in text) f.writeText(text.replace(orig, patched))
            check(patched in f.readText()) { "LDOA gesture patch 7 failed for ${f.name}" }
        }
    }
}
