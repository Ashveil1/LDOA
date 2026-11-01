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
 * Usage: eglprobe [--hold SECONDS]
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

    if (argc >= 3 && strcmp(argv[1], "--hold") == 0) {
        int secs = atoi(argv[2]);
        if (secs > 0) { STAGE("hold-entered"); sleep((unsigned int) secs); STAGE("hold-exited"); }
    }

    makeCurrent(dpy, NULL, NULL, NULL);
    destroyContext(dpy, ctx);
    terminate(dpy);
    printf("EGLPROBE-OK\n");
    fflush(stdout);
    return 0;
}
