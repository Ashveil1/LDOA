/* xmap-pixels.c - TEST-ONLY bounded root-backing pixel sampler for the
 * LDOA GNOME session diagnostic (xmap workstream).
 *
 * PROVENANCE: original code written for this project's instrumentation;
 * no third-party code copied. Not shipped in any product artifact;
 * compiled only in CI and run only inside the emulator guest by the
 * instrumented test arm.
 *
 * WHAT IT READS: XGetImage(ZPixmap) on small patches of the ROOT window
 * of the display named by $DISPLAY. Protocol semantics (X.Org X11
 * protocol spec, GetImage request,
 * x.org/releases/current/doc/xproto/x11protocol.html#requests:GetImage):
 *   - the window must be VIEWABLE and the rectangle inside its edges
 *     (else BadMatch);
 *   - contents of regions obscured by NON-INFERIOR windows are the
 *     backing-store contents if any, else UNDEFINED (root has no
 *     non-inferior overlaps: children are inferiors);
 *   - contents of VISIBLE regions of inferiors of DIFFERENT DEPTH than
 *     the sampled window are UNDEFINED (a 32-depth ARGB stage window
 *     over a 24-depth root is exactly this case - record, never guess);
 *   - the pointer cursor image is never included.
 * VENDORED-SERVER FACT (termux-x11 lorie, this tree): the screen pixmap
 * is a CPU-mapped LorieBuffer (buffer.c mmap PROT_READ|PROT_WRITE
 * MAP_SHARED; AHB usage CPU_READ_OFTEN|CPU_WRITE_OFTEN), fb-based
 * GetImage reads that CPU mapping. On this emulator Present GPU offload
 * is disabled (renderer.cpp:538 incoherence finding), so no GPU-side
 * writes to that buffer are expected - but CPU/GPU coherence is NOT
 * guaranteed by anything here, and XSync flushes only the X protocol
 * stream. A black or failed sample is NOT proof guest content is
 * absent: undefined-inferior regions, incoherence, timing, or an
 * X error are all recorded separately below.
 *
 * READ-ONLY: issues only GetWindowAttributes / GetImage / XSync. No
 * windows created, no display writes, no mapped windows.
 *
 * BOUNDS: 5 patches (center + 4 corners) of at most 32x32 pixels each
 * (<=5120 pixels, P6 PPM <= ~16 KiB), 20s deadline, distinct exit
 * codes, every failure recorded separately.
 *
 * Exit codes: 0 all patches sampled; 2 display open failed; 3 every
 * GetImage failed; 4 X protocol error recorded; 5 unsupported visual
 * (not TrueColor/DirectColor or zero masks); 6 deadline hit; 7 some
 * patches failed (partial sample - the PPM still contains the ok
 * patches); 8 output write failed (partial file REMOVED, no success
 * is logged in that case).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <signal.h>
#include <time.h>
#include <unistd.h>

#ifndef XMAP_SELFTEST
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#else
/* Selftest mode: no X11 headers. Minimal stand-ins so the conversion
 * and PPM assembly compile and run against SYNTHETIC images only.
 * Host-fixture results say nothing about runtime behavior. */
typedef unsigned long XID;
typedef struct { unsigned long red_mask, green_mask, blue_mask; int class; } XMapVisual;
#define TrueColor 4
#define DirectColor 5
#endif

#define MAX_PATCH 32
#define NPATCHES 5
#define DEADLINE_SECS 20

#ifndef XMAP_SELFTEST
static long long now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static void on_alarm(int sig) { (void)sig; _exit(6); }
#endif /* !XMAP_SELFTEST (now_ms/on_alarm) */

/* mask -> shift/bits; zero mask reported as unusable, never guessed */
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

static unsigned char scale_channel(unsigned long pixel, unsigned long mask,
                                   int shift, int bits) {
    unsigned long v = (pixel & mask) >> shift;
    unsigned long maxv = (1UL << bits) - 1;
    if (!maxv) return 0;
    return (unsigned char)((v * 255UL + maxv / 2) / maxv);
}

static int convert_pixel(unsigned long pixel,
                         unsigned long rmask, unsigned long gmask, unsigned long bmask,
                         int rs, int rb, int gs, int gb, int bs, int bb,
                         unsigned char *r, unsigned char *g, unsigned char *b) {
    *r = scale_channel(pixel, rmask, rs, rb);
    *g = scale_channel(pixel, gmask, gs, gb);
    *b = scale_channel(pixel, bmask, bs, bb);
    return 0;
}

/* write one converted pixel row-major into the patch buffer */
struct patch { int x, y, size; long long t0, t1; int ok; const char *err; };

/* Write result is checked at EVERY step; any failure removes the
 * partial output (a truncated PPM must never survive as evidence) and
 * returns nonzero so main exits 8 with XP_ERROR stage=write.
 * Compiled in selftest mode too (pure stdio) so the failure path is
 * executed against /dev/full. */
static int write_ppm(const char *path, int ps, unsigned char *pixels, long long bytes,
                     const struct patch patches[NPATCHES]) {
    FILE *f = fopen(path, "wb");
    int i, bad = 0;
    if (!f) return -1;
    if (fprintf(f, "P6\n") < 0) bad = 1;
    if (!bad && fprintf(f, "# xmap-pixels sample; patches stacked vertically in order:\n") < 0) bad = 1;
    for (i = 0; !bad && i < NPATCHES; i++)
        if (fprintf(f, "# patch %d: x=%d y=%d size=%d ok=%d t0=%lld t1=%lld err=%s\n",
                i, patches[i].x, patches[i].y, patches[i].size, patches[i].ok,
                patches[i].t0, patches[i].t1, patches[i].err ? patches[i].err : "none") < 0) bad = 1;
    if (!bad && fprintf(f, "# black or failed patches are NOT proof of absent guest content\n") < 0) bad = 1;
    if (!bad && fprintf(f, "%d %d\n255\n", ps, ps * NPATCHES) < 0) bad = 1;
    if (!bad && fwrite(pixels, 1, (size_t)bytes, f) != (size_t)bytes) bad = 1;
    if (fclose(f) != 0) bad = 1;
    if (bad) { remove(path); return -2; }
    return 0;
}

#ifdef XMAP_SELFTEST
/* Synthetic XImage layout reader: ZPixmap, given bpp (16 or 32) and
 * byte order (0=LSBFirst, 1=MSBFirst). Mirrors what XGetPixel would
 * return for the same storage; host fixture only. */
static unsigned long synth_get_pixel(const unsigned char *buf, int bpp, int bo,
                                     int width, int x, int y) {
    const unsigned char *p;
    if (bpp == 32) {
        p = buf + ((size_t)y * width + x) * 4;
        if (bo == 0) return (unsigned long)p[0] | ((unsigned long)p[1] << 8) |
                            ((unsigned long)p[2] << 16) | ((unsigned long)p[3] << 24);
        return ((unsigned long)p[0] << 24) | ((unsigned long)p[1] << 16) |
               ((unsigned long)p[2] << 8) | (unsigned long)p[3];
    }
    p = buf + ((size_t)y * width + x) * 2;
    if (bo == 0) return (unsigned long)p[0] | ((unsigned long)p[1] << 8);
    return ((unsigned long)p[0] << 8) | (unsigned long)p[1];
}

static int fails = 0, passes = 0;
static void expect(const char *name, long got, long want) {
    if (got == want) { passes++; printf("SELFTEST-PASS %s\n", name); }
    else { fails++; printf("SELFTEST-FAIL %s: got %ld want %ld\n", name, got, want); }
}

int main(void) {
    unsigned char r, g, b;
    /* 24/32bpp TrueColor standard masks */
    expect("mask std r", mask_decode(0xFF0000UL, &(int){0}, &(int){0}), 0);
    {
        int rs, rb, gs, gb, bs, bb;
        mask_decode(0xFF0000UL, &rs, &rb); mask_decode(0x00FF00UL, &gs, &gb);
        mask_decode(0x0000FFUL, &bs, &bb);
        convert_pixel(0x00FF8000UL, 0xFF0000UL, 0x00FF00UL, 0x0000FFUL,
                      rs, rb, gs, gb, bs, bb, &r, &g, &b);
        expect("std red ch", r, 255); expect("std green ch", g, 128); expect("std blue ch", b, 0);
    }
    /* 16bpp 565 */
    {
        int rs, rb, gs, gb, bs, bb;
        mask_decode(0xF800UL, &rs, &rb); mask_decode(0x07E0UL, &gs, &gb);
        mask_decode(0x001FUL, &bs, &bb);
        convert_pixel(0xF800UL, 0xF800UL, 0x07E0UL, 0x001FUL,
                      rs, rb, gs, gb, bs, bb, &r, &g, &b);
        expect("565 red", r, 255); expect("565 green", g, 0);
        convert_pixel(0xFFFFUL, 0xF800UL, 0x07E0UL, 0x001FUL,
                      rs, rb, gs, gb, bs, bb, &r, &g, &b);
        expect("565 white g", g, 255);
    }
    /* BGR-ordered masks (inverted layout) */
    {
        int rs, rb, gs, gb, bs, bb;
        mask_decode(0x0000FFUL, &rs, &rb); mask_decode(0x00FF00UL, &gs, &gb);
        mask_decode(0xFF0000UL, &bs, &bb);
        convert_pixel(0x000000FFUL, 0x0000FFUL, 0x00FF00UL, 0xFF0000UL,
                      rs, rb, gs, gb, bs, bb, &r, &g, &b);
        expect("bgr red", r, 255); expect("bgr blue", b, 0);
    }
    /* malformed: zero mask and non-contiguous mask are hard errors */
    expect("zero mask rejected", mask_decode(0UL, &(int){0}, &(int){0}), -1);
    expect("noncontig rejected", mask_decode(0xF0F0UL, &(int){0}, &(int){0}), -2);
    /* synthetic 32bpp LSBFirst image, 4x4, known pixel at (1,2) = FF8040 (XRGB) */
    {
        unsigned char img[4 * 4 * 4];
        unsigned long px;
        memset(img, 0, sizeof img);
        img[(2 * 4 + 1) * 4 + 0] = 0x40; img[(2 * 4 + 1) * 4 + 1] = 0x80;
        img[(2 * 4 + 1) * 4 + 2] = 0xFF;
        px = synth_get_pixel(img, 32, 0, 4, 1, 2);
        expect("synth pixel value", (long)px, 0xFF8040L);
        {
            int rs, rb, gs, gb, bs, bb;
            mask_decode(0xFF0000UL, &rs, &rb); mask_decode(0x00FF00UL, &gs, &gb);
            mask_decode(0x0000FFUL, &bs, &bb);
            convert_pixel(px, 0xFF0000UL, 0x00FF00UL, 0x0000FFUL, rs, rb, gs, gb, bs, bb, &r, &g, &b);
            expect("synth conv r", r, 255); expect("synth conv g", g, 128); expect("synth conv b", b, 64);
        }
    }
    /* 16bpp MSBFirst */
    {
        unsigned char img[2 * 2 * 2];
        memset(img, 0, sizeof img);
        img[(1 * 2 + 1) * 2 + 0] = 0x07; img[(1 * 2 + 1) * 2 + 1] = 0xE0;
        expect("synth 16bpp msb", (long)synth_get_pixel(img, 16, 1, 2, 1, 1), 0x07E0L);
    }

    /* Output-write failure path: /dev/full fails every write with
     * ENOSPC. write_ppm must fail explicitly - success must never be
     * claimed for a file that was not fully written. */
    {
        struct patch p[NPATCHES];
        unsigned char px[8] = {1,2,3,4,5,6,7,8};
        FILE *vf; long vsz = -1;
        memset(p, 0, sizeof p);
        expect("write_ppm /dev/full explicit fail", write_ppm("/dev/full", 2, px, 8, p) != 0, 1);
        expect("write_ppm real file ok", write_ppm("/tmp/xmap-selftest-out.ppm", 2, px, 8, p), 0);
        vf = fopen("/tmp/xmap-selftest-out.ppm", "rb");
        if (vf) { fseek(vf, 0, SEEK_END); vsz = ftell(vf); fclose(vf); }
        remove("/tmp/xmap-selftest-out.ppm");
        expect("written file holds pixels", vsz > 8, 1);
    }
    printf("XMAP-PIXELS-SELFTEST: %d passed, %d failed\n", passes, fails);
    return fails ? 1 : 0;
}

#else /* runtime build */

static volatile int xerr_seen = 0;
static int xerr_code = 0, xerr_request = 0;
static int xerr_handler(Display *d, XErrorEvent *e) {
    (void)d;
    xerr_seen = 1; xerr_code = e->error_code; xerr_request = e->request_code;
    return 0;
}

int main(int argc, char **argv) {
    Display *dpy;
    Window root;
    XWindowAttributes attr;
    int ps, i, okcount = 0;
    unsigned char *ppm;
    long long ppm_bytes;
    struct patch patches[NPATCHES];
    int px[NPATCHES], py[NPATCHES];
    const char *names[NPATCHES] = { "center", "nw", "ne", "sw", "se" };
    int rs, rb, gs, gb, bs, bb;
    long long t_start = now_ms();

    signal(SIGALRM, on_alarm);
    alarm(DEADLINE_SECS);

    if (argc < 2) { fprintf(stderr, "usage: xmap-pixels <out.ppm>\n"); return 2; }

    dpy = XOpenDisplay(NULL);
    if (!dpy) { printf("XP_ERROR stage=open-display detail=XOpenDisplay-NULL\nXP_DONE patches-ok=0\n"); return 2; }
    XSetErrorHandler(xerr_handler);

    root = DefaultRootWindow(dpy);
    if (!XGetWindowAttributes(dpy, root, &attr)) {
        printf("XP_ERROR stage=get-attr detail=XGetWindowAttributes-failed\nXP_DONE patches-ok=0\n");
        XCloseDisplay(dpy);
        return 4;
    }
    printf("XP_META drawable=0x%lx root-geometry=%dx%d+%d+%d depth=%d visual-class=%d"
           " visualid=0x%lx masks=%lx/%lx/%lx bits-per-rgb=%d screen-byte-order=%d"
           " bitmap-unit=%d bitmap-pad=%d vendor=[%s] proto=%d.%d t=%lld\n",
           (unsigned long)root, attr.width, attr.height, attr.x, attr.y, attr.depth,
           attr.visual ? attr.visual->class : -1,
           attr.visual ? attr.visual->visualid : 0UL,
           attr.visual ? attr.visual->red_mask : 0UL,
           attr.visual ? attr.visual->green_mask : 0UL,
           attr.visual ? attr.visual->blue_mask : 0UL,
           attr.visual ? attr.visual->bits_per_rgb : -1,
           ImageByteOrder(dpy), BitmapUnit(dpy), BitmapPad(dpy),
           ServerVendor(dpy), ProtocolVersion(dpy), ProtocolRevision(dpy), now_ms());

    if (!attr.visual ||
        (attr.visual->class != TrueColor && attr.visual->class != DirectColor) ||
        mask_decode(attr.visual->red_mask, &rs, &rb) ||
        mask_decode(attr.visual->green_mask, &gs, &gb) ||
        mask_decode(attr.visual->blue_mask, &bs, &bb)) {
        printf("XP_ERROR stage=visual detail=unsupported-or-zero-masks\nXP_DONE patches-ok=0\n");
        XCloseDisplay(dpy);
        return 5;
    }

    ps = MAX_PATCH;
    if (attr.width / 4 < ps) ps = attr.width / 4;
    if (attr.height / 4 < ps) ps = attr.height / 4;
    if (ps < 1) ps = 1;
    px[0] = (attr.width - ps) / 2;  py[0] = (attr.height - ps) / 2;
    px[1] = 8 < attr.width - ps ? 8 : 0;  py[1] = 8 < attr.height - ps ? 8 : 0;
    px[2] = attr.width - ps - px[1];  py[2] = py[1];
    px[3] = px[1];  py[3] = attr.height - ps - py[1];
    px[4] = px[2];  py[4] = py[3];

    ppm = malloc((size_t)ps * ps * NPATCHES * 3);
    if (!ppm) { XCloseDisplay(dpy); return 3; }

    /* Flush + round-trip so prior requests reach the server before
     * sampling. This synchronizes the X PROTOCOL stream only; it says
     * nothing about CPU/GPU buffer coherence. */
    XSync(dpy, False);

    for (i = 0; i < NPATCHES; i++) {
        XImage *im;
        int x, y;
        patches[i].x = px[i]; patches[i].y = py[i]; patches[i].size = ps;
        patches[i].t0 = now_ms(); patches[i].ok = 0; patches[i].err = NULL;
        xerr_seen = 0;
        im = XGetImage(dpy, root, px[i], py[i], (unsigned)ps, (unsigned)ps,
                       AllPlanes, ZPixmap);
        XSync(dpy, False);
        patches[i].t1 = now_ms();
        if (!im) { patches[i].err = "getimage-null"; }
        else if (xerr_seen) { patches[i].err = "x-error"; }
        else {
            unsigned char *dst = ppm + (size_t)i * ps * ps * 3;
            for (y = 0; y < ps; y++)
                for (x = 0; x < ps; x++) {
                    unsigned long pv = XGetPixel(im, x, y);
                    unsigned char r, g, b;
                    convert_pixel(pv, attr.visual->red_mask, attr.visual->green_mask,
                                  attr.visual->blue_mask, rs, rb, gs, gb, bs, bb, &r, &g, &b);
                    dst[((size_t)y * ps + x) * 3 + 0] = r;
                    dst[((size_t)y * ps + x) * 3 + 1] = g;
                    dst[((size_t)y * ps + x) * 3 + 2] = b;
                }
            patches[i].ok = 1; okcount++;
        }
        if (im) XDestroyImage(im);
        printf("XP_PATCH name=%s x=%d y=%d size=%d ok=%d t0=%lld t1=%lld err=%s"
               "%s\n", names[i], px[i], py[i], ps, patches[i].ok,
               patches[i].t0, patches[i].t1,
               patches[i].err ? patches[i].err : "none",
               xerr_seen ? " (X error recorded)" : "");
    }

    ppm_bytes = (long long)ps * ps * NPATCHES * 3;
    if (okcount > 0) {
        int wr = write_ppm(argv[1], ps, ppm, ppm_bytes, patches);
        if (wr) {
            /* partial output already removed by write_ppm; nothing
             * usable remains - say so and exit distinctly. */
            printf("XP_ERROR stage=write detail=ppm-write-failed partial-removed rc=%d\n", wr);
            printf("XP_DONE patches-ok=%d elapsed-ms=%lld write=FAILED\n",
                   okcount, now_ms() - t_start);
            free(ppm);
            XCloseDisplay(dpy);
            return 8;
        }
        printf("XP_RESULT out=%s bytes=%lld patches-ok=%d note=[black-or-failed-patches-are-not-proof-of-absent-content]\n",
               argv[1], ppm_bytes, okcount);
    }
    printf("XP_DONE patches-ok=%d elapsed-ms=%lld\n", okcount, now_ms() - t_start);
    free(ppm);
    XCloseDisplay(dpy);
    if (xerr_seen) { printf("XP_XERROR code=%d request=%d\n", xerr_code, xerr_request); return 4; }
    if (okcount == 0) return 3;
    if (okcount < NPATCHES) return 7;
    return 0;
}
#endif
