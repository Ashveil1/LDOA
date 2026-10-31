/*
 * LDOA bounded creat(2) -> openat(2) interposition shim.
 *
 * Scope: x86_64 CI EMULATOR DIAGNOSTIC ONLY. Not for ARM, not for the
 * product/APK default path (launcher gates on uname -m == x86_64).
 *
 * Grounded chain (binary + source verified, 2026-11-01):
 *   - glibc 2.39 x86_64 creat() is a raw legacy syscall (NR 85), verified
 *     by disassembly of Noble libc6_2.39-0ubuntu8.9_amd64.
 *   - The Android-app seccomp profile blocks the legacy path-syscall class.
 *   - Vendored proot 5.1.107.101 (Termux) seccomp handler rewrites
 *     PR_link -> linkat but has NO PR_creat case (src/tracee/seccomp.c),
 *     so creat falls through to -ENOSYS.
 *   - libICE 1.0.10 IceLockAuthFile (src/authutil.c) calls creat(); the
 *     failure makes gnome-session's update_iceauthority return FALSE,
 *     which gsm-xsmp-server.c:655 treats as fatal -> exit(1).
 *   - ARM aarch64 libc creat tail-calls openat (verified in Noble arm64
 *     libc6); aarch64 has no legacy path syscalls, so ARM is unaffected
 *     for THIS call path (no claim about other ARM ICE-lock health).
 *
 * Semantics preserved: creat(path, mode) == open(path, O_WRONLY|O_CREAT|O_TRUNC, mode)
 * (POSIX). openat(AT_FDCWD, ...) with a relative path resolves against the
 * caller's cwd exactly like creat. Locking, auth, and file contents are
 * untouched; errno comes straight from openat.
 *
 * Unresolved micro-link (labeled): whether the Android delivery is
 * RET_ERRNO vs SIGSYS-then-proot-default is NOT distinguished by an
 * observed -1/ENOSYS return alone; this shim is valid under either.
 */
#define _GNU_SOURCE
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

__attribute__((constructor))
static void ldoa_creat_shim_init(void)
{
    /* Bounded: single short line to stderr so CI logs prove the preload
     * actually took effect in the session process (positive evidence,
     * not absence-of-warning). */
    static const char msg[] = "ldoa-creat-shim: loaded (x86_64 CI diagnostic)\n";
    (void)!write(STDERR_FILENO, msg, sizeof(msg) - 1);
}

int creat(const char *path, mode_t mode)
{
    return openat(AT_FDCWD, path, O_WRONLY | O_CREAT | O_TRUNC, mode);
}

int creat64(const char *path, mode_t mode)
{
    return openat(AT_FDCWD, path, O_WRONLY | O_CREAT | O_TRUNC | O_LARGEFILE, mode);
}
