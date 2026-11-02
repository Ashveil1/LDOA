package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures mirror the source-verified output shapes (xwininfo.c
 * Display_Stats_Info / display_tree_info_1, xprop atom rendering).
 * Every malformed case must yield null - the probe fails closed.
 */
class RenderProbeParseTest {

    private val rootStats = """
xwininfo: Window id: 0x7a00007 (the root window) "LDOA"

  Absolute upper-left X:  0
  Absolute upper-left Y:  0
  Relative upper-left X:  0
  Relative upper-left Y:  0
  Width: 1440
  Height: 2960
  Depth: 24
  Map State: IsViewable
  Corners:  +0+0  -0+0  -0-0  +0-0
  -geometry 1440x2960+0+0
""".trimIndent()

    private val xtermStats = """
xwininfo: Window id: 0x140000e "xterm"

  Absolute upper-left X:  0
  Absolute upper-left Y:  48
  Relative upper-left X:  0
  Relative upper-left Y:  0
  Width: 1440
  Height: 2912
  Depth: 24
  Map State: IsViewable
""".trimIndent()

    private val tree = """
xwininfo: Window id: 0x7a00007 (the root window) "LDOA"

  Root window id: 0x7a00007 (the root window) "LDOA"
  Parent window id: 0x0 (none)
     2 children:
     0x1400010 "xterm": ("xterm" "XTerm")  1440x2912+0+48  +0+48
        0 children.
     0x1400007 "Desktop": ("pcmanfm" "Pcmanfm")  1440x2960+0+0  +0+0
        1 child:
        0x1400008: ()  1x1+-1+-1  +-1+-1
""".trimIndent()

    @Test fun rootStatsParses() {
        val s = RenderProbeParse.winStats(rootStats)!!
        assertEquals(0, s.absX); assertEquals(0, s.absY)
        assertEquals(1440, s.w); assertEquals(2960, s.h)
        assertEquals("IsViewable", s.mapState)
    }

    @Test fun xtermStatsParses() {
        val s = RenderProbeParse.winStats(xtermStats)!!
        assertEquals(48, s.absY); assertEquals(2912, s.h)
    }

    @Test fun unmappedParses() {
        val s = RenderProbeParse.winStats(xtermStats.replace("IsViewable", "IsUnMapped"))!!
        assertEquals("IsUnMapped", s.mapState)
    }

    @Test fun missingHeightIsUnreadableNotZero() {
        assertNull(RenderProbeParse.winStats(xtermStats.replace("  Height: 2912\n", "")))
    }

    @Test fun zeroHeightIsUnreadableNotAccepted() {
        assertNull(RenderProbeParse.winStats(xtermStats.replace("  Height: 2912", "  Height: 0")))
    }

    @Test fun unknownMapStateIsUnreadable() {
        assertNull(RenderProbeParse.winStats(xtermStats.replace("IsViewable", "IsWeird")))
    }

    @Test fun topLevelChildrenSkipsNestedAndCarriesGeometry() {
        val kids = RenderProbeParse.topLevelChildren(tree)!!
        assertEquals(2, kids.size)
        val desk = kids.first { it.id == 0x1400007L }
        assertEquals(1440, desk.geomW); assertEquals(2960, desk.geomH)
        assertEquals(0, desk.absX); assertEquals(0, desk.absY)
        val xt = kids.first { it.id == 0x1400010L }
        assertEquals(2912, xt.geomH); assertEquals(48, xt.absY)
    }

    @Test fun zeroChildrenIsValidEmpty() {
        val t = """
xwininfo: Window id: 0x7a00007 (the root window) "LDOA"

  Root window id: 0x7a00007 (the root window) "LDOA"
  Parent window id: 0x0 (none)
     0 children.
""".trimIndent()
        assertEquals(emptyList<RenderProbeParse.ChildLine>(), RenderProbeParse.topLevelChildren(t))
    }

    @Test fun declaredCountMismatchIsNull() {
        // declares 2 children, lists only 1
        val t = tree.lines().filter { !it.contains("0x1400010") }.joinToString("\n")
        assertNull(RenderProbeParse.topLevelChildren(t))
    }

    @Test fun malformedTopLevelRecordIsNullNotDropped() {
        // second child record loses its geometry - must NOT be dropped
        val t = tree.replace("1440x2912+0+48  +0+48", "BOGUS-GEOMETRY")
        assertNull(RenderProbeParse.topLevelChildren(t))
    }

    @Test fun nestedOnlyListingWithoutHeaderIsNull() {
        assertNull(RenderProbeParse.topLevelChildren("        0x1400008: ()  1x1+-1+-1  +-1+-1\n"))
    }

    @Test fun mapRectValid() {
        val m = RenderProbeParse.mapRect(288, 1036, 864, 888, 0.5f, 0.5f, 10, 20, 800, 1600)!!
        assertEquals(intArrayOf(10 + 144, 20 + 518, 10 + 576, 20 + 962).toList(), m.toList())
    }

    @Test fun mapRectEmptyRoiIsNull() {
        assertNull(RenderProbeParse.mapRect(0, 0, 0, 100, 1f, 1f, 0, 0, 800, 1600))
    }

    @Test fun mapRectOffscreenIsNull() {
        assertNull(RenderProbeParse.mapRect(1000, 0, 500, 500, 1f, 1f, 0, 0, 800, 1600))
    }

    @Test fun mapRectPartiallyOutsideIsNullNotClamped() {
        assertNull(RenderProbeParse.mapRect(700, 0, 200, 500, 1f, 1f, 0, 0, 800, 1600))
    }

    @Test fun mapRectTooFewSamplesIsNull() {
        // 64x64 at grid 32 = 2x2 = 4 samples OK; 33x33 at grid 32 = 2x2 OK;
        // 32x32 = 1x1 < 4 -> null
        assertNull(RenderProbeParse.mapRect(0, 0, 32, 32, 1f, 1f, 0, 0, 800, 1600))
    }

    @Test fun mapRectBadScaleIsNull() {
        assertNull(RenderProbeParse.mapRect(0, 0, 100, 100, 0f, 1f, 0, 0, 800, 1600))
        assertNull(RenderProbeParse.mapRect(0, 0, 100, 100, Float.NaN, 1f, 0, 0, 800, 1600))
    }

    @Test fun xpropPidParses() {
        assertEquals(8603L, RenderProbeParse.xpropPid("_NET_WM_PID(CARDINAL) = 8603\nWM_CLASS(STRING) = \"pcmanfm\", \"Pcmanfm\"\n"))
    }

    @Test fun xpropPidAbsentDistinguished() {
        val t = "_NET_WM_PID:  not found.\n"
        assertNull(RenderProbeParse.xpropPid(t))
        assertTrue(RenderProbeParse.xpropPidAbsent(t))
    }

    @Test fun xpropGarbageIsNull() {
        assertNull(RenderProbeParse.xpropPid("garbage\n"))
    }

    @Test fun wmClassParses() {
        assertEquals("xterm" to "XTerm",
            RenderProbeParse.xpropClass("WM_CLASS(STRING) = \"xterm\", \"XTerm\"\n"))
    }

    @Test fun searchIdsParsesDecimalsOnly() {
        assertEquals(listOf(1L, 22L), RenderProbeParse.searchIds("1\n22\n"))
        assertNull(RenderProbeParse.searchIds("22\n0xbogus\n"))
        assertEquals(emptyList<Long>(), RenderProbeParse.searchIds(""))
    }

    @Test fun overlapGeometry() {
        val desk = RenderProbeParse.WinStats(0, 0, 1440, 2960, "IsViewable")
        assertTrue(desk.overlaps(288, 1036, 864, 888))       // central band
        assertTrue(!desk.overlaps(0, 0, 0, 0))               // empty ROI never overlaps
    }

    @Test fun xpropWmTypesDesktop() {
        assertEquals(listOf("_NET_WM_WINDOW_TYPE_DESKTOP"),
            RenderProbeParse.xpropWmTypes("_NET_WM_WINDOW_TYPE(ATOM) = _NET_WM_WINDOW_TYPE_DESKTOP\n"))
    }

    @Test fun xpropWmTypesMultiValueOrderPreserved() {
        assertEquals(listOf("_NET_WM_WINDOW_TYPE_DESKTOP", "_NET_WM_WINDOW_TYPE_NORMAL"),
            RenderProbeParse.xpropWmTypes(
                "_NET_WM_WINDOW_TYPE(ATOM) = _NET_WM_WINDOW_TYPE_DESKTOP, _NET_WM_WINDOW_TYPE_NORMAL\n"))
    }

    @Test fun xpropWmTypesAbsentIsEmptyNotNull() {
        assertEquals(emptyList<String>(),
            RenderProbeParse.xpropWmTypes("_NET_WM_WINDOW_TYPE:  not found.\n"))
    }

    @Test fun xpropWmTypesMalformedValueFailsClosed() {
        assertNull(RenderProbeParse.xpropWmTypes("_NET_WM_WINDOW_TYPE(ATOM) = not-an-atom\n"))
        assertNull(RenderProbeParse.xpropWmTypes("_NET_WM_WINDOW_TYPE(ATOM) = _NET_WM_WINDOW_TYPE_DESKTOP, junk\n"))
        assertNull(RenderProbeParse.xpropWmTypes("_NET_WM_WINDOW_TYPE(ATOM) = \n"))
    }

    @Test fun xpropWmTypesMissingLineFailsClosed() {
        assertNull(RenderProbeParse.xpropWmTypes("garbage\n"))
        assertNull(RenderProbeParse.xpropWmTypes(""))
    }

    @Test fun xpropCombinedPidClassTypeEachParserReadsOwnPart() {
        val out = "_NET_WM_PID(CARDINAL) = 4728\n" +
            "WM_CLASS(STRING) = \"pcmanfm\", \"Pcmanfm\"\n" +
            "_NET_WM_WINDOW_TYPE(ATOM) = _NET_WM_WINDOW_TYPE_DESKTOP\n"
        assertEquals(4728L, RenderProbeParse.xpropPid(out))
        assertEquals("pcmanfm" to "Pcmanfm", RenderProbeParse.xpropClass(out))
        assertEquals(listOf("_NET_WM_WINDOW_TYPE_DESKTOP"), RenderProbeParse.xpropWmTypes(out))
    }

    @Test fun anyOverlapDisjointTiles() {
        assertTrue(!RenderProbeParse.anyOverlap(listOf(intArrayOf(0,0,100,100), intArrayOf(100,0,100,100), intArrayOf(0,100,200,100))))
        assertTrue(RenderProbeParse.anyOverlap(listOf(intArrayOf(0,0,101,100), intArrayOf(100,0,100,100))))
        assertTrue(!RenderProbeParse.anyOverlap(emptyList()))
    }

    @Test fun xrandrSingleAutomaticPrimary() {
        val out = "Monitors: 1\n 0: +*default 1440/364x2960/744+0+0  default\n"
        val ms = RenderProbeParse.xrandrListMonitors(out)!!
        assertEquals(1, ms.size)
        assertEquals(0, ms[0].index); assertTrue(ms[0].primary); assertTrue(ms[0].active)
        assertEquals(0, ms[0].x); assertEquals(0, ms[0].y)
        assertEquals(1440, ms[0].w); assertEquals(2960, ms[0].h)
    }

    @Test fun xrandrTwoHeadsOnePrimary() {
        val out = "Monitors: 2\n" +
            " 0: +*default 1440/364x1480/372+0+0  default\n" +
            " 1: +default 1440/364x1480/372+0+1480  default\n"
        val ms = RenderProbeParse.xrandrListMonitors(out)!!
        assertEquals(2, ms.size)
        assertTrue(ms[0].primary); assertTrue(!ms[1].primary)
        assertTrue(ms[0].active); assertTrue(ms[1].active)
        assertEquals(1480, ms[1].y)
    }

    @Test fun xrandrNoPrimaryMarkerPreserved() {
        val ms = RenderProbeParse.xrandrListMonitors("Monitors: 1\n 0: +default 1440/364x2960/744+0+0  default\n")!!
        assertTrue(!ms[0].primary); assertTrue(ms[0].active)
    }

    @Test fun xrandrNegativeCoordinatesUseTheRealLiteralPlusSignedFormat() {
        // xrandr.c prints "+%d" per coordinate: a negative x renders "+-100"
        val ms = RenderProbeParse.xrandrListMonitors(
            "Monitors: 1\n 0: +*default 1920/510x1080/287+-100+-50  default\n")!!
        assertEquals(-100, ms[0].x); assertEquals(-50, ms[0].y)
        // a bare "-100" (no literal '+') is NOT the real format: fail closed
        assertNull(RenderProbeParse.xrandrListMonitors(
            "Monitors: 1\n 0: +*default 1920/510x1080/287-100+0  default\n"))
    }

    @Test fun xrandrClientDefinedPrimaryWithoutAutomaticFlag() {
        val ms = RenderProbeParse.xrandrListMonitors(
            "Monitors: 1\n 0: *virt-1 1920/510x1080/287+0+0  HDMI-1\n")!!
        assertTrue(ms[0].primary); assertTrue(ms[0].active)
        assertEquals("virt-1", ms[0].name)
    }

    @Test fun xrandrInactiveClientMonitorParsesAsInactive() {
        // xrandr --listmonitors (get_active=False) keeps zero-area
        // client-defined monitors in the list (rrmonitor.c zero-area rule);
        // they carry NO flags, 0x0 geometry and NO outputs
        val out = "Monitors: 2\n" +
            " 0: +*default 1440/364x2960/744+0+0  default\n" +
            " 1: side 0/0x0/0+0+0 \n"
        val ms = RenderProbeParse.xrandrListMonitors(out)!!
        assertEquals(2, ms.size)
        assertTrue(ms[1].name == "side"); assertTrue(!ms[1].active); assertTrue(!ms[1].primary)
    }

    @Test fun xrandrZeroDeclaredMonitorsIsValidEmpty() {
        assertEquals(0, RenderProbeParse.xrandrListMonitors("Monitors: 0\n")!!.size)
    }

    @Test fun xrandrStrictCountAndShape() {
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 2\n 0: +*default 1440/364x2960/744+0+0  default\n"))
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 1\n garbage\n"))
        assertNull(RenderProbeParse.xrandrListMonitors("garbage\n"))
        assertNull(RenderProbeParse.xrandrListMonitors(""))
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 1\n 0: +default 1440x2960+0+0  default\n"))
        assertNull(RenderProbeParse.xrandrListMonitors("RandR 1.5 not supported\n"))
    }

    @Test fun xrandrRejectsDuplicateAndGappedIndices() {
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 2\n" +
            " 0: +*default 1440/364x1480/372+0+0  default\n" +
            " 0: +default 1440/364x1480/372+0+1480  default\n"))
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 2\n" +
            " 0: +*default 1440/364x1480/372+0+0  default\n" +
            " 2: +default 1440/364x1480/372+0+1480  default\n"))
    }

    @Test fun xrandrRejectsMultiplePrimariesAndPrimaryOnInactive() {
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 2\n" +
            " 0: +*default 1440/364x1480/372+0+0  default\n" +
            " 1: +*default 1440/364x1480/372+0+1480  default\n"))
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 1\n" +
            " 0: *side 0/0x0/0+0+0 \n"))
    }

    @Test fun xrandrRejectsBadDimensionsOverflowAndWireRange() {
        // half-zero geometry
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 1\n 0: +default 0/0x5/0+0+0  d\n"))
        // dims beyond CARD16
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 1\n 0: +default 65536/364x2960/744+0+0  d\n"))
        // coords beyond INT16
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 1\n 0: +default 1440/364x2960/744+32768+0  d\n"))
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 1\n 0: +default 1440/364x2960/744+-32769+0  d\n"))
        // integer overflow beyond Long
        assertNull(RenderProbeParse.xrandrListMonitors("Monitors: 1\n 0: +default 99999999999999999999/364x2960/744+0+0  d\n"))
    }

    // Full-output-shaped fixtures: the REAL xdpyinfo -ext XINERAMA output
    // (xdpyinfo.c source-verified) = the core-display preamble (WITH its
    // own "number of screens:" and "screen #0:"/"dimensions:" lines that
    // must NEVER be consulted) + the XINERAMA extension section.
    private fun xdpyinfoFull(xineramaSection: String, withXinerama: Boolean = true): String {
        val exts = if (withXinerama) "    XINERAMA\n" else ""
        return "name of display:    :0\n" +
            "version number:    11.0\n" +
            "vendor string:    The X.Org Foundation\n" +
            "vendor release number:    12001000\n" +
            "X.Org version: 1.20.10\n" +
            "maximum request size:  16777212 bytes\n" +
            "motion buffer size:  256\n" +
            "bitmap unit, bit order, padding:    32, LSBFirst, 32\n" +
            "image byte order:    LSBFirst\n" +
            "number of supported pixmap formats:    2\n" +
            "supported pixmap formats:\n" +
            "    depth 1, bits_per_pixel 1, scanline_pad 32\n" +
            "    depth 24, bits_per_pixel 32, scanline_pad 32\n" +
            "keycode range:    8    255\n" +
            "focus:  window 0x2c00004, revert to Parent\n" +
            "number of extensions:    24\n" +
            "    BIG-REQUESTS\n" +
            "    Composite\n" +
            "    DAMAGE\n" +
            "    DOUBLE-BUFFER\n" +
            "    DPMS\n" +
            "    GLX\n" +
            "    RANDR\n" +
            "    RENDER\n" +
            "    SECURITY\n" +
            "    SHAPE\n" +
            "    SYNC\n" +
            "    X-Resource\n" +
            "    XC-MISC\n" +
            "    XFIXES\n" +
            exts +
            "    XInputExtension\n" +
            "    XKEYBOARD\n" +
            "    XTEST\n" +
            "    XVideo\n" +
            "default screen number:    0\n" +
            "number of screens:    1\n" +
            "\n" +
            "screen #0:\n" +
            "  dimensions:    1440x2960 pixels (381x783 millimeters)\n" +
            "  resolution:    96x96 dots per inch\n" +
            "  depths (7):    24, 1, 4, 8, 15, 16, 32\n" +
            "  root window id:    0x29a\n" +
            "  depth of root window:    24 planes\n" +
            "  number of colormaps:    minimum 1, maximum 1\n" +
            "  default colormap:    0x20\n" +
            "  default number of colormap cells:    256\n" +
            "  preallocated pixels:    black 0, white 16777215\n" +
            "  options:    backing-store WHEN MAPPED, save-unders NO\n" +
            "  largest cursor:    64x64\n" +
            "  current input event mask:    0x0\n" +
            xineramaSection
    }

    @Test fun xdpyinfoActiveOneHeadIgnoresCorePreamble() {
        // THE regression fixture: the core preamble's "number of screens:"
        // and "screen #0:"/"dimensions:" lines must NOT be parsed as
        // Xinerama topology
        val out = xdpyinfoFull("\nXINERAMA version 1.1 opcode: 141\n  head #0: 1440x2960 @ 0,0\n")
        val r = RenderProbeParse.xdpyinfoXinerama(out)!!
        assertEquals(RenderProbeParse.XineramaState.ACTIVE, r.state)
        assertEquals(1, r.heads.size)
        assertTrue(r.heads[0].contentEquals(intArrayOf(0, 0, 1440, 2960)))
    }

    @Test fun xdpyinfoActiveTwoHeads() {
        val out = xdpyinfoFull("\nXINERAMA version 1.1 opcode: 141\n" +
            "  head #0: 1440x1480 @ 0,0\n  head #1: 1440x1480 @ 0,1480\n")
        val r = RenderProbeParse.xdpyinfoXinerama(out)!!
        assertEquals(RenderProbeParse.XineramaState.ACTIVE, r.state)
        assertEquals(2, r.heads.size)
        assertTrue(r.heads[1].contentEquals(intArrayOf(0, 1480, 1440, 1480)))
    }

    @Test fun xdpyinfoHeadCoordinatesArePlainSigned() {
        val out = xdpyinfoFull("\nXINERAMA version 1.1 opcode: 141\n" +
            "  head #0: 1920x1080 @ -1920,0\n")
        val r = RenderProbeParse.xdpyinfoXinerama(out)!!
        assertTrue(r.heads[0].contentEquals(intArrayOf(-1920, 0, 1920, 1080)))
    }

    @Test fun xdpyinfoInactiveIsDeclaredEmpty() {
        val out = xdpyinfoFull("\nXINERAMA version 1.1 opcode: 141\n  Xinerama is inactive.\n")
        val r = RenderProbeParse.xdpyinfoXinerama(out)!!
        assertEquals(RenderProbeParse.XineramaState.INACTIVE, r.state)
        assertTrue(r.heads.isEmpty())
    }

    @Test fun xdpyinfoActiveZeroHeadsIsTheDeclaredLorieState() {
        // vendored pseudoramiX.c: IsActive = !noPseudoramiXExtension (TRUE)
        // with ZERO registered screens -> bare header, no records
        val out = xdpyinfoFull("\nXINERAMA version 1.1 opcode: 141\n")
        val r = RenderProbeParse.xdpyinfoXinerama(out)!!
        assertEquals(RenderProbeParse.XineramaState.ACTIVE, r.state)
        assertTrue(r.heads.isEmpty())
    }

    @Test fun xdpyinfoAbsentIsDeclaredNotMalformed() {
        val out = xdpyinfoFull("\nXINERAMA extension not supported by server\n", withXinerama = false)
        val r = RenderProbeParse.xdpyinfoXinerama(out)!!
        assertEquals(RenderProbeParse.XineramaState.ABSENT, r.state)
        assertTrue(r.heads.isEmpty())
    }

    @Test fun xdpyinfoMalformedFailsClosedNeverFallback() {
        // inactive marker mixed with head records
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version 1.1 opcode: 141\n  Xinerama is inactive.\n  head #0: 1440x2960 @ 0,0\n")))
        // gapped head indices
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version 1.1 opcode: 141\n  head #0: 1440x1480 @ 0,0\n  head #2: 1440x1480 @ 0,1480\n")))
        // duplicate head index
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version 1.1 opcode: 141\n  head #0: 1440x1480 @ 0,0\n  head #0: 1440x1480 @ 0,1480\n")))
        // zero dims on a head
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version 1.1 opcode: 141\n  head #0: 0x2960 @ 0,0\n")))
        // garbage inside the section
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version 1.1 opcode: 141\n  head #0: 1440x2960 @ 0,0\n  garbage\n")))
        // interior blank line inside the section
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version 1.1 opcode: 141\n  head #0: 1440x2960 @ 0,0\n\n  head #1: 1440x2960 @ 0,0\n")))
        // header AND not-supported line: contradictory
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version 1.1 opcode: 141\nXINERAMA extension not supported by server\n")))
        // coord beyond INT16
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version 1.1 opcode: 141\n  head #0: 1440x2960 @ 32768,0\n")))
        // duplicate inactive marker
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version 1.1 opcode: 141\n  Xinerama is inactive.\n  Xinerama is inactive.\n")))
        // MALFORMED header + explicit not-supported: must NOT classify ABSENT
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version X.Y opcode: ??\nXINERAMA extension not supported by server\n", withXinerama = false)))
        // malformed header alone (column-0 XINERAMA line, no valid header)
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA version X.Y opcode: ??\n")))
        // column-0 XINERAMA junk BEFORE a valid header
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull(
            "\nXINERAMA junk line\nXINERAMA version 1.1 opcode: 141\n  head #0: 1440x2960 @ 0,0\n")))
        // no XINERAMA evidence at all (core preamble only / garbage / empty)
        assertNull(RenderProbeParse.xdpyinfoXinerama(xdpyinfoFull("", withXinerama = false)))
        assertNull(RenderProbeParse.xdpyinfoXinerama("garbage\n"))
        assertNull(RenderProbeParse.xdpyinfoXinerama(""))
    }

    @Test fun matchMonitorBijectionSingleExact() {
        val map = RenderProbeParse.matchMonitorBijection(
            listOf(intArrayOf(0,0,1440,2960)), listOf(intArrayOf(0,0,1440,2960)))!!
        assertEquals(0, map[0])
    }

    @Test fun matchMonitorBijectionTwoHeadsOrderSwapped() {
        val mons = listOf(intArrayOf(0,0,1440,1480), intArrayOf(0,1480,1440,1480))
        val desks = listOf(intArrayOf(0,1480,1440,1480), intArrayOf(0,0,1440,1480))
        val map = RenderProbeParse.matchMonitorBijection(desks, mons)!!
        assertTrue(map.contentEquals(intArrayOf(1, 0)))
    }

    @Test fun matchMonitorBijectionAdjacentBoundaryTilesExact() {
        // boundary ownership is settled by EXACTNESS, not point tests:
        // vertically adjacent tiles sharing the y=100 edge match exactly
        val tiles = listOf(intArrayOf(0,0,100,100), intArrayOf(0,100,100,100))
        val map = RenderProbeParse.matchMonitorBijection(tiles, tiles.reversed())!!
        assertTrue(map.contentEquals(intArrayOf(1, 0)))
    }

    @Test fun matchMonitorBijectionRejectsOffByOneAndPhantom() {
        // off-by-one: a mis-realized desktop, NOT noise
        assertNull(RenderProbeParse.matchMonitorBijection(
            listOf(intArrayOf(0,0,1440,1479)), listOf(intArrayOf(0,0,1440,1480))))
        // phantom: desktop matches no monitor
        assertNull(RenderProbeParse.matchMonitorBijection(
            listOf(intArrayOf(0,0,1440,1480)), listOf(intArrayOf(0,100,1440,1480))))
        // count mismatch
        assertNull(RenderProbeParse.matchMonitorBijection(
            listOf(intArrayOf(0,0,100,100), intArrayOf(0,100,100,100)),
            listOf(intArrayOf(0,0,100,200))))
        // malformed rect shape
        assertNull(RenderProbeParse.matchMonitorBijection(
            listOf(intArrayOf(0,0,100)), listOf(intArrayOf(0,0,100,100))))
    }

    @Test fun matchMonitorBijectionRejectsCloneDuplicateMonitors() {
        // clone topology: two identical monitor rects -> ambiguous
        val clone = listOf(intArrayOf(0,0,1440,2960), intArrayOf(0,0,1440,2960))
        assertNull(RenderProbeParse.matchMonitorBijection(clone, clone))
    }
}
