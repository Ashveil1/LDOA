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
}
