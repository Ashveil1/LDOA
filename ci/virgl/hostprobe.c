/* hostprobe - HOST-side EGL/GLES renderer probe for the VirGL spike. Runs
 *  OUTSIDE proot, in the app's own Android process environment, against
 *  the same host EGL the vtest server uses (virgl_test_server
 *  --use-egl-surfaceless -> epoxy -> Android libEGL). Its GL-RENDERER is
 *  the independent host-backend measurement that cross-checks the
 *  guest-reported "virgl (<host>)" parenthetical: both come from the
 *  same host GLES driver, so they must name the same backend. A software
 *  host (SwiftShader/llvmpipe on the emulator) is recorded as software -
 *  never upgraded to a GPU claim.
 *
 *  Build (CI, NDK clang, per app ABI):
 *    <ndk>/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang -O2 -o hostprobe-x86_64 hostprobe.c -lEGL -lGLESv2
 *    <...>/aarch64-linux-android26-clang -O2 -o hostprobe-arm64 hostprobe.c -lEGL -lGLESv2
 *  Exit codes: 0 ok, 3 eglGetDisplay, 4 eglInitialize, 6 no config,
 *  7 context, 8 makeCurrent (both surfaceless and pbuffer failed). */
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY) { fprintf(stderr, "HOSTPROBE-ERROR stage=3 eglGetDisplay\n"); return 3; }
    EGLint maj = 0, min = 0;
    if (!eglInitialize(dpy, &maj, &min)) { fprintf(stderr, "HOSTPROBE-ERROR stage=4 eglInitialize\n"); return 4; }
    printf("EGL-VENDOR: %s\n", eglQueryString(dpy, EGL_VENDOR));
    printf("EGL-VERSION: %s\n", eglQueryString(dpy, EGL_VERSION));
    if (!eglBindAPI(EGL_OPENGL_ES_API)) { fprintf(stderr, "HOSTPROBE-ERROR stage=5 eglBindAPI\n"); return 5; }
    EGLint cfgAttrs[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLConfig cfg; EGLint n = 0;
    if (!eglChooseConfig(dpy, cfgAttrs, &cfg, 1, &n) || n < 1) { fprintf(stderr, "HOSTPROBE-ERROR stage=6 eglChooseConfig\n"); return 6; }
    EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttrs);
    if (ctx == EGL_NO_CONTEXT) { fprintf(stderr, "HOSTPROBE-ERROR stage=7 eglCreateContext\n"); return 7; }
    EGLSurface surf = EGL_NO_SURFACE;
    if (!eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) {
        /* EGL_KHR_surfaceless_context unavailable: 1x1 pbuffer fallback. */
        EGLint pbAttrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        surf = eglCreatePbufferSurface(dpy, cfg, pbAttrs);
        if (surf == EGL_NO_SURFACE || !eglMakeCurrent(dpy, surf, surf, ctx)) {
            fprintf(stderr, "HOSTPROBE-ERROR stage=8 makeCurrent (surfaceless + pbuffer)\n");
            return 8;
        }
    }
    const GLubyte *vendor = glGetString(GL_VENDOR);
    const GLubyte *renderer = glGetString(GL_RENDERER);
    const GLubyte *version = glGetString(GL_VERSION);
    printf("GL-VENDOR: %s\n", vendor ? (const char *) vendor : "(null)");
    printf("GL-RENDERER: %s\n", renderer ? (const char *) renderer : "(null)");
    printf("GL-VERSION: %s\n", version ? (const char *) version : "(null)");
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (surf != EGL_NO_SURFACE) eglDestroySurface(dpy, surf);
    eglDestroyContext(dpy, ctx);
    eglTerminate(dpy);
    printf("HOSTPROBE-OK\n");
    return 0;
}
