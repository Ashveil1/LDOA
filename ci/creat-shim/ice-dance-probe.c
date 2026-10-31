/*
 * LDOA ICE-lock dance probe (x86_64 CI emulator diagnostic).
 *
 * Runs independent arms against UNIQUE files it owns (probe-<pid>-*) and
 * cleans up ONLY its own files. Prints actual rc + errno per call so the
 * CI log carries the raw evidence. Always exits 0 (diagnostic, not a gate).
 *
 * Arm (e) replicates the exact libICE 1.0.10 IceLockAuthFile first-try
 * sequence (src/authutil.c:140-230): stat, creat(0666), close, link,
 * then the IceUnlockAuthFile cleanup (unlink -c, unlink -l). Lock files
 * are transient in the real code (unlock runs at the end of
 * update_iceauthority, gsm-xsmp-server.c:547), so the probe does the same.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static void report(const char *arm, const char *call, long rc)
{
    int e = (rc == -1) ? errno : 0;
    printf("arm=%s call=%s rc=%ld errno=%d(%s)\n", arm, call, rc, e,
           e ? strerror(e) : "none");
}

int main(void)
{
    char base[256], cname[288], lname[288], ctl[288];
    pid_t p = getpid();
    snprintf(base, sizeof(base), "/tmp/ldoa-diag/probe-%d-base", (int)p);
    snprintf(cname, sizeof(cname), "%s-c", base);
    snprintf(lname, sizeof(lname), "%s-l", base);
    snprintf(ctl,   sizeof(ctl),   "/tmp/ldoa-diag/probe-%d-ctl", (int)p);

    /* (a) raw legacy syscall: observes whether NR 85 is deliverable at all.
     * A -1/ENOSYS here does NOT by itself distinguish RET_ERRNO from
     * SIGSYS-then-proot-default; that micro-link stays labeled unresolved. */
    errno = 0;
    long rc = syscall(SYS_creat, cname, 0666);
    report("raw_creat", "syscall(SYS_creat)", rc);
    if (rc != -1) { close((int)rc); unlink(cname); }

    /* (b) libc creat: the exact call IceLockAuthFile makes. Without the
     * shim this takes the same glibc wrapper -> raw NR 85 path. With the
     * shim preloaded it resolves to openat instead. */
    errno = 0;
    rc = creat(cname, 0666);
    report("libc_creat", "creat()", rc);
    if (rc != -1) { close((int)rc); unlink(cname); }

    /* (c) creat64: same weak-symbol path in glibc; shim covers both. */
    errno = 0;
    rc = creat64(cname, 0666);
    report("libc_creat64", "creat64()", rc);
    if (rc != -1) { close((int)rc); unlink(cname); }

    /* (d) openat control: the modern path-syscall that proot passes; must
     * succeed in every environment, isolating the failure to the legacy
     * call class. */
    errno = 0;
    rc = openat(AT_FDCWD, ctl, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    report("openat_control", "openat()", rc);
    if (rc != -1) { close((int)rc); }

    /* (e) full IceLockAuthFile first-try dance replica, unique owned files */
    {
        struct stat st;
        int ok = 1;
        errno = 0;
        rc = stat(cname, &st);
        report("dance", "stat(-c)", rc); /* expect -1/ENOENT on fresh path */
        errno = 0;
        rc = creat(cname, 0666);
        report("dance", "creat(-c,0666)", rc);
        if (rc == -1) ok = 0;
        else close((int)rc);
        if (ok) {
            errno = 0;
            rc = link(cname, lname);
            report("dance", "link(-c,-l)", rc);
            if (rc == -1) ok = 0;
        }
        printf("arm=dance outcome=%s\n", ok ? "IceAuthLockSuccess-equivalent" : "IceAuthLockError-equivalent");
        /* IceUnlockAuthFile replica: cleanup own lock files */
        unlink(cname);
        unlink(lname);
    }

    /* cleanup: only files this process created */
    unlink(ctl);
    return 0;
}
