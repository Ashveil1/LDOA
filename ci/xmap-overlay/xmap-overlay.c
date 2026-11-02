/* xmap-overlay.c - TEST-ONLY bounded COMPOSITE OVERLAY pixel sampler for
 * the LDOA GNOME session diagnostic (xmap workstream, E-PIX v2).
 *
 * PROVENANCE: original code written for this project's instrumentation;
 * no third-party code copied. Not shipped in any product artifact;
 * compiled only in CI and run only inside the emulator guest by the
 * instrumented test arm.
 *
 * PURPOSE: under X11 compositing the compositor's output drawable is
 * the Composite Overlay Window (COW), not the root window, so root
 * XGetImage patches cannot see composited content. This helper samples
 * the COW - tiered and gated, with every side effect recorded:
 *
 *   Tier 0 (read-only): XCompositeQueryVersion + XGetSelectionOwner on
 *     _NET_WM_CM_S0. NO overlay request is ever issued when the owner
 *     is None (XCompositeGetOverlayWindow would create/MAP the overlay
 *     itself - XCOMPOSITE(3): "If this Composite Overlay Window has not
 *     yet been mapped, it is mapped by this request").
 *   Tier 1 (read-only): owner window attributes, recorded. Producer
 *     attribution is ALWAYS UNKNOWN: XC-MISC GetXIDRange returns an
 *     available ID allocation range, NOT the connection's resource-id
 *     mask, so it cannot establish a client partition, and no XID
 *     high-bit partition is ever assumed. A CM selection owner is a
 *     current selection state, not an authenticated compositor producer.
 *   Tier 2 (gated on a non-None owner): XCompositeGetOverlayWindow -
 *     an ACQUISITION+MAPPING operation, recorded as such (XO_SIDE_EFFECT),
 *     with the owner re-read immediately BEFORE and AFTER (selection
 *     ownership can race). Post-get mapstate is NEVER claimed as
 *     preexisting viewability. Matched XCompositeReleaseOverlayWindow +
 *     XCloseDisplay on EVERY path after a successful Get; release+close
 *     are recorded but never claimed to restore pre-probe state.
 *
 * WHAT A SAMPLE MEANS: 5 sparse XGetImage patches of the overlay
 * drawable. Producer attribution is per XO_ATTRIB only (a selection-
 * producer UNKNOWN). It is NOT the Android-presented frame; black or
 * failed patches are NOT proof of absent content; 5 patches are NOT
 * whole-output coverage; the overlay can be shaped away under
 * unredirected fullscreen windows (mutter shape_cow_for_window).
 *
 * BOUNDS: 5 patches (center + 4 corners) of at most 32x32 pixels
 * (<=5120 pixels, P6 PPM <= ~16 KiB), 20s deadline, distinct exit
 * codes, every failure recorded separately. Failed patch slots are
 * deterministic ZERO bytes (calloc), never malloc garbage. The deadline
 * _exit(6) is a DISCONNECT BACKSTOP: the server releases a client's
 * overlay reference on connection close (XCOMPOSITE(3)); it is never
 * claimed as an explicit matched release.
 *
 * Exit codes: 0 all patches sampled; 2 display open failed; 3 every
 * GetImage failed; 4 X protocol error recorded; 5 unsupported visual;
 * 6 deadline hit; 7 partial sample (PPM holds ok patches, failed slots
 * zeroed); 8 output write failed (partial file REMOVED); 10 SKIPPED -
 * recorded outcome, no overlay request issued (no Composite extension,
 * no CM atom, owner None, or owner lost before Get); 11 usage.
 *
 * stdout protocol (parsed by the instrumented test):
 *   XO_META root=0x.. vendor=[..] proto=M.m t=..
 *   XO_COMPOSITE_VER M.m | absent
 *   XO_CM_OWNER initial=0x..|None screen=0
 *   XO_OWNER_ATTRS id=0x.. wxh=WxH+X+Y mapstate=Is*  (or rc=fail)
 *   XO_ATTRIB result=UNKNOWN detail=no-server-provided-client-identification (always)
 *   XO_SIDE_EFFECT note=[acquisition+mapping semantics; not passive]
 *   XO_OWNER pre=0x..  /  XO_OWNER post=0x..
 *   XO_NOTE owner-changed-before-get|owner-changed-during-get|owner-lost-before-get ..
 *   XO_OVERLAY id=0x..
 *   XO_OVERLAY_ATTRS wxh=.. mapstate=.. mapstate-label=post-get-never-preexisting-viewability ..
 *   XO_PATCH name=.. x=.. y=.. size=.. ok=0|1 t0=.. t1=.. err=..
 *   XO_RELEASE result=released|x-error ..
 *   XO_RESULT out=.. bytes=.. patches-ok=N note=[..] | XO_RESULT skipped=<reason> note=[..]
 *   XO_ERROR stage=.. detail=..  /  XO_XERROR code=.. request=..
 *   XO_DONE patches-ok=N elapsed-ms=..
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>

#define MAX_PATCH 32
#define NPATCHES 5
#define DEADLINE_SECS 20

static long long now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

/* ---- shared conversion + PPM assembly (same math as ci/xmap-pixels) --- */

static int mask_decode(unsigned long mask, int *shift, int *bits) {
    int s = 0, b = 0;
    unsigned long m = mask;
    if (!m) return -1;
    while (!(m & 1)) { s++; m >>= 1; }
    while (m & 1) { b++; m >>= 1; }
    if (m) return -2; /* non-contiguous mask: unsupported, recorded */
    *shift = s; *bits = b;
    return 0;
}

#ifndef XMAP_SELFTEST
static unsigned char scale_channel(unsigned long pixel, unsigned long mask,
                                   int shift, int bits) {
    unsigned long v = (pixel & mask) >> shift;
    unsigned long maxv = (1UL << bits) - 1;
    if (!maxv) return 0;
    return (unsigned char)((v * 255UL + maxv / 2) / maxv);
}
#endif /* !XMAP_SELFTEST (scale_channel) */

struct patch { int x, y, size; long long t0, t1; int ok; const char *err; };

/* Write result is checked at EVERY step; any failure removes the
 * partial output (a truncated PPM must never survive as evidence). */
static int write_ppm(const char *path, int ps, unsigned char *pixels, long long bytes,
                     const struct patch patches[NPATCHES]) {
    FILE *f = fopen(path, "wb");
    int i, bad = 0;
    if (!f) return -1;
    if (fprintf(f, "P6\n") < 0) bad = 1;
    if (!bad && fprintf(f, "# xmap-overlay sample; patches stacked vertically in order:\n") < 0) bad = 1;
    for (i = 0; !bad && i < NPATCHES; i++)
        if (fprintf(f, "# patch %d: x=%d y=%d size=%d ok=%d t0=%lld t1=%lld err=%s\n",
                i, patches[i].x, patches[i].y, patches[i].size, patches[i].ok,
                patches[i].t0, patches[i].t1, patches[i].err ? patches[i].err : "none") < 0) bad = 1;
    if (!bad && fprintf(f, "# sampled composite-overlay drawable content; black or failed patches are NOT proof of absent content\n") < 0) bad = 1;
    if (!bad && fprintf(f, "# producer attribution per XO_ATTRIB only; NOT the Android-presented frame\n") < 0) bad = 1;
    if (!bad && fprintf(f, "%d %d\n255\n", ps, ps * NPATCHES) < 0) bad = 1;
    if (!bad && fwrite(pixels, 1, (size_t)bytes, f) != (size_t)bytes) bad = 1;
    if (fclose(f) != 0) bad = 1;
    if (bad) { remove(path); return -2; }
    return 0;
}

/* ---- X abstraction: every X interaction sits behind this vtable so the
 * host selftest can drive the REAL control flow with scenario doubles. */

struct xattrs {
    long x, y; unsigned width, height;
    int map_state;   /* 0 IsUnmapped, 1 IsUnviewable, 2 IsViewable */
    int depth, vclass; unsigned long red, green, blue; int has_visual;
};

typedef struct {
    int  (*open_display)(void **dpy_out);
    void (*close_display)(void *dpy);
    unsigned long (*default_root)(void *dpy);
    const char *(*server_vendor)(void *dpy);
    int (*proto_version)(void *dpy);
    int (*proto_revision)(void *dpy);
    int (*sync)(void *dpy);
    int (*composite_query_version)(void *dpy, int *maj, int *min); /* 0 ok, 1 absent */
    unsigned long (*intern_cm_atom)(void *dpy);                    /* 0 on failure */
    unsigned long (*get_selection_owner)(void *dpy, unsigned long atom);
    int (*get_window_attributes)(void *dpy, unsigned long w, struct xattrs *out); /* 0 ok */
    unsigned long (*get_overlay_window)(void *dpy, unsigned long root); /* 0 = failed */
    void (*release_overlay_window)(void *dpy, unsigned long ov);
    int (*get_image_patch)(void *dpy, unsigned long drawable, int x, int y, unsigned ps,
                           const struct xattrs *a, unsigned char *dst_rgb); /* 0 ok */
    void (*xerr_clear)(void);
    int (*xerr_seen)(void);
    void (*xerr_describe)(char *buf, size_t n);
} XOps;

/* ONE cleanup path after a confirmed-acquired overlay XID: release is
 * always attempted, the X-error state is inspected, and the outcome is
 * recorded as attempted + confirmed|x-error with its context tag. The
 * deadline _exit(6) path is a disconnect backstop instead (the server
 * releases the reference on connection close), never claimed here. */
static int do_release(const XOps *ops, void *dpy, unsigned long ov, const char *ctx) {
    char eb[96];
    ops->xerr_clear();
    ops->release_overlay_window(dpy, ov);
    ops->sync(dpy);
    if (ops->xerr_seen()) {
        ops->xerr_describe(eb, sizeof eb);
        printf("XO_RELEASE attempted=1 result=x-error %s context=%s\n", eb, ctx);
        return 1;
    }
    printf("XO_RELEASE attempted=1 result=confirmed context=%s\n", ctx);
    return 0;
}

static const char *map_state_str(int ms) {
    switch (ms) {
        case 0: return "IsUnmapped";
        case 1: return "IsUnviewable";
        case 2: return "IsViewable";
        default: return "UNPARSED";
    }
}

static void fmt_xid(char *b, size_t n, unsigned long v) {
    if (v) snprintf(b, n, "0x%lx", v);
    else snprintf(b, n, "None");
}

/* ---- shared probe flow (runtime AND host selftest drive this) ---- */

static int run_probe(const XOps *ops, const char *out_path) {
    long long t_start = now_ms();
    void *dpy = NULL;
    unsigned long root, atom, owner0, owner_pre, owner_post, ov = 0;
    int i, okcount = 0, maj = 0, min = 0, ps;
    struct xattrs oa;
    struct patch patches[NPATCHES];
    int px[NPATCHES], py[NPATCHES];
    const char *names[NPATCHES] = { "center", "nw", "ne", "sw", "se" };
    int rs = 0, rb = 0, gs = 0, gb = 0, bs = 0, bb = 0;
    char b1[32], b2[32], eb[96];
    unsigned char *ppm;
    long long ppm_bytes;

    if (ops->open_display(&dpy)) {
        printf("XO_ERROR stage=open-display\nXO_DONE patches-ok=0\n");
        return 2;
    }
    root = ops->default_root(dpy);
    printf("XO_META root=0x%lx vendor=[%s] proto=%d.%d t=%lld\n",
           root, ops->server_vendor(dpy), ops->proto_version(dpy),
           ops->proto_revision(dpy), now_ms());

    /* Tier 0a: Composite extension presence (read-only version query). */
    if (ops->composite_query_version(dpy, &maj, &min)) {
        printf("XO_COMPOSITE_VER absent\n");
        printf("XO_RESULT skipped=no-composite-extension note=[no overlay request issued;"
               " root-only fallback preserved by caller]\n");
        printf("XO_DONE patches-ok=0 elapsed-ms=%lld\n", now_ms() - t_start);
        ops->close_display(dpy);
        return 10;
    }
    printf("XO_COMPOSITE_VER %d.%d\n", maj, min);

    /* Tier 0b: compositing-manager selection owner (pure read). */
    atom = ops->intern_cm_atom(dpy);
    if (!atom) {
        printf("XO_CM_ATOM absent note=[atom not currently interned on this server;"
               " the only_if_exists query did not create it, and current absence is not evidence"
               " that no compositor ever registered]\n");
        printf("XO_RESULT skipped=no-cm-atom note=[no overlay request issued; root-only"
               " fallback preserved]\n");
        printf("XO_DONE patches-ok=0 elapsed-ms=%lld\n", now_ms() - t_start);
        ops->close_display(dpy);
        return 10;
    }
    owner0 = ops->get_selection_owner(dpy, atom);
    fmt_xid(b1, sizeof b1, owner0);
    printf("XO_CM_OWNER initial=%s screen=0\n", b1);
    if (!owner0) {
        printf("XO_RESULT skipped=cm-owner-none note=[no compositor selection owner;"
               " per design NO overlay request issued - GetOverlayWindow would create/map"
               " the overlay itself; root-only fallback preserved]\n");
        printf("XO_DONE patches-ok=0 elapsed-ms=%lld\n", now_ms() - t_start);
        ops->close_display(dpy);
        return 10;
    }

    /* Tier 1: owner attributes (record only). Producer attribution is
     * ALWAYS UNKNOWN: no server-provided client identification is used
     * (XC-MISC GetXIDRange is an allocation range, not a resource mask)
     * and no XID partition is assumed. */
    if (!ops->get_window_attributes(dpy, owner0, &oa))
        printf("XO_OWNER_ATTRS id=0x%lx wxh=%ux%u%+ld%+ld mapstate=%s\n",
               owner0, oa.width, oa.height, oa.x, oa.y, map_state_str(oa.map_state));
    else
        printf("XO_OWNER_ATTRS id=0x%lx rc=fail\n", owner0);
    printf("XO_ATTRIB result=UNKNOWN detail=no-server-provided-client-identification"
           " note=[a CM selection owner is a current selection state, not an"
           " authenticated compositor producer; no XID partition assumed]\n");

    printf("XO_SIDE_EFFECT note=[XCompositeGetOverlayWindow is an acquisition+mapping"
           " operation: it creates/maps the overlay window if not already mapped;"
           " a CM selection owner does not prove the overlay pre-existed viewable;"
           " release+close are recorded but never claimed to restore pre-probe state]\n");

    /* Tier 2 gate: owner re-read immediately BEFORE the acquisition. */
    owner_pre = ops->get_selection_owner(dpy, atom);
    fmt_xid(b1, sizeof b1, owner_pre);
    printf("XO_OWNER pre=%s\n", b1);
    if (!owner_pre) {
        printf("XO_NOTE owner-lost-before-get (selection raced away; recorded,"
               " no overlay request issued)\n");
        printf("XO_RESULT skipped=cm-owner-lost note=[root-only fallback preserved]\n");
        printf("XO_DONE patches-ok=0 elapsed-ms=%lld\n", now_ms() - t_start);
        ops->close_display(dpy);
        return 10;
    }
    if (owner_pre != owner0) {
        fmt_xid(b1, sizeof b1, owner0); fmt_xid(b2, sizeof b2, owner_pre);
        printf("XO_NOTE owner-changed-before-get from=%s to=%s\n", b1, b2);
    }

    ops->xerr_clear();
    ov = ops->get_overlay_window(dpy, root);
    ops->sync(dpy);
    owner_post = ops->get_selection_owner(dpy, atom);
    fmt_xid(b1, sizeof b1, owner_post);
    printf("XO_OWNER post=%s\n", b1);
    if (owner_post != owner_pre) {
        fmt_xid(b1, sizeof b1, owner_pre); fmt_xid(b2, sizeof b2, owner_post);
        printf("XO_NOTE owner-changed-during-get from=%s to=%s\n", b1, b2);
    }
    if (!ov || ops->xerr_seen()) {
        ops->xerr_describe(eb, sizeof eb);
        printf("XO_ERROR stage=get-overlay detail=%s\n", ov ? "x-error-with-xid" : "null-window");
        printf("XO_XERROR %s\n", ops->xerr_seen() ? eb : "none");
        printf("XO_NOTE acquisition state UNCERTAIN; no release issued (no confirmed"
               " acquired overlay XID); disconnect is the cleanup backstop\n");
        printf("XO_DONE patches-ok=0 elapsed-ms=%lld\n", now_ms() - t_start);
        ops->close_display(dpy);
        return 4;
    }
    printf("XO_OVERLAY id=0x%lx\n", ov);

    if (ops->get_window_attributes(dpy, ov, &oa)) {
        printf("XO_ERROR stage=get-attr-overlay\n");
        do_release(ops, dpy, ov, "get-attr-fail");
        printf("XO_DONE patches-ok=0\n");
        ops->close_display(dpy);
        return 4;
    }
    printf("XO_OVERLAY_ATTRS wxh=%ux%u%+ld%+ld mapstate=%s"
           " mapstate-label=post-get-never-preexisting-viewability depth=%d"
           " visual-class=%d masks=%lx/%lx/%lx\n",
           oa.width, oa.height, oa.x, oa.y, map_state_str(oa.map_state),
           oa.depth, oa.vclass, oa.red, oa.green, oa.blue);

    if (!oa.has_visual || (oa.vclass != 4 && oa.vclass != 5) ||  /* TrueColor/DirectColor */
        mask_decode(oa.red, &rs, &rb) || mask_decode(oa.green, &gs, &gb) ||
        mask_decode(oa.blue, &bs, &bb)) {
        printf("XO_ERROR stage=visual detail=unsupported-or-zero-masks\n");
        do_release(ops, dpy, ov, "visual-unsupported");
        printf("XO_DONE patches-ok=0\n");
        ops->close_display(dpy);
        return 5;
    }

    ps = MAX_PATCH;
    if ((int)oa.width / 4 < ps) ps = (int)oa.width / 4;
    if ((int)oa.height / 4 < ps) ps = (int)oa.height / 4;
    if (ps < 1) ps = 1;
    px[0] = ((int)oa.width - ps) / 2;  py[0] = ((int)oa.height - ps) / 2;
    px[1] = 8 < (int)oa.width - ps ? 8 : 0;  py[1] = 8 < (int)oa.height - ps ? 8 : 0;
    px[2] = (int)oa.width - ps - px[1];      py[2] = py[1];
    px[3] = px[1];  py[3] = (int)oa.height - ps - py[1];
    px[4] = px[2];  py[4] = py[3];

    /* calloc: failed patch slots stay deterministic ZERO bytes. */
    ppm = calloc((size_t)ps * ps * NPATCHES * 3, 1);
    if (!ppm) {
        do_release(ops, dpy, ov, "alloc-fail");
        printf("XO_DONE patches-ok=0\n");
        ops->close_display(dpy);
        return 3;
    }

    ops->sync(dpy); /* protocol-stream sync only; no coherence claim */
    for (i = 0; i < NPATCHES; i++) {
        patches[i].x = px[i]; patches[i].y = py[i]; patches[i].size = ps;
        patches[i].t0 = now_ms(); patches[i].ok = 0; patches[i].err = NULL;
        ops->xerr_clear();
        if (ops->get_image_patch(dpy, ov, px[i], py[i], (unsigned)ps, &oa,
                                 ppm + (size_t)i * ps * ps * 3)) {
            patches[i].err = ops->xerr_seen() ? "x-error" : "getimage-null";
        } else {
            patches[i].ok = 1; okcount++;
        }
        patches[i].t1 = now_ms();
        printf("XO_PATCH name=%s x=%d y=%d size=%d ok=%d t0=%lld t1=%lld err=%s\n",
               names[i], px[i], py[i], ps, patches[i].ok,
               patches[i].t0, patches[i].t1,
               patches[i].err ? patches[i].err : "none");
    }

    ppm_bytes = (long long)ps * ps * NPATCHES * 3;
    if (okcount > 0) {
        int wr = write_ppm(out_path, ps, ppm, ppm_bytes, patches);
        if (wr) {
            printf("XO_ERROR stage=write detail=ppm-write-failed partial-removed rc=%d\n", wr);
            do_release(ops, dpy, ov, "write-fail");
            printf("XO_DONE patches-ok=%d elapsed-ms=%lld write=FAILED\n",
                   okcount, now_ms() - t_start);
            free(ppm);
            ops->close_display(dpy);
            return 8;
        }
        printf("XO_RESULT out=%s bytes=%lld patches-ok=%d note=[sampled composite-overlay"
               " drawable content; producer attribution per XO_ATTRIB only (selection-state"
               " producer UNKNOWN); NOT the Android-presented frame; black-or-failed != absent"
               " content; 5 sparse patches != whole output; overlay can be shaped away"
               " under unredirected fullscreen windows]\n",
               out_path, ppm_bytes, okcount);
    }

    /* Matched release on the success path; outcome inspected+recorded. */
    if (do_release(ops, dpy, ov, "sample-complete")) {
        printf("XO_DONE patches-ok=%d elapsed-ms=%lld\n", okcount, now_ms() - t_start);
        free(ppm);
        ops->close_display(dpy);
        return 4;
    }
    printf("XO_DONE patches-ok=%d elapsed-ms=%lld\n", okcount, now_ms() - t_start);
    free(ppm);
    ops->close_display(dpy);
    if (okcount == 0) return 3;
    if (okcount < NPATCHES) return 7;
    return 0;
}

#ifndef XMAP_SELFTEST
/* ======================= runtime build (Xlib) ======================= */
#include <signal.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>

/* libXcomposite client ABI: three stable signatures, linked -lXcomposite.
 * Declared here so the build does not depend on header packaging drift. */
extern int XCompositeQueryVersion(Display *dpy, int *major, int *minor);
extern Window XCompositeGetOverlayWindow(Display *dpy, Window w);
extern void XCompositeReleaseOverlayWindow(Display *dpy, Window w);

static volatile int xerr_seen_flag = 0;
static int xerr_code = 0, xerr_request = 0;
static int xerr_handler(Display *d, XErrorEvent *e) {
    (void)d;
    xerr_seen_flag = 1; xerr_code = e->error_code; xerr_request = e->request_code;
    return 0;
}
static void on_alarm(int sig) { (void)sig; _exit(6); }

static Display *rt_dpy = NULL; /* for the error handler globals */

static int rt_open(void **out) {
    rt_dpy = XOpenDisplay(NULL);
    if (!rt_dpy) return 1;
    XSetErrorHandler(xerr_handler);
    *out = rt_dpy;
    return 0;
}
static void rt_close(void *d) { XCloseDisplay((Display *)d); }
static unsigned long rt_root(void *d) { return (unsigned long)DefaultRootWindow((Display *)d); }
static const char *rt_vendor(void *d) { return ServerVendor((Display *)d); }
static int rt_pver(void *d) { return ProtocolVersion((Display *)d); }
static int rt_prev(void *d) { return ProtocolRevision((Display *)d); }
static int rt_sync(void *d) { XSync((Display *)d, False); return 0; }
static int rt_comp_ver(void *d, int *maj, int *min) {
    return XCompositeQueryVersion((Display *)d, maj, min) ? 0 : 1;
}
static unsigned long rt_cm_atom(void *d) {
    return (unsigned long)XInternAtom((Display *)d, "_NET_WM_CM_S0", True);
}
static unsigned long rt_owner(void *d, unsigned long atom) {
    return (unsigned long)XGetSelectionOwner((Display *)d, (Atom)atom);
}
static int rt_attrs(void *d, unsigned long w, struct xattrs *out) {
    XWindowAttributes a;
    if (!XGetWindowAttributes((Display *)d, (Window)w, &a)) return 1;
    out->x = a.x; out->y = a.y;
    out->width = (unsigned)a.width; out->height = (unsigned)a.height;
    out->map_state = (a.map_state == IsViewable) ? 2 : (a.map_state == IsUnviewable) ? 1 : 0;
    out->depth = a.depth;
    if (a.visual) {
        out->has_visual = 1; out->vclass = a.visual->class;
        out->red = a.visual->red_mask; out->green = a.visual->green_mask;
        out->blue = a.visual->blue_mask;
    } else {
        out->has_visual = 0; out->vclass = -1; out->red = out->green = out->blue = 0;
    }
    return 0;
}
static unsigned long rt_get_overlay(void *d, unsigned long root) {
    return (unsigned long)XCompositeGetOverlayWindow((Display *)d, (Window)root);
}
static void rt_release_overlay(void *d, unsigned long ov) {
    XCompositeReleaseOverlayWindow((Display *)d, (Window)ov);
}
static int rt_get_patch(void *d, unsigned long drawable, int x, int y, unsigned ps,
                        const struct xattrs *a, unsigned char *dst) {
    Display *dpy = (Display *)d;
    XImage *im;
    unsigned ix, iy;
    int rs, rb, gs, gb, bs, bb;
    if (mask_decode(a->red, &rs, &rb) || mask_decode(a->green, &gs, &gb) ||
        mask_decode(a->blue, &bs, &bb)) return 1;
    im = XGetImage(dpy, (Window)drawable, x, y, ps, ps, AllPlanes, ZPixmap);
    XSync(dpy, False);
    if (!im) return 1;
    if (xerr_seen_flag) { XDestroyImage(im); return 1; }
    for (iy = 0; iy < ps; iy++)
        for (ix = 0; ix < ps; ix++) {
            unsigned long pv = XGetPixel(im, (int)ix, (int)iy);
            unsigned char *p = dst + ((size_t)iy * ps + ix) * 3;
            p[0] = scale_channel(pv, a->red, rs, rb);
            p[1] = scale_channel(pv, a->green, gs, gb);
            p[2] = scale_channel(pv, a->blue, bs, bb);
        }
    XDestroyImage(im);
    return 0;
}
static void rt_xerr_clear(void) { xerr_seen_flag = 0; xerr_code = 0; xerr_request = 0; }
static int rt_xerr_seen(void) { return xerr_seen_flag; }
static void rt_xerr_describe(char *buf, size_t n) {
    snprintf(buf, n, "code=%d request=%d", xerr_code, xerr_request);
}

int main(int argc, char **argv) {
    static XOps ops;
    signal(SIGALRM, on_alarm);
    alarm(DEADLINE_SECS);
    if (argc < 2) {
        fprintf(stderr, "usage: xmap-overlay <out.ppm>\n");
        return 11;
    }
    ops.open_display = rt_open;
    ops.close_display = rt_close;
    ops.default_root = rt_root;
    ops.server_vendor = rt_vendor;
    ops.proto_version = rt_pver;
    ops.proto_revision = rt_prev;
    ops.sync = rt_sync;
    ops.composite_query_version = rt_comp_ver;
    ops.intern_cm_atom = rt_cm_atom;
    ops.get_selection_owner = rt_owner;
    ops.get_window_attributes = rt_attrs;
    ops.get_overlay_window = rt_get_overlay;
    ops.release_overlay_window = rt_release_overlay;
    ops.get_image_patch = rt_get_patch;
    ops.xerr_clear = rt_xerr_clear;
    ops.xerr_seen = rt_xerr_seen;
    ops.xerr_describe = rt_xerr_describe;
    return run_probe(&ops, argv[1]);
}

#else /* ==================== host selftest (fake X) ==================== */
/* Scenario-driven doubles for the XOps vtable. These exercise the REAL
 * run_probe control flow end to end on any host with no X server;
 * nothing here says anything about runtime X behavior (the CI runtime
 * compile is the first real runtime-branch check). */
#include <fcntl.h>

struct fake {
    unsigned long owner_seq[4]; int n_owners; int owner_reads;
    unsigned long overlay;
    int composite_present;
    int atom_absent;
    int get_mode;       /* 0 ok, 1 return-0+xerr, 2 return-xid+xerr (uncertain) */
    int release_xerr;
    unsigned fail_mask; /* bit k -> k-th patch GetImage fails */
    int get_calls, release_calls, patch_calls;
    int xerr_flag;
};
static struct fake *g_fake;

static int fk_open(void **out) { *out = (void *)0x1; return 0; }
static void fk_close(void *d) { (void)d; }
static unsigned long fk_root(void *d) { (void)d; return 0x511UL; }
static const char *fk_vendor(void *d) { (void)d; return "Fake X.Org"; }
static int fk_pver(void *d) { (void)d; return 11; }
static int fk_prev(void *d) { (void)d; return 0; }
static int fk_sync(void *d) { (void)d; return 0; }
static int fk_comp_ver(void *d, int *maj, int *min) {
    (void)d;
    if (!g_fake->composite_present) return 1;
    *maj = 0; *min = 4;
    return 0;
}
static unsigned long fk_atom(void *d) { (void)d; return g_fake->atom_absent ? 0UL : 300UL; }
static unsigned long fk_owner(void *d, unsigned long atom) {
    int idx;
    (void)d; (void)atom;
    idx = g_fake->owner_reads < g_fake->n_owners - 1 ?
          g_fake->owner_reads : g_fake->n_owners - 1;
    if (g_fake->owner_reads < g_fake->n_owners) g_fake->owner_reads++;
    return g_fake->owner_seq[idx];
}
static int fk_attrs(void *d, unsigned long w, struct xattrs *out) {
    (void)d;
    if (w == g_fake->overlay) {
        out->x = 0; out->y = 0; out->width = 1280; out->height = 1024;
        out->map_state = 2; out->depth = 24; out->has_visual = 1; out->vclass = 4;
        out->red = 0xFF0000UL; out->green = 0x00FF00UL; out->blue = 0x0000FFUL;
    } else {
        out->x = -100; out->y = -100; out->width = 1; out->height = 1;
        out->map_state = 0; out->depth = 24; out->has_visual = 1; out->vclass = 4;
        out->red = 0xFF0000UL; out->green = 0x00FF00UL; out->blue = 0x0000FFUL;
    }
    return 0;
}
static unsigned long fk_get_overlay(void *d, unsigned long root) {
    (void)d; (void)root;
    g_fake->get_calls++;
    if (g_fake->get_mode == 1) { g_fake->xerr_flag = 1; return 0; }
    if (g_fake->get_mode == 2) { g_fake->xerr_flag = 1; return g_fake->overlay; }
    return g_fake->overlay;
}
static void fk_release_overlay(void *d, unsigned long ov) {
    (void)d; (void)ov;
    g_fake->release_calls++;
    if (g_fake->release_xerr) g_fake->xerr_flag = 1;
}
static int fk_patch(void *d, unsigned long drawable, int x, int y, unsigned ps,
                    const struct xattrs *a, unsigned char *dst) {
    unsigned ix, iy;
    int idx;
    (void)d; (void)drawable; (void)a;
    idx = g_fake->patch_calls++;
    if (g_fake->fail_mask & (1U << idx)) return 1;
    for (iy = 0; iy < ps; iy++)
        for (ix = 0; ix < ps; ix++) {
            unsigned char *p = dst + ((size_t)iy * ps + ix) * 3;
            p[0] = (unsigned char)((x + (int)ix) & 0xFF);
            p[1] = (unsigned char)((y + (int)iy) & 0xFF);
            p[2] = 0x80;
        }
    return 0;
}
static void fk_xerr_clear(void) { g_fake->xerr_flag = 0; }
static int fk_xerr_seen(void) { return g_fake->xerr_flag; }
static void fk_xerr_describe(char *buf, size_t n) { snprintf(buf, n, "code=3 request=98"); }

static void make_fake_ops(XOps *ops, struct fake *cfg) {
    g_fake = cfg;
    ops->open_display = fk_open;
    ops->close_display = fk_close;
    ops->default_root = fk_root;
    ops->server_vendor = fk_vendor;
    ops->proto_version = fk_pver;
    ops->proto_revision = fk_prev;
    ops->sync = fk_sync;
    ops->composite_query_version = fk_comp_ver;
    ops->intern_cm_atom = fk_atom;
    ops->get_selection_owner = fk_owner;
    ops->get_window_attributes = fk_attrs;
    ops->get_overlay_window = fk_get_overlay;
    ops->release_overlay_window = fk_release_overlay;
    ops->get_image_patch = fk_patch;
    ops->xerr_clear = fk_xerr_clear;
    ops->xerr_seen = fk_xerr_seen;
    ops->xerr_describe = fk_xerr_describe;
}

static int fails = 0, passes = 0;
static void expect1(const char *name, int cond) {
    if (cond) { passes++; printf("SELFTEST-PASS %s\n", name); }
    else { fails++; printf("SELFTEST-FAIL %s\n", name); }
}
static long fsize(const char *path) {
    FILE *f = fopen(path, "rb");
    long n = -1;
    if (f) { fseek(f, 0, SEEK_END); n = ftell(f); fclose(f); }
    return n;
}

/* Run one scenario with stdout captured to a scratch file; returns the
 * run_probe exit code and the captured transcript in logbuf. */
static int run_scenario(struct fake *cfg, const char *out,
                        char *logbuf, size_t logn) {
    char tmp[128];
    int saved, fd, rc;
    FILE *f;
    size_t n;
    XOps ops;
    snprintf(tmp, sizeof tmp, "/tmp/xo-selftest-log-%d.txt", (int)getpid());
    fflush(stdout);
    saved = dup(1);
    fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    dup2(fd, 1);
    make_fake_ops(&ops, cfg);
    rc = run_probe(&ops, out);
    fflush(stdout);
    dup2(saved, 1);
    close(saved);
    close(fd);
    f = fopen(tmp, "rb");
    n = f ? fread(logbuf, 1, logn - 1, f) : 0;
    logbuf[n] = '\0';
    if (f) fclose(f);
    remove(tmp);
    return rc;
}

int main(void) {
    static char log[8192];
    struct fake c;
    int rc;
    const char *out = "/tmp/xo-selftest-out.ppm";

    /* 0: happy path - owner stable, attribution UNKNOWN by design */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = c.owner_seq[1] = c.owner_seq[2] = 0x400007UL; c.n_owners = 3;
    c.overlay = 0x400009UL; c.composite_present = 1;
    remove(out);
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("happy rc=0", rc == 0);
    expect1("happy get+release matched", c.get_calls == 1 && c.release_calls == 1);
    expect1("happy owner read 3x (initial/pre/post)", c.owner_reads == 3);
    expect1("happy attrib UNKNOWN", strstr(log, "XO_ATTRIB result=UNKNOWN") != NULL);
    expect1("happy no client-partition label", strstr(log, "same-client") == NULL &&
            strstr(log, "different-client") == NULL);
    expect1("happy side-effect recorded", strstr(log, "XO_SIDE_EFFECT") != NULL);
    expect1("happy release attempted+confirmed",
            strstr(log, "XO_RELEASE attempted=1 result=confirmed context=sample-complete") != NULL);
    expect1("happy artifact written", fsize(out) > 15360L);
    expect1("happy post-get label", strstr(log, "post-get-never-preexisting-viewability") != NULL);
    remove(out);

    /* 1: owner None - NO overlay request may be issued */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = 0; c.n_owners = 1; c.composite_present = 1;
    remove(out);
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("owner-none rc=10", rc == 10);
    expect1("owner-none zero GetOverlay", c.get_calls == 0);
    expect1("owner-none zero Release", c.release_calls == 0);
    expect1("owner-none skip recorded", strstr(log, "skipped=cm-owner-none") != NULL);
    expect1("owner-none no artifact", fsize(out) < 0);

    /* 2: owner lost between initial and pre-get read */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = 0x400007UL; c.owner_seq[1] = 0; c.n_owners = 2;
    c.composite_present = 1;
    remove(out);
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("owner-lost rc=10", rc == 10);
    expect1("owner-lost zero GetOverlay", c.get_calls == 0);
    expect1("owner-lost note", strstr(log, "owner-lost-before-get") != NULL);

    /* 3: owner changes during Get - recorded; attribution never retained */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = c.owner_seq[1] = 0x400007UL; c.owner_seq[2] = 0x4000aaUL; c.n_owners = 3;
    c.overlay = 0x400009UL; c.composite_present = 1;
    remove(out);
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("owner-race rc=0", rc == 0);
    expect1("owner-race note", strstr(log, "owner-changed-during-get") != NULL);
    expect1("owner-race attrib still UNKNOWN", strstr(log, "XO_ATTRIB result=UNKNOWN") != NULL);
    expect1("owner-race no client label", strstr(log, "same-client") == NULL);
    expect1("owner-race release confirmed", strstr(log, "result=confirmed") != NULL);
    remove(out);

    /* 4: GetOverlayWindow returns 0 with X error - no release issuable */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = c.owner_seq[1] = c.owner_seq[2] = 0x400007UL; c.n_owners = 3;
    c.composite_present = 1; c.get_mode = 1;
    remove(out);
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("get-xerr rc=4", rc == 4);
    expect1("get-xerr zero Release", c.release_calls == 0);
    expect1("get-xerr stage line", strstr(log, "stage=get-overlay") != NULL);
    expect1("get-xerr uncertain-acquisition wording",
            strstr(log, "acquisition state UNCERTAIN") != NULL &&
            strstr(log, "cleanup backstop") != NULL);
    expect1("get-xerr no artifact", fsize(out) < 0);

    /* 5: GetOverlayWindow returns an XID WITH an X error - uncertain, no release */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = c.owner_seq[1] = c.owner_seq[2] = 0x400007UL; c.n_owners = 3;
    c.overlay = 0x400009UL; c.composite_present = 1; c.get_mode = 2;
    remove(out);
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("get-xid+xerr rc=4", rc == 4);
    expect1("get-xid+xerr zero Release", c.release_calls == 0);
    expect1("get-xid+xerr detail", strstr(log, "x-error-with-xid") != NULL);
    expect1("get-xid+xerr uncertain wording", strstr(log, "acquisition state UNCERTAIN") != NULL);

    /* 6: partial sample - 2 of 5 patches fail; artifact holds ok patches */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = c.owner_seq[1] = c.owner_seq[2] = 0x400007UL; c.n_owners = 3;
    c.overlay = 0x400009UL; c.composite_present = 1; c.fail_mask = 0x5;
    remove(out);
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("partial rc=7", rc == 7);
    expect1("partial patches-ok=3", strstr(log, "patches-ok=3") != NULL);
    expect1("partial artifact retained", fsize(out) > 15360L);
    expect1("partial release confirmed", strstr(log, "result=confirmed") != NULL);
    remove(out);

    /* 7: write failure (/dev/full) - partial removed; release attempted+confirmed */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = c.owner_seq[1] = c.owner_seq[2] = 0x400007UL; c.n_owners = 3;
    c.overlay = 0x400009UL; c.composite_present = 1;
    rc = run_scenario(&c, "/dev/full", log, sizeof log);
    expect1("write-fail rc=8", rc == 8);
    expect1("write-fail stage=write", strstr(log, "stage=write") != NULL);
    expect1("write-fail release attempted+confirmed",
            strstr(log, "XO_RELEASE attempted=1 result=confirmed context=write-fail") != NULL);

    /* 8: release X-errors - recorded as attempted+x-error, exit 4 */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = c.owner_seq[1] = c.owner_seq[2] = 0x400007UL; c.n_owners = 3;
    c.overlay = 0x400009UL; c.composite_present = 1; c.release_xerr = 1;
    remove(out);
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("release-xerr rc=4", rc == 4);
    expect1("release-xerr attempted", c.release_calls == 1);
    expect1("release-xerr recorded", strstr(log, "XO_RELEASE attempted=1 result=x-error") != NULL);
    remove(out);

    /* 9: owner changes between initial and pre-get - recorded, proceeds */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = 0x400007UL; c.owner_seq[1] = c.owner_seq[2] = 0x4000bbUL; c.n_owners = 3;
    c.overlay = 0x400009UL; c.composite_present = 1;
    remove(out);
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("changed-before rc=0", rc == 0);
    expect1("changed-before note", strstr(log, "owner-changed-before-get") != NULL);
    expect1("changed-before release confirmed", strstr(log, "result=confirmed") != NULL);
    remove(out);

    /* 10: Composite extension absent - skip, zero requests */
    memset(&c, 0, sizeof c);
    c.composite_present = 0;
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("no-composite rc=10", rc == 10);
    expect1("no-composite zero GetOverlay", c.get_calls == 0);
    expect1("no-composite skip recorded", strstr(log, "skipped=no-composite-extension") != NULL);

    /* 11: every patch fails - rc 3, no artifact, release still confirmed */
    memset(&c, 0, sizeof c);
    c.owner_seq[0] = c.owner_seq[1] = c.owner_seq[2] = 0x400007UL; c.n_owners = 3;
    c.overlay = 0x400009UL; c.composite_present = 1; c.fail_mask = 0x1F;
    remove(out);
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("all-fail rc=3", rc == 3);
    expect1("all-fail no artifact", fsize(out) < 0);
    expect1("all-fail release confirmed", strstr(log, "result=confirmed") != NULL);

    /* 12: CM atom not interned on the fake server - skip, zero requests */
    memset(&c, 0, sizeof c);
    c.composite_present = 1; c.atom_absent = 1;
    rc = run_scenario(&c, out, log, sizeof log);
    expect1("atom-absent rc=10", rc == 10);
    expect1("atom-absent zero GetOverlay", c.get_calls == 0);
    expect1("atom-absent zero Release", c.release_calls == 0);
    expect1("atom-absent skip recorded", strstr(log, "skipped=no-cm-atom") != NULL);

    printf("XMAP-OVERLAY-SELFTEST: %d passed, %d failed\n", passes, fails);
    return fails ? 1 : 0;
}
#endif
