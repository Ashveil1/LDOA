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

// Vendored patch (LDOA gestures): direct-touch stationary hold 500ms+ =
// right-click injected on release (movement keeps the sequence a native
// drag, so press-hold-drag works) and two-finger drag = wheel scroll, each
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
     * Android-side gesture (long-press right-click, two-finger scroll)
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
    /** LDOA hold-release right-click bookkeeping: a stationary one-finger
     * hold of 500ms+ injects a right-click on release; any movement turns
     * the sequence into a native drag and cancels the injection, so
     * press-hold-drag keeps working everywhere. */
    private long mHoldDownTime;
    private float mHoldDownX, mHoldDownY;
    private boolean mHoldMoved;
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
                    mHoldDownTime = event.getEventTime();
                    mHoldDownX = event.getX();
                    mHoldDownY = event.getY();
                    mHoldMoved = false;
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
                    // LDOA: a second finger cancels hold-release right-click.
                    mHoldMoved = true;
                    break;

                case MotionEvent.ACTION_MOVE:
                    // LDOA hold tracking: movement beyond slop makes this a
                    // native drag; no right-click fires on release.
                    if (mHoldDownTime != 0L
                            && (Math.abs(event.getX() - mHoldDownX) > 30f
                                || Math.abs(event.getY() - mHoldDownY) > 30f))
                        mHoldMoved = true;
                    break;

                case MotionEvent.ACTION_UP:
                    // LDOA direct touch: a stationary one-finger hold of
                    // half a second or longer becomes a right-click at the
                    // held point, injected on release. A moved hold stays a
                    // native drag (press-hold-drag keeps working). The raw
                    // tap was forwarded all along; its select side effect
                    // mirrors a real right-click's.
                    if (mHoldDownTime != 0L) {
                        if (!mHoldMoved && event.getEventTime() - mHoldDownTime >= 500
                                && mInputStrategy instanceof InputStrategyInterface.NullInputStrategy
                                && directTouchPrefs(mActivity).getBoolean("ldoa_longpress_rightclick", true)) {
                            mRenderData.mapScreenPoint(mHoldDownX, mHoldDownY, mappedPoint);
                            mInjector.sendCursorMove(mappedPoint[0], mappedPoint[1], false);
                            mInjector.sendMouseClick(InputStub.BUTTON_RIGHT, false);
                        }
                        mHoldDownTime = 0L;
                    }
                    break;
"""
            val text = f.readText()
            if (orig in text && patched !in text) f.writeText(text.replace(orig, patched))
            check(patched in f.readText()) { "LDOA gesture patch 7 failed for ${f.name}" }
        }
    }
}
