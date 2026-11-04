# Finite honest-fixture process contract

Scope excludes unrelated processes and malicious same-UID writers. No universal process-absence verdict.

Producer is direct initial tracee, not /bin/sh wrapper. Producer source has no direct fork/vfork/exec/system/popen/spawn call. This does NOT prove loader/GLib/dependencies never clone or spawn. GLib may create threads; positive normal-end contract requires them to terminate with producer. Dynamic GLib backend cannot launch helpers silently as an assumed fact: INOTIFY backend observed, no external module path/cache inherited, fixed empty module directory and GIO_USE_FILE_MONITOR=inotify. This is a controlled fixture assumption requiring runtime READY and normal terminal receipt.

Exact intended process launch:
`nativeLibraryDir/libproot.so --link2symlink --kill-on-exit -0 -r APP_PRIVATE_ROOTFS -b /dev -b /proc -b /sys -w / /producer BASENAME TOKEN PIN`
PROOT_LOADER points to packaged ELF64 loader. PROOT_LOADER_32 omitted because no ELF32 inputs/exec permitted. LD_LIBRARY_PATH=nativeLibraryDir, PROOT_TMP_DIR=owned private app dir. No X socket bind because fixture has no Xserver. This is minimal production-like subset, not falsely called exact whole production bind list.

Native libproot source evidence v5.1.107.101 marks initial tracee killall_on_exit and on its termination kills recorded tracees; atexit/handled signals similarly kill recorded tracees. Shipped prebuilt byte/source equivalence not reproduced. Exact APK/ABI runtime must test this contract; source alone does not prove all kills reaped.

Android records direct proot child host PID/start and known descendants before/during run, never guest PID or process-name search. Positive cleanup result is KNOWN_OWNED_REAPED only when a classified terminal receipt is observed (exact normal END and exit0, or exact HSREADY1 UNAVAILABLE MONITOR and exit73), direct Process exits, and anchored proot plus every captured owned identity is gone. This is known-identity cleanup, not a no-spawn proof or universal residual absence. Classified exit plus unknown inaccessible proc identity is UNKNOWN, not success. No broad UID kill, no unrelated process enumeration disclosure.

Failure cleanup may signal only captured host PID/start identities, bounded TERM then KILL and owned Process.destroyForcibly. Tracer SIGKILL bypasses handler/atexit; therefore forced fallback result stays UNKNOWN even if known identities disappear. It is not universal absence proof. These outcomes are preserved as evidence/MISMATCH, not transport corruption.

Current OwnedTree helper's positive branch reflects this limited contract. Main case executor/continuous capture not yet wired, so no runtime result exists.

Producer normal-end timer3.5s is cooperative main-loop behavior, NOT a hard orphan bound. Android outer5s deadline owns timeout; if loop blocks, normal-end is not observed and cleanup remains UNKNOWN on forced path.

Monitor unavailable remains a capability gap even when its expected-negative observation MATCHes. Any wrong receipt/exit, forced termination or unreadable identity retains UNKNOWN. Producer 3.5s cooperative lifetime versus5s outer observation leaves only1.5s target-unverified startup margin.

Session parent bind added explicitly at /run/ldoa-handshake. Parent and child path reopened nofollow and matched to retained dev/inode immediately before ProcessBuilder. Path use still has a race before proot resolves it; excluded hostile sameUID mutation is not defended by the token. Runtime/staging root validation remains unfinished.

Request generation field is1 then2 only. Every structurally valid generation increments seen once; consumption stays one-shot, generation2 never publishes again. No dev/inode reuse or ctime heuristic, retry or repair.
