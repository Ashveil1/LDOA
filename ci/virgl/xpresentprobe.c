/* xpresentprobe - minimal guest-side EGL-on-X11 PRESENTATION probe for
 * the VirGL black-frame attribution (ci/virgl/README.md). eglprobe.c
 * (surfaceless) proves the guest driver can RENDER; it cannot answer
 * the question the black-frame attribution is actually about: does an
 * EGL/X11 window surface rendered by the GUEST driver named in
 * GALLIUM_DRIVER put visible pixels on the X server? This probe is the
 * mutter-x11 mechanism in miniature:
 *   XOpenDisplay -> XCreateSimpleWindow -> XMapWindow
 *   -> eglGetPlatformDisplay(EGL_PLATFORM_X11_KHR) -> window surface
 *   -> ES2 clear to a solid caller-chosen color -> eglSwapBuffers x N
 *   -> hold with the window still mapped so an external xwd can capture
 * Every stage prints a marker; every failure exits with a stage-unique
 * code plus eglGetError(), so a silent presentation gap (black capture
 * despite XPRESENT-OK, renderer string healthy) is distinguishable from
 * an init failure (the exit code names the exact failing call).
 *
 * GLX is deliberately NOT used: on the emulator lane lorie's GLX is
 * indirect-only (no usable DRI3 device), so a GLX client would render
 * in the X SERVER (never the guest driver) and could present color
 * while the virpipe path is broken - a false pass. Rationale and
 * provenance: ci/virgl/eglprobe.c header.
 *
 * Runtime deps (guest rootfs, dlopen'ed): libX11.so.6, libEGL.so.1,
 * libGLESv2.so.2 - all present in the GNOME-provisioned rootfs.
 * Build (CI lane; runner gate: noble only, so glibc symbol versions
 * never exceed the rootfs' 2.39):
 *   gcc -O2 -Wall -o xpresentprobe-x86_64 ci/virgl/xpresentprobe.c -ldl
 *   aarch64-linux-gnu-gcc -O2 -Wall -o xpresentprobe-aarch64 ci/virgl/xpresentprobe.c -ldl
 * Runs INSIDE proot: proot's loader reads and maps the guest ELF, so
 * the app-writable rootfs path needs no kernel exec permission.
 *
 * Usage: xpresentprobe [--rgb R G B] [--frames N] [--hold SECONDS]
 *   Defaults: green (0 1 0), 45 frames, 6s hold.
 * Exit codes: 0 ok / 2 dlopen libX11 / 3 XOpenDisplay / 4 dlopen libEGL
 *   / 5 no X11 platform display / 6 eglInitialize / 7 eglChooseConfig
 *   / 8 eglBindAPI / 9 eglCreateContext / 10 eglCreateWindowSurface
 *   / 11 eglMakeCurrent / 12 dlopen libGLESv2 / 13 eglSwapBuffers
 *   returned EGL_FALSE mid-loop (eglGetError printed).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <unistd.h>

typedef int EGLint;
typedef unsigned int EGLenum;
typedef unsigned int EGLBoolean;
typedef void *EGLDisplay;
typedef void *EGLConfig;
typedef void *EGLContext;
typedef void *EGLSurface;

#define EGL_FALSE 0
#define EGL_TRUE 1
#define EGL_NONE 0x3038
#define EGL_SURFACE_TYPE 0x3033
#define EGL_WINDOW_BIT 0x0004
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_OPENGL_ES2_BIT 0x0004
#define EGL_OPENGL_ES_API 0x30A0
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#define EGL_RED_SIZE 0x3024
#define EGL_GREEN_SIZE 0x3023
#define EGL_BLUE_SIZE 0x3022
#define EGL_ALPHA_SIZE 0x3021
#define EGL_VENDOR 0x3053
#define EGL_VERSION 0x3054
#define EGL_PLATFORM_X11_KHR 0x31D5

#define GL_VENDOR 0x1F00
#define GL_RENDERER 0x1F01
#define GL_VERSION 0x1F02
#define GL_COLOR_BUFFER_BIT 0x4000

/* X11 (opaque; XID = unsigned long on both LP64 arches) */
typedef struct _XDisplay Display;
typedef unsigned long XWindow;

typedef Display *(*pfn_XOpenDisplay)(const char *);
typedef XWindow (*pfn_XDefaultRootWindow)(Display *);
typedef XWindow (*pfn_XCreateSimpleWindow)(Display *, XWindow, int, int,
    unsigned int, unsigned int, unsigned int, unsigned long, unsigned long);
typedef int (*pfn_XMapWindow)(Display *, XWindow);
typedef int (*pfn_XSync)(Display *, int);
typedef int (*pfn_XDestroyWindow)(Display *, XWindow);
typedef int (*pfn_XCloseDisplay)(Display *);

typedef EGLDisplay (*pfn_eglGetPlatformDisplay)(EGLenum, void *, const EGLint *);
typedef EGLDisplay (*pfn_eglGetDisplay)(void *);
typedef EGLBoolean (*pfn_eglInitialize)(EGLDisplay, EGLint *, EGLint *);
typedef EGLBoolean (*pfn_eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLBoolean (*pfn_eglBindAPI)(EGLenum);
typedef EGLContext (*pfn_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
typedef EGLSurface (*pfn_eglCreateWindowSurface)(EGLDisplay, EGLConfig, XWindow, const EGLint *);
typedef EGLBoolean (*pfn_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLBoolean (*pfn_eglSwapBuffers)(EGLDisplay, EGLSurface);
typedef EGLint (*pfn_eglGetError)(void);
typedef const char *(*pfn_eglQueryString)(EGLDisplay, EGLint);
typedef EGLBoolean (*pfn_eglTerminate)(EGLDisplay);

typedef void (*pfn_glClearColor)(float, float, float, float);
typedef void (*pfn_glClear)(unsigned int);
typedef const unsigned char *(*pfn_glGetString)(unsigned int);

static void *sym(void *h, const char *n) {
    void *p = dlsym(h, n);
    if (!p) fprintf(stderr, "XPRESENT-MISSING-SYMBOL %s\n", n);
    return p;
}

int main(int argc, char **argv) {
    float r = 0.0f, g = 1.0f, b = 0.0f;
    int frames = 45, hold = 6;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--rgb") && i + 3 < argc) {
            r = (float)atof(argv[++i]); g = (float)atof(argv[++i]); b = (float)atof(argv[++i]);
        } else if (!strcmp(argv[i], "--frames") && i + 1 < argc) {
            frames = atoi(argv[++i]);
        } else if (!strcmp(argv[i], "--hold") && i + 1 < argc) {
            hold = atoi(argv[++i]);
        } else {
            fprintf(stderr, "XPRESENT-BAD-ARG %s\n", argv[i]);
            return 2;
        }
    }
    if (frames < 1) frames = 1;
    if (frames > 600) frames = 600;
    if (hold < 0) hold = 0;
    if (hold > 60) hold = 60;

    void *hx = dlopen("libX11.so.6", RTLD_NOW | RTLD_GLOBAL);
    if (!hx) { fprintf(stderr, "XPRESENT-FAIL dlopen libX11.so.6: %s\n", dlerror()); return 2; }
    pfn_XOpenDisplay pXOpenDisplay = (pfn_XOpenDisplay)sym(hx, "XOpenDisplay");
    pfn_XDefaultRootWindow pXDefaultRootWindow = (pfn_XDefaultRootWindow)sym(hx, "XDefaultRootWindow");
    pfn_XCreateSimpleWindow pXCreateSimpleWindow = (pfn_XCreateSimpleWindow)sym(hx, "XCreateSimpleWindow");
    pfn_XMapWindow pXMapWindow = (pfn_XMapWindow)sym(hx, "XMapWindow");
    pfn_XSync pXSync = (pfn_XSync)sym(hx, "XSync");
    pfn_XDestroyWindow pXDestroyWindow = (pfn_XDestroyWindow)sym(hx, "XDestroyWindow");
    pfn_XCloseDisplay pXCloseDisplay = (pfn_XCloseDisplay)sym(hx, "XCloseDisplay");
    if (!pXOpenDisplay || !pXDefaultRootWindow || !pXCreateSimpleWindow ||
        !pXMapWindow || !pXSync || !pXDestroyWindow || !pXCloseDisplay) return 2;

    Display *dpy = pXOpenDisplay(NULL);
    if (!dpy) { fprintf(stderr, "XPRESENT-FAIL XOpenDisplay (DISPLAY=%s)\n", getenv("DISPLAY") ?: "(unset)"); return 3; }
    XWindow root = pXDefaultRootWindow(dpy);
    XWindow win = pXCreateSimpleWindow(dpy, root, 64, 64, 320, 240, 0, 0, 0);
    pXMapWindow(dpy, win);
    pXSync(dpy, 0);
    printf("XPRESENT-STAGE window-mapped id=0x%lx geo=320x240+64+64\n", win);
    fflush(stdout);

    void *he = dlopen("libEGL.so.1", RTLD_NOW | RTLD_GLOBAL);
    if (!he) { fprintf(stderr, "XPRESENT-FAIL dlopen libEGL.so.1: %s\n", dlerror()); return 4; }
    pfn_eglGetError pGetError = (pfn_eglGetError)sym(he, "eglGetError");
    pfn_eglGetPlatformDisplay pGetPlatformDisplay = (pfn_eglGetPlatformDisplay)sym(he, "eglGetPlatformDisplay");
    pfn_eglGetDisplay pGetDisplay = (pfn_eglGetDisplay)sym(he, "eglGetDisplay");
    pfn_eglInitialize pInitialize = (pfn_eglInitialize)sym(he, "eglInitialize");
    pfn_eglChooseConfig pChooseConfig = (pfn_eglChooseConfig)sym(he, "eglChooseConfig");
    pfn_eglBindAPI pBindAPI = (pfn_eglBindAPI)sym(he, "eglBindAPI");
    pfn_eglCreateContext pCreateContext = (pfn_eglCreateContext)sym(he, "eglCreateContext");
    pfn_eglCreateWindowSurface pCreateWindowSurface = (pfn_eglCreateWindowSurface)sym(he, "eglCreateWindowSurface");
    pfn_eglMakeCurrent pMakeCurrent = (pfn_eglMakeCurrent)sym(he, "eglMakeCurrent");
    pfn_eglSwapBuffers pSwapBuffers = (pfn_eglSwapBuffers)sym(he, "eglSwapBuffers");
    pfn_eglQueryString pQueryString = (pfn_eglQueryString)sym(he, "eglQueryString");
    pfn_eglTerminate pTerminate = (pfn_eglTerminate)sym(he, "eglTerminate");
    if (!pGetError || !pInitialize || !pChooseConfig || !pBindAPI || !pCreateContext ||
        !pCreateWindowSurface || !pMakeCurrent || !pSwapBuffers || !pQueryString) return 4;

    EGLDisplay edpy = NULL;
    const char *how = NULL;
    if (pGetPlatformDisplay) {
        edpy = pGetPlatformDisplay(EGL_PLATFORM_X11_KHR, (void *)dpy, NULL);
        how = "eglGetPlatformDisplay(EGL_PLATFORM_X11_KHR)";
    }
    if ((!edpy) && pGetDisplay) {
        edpy = pGetDisplay((void *)dpy);
        how = "eglGetDisplay (fallback)";
    }
    if (!edpy) {
        fprintf(stderr, "XPRESENT-FAIL no X11 platform display err=0x%x\n", pGetError());
        return 5;
    }
    printf("XPRESENT-STAGE display-acquired via=%s\n", how);
    if (pInitialize(edpy, NULL, NULL) != EGL_TRUE) {
        fprintf(stderr, "XPRESENT-FAIL eglInitialize err=0x%x\n", pGetError());
        return 6;
    }
    printf("XPRESENT-EGL-VENDOR %s\n", pQueryString(edpy, EGL_VENDOR));
    printf("XPRESENT-EGL-VERSION %s\n", pQueryString(edpy, EGL_VERSION));
    fflush(stdout);

    EGLint cfgAttrs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig cfg; EGLint ncfg = 0;
    if (pChooseConfig(edpy, cfgAttrs, &cfg, 1, &ncfg) != EGL_TRUE || ncfg < 1) {
        fprintf(stderr, "XPRESENT-FAIL eglChooseConfig n=%d err=0x%x\n", ncfg, pGetError());
        return 7;
    }
    if (pBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
        fprintf(stderr, "XPRESENT-FAIL eglBindAPI err=0x%x\n", pGetError());
        return 8;
    }
    EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ectx = pCreateContext(edpy, cfg, NULL, ctxAttrs);
    if (!ectx) {
        fprintf(stderr, "XPRESENT-FAIL eglCreateContext err=0x%x\n", pGetError());
        return 9;
    }
    EGLSurface surf = pCreateWindowSurface(edpy, cfg, win, NULL);
    if (!surf) {
        fprintf(stderr, "XPRESENT-FAIL eglCreateWindowSurface err=0x%x\n", pGetError());
        return 10;
    }
    if (pMakeCurrent(edpy, surf, surf, ectx) != EGL_TRUE) {
        fprintf(stderr, "XPRESENT-FAIL eglMakeCurrent err=0x%x\n", pGetError());
        return 11;
    }
    printf("XPRESENT-STAGE context-current\n");
    fflush(stdout);

    void *hg = dlopen("libGLESv2.so.2", RTLD_NOW | RTLD_GLOBAL);
    if (!hg) { fprintf(stderr, "XPRESENT-FAIL dlopen libGLESv2.so.2: %s\n", dlerror()); return 12; }
    pfn_glClearColor pClearColor = (pfn_glClearColor)sym(hg, "glClearColor");
    pfn_glClear pClear = (pfn_glClear)sym(hg, "glClear");
    pfn_glGetString pGlGetString = (pfn_glGetString)sym(hg, "glGetString");
    if (!pClearColor || !pClear || !pGlGetString) return 12;

    const unsigned char *rend = pGlGetString(GL_RENDERER);
    printf("XPRESENT-RENDERER %s\n", rend ? (const char *)rend : "(null)");
    printf("XPRESENT-GL-VENDOR %s\n", (const char *)(pGlGetString(GL_VENDOR) ?: (const unsigned char *)"(null)"));
    printf("XPRESENT-GL-VERSION %s\n", (const char *)(pGlGetString(GL_VERSION) ?: (const unsigned char *)"(null)"));
    printf("XPRESENT-GALLIUM_DRIVER %s\n", getenv("GALLIUM_DRIVER") ?: "(unset)");
    fflush(stdout);

    pClearColor(r, g, b, 1.0f);
    int swaps = 0;
    for (int i = 0; i < frames; i++) {
        pClear(GL_COLOR_BUFFER_BIT);
        if (pSwapBuffers(edpy, surf) != EGL_TRUE) {
            fprintf(stderr, "XPRESENT-FAIL eglSwapBuffers frame=%d err=0x%x\n", i, pGetError());
            return 13;
        }
        swaps++;
        usleep(16667);
    }
    pXSync(dpy, 0);
    printf("XPRESENT-OK swaps=%d rgb=%.2f,%.2f,%.2f hold=%d\n", swaps, r, g, b, hold);
    fflush(stdout);
    if (hold > 0) sleep((unsigned int)hold);

    if (pTerminate) pTerminate(edpy);
    pXDestroyWindow(dpy, win);
    pXCloseDisplay(dpy);
    return 0;
}
