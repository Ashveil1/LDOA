/* eglprobe - minimal guest-side EGL surfaceless renderer probe for the
 * VirGL spike (ci/virgl/README.md). No X, no GLX, no GNOME: it creates a
 * GLES2 context on EGL_PLATFORM_SURFACELESS_MESA and prints the renderer
 * strings. This is the same mechanism mesa's own CI uses to qualify
 * virpipe (GALLIUM_DRIVER=virpipe + surfaceless) against
 * virgl_test_server, and it is the ONLY probe whose renderer string can
 * prove the GUEST mesa driver: lorie's GLX is indirect-only on the
 * emulator (no usable DRI3 device), so glxinfo would report the X
 * SERVER's renderer (lorie epoxy/GLESv2), never the guest driver's.
 *
 * Runtime deps live in the guest rootfs (dlopen'ed): libEGL.so.1 and
 * libGLESv2.so.2 (noble mesa: libegl1 GLVND dispatch + libegl-mesa0
 * vendor + libgl1-mesa-dri megadriver, provisioned by
 * VirglRootfsProvisionTest). Build (CI lane; runner gate: noble only,
 * so glibc symbol versions never exceed the rootfs' 2.39):
 *   gcc -O2 -Wall -o eglprobe-x86_64 ci/virgl/eglprobe.c -ldl
 *   aarch64-linux-gnu-gcc -O2 -Wall -o eglprobe-aarch64 ci/virgl/eglprobe.c -ldl
 * The probe runs INSIDE proot: proot's loader (libproot_loader.so from
 * nativeLibraryDir) reads and maps the guest ELF, so the app-writable
 * rootfs path needs no kernel exec permission.
 *
 * Usage: eglprobe [--hold SECONDS] [--visual PPM_PATH] [--bench FRAMES]
 *   [--bench-latency]
 *   Prints EGL-VENDOR / EGL-VERSION / GL-VENDOR / GL-RENDERER /
 *   GL-VERSION, then EGLPROBE-OK. --hold keeps the EGL connection open
 *   for SECONDS after printing (lets the host observe the vtest worker
 *   forked for this client). Exit codes: 0 ok, 2 dlopen libEGL, 3
 *   eglGetPlatformDisplay, 4 eglInitialize, 5 eglBindAPI, 6 no config,
 *   7 context, 8 makeCurrent, 9 dlopen libGLESv2, 10 no surfaceless platform. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <unistd.h>
#include <time.h>

typedef int EGLint;
typedef unsigned int EGLenum;
typedef unsigned int EGLBoolean;
typedef void *EGLDisplay;
typedef void *EGLConfig;
typedef void *EGLContext;
typedef void *EGLSurface;

#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#define EGL_NONE 0x3038
#define EGL_SURFACE_TYPE 0x3033
#define EGL_PBUFFER_BIT 0x0001
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_OPENGL_ES2_BIT 0x0004
#define EGL_OPENGL_ES_API 0x30A0
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#define EGL_EXTENSIONS 0x3055
#define EGL_VENDOR 0x3053
#define EGL_VERSION 0x3054

#define GL_VENDOR 0x1F00
#define GL_RENDERER 0x1F01
#define GL_VERSION 0x1F02
#define GL_RGBA 0x1908
#define GL_RGB 0x1907
#define GL_UNSIGNED_BYTE 0x1401
#define GL_COLOR_BUFFER_BIT 0x4000
#define GL_FRAMEBUFFER 0x8D40
#define GL_RENDERBUFFER 0x8D41
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_RGBA4 0x8056
#define GL_RGB5_A1 0x8057
#define GL_RGBA8_OES 0x8058
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#define GL_ARRAY_BUFFER 0x8892
#define GL_STATIC_DRAW 0x88E4
#define GL_TRIANGLES 0x0004
#define GL_FLOAT 0x1406
#define GL_FALSE 0
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82
#define GL_INFO_LOG_LENGTH 0x8B84

typedef EGLDisplay (*PFN_eglGetPlatformDisplay)(EGLenum, void *, const EGLint *);
typedef EGLBoolean (*PFN_eglInitialize)(EGLDisplay, EGLint *, EGLint *);
typedef EGLBoolean (*PFN_eglBindAPI)(EGLenum);
typedef EGLBoolean (*PFN_eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLContext (*PFN_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
typedef EGLBoolean (*PFN_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef const char *(*PFN_eglQueryString)(EGLDisplay, EGLint);
typedef EGLint (*PFN_eglGetError)(void);
typedef EGLBoolean (*PFN_eglDestroyContext)(EGLDisplay, EGLContext);
typedef EGLBoolean (*PFN_eglTerminate)(EGLDisplay);
typedef const unsigned char *(*PFN_glGetString)(EGLenum);
typedef void (*PFN_glGenFramebuffers)(int, unsigned int *);
typedef void (*PFN_glBindFramebuffer)(EGLenum, unsigned int);
typedef void (*PFN_glGenRenderbuffers)(int, unsigned int *);
typedef void (*PFN_glBindRenderbuffer)(EGLenum, unsigned int);
typedef void (*PFN_glRenderbufferStorage)(EGLenum, EGLenum, int, int);
typedef void (*PFN_glFramebufferRenderbuffer)(EGLenum, EGLenum, EGLenum, unsigned int);
typedef EGLenum (*PFN_glCheckFramebufferStatus)(EGLenum);
typedef void (*PFN_glViewport)(int, int, int, int);
typedef void (*PFN_glClearColor)(float, float, float, float);
typedef void (*PFN_glClear)(unsigned int);
typedef unsigned int (*PFN_glCreateShader)(EGLenum);
typedef void (*PFN_glShaderSource)(unsigned int, int, const char **, const int *);
typedef void (*PFN_glCompileShader)(unsigned int);
typedef void (*PFN_glGetShaderiv)(unsigned int, EGLenum, int *);
typedef void (*PFN_glGetShaderInfoLog)(unsigned int, int, int *, char *);
typedef unsigned int (*PFN_glCreateProgram)(void);
typedef void (*PFN_glAttachShader)(unsigned int, unsigned int);
typedef void (*PFN_glLinkProgram)(unsigned int);
typedef void (*PFN_glGetProgramiv)(unsigned int, EGLenum, int *);
typedef void (*PFN_glGetProgramInfoLog)(unsigned int, int, int *, char *);
typedef void (*PFN_glUseProgram)(unsigned int);
typedef void (*PFN_glGenBuffers)(int, unsigned int *);
typedef void (*PFN_glBindBuffer)(EGLenum, unsigned int);
typedef void (*PFN_glBufferData)(EGLenum, long, const void *, EGLenum);
typedef void (*PFN_glVertexAttribPointer)(unsigned int, int, EGLenum, unsigned char, int, const void *);
typedef void (*PFN_glEnableVertexAttribArray)(unsigned int);
typedef void (*PFN_glDrawArrays)(EGLenum, int, int);
typedef void (*PFN_glReadPixels)(int, int, int, int, EGLenum, EGLenum, void *);
typedef void (*PFN_glFinish)(void);
typedef int (*PFN_glGetAttribLocation)(unsigned int, const char *);
typedef EGLenum (*PFN_glGetError)(void);


/* ================= Visual-proof scene (P1) =================
 * SOURCE-DEFINED expectations: the scene and every check below are
 * defined HERE, never blessed from a run. 256x256 RGBA, coordinates
 * top-down (row 0 = top, PPM order; GL VBO y-flips once at build).
 *   - 4 quadrant solid fills (exact 0/255 extremes, dither-immune):
 *       top-left red, top-right green, bottom-left blue, bottom-right white
 *   - 8x8 checkerboard 64px wide centered (x 96..159, y 96..159), 8px
 *     cells, top-left cell BLACK, exact black/white
 *   - gradient bar full width (y 232..247), black at left -> white at
 *     right (vertex-color interpolation; endpoints checked tight,
 *     midpoint checked loose: interpolation/dither is driver-legit)
 * Region checks sample patch CENTERS only (no edge pixels). All-solid
 * colors use 0/255 extremes so NO dithering tolerance is needed. */
#define SCN_W 256
#define SCN_H 256
#define SCN_CHECK_X0 96
#define SCN_CHECK_Y0 96
#define SCN_CHECK_CELL 8
#define SCN_CHECK_N 8
#define SCN_GRAD_Y0 232
#define SCN_GRAD_Y1 248

/* Expected pixel of the scene at (x, y), top-down. Shared by the
 * software expectation generator (local tests) and the region checker,
 * so the expected image is defined once. */
static void scene_expected(int x, int y, unsigned char *r, unsigned char *g, unsigned char *b) {
    if (y >= SCN_GRAD_Y0 && y < SCN_GRAD_Y1) {
        int v = (x * 255 + (SCN_W - 1) / 2) / (SCN_W - 1);
        *r = *g = *b = (unsigned char) v;
        return;
    }
    if (x >= SCN_CHECK_X0 && x < SCN_CHECK_X0 + SCN_CHECK_N * SCN_CHECK_CELL &&
        y >= SCN_CHECK_Y0 && y < SCN_CHECK_Y0 + SCN_CHECK_N * SCN_CHECK_CELL) {
        int cx = (x - SCN_CHECK_X0) / SCN_CHECK_CELL, cy = (y - SCN_CHECK_Y0) / SCN_CHECK_CELL;
        unsigned char v = ((cx + cy) % 2 == 0) ? 0 : 255;
        *r = *g = *b = v;
        return;
    }
    if (x < SCN_W / 2 && y < SCN_H / 2) { *r = 255; *g = 0; *b = 0; return; }
    if (x >= SCN_W / 2 && y < SCN_H / 2) { *r = 0; *g = 255; *b = 0; return; }
    if (x < SCN_W / 2 && y >= SCN_H / 2) { *r = 0; *g = 0; *b = 255; return; }
    *r = *g = *b = 255;
}

/* FNV-1a 64 over the RGBA frame: a change DETECTOR, not correctness
 * proof. Correctness is the region checks below + the exported image. */
static unsigned long long frame_fnv64(const unsigned char *px, int w, int h) {
    unsigned long long h64 = 14695981039346656037ULL; /* FNV-1a 64 offset basis */
    long n = (long) w * h * 4, i;
    for (i = 0; i < n; i++) { h64 ^= px[i]; h64 *= 1099511628211ULL; }
    return h64;
}

/* One named region expectation: exact-match or bounded channel range. */
typedef struct {
    const char *name;
    int x, y;                 /* sample point (patch center) */
    unsigned char er, eg, eb; /* expected */
    int tol;                  /* per-channel tolerance */
} RegionCheck;

/* Check an actual RGBA frame (top-down rows, as read back with the
 * y-flip applied by the caller) against the source-defined scene.
 * Returns number of FAILED checks; prints one line per check. */
static int check_frame(const unsigned char *px, int w, int h, int *out_checks) {
    RegionCheck checks[16];
    int nc = 0, fails = 0, i;
    /* quadrant patch centers, far from overlays */
    checks[nc++] = (RegionCheck) { "quad-red",    32, 32, 255, 0, 0, 0 };
    checks[nc++] = (RegionCheck) { "quad-green", 224, 32, 0, 255, 0, 0 };
    checks[nc++] = (RegionCheck) { "quad-blue",   32, 224, 0, 0, 255, 0 };
    checks[nc++] = (RegionCheck) { "quad-white", 224, 224, 255, 255, 255, 0 };
    /* checker cell centers: (0,0)=black, (1,0)=white, (0,1)=white, (7,7)=BLACK
     * ((7+7)%2==0; caught by the local visual-core test, not by review) */
    checks[nc++] = (RegionCheck) { "check-00-black", SCN_CHECK_X0 + 4, SCN_CHECK_Y0 + 4, 0, 0, 0, 0 };
    checks[nc++] = (RegionCheck) { "check-10-white", SCN_CHECK_X0 + 12, SCN_CHECK_Y0 + 4, 255, 255, 255, 0 };
    checks[nc++] = (RegionCheck) { "check-01-white", SCN_CHECK_X0 + 4, SCN_CHECK_Y0 + 12, 255, 255, 255, 0 };
    checks[nc++] = (RegionCheck) { "check-77-black", SCN_CHECK_X0 + 7 * 8 + 4, SCN_CHECK_Y0 + 7 * 8 + 4, 0, 0, 0, 0 };
    /* gradient: tight endpoints (generator: round(x*255/255)), loose
     * midpoint (driver-legit interpolation/dither variance) */
    checks[nc++] = (RegionCheck) { "grad-left",  4, (SCN_GRAD_Y0 + SCN_GRAD_Y1) / 2, 4, 4, 4, 6 };
    checks[nc++] = (RegionCheck) { "grad-right", 251, (SCN_GRAD_Y0 + SCN_GRAD_Y1) / 2, 251, 251, 251, 6 };
    checks[nc++] = (RegionCheck) { "grad-mid", 128, (SCN_GRAD_Y0 + SCN_GRAD_Y1) / 2, 128, 128, 128, 24 };
    for (i = 0; i < nc; i++) {
        RegionCheck *c = &checks[i];
        const unsigned char *p = px + ((long) c->y * w + c->x) * 4;
        unsigned char er, eg, eb;
        int dr, dg, db, ok;
        scene_expected(c->x, c->y, &er, &eg, &eb); /* cross-check table vs generator */
        if (er != c->er || eg != c->eg || eb != c->eb) {
            printf("EGLPROBE-VISUAL-REGION %s INTERNAL-MISMATCH table=(%d,%d,%d) generator=(%d,%d,%d)\n",
                   c->name, c->er, c->eg, c->eb, er, eg, eb);
            fails++;
            continue;
        }
        dr = p[0] > c->er ? p[0] - c->er : c->er - p[0];
        dg = p[1] > c->eg ? p[1] - c->eg : c->eg - p[1];
        db = p[2] > c->eb ? p[2] - c->eb : c->eb - p[2];
        ok = dr <= c->tol && dg <= c->tol && db <= c->tol;
        if (!ok) fails++;
        printf("EGLPROBE-VISUAL-REGION %s %s actual=(%d,%d,%d) expected=(%d,%d,%d) tol=%d\n",
               c->name, ok ? "OK" : "FAIL", p[0], p[1], p[2], c->er, c->eg, c->eb, c->tol);
    }
    *out_checks = nc;
    return fails;
}

/* P6 PPM (RGB, alpha dropped), rows top-down. */
static int write_ppm(const char *path, const unsigned char *px, int w, int h) {
    FILE *f = fopen(path, "wb");
    long i, n = (long) w * h;
    int bad = 0;
    if (!f) return -1;
    if (fprintf(f, "P6\n%d %d\n255\n", w, h) < 0) bad = 1;
    for (i = 0; i < n && !bad; i++) {
        if (fputc(px[i * 4], f) == EOF || fputc(px[i * 4 + 1], f) == EOF ||
            fputc(px[i * 4 + 2], f) == EOF) bad = 1;
    }
    if (fflush(f) != 0) bad = 1;
    if (fclose(f) != 0) bad = 1; /* flush/close surface deferred write errors */
    return bad ? -1 : 0;
}

/* Flushed stage breadcrumbs on stderr: proot drains stderr concurrently
 * and the partial capture survives runGuest's timeout kill, so the last
 * breadcrumb localizes a hang instead of a bare "thread alive". */
#define STAGE(s) do { fprintf(stderr, "EGLPROBE-STAGE: %s\n", s); fflush(stderr); } while (0)

static int fail(int stage, const char *what, PFN_eglGetError getError) {
    fprintf(stderr, "EGLPROBE-ERROR stage=%d %s", stage, what);
    if (getError) fprintf(stderr, " eglError=0x%x", getError());
    fprintf(stderr, "\n");
    fflush(stderr);
    return stage;
}


/* ================= GL driver for visual/bench (P1/P2) ================= */
typedef struct {
    PFN_glGenFramebuffers GenFramebuffers;
    PFN_glBindFramebuffer BindFramebuffer;
    PFN_glGenRenderbuffers GenRenderbuffers;
    PFN_glBindRenderbuffer BindRenderbuffer;
    PFN_glRenderbufferStorage RenderbufferStorage;
    PFN_glFramebufferRenderbuffer FramebufferRenderbuffer;
    PFN_glCheckFramebufferStatus CheckFramebufferStatus;
    PFN_glViewport Viewport;
    PFN_glClearColor ClearColor;
    PFN_glClear Clear;
    PFN_glCreateShader CreateShader;
    PFN_glShaderSource ShaderSource;
    PFN_glCompileShader CompileShader;
    PFN_glGetShaderiv GetShaderiv;
    PFN_glGetShaderInfoLog GetShaderInfoLog;
    PFN_glCreateProgram CreateProgram;
    PFN_glAttachShader AttachShader;
    PFN_glLinkProgram LinkProgram;
    PFN_glGetProgramiv GetProgramiv;
    PFN_glGetProgramInfoLog GetProgramInfoLog;
    PFN_glUseProgram UseProgram;
    PFN_glGenBuffers GenBuffers;
    PFN_glBindBuffer BindBuffer;
    PFN_glBufferData BufferData;
    PFN_glVertexAttribPointer VertexAttribPointer;
    PFN_glEnableVertexAttribArray EnableVertexAttribArray;
    PFN_glDrawArrays DrawArrays;
    PFN_glReadPixels ReadPixels;
    PFN_glFinish Finish;
    PFN_glGetError GetError;
} GlFns;

static void *glsym(void *gl, const char *name) {
    void *f = dlsym(gl, name);
    if (!f) fprintf(stderr, "EGLPROBE-ERROR missing GL symbol %s\n", name);
    return f;
}

static int load_gl_fns(void *gl, GlFns *G) {
    G->GenFramebuffers = (PFN_glGenFramebuffers) glsym(gl, "glGenFramebuffers");
    G->BindFramebuffer = (PFN_glBindFramebuffer) glsym(gl, "glBindFramebuffer");
    G->GenRenderbuffers = (PFN_glGenRenderbuffers) glsym(gl, "glGenRenderbuffers");
    G->BindRenderbuffer = (PFN_glBindRenderbuffer) glsym(gl, "glBindRenderbuffer");
    G->RenderbufferStorage = (PFN_glRenderbufferStorage) glsym(gl, "glRenderbufferStorage");
    G->FramebufferRenderbuffer = (PFN_glFramebufferRenderbuffer) glsym(gl, "glFramebufferRenderbuffer");
    G->CheckFramebufferStatus = (PFN_glCheckFramebufferStatus) glsym(gl, "glCheckFramebufferStatus");
    G->Viewport = (PFN_glViewport) glsym(gl, "glViewport");
    G->ClearColor = (PFN_glClearColor) glsym(gl, "glClearColor");
    G->Clear = (PFN_glClear) glsym(gl, "glClear");
    G->CreateShader = (PFN_glCreateShader) glsym(gl, "glCreateShader");
    G->ShaderSource = (PFN_glShaderSource) glsym(gl, "glShaderSource");
    G->CompileShader = (PFN_glCompileShader) glsym(gl, "glCompileShader");
    G->GetShaderiv = (PFN_glGetShaderiv) glsym(gl, "glGetShaderiv");
    G->GetShaderInfoLog = (PFN_glGetShaderInfoLog) glsym(gl, "glGetShaderInfoLog");
    G->CreateProgram = (PFN_glCreateProgram) glsym(gl, "glCreateProgram");
    G->AttachShader = (PFN_glAttachShader) glsym(gl, "glAttachShader");
    G->LinkProgram = (PFN_glLinkProgram) glsym(gl, "glLinkProgram");
    G->GetProgramiv = (PFN_glGetProgramiv) glsym(gl, "glGetProgramiv");
    G->GetProgramInfoLog = (PFN_glGetProgramInfoLog) glsym(gl, "glGetProgramInfoLog");
    G->UseProgram = (PFN_glUseProgram) glsym(gl, "glUseProgram");
    G->GenBuffers = (PFN_glGenBuffers) glsym(gl, "glGenBuffers");
    G->BindBuffer = (PFN_glBindBuffer) glsym(gl, "glBindBuffer");
    G->BufferData = (PFN_glBufferData) glsym(gl, "glBufferData");
    G->VertexAttribPointer = (PFN_glVertexAttribPointer) glsym(gl, "glVertexAttribPointer");
    G->EnableVertexAttribArray = (PFN_glEnableVertexAttribArray) glsym(gl, "glEnableVertexAttribArray");
    G->DrawArrays = (PFN_glDrawArrays) glsym(gl, "glDrawArrays");
    G->ReadPixels = (PFN_glReadPixels) glsym(gl, "glReadPixels");
    G->Finish = (PFN_glFinish) glsym(gl, "glFinish");
    G->GetError = (PFN_glGetError) glsym(gl, "glGetError");
    return !G->GenFramebuffers || !G->BindFramebuffer || !G->GenRenderbuffers ||
           !G->BindRenderbuffer || !G->RenderbufferStorage || !G->FramebufferRenderbuffer ||
           !G->CheckFramebufferStatus || !G->Viewport || !G->ClearColor || !G->Clear ||
           !G->CreateShader || !G->ShaderSource || !G->CompileShader || !G->GetShaderiv ||
           !G->GetShaderInfoLog || !G->CreateProgram || !G->AttachShader || !G->LinkProgram ||
           !G->GetProgramiv || !G->GetProgramInfoLog || !G->UseProgram || !G->GenBuffers ||
           !G->BindBuffer || !G->BufferData || !G->VertexAttribPointer ||
           !G->EnableVertexAttribArray || !G->DrawArrays || !G->ReadPixels || !G->Finish ||
           !G->GetError;
}

static const char *VS_SRC =
    "attribute vec2 aPos; attribute vec3 aColor; varying vec3 vColor;\n"
    "void main() { vColor = aColor; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
static const char *FS_SRC =
    "precision mediump float; varying vec3 vColor;\n"
    "void main() { gl_FragColor = vec4(vColor, 1.0); }\n";

/* One solid or gradient quad appended to the vertex buffer (5 floats per
 * vertex: clip x,y + rgb; 6 vertices). Coordinates are top-down scene
 * pixels; y is flipped here, once. */
static int quad(float *v, int n, int x0, int y0, int x1, int y1,
                float r0, float g0, float b0, float r1, float g1, float b1) {
    float ax = (float) x0 / SCN_W * 2.0f - 1.0f, bx = (float) x1 / SCN_W * 2.0f - 1.0f;
    float ay = 1.0f - (float) y0 / SCN_H * 2.0f, by = 1.0f - (float) y1 / SCN_H * 2.0f;
    /* tri 1: (ax,ay,c0) (bx,ay,c0->c1 horizontal) (ax,by) ; tri 2: (bx,ay) (bx,by,c1) (ax,by) */
    float verts[30] = {
        ax, ay, r0, g0, b0,  bx, ay, r1, g1, b1,  ax, by, r0, g0, b0,
        bx, ay, r1, g1, b1,  bx, by, r1, g1, b1,  ax, by, r0, g0, b0 };
    int i;
    if (n + 30 > 6 * 5 * 128) return -1;
    for (i = 0; i < 30; i++) v[n + i] = verts[i];
    return n + 30;
}

/* Build the whole scene VBO from the SAME geometric definitions the
 * checker uses. Returns vertex count (floats/5), or -1. */
static int build_scene_vbo(float *v) {
    int n = 0, cx, cy;
    n = quad(v, n, 0, 0, 128, 128, 1, 0, 0, 1, 0, 0);            /* red TL */
    n = quad(v, n, 128, 0, 256, 128, 0, 1, 0, 0, 1, 0);          /* green TR */
    n = quad(v, n, 0, 128, 128, 256, 0, 0, 1, 0, 0, 1);          /* blue BL */
    n = quad(v, n, 128, 128, 256, 256, 1, 1, 1, 1, 1, 1);        /* white BR */
    for (cy = 0; cy < SCN_CHECK_N; cy++)
        for (cx = 0; cx < SCN_CHECK_N; cx++) {
            float c = ((cx + cy) % 2 == 0) ? 0.0f : 1.0f;
            n = quad(v, n, SCN_CHECK_X0 + cx * SCN_CHECK_CELL, SCN_CHECK_Y0 + cy * SCN_CHECK_CELL,
                     SCN_CHECK_X0 + (cx + 1) * SCN_CHECK_CELL, SCN_CHECK_Y0 + (cy + 1) * SCN_CHECK_CELL,
                     c, c, c, c, c, c);
        }
    n = quad(v, n, 0, SCN_GRAD_Y0, SCN_W, SCN_GRAD_Y1, 0, 0, 0, 1, 1, 1); /* black->white */
    if (n < 0) return -1;
    return n / 5;
}

static unsigned int build_program(GlFns *G) {
    char log[512];
    int ok = 0, len = 0;
    unsigned int vs = G->CreateShader(GL_VERTEX_SHADER);
    unsigned int fs = G->CreateShader(GL_FRAGMENT_SHADER);
    unsigned int prog;
    G->ShaderSource(vs, 1, &VS_SRC, NULL); G->CompileShader(vs);
    G->GetShaderiv(vs, GL_COMPILE_STATUS, &ok);
    if (!ok) { G->GetShaderInfoLog(vs, sizeof(log), &len, log); fprintf(stderr, "EGLPROBE-ERROR vs compile: %s\n", log); return 0; }
    G->ShaderSource(fs, 1, &FS_SRC, NULL); G->CompileShader(fs);
    G->GetShaderiv(fs, GL_COMPILE_STATUS, &ok);
    if (!ok) { G->GetShaderInfoLog(fs, sizeof(log), &len, log); fprintf(stderr, "EGLPROBE-ERROR fs compile: %s\n", log); return 0; }
    prog = G->CreateProgram();
    G->AttachShader(prog, vs); G->AttachShader(prog, fs);
    G->LinkProgram(prog);
    G->GetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) { G->GetProgramInfoLog(prog, sizeof(log), &len, log); fprintf(stderr, "EGLPROBE-ERROR link: %s\n", log); return 0; }
    return prog;
}

/* Render the scene into the FBO. Caller bound the FBO + program + VBO. */
static void draw_scene(GlFns *G, int vtxCount) {
    G->Viewport(0, 0, SCN_W, SCN_H);
    G->ClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    G->Clear(GL_COLOR_BUFFER_BIT);
    G->DrawArrays(GL_TRIANGLES, 0, vtxCount);
}

int main(int argc, char **argv) {
    void *egl = dlopen("libEGL.so.1", RTLD_NOW | RTLD_GLOBAL);
    if (!egl) egl = dlopen("libEGL.so", RTLD_NOW | RTLD_GLOBAL);
    if (!egl) { fprintf(stderr, "EGLPROBE-ERROR stage=2 dlopen(libEGL): %s\n", dlerror()); return 2; }

    PFN_eglGetPlatformDisplay getPlatformDisplay = (PFN_eglGetPlatformDisplay) dlsym(egl, "eglGetPlatformDisplay");
    PFN_eglInitialize initialize = (PFN_eglInitialize) dlsym(egl, "eglInitialize");
    PFN_eglBindAPI bindAPI = (PFN_eglBindAPI) dlsym(egl, "eglBindAPI");
    PFN_eglChooseConfig chooseConfig = (PFN_eglChooseConfig) dlsym(egl, "eglChooseConfig");
    PFN_eglCreateContext createContext = (PFN_eglCreateContext) dlsym(egl, "eglCreateContext");
    PFN_eglMakeCurrent makeCurrent = (PFN_eglMakeCurrent) dlsym(egl, "eglMakeCurrent");
    PFN_eglQueryString queryString = (PFN_eglQueryString) dlsym(egl, "eglQueryString");
    PFN_eglGetError getError = (PFN_eglGetError) dlsym(egl, "eglGetError");
    PFN_eglDestroyContext destroyContext = (PFN_eglDestroyContext) dlsym(egl, "eglDestroyContext");
    PFN_eglTerminate terminate = (PFN_eglTerminate) dlsym(egl, "eglTerminate");
    if (!getPlatformDisplay || !initialize || !bindAPI || !chooseConfig || !createContext ||
        !makeCurrent || !queryString || !getError || !destroyContext || !terminate) {
        fprintf(stderr, "EGLPROBE-ERROR stage=2 missing egl symbol in libEGL\n");
        return 2;
    }
    STAGE("libEGL-ready");

    /* Capability check BEFORE relying on the platform: the surfaceless
     *  platform is a Mesa extension (EGL_MESA_platform_surfaceless), not
     *  core EGL - verify it in the client extension string instead of
     *  assuming. */
    const char *clientExts = queryString(NULL, EGL_EXTENSIONS);
    printf("EGL-CLIENT-EXTENSIONS: %s\n", clientExts ? clientExts : "(null)");
    fflush(stdout);
    if (!clientExts || !strstr(clientExts, "EGL_MESA_platform_surfaceless")) {
        fprintf(stderr, "EGLPROBE-ERROR stage=10 EGL_MESA_platform_surfaceless NOT in client extensions\n");
        return 10;
    }
    STAGE("client-exts-ok");
    EGLDisplay dpy = getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, NULL, NULL);
    if (!dpy) return fail(3, "eglGetPlatformDisplay(SURFACELESS)", getError);
    STAGE("display-ok");
    EGLint maj = 0, min = 0;
    if (!initialize(dpy, &maj, &min)) return fail(4, "eglInitialize", getError);
    STAGE("init-ok");
    printf("EGL-VENDOR: %s\n", queryString(dpy, EGL_VENDOR));
    printf("EGL-VERSION: %s\n", queryString(dpy, EGL_VERSION));
    fflush(stdout);

    if (!bindAPI(EGL_OPENGL_ES_API)) return fail(5, "eglBindAPI(GLES)", getError);
    STAGE("bind-ok");
    EGLint cfgAttrs[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLConfig cfg;
    EGLint n = 0;
    if (!chooseConfig(dpy, cfgAttrs, &cfg, 1, &n) || n < 1) return fail(6, "eglChooseConfig", getError);
    STAGE("config-ok");
    EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = createContext(dpy, cfg, NULL, ctxAttrs);
    if (!ctx) return fail(7, "eglCreateContext", getError);
    STAGE("context-ok");
    if (!makeCurrent(dpy, NULL, NULL, ctx)) return fail(8, "eglMakeCurrent(surfaceless)", getError);
    STAGE("current-ok");

    void *gl = dlopen("libGLESv2.so.2", RTLD_NOW);
    if (!gl) gl = dlopen("libGLESv2.so", RTLD_NOW);
    if (!gl) { fprintf(stderr, "EGLPROBE-ERROR stage=9 dlopen(libGLESv2): %s\n", dlerror()); return 9; }
    PFN_glGetString glGetString = (PFN_glGetString) dlsym(gl, "glGetString");
    if (!glGetString) { fprintf(stderr, "EGLPROBE-ERROR stage=9 missing glGetString\n"); return 9; }
    STAGE("libGLES-ready");
    const unsigned char *vendor = glGetString(GL_VENDOR);
    const unsigned char *renderer = glGetString(GL_RENDERER);
    const unsigned char *version = glGetString(GL_VERSION);
    printf("GL-VENDOR: %s\n", vendor ? (const char *) vendor : "(null)");
    printf("GL-RENDERER: %s\n", renderer ? (const char *) renderer : "(null)");
    printf("GL-VERSION: %s\n", version ? (const char *) version : "(null)");
    printf("EGLPROBE-STRINGS-DONE\n");
    fflush(stdout);

    const char *visualPath = NULL;
    int benchFrames = 0, benchLatency = 0, holdSecs = 0, ai;
    for (ai = 1; ai < argc; ai++) {
        if (strcmp(argv[ai], "--hold") == 0 && ai + 1 < argc) holdSecs = atoi(argv[++ai]);
        else if (strcmp(argv[ai], "--visual") == 0 && ai + 1 < argc) visualPath = argv[++ai];
        else if (strcmp(argv[ai], "--bench") == 0 && ai + 1 < argc) benchFrames = atoi(argv[++ai]);
        else if (strcmp(argv[ai], "--bench-latency") == 0) benchLatency = 1;
    }

    if (visualPath || benchFrames > 0) {
        /* P1/P2: offscreen FBO rendering with source-defined visual
         * checks, exported image, and glFinish-synchronized timing.
         * What wallMs measures: GPU-COMPLETED offscreen FBO throughput
         * (or, with --bench-latency, per-frame round-trip latency) for
         * one fixed workload - NOT display-path FPS, NOT GNOME
         * smoothness, NOT a user-visible performance verdict. */
        GlFns G;
        unsigned int fbo = 0, rbo = 0, prog = 0, vbo = 0;
        float *vtx = NULL;
        unsigned char *px = NULL;
        int vtxCount = 0, nchecks = 0, fails = 0, frame;
        struct timespec t0, t1;
        if (load_gl_fns(gl, &G)) { fprintf(stderr, "EGLPROBE-ERROR stage=13 GL function load\n"); return 13; }
        vtx = malloc(sizeof(float) * 6 * 5 * 128);
        px = malloc((size_t) SCN_W * SCN_H * 4);
        if (!vtx || !px) { fprintf(stderr, "EGLPROBE-ERROR stage=13 alloc\n"); return 13; }
        vtxCount = build_scene_vbo(vtx);
        if (vtxCount <= 0) { fprintf(stderr, "EGLPROBE-ERROR stage=13 scene build\n"); return 13; }

        G.GenFramebuffers(1, &fbo);
        G.BindFramebuffer(GL_FRAMEBUFFER, fbo);
        G.GenRenderbuffers(1, &rbo);
        G.BindRenderbuffer(GL_RENDERBUFFER, rbo);
        G.RenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8_OES, SCN_W, SCN_H);
        G.FramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rbo);
        if (G.CheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            fprintf(stderr, "EGLPROBE-ERROR stage=13 FBO incomplete (0x%x)\n", G.GetError());
            return 13;
        }
        prog = build_program(&G);
        if (!prog) return 13;
        G.UseProgram(prog);
        G.GenBuffers(1, &vbo);
        G.BindBuffer(GL_ARRAY_BUFFER, vbo);
        G.BufferData(GL_ARRAY_BUFFER, (long) vtxCount * 5 * (long) sizeof(float), vtx, GL_STATIC_DRAW);
        /* Explicit attribute locations via BindAttribLocation-style
         * binding BEFORE link would be ideal; GLES2 allows querying
         * after link - query and use what the linker assigned. */
        {
            int locPos = -1, locColor = -1;
            PFN_glGetAttribLocation getLoc =
                (PFN_glGetAttribLocation) dlsym(gl, "glGetAttribLocation");
            if (!getLoc) { fprintf(stderr, "EGLPROBE-ERROR stage=13 no glGetAttribLocation\n"); return 13; }
            locPos = getLoc(prog, "aPos");
            locColor = getLoc(prog, "aColor");
            if (locPos < 0 || locColor < 0) { fprintf(stderr, "EGLPROBE-ERROR stage=13 attrib lookup\n"); return 13; }
            G.VertexAttribPointer((unsigned int) locPos, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (const void *) 0);
            G.EnableVertexAttribArray((unsigned int) locPos);
            G.VertexAttribPointer((unsigned int) locColor, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (const void *) (2 * sizeof(float)));
            G.EnableVertexAttribArray((unsigned int) locColor);
        }
        STAGE("visual-setup-ok");

        if (visualPath) {
            int y;
            draw_scene(&G, vtxCount);
            G.Finish(); /* GPU completion BEFORE readback and timing */
            G.ReadPixels(0, 0, SCN_W, SCN_H, GL_RGBA, GL_UNSIGNED_BYTE, px);
            if (G.GetError() != 0) { fprintf(stderr, "EGLPROBE-ERROR stage=13 readPixels\n"); return 13; }
            /* readPixels is bottom-up; flip to top-down for PPM/checks */
            for (y = 0; y < SCN_H / 2; y++) {
                unsigned char *a = px + (long) y * SCN_W * 4;
                unsigned char *b = px + (long) (SCN_H - 1 - y) * SCN_W * 4;
                int x;
                for (x = 0; x < SCN_W * 4; x++) { unsigned char t = a[x]; a[x] = b[x]; b[x] = t; }
            }
            printf("EGLPROBE-FRAME-FNV64: %016llx\n", frame_fnv64(px, SCN_W, SCN_H));
            fails = check_frame(px, SCN_W, SCN_H, &nchecks);
            printf("EGLPROBE-VISUAL-RESULT: %s (%d/%d regions OK)\n",
                   fails == 0 ? "OK" : "FAIL", nchecks - fails, nchecks);
            fflush(stdout);
            if (write_ppm(visualPath, px, SCN_W, SCN_H) != 0) {
                fprintf(stderr, "EGLPROBE-ERROR stage=12 PPM write failed\n");
                return 12; /* evidence capture break - distinct from a render failure */
            }
            printf("EGLPROBE-VISUAL-PPM: written\n");
            fflush(stdout);
            if (fails) return 11;
        }

        if (benchFrames > 0) {
            draw_scene(&G, vtxCount); /* warmup: shaders/VBO/FBO hot */
            G.Finish();
            clock_gettime(CLOCK_MONOTONIC, &t0);
            for (frame = 0; frame < benchFrames; frame++) {
                draw_scene(&G, vtxCount);
                if (benchLatency) G.Finish();
            }
            G.Finish(); /* synchronize GPU completion BEFORE stopping the clock */
            clock_gettime(CLOCK_MONOTONIC, &t1);
            {
                long long wallMs = (long long) (t1.tv_sec - t0.tv_sec) * 1000LL +
                                   (t1.tv_nsec - t0.tv_nsec) / 1000000LL;
                printf("EGLPROBE-BENCH frames=%d wallMs=%lld msPerFrame=%lld.%03lld mode=%s\n",
                       benchFrames, wallMs, wallMs / benchFrames,
                       (wallMs * 1000 / benchFrames) % 1000,
                       benchLatency ? "per-frame-finish" : "batched-finish");
                printf("EGLPROBE-BENCH-NOTE: GPU-completed offscreen FBO throughput for one fixed "
                       "workload; NOT display-path FPS, NOT GNOME smoothness, NOT a smoothness verdict\n");
                fflush(stdout);
            }
        }
    }

    if (holdSecs > 0) { STAGE("hold-entered"); sleep((unsigned int) holdSecs); STAGE("hold-exited"); }

    makeCurrent(dpy, NULL, NULL, NULL);
    destroyContext(dpy, ctx);
    terminate(dpy);
    printf("EGLPROBE-OK\n");
    fflush(stdout);
    return 0;
}
