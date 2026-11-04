# Snapshot plus static consistency: source candidate only

This review supersedes historical execution-state text in REVIEW.md and ROOT-CHILD-PROPOSAL.md. No push, registration, runner read, host-byte parsing, sudo, tool invocation, download, or execution is authorized or performed here. Only synthetic temporary fixtures and offline source generation were run.

## Exact integrated route

The reviewed noncircular dispatch gate and fresh /ldoa-qualification root-child installer remain. The helper now carries the descriptor adapter, parser, resolver and fixed OS configuration policy as literal stdlib-only source. No repository checkout/import precedes privileged work. The workflow remains if:false. All literal digests change; the historical helper digest must NOT authorize this new helper. Exact future source commit, external postcommit manifest, workflow and helper review are still required. workflow_sha remains opaque.

The existing snapshot lifecycle performs inventory/copy/seal/verify, then calls integrated_static_phase before its existing finally cleanup. That call authenticates both logical tool aliases and canonical sealed bytes against current snapshot rows, binds the current root descriptor, captures OS configuration and closes the finite static dependency graph from clang-21 and lld. It captures configuration again, rereads every recorded file or absence, verifies bindings, then returns static-only evidence. Snapshot verify/root identity checks run again after the callback. Exceptions now explicitly set STOP even if a snapshot receipt had already been constructed. Failure and success both enter the existing cleanup path. Emergency cleanup still authenticates this exact newly generated helper, not an old receipt.

## Bounds, not expansion

Snapshot caps are unchanged: tools512MiB, headers64MiB/4096, total600MiB, descriptors4MiB, traversal8192, ancestry32, receipt4MiB. Host parser caps remain192MiB/ELF,128 program headers,4096 dynamic entries,1MiB string table,4096 bytes per string,64 needed names,64 nodes,256 edges,512MiB total. Integrated I/O conservatively also caps ALL unique requested paths, including absent candidates and config paths, at64 and lookup attempts INCLUDING postchecks at256; these extra STOPs are intentional, not loader rejection predictions. Config files have a4MiB cap BEFORE reading. Alias limits remain32 hops/256 events/512-character path. No retry, broader search, package acquisition or fallback is wired.

The integrated receipt is checked at4MiB before insertion into the snapshot receipt. Existing receipt emitter limits and outer timeouts remain. Artifact/log quotas are not independently measured.

## OS policy and limits

Bind /etc/ld.so.cache, /etc/ld.so.conf, /etc/ld.so.preload and the exact nofollow /etc/ld.so.conf.d directory membership and files. Absence is recorded and rechecked. Nonempty preload stops. Included configuration is NOT interpreted to expand search roots; cache, hwcaps, CPU/auxv selection, loader versions, symbol versions and ABI compatibility remain unmodeled. Nested config directories and unsupported aliases stop rather than widen. Files included elsewhere by configuration are not authenticated by this closed model. This is an explicit runtime trust limit, not full OS configuration closure.

No environment or tool consumer is executed. The fixed clean environment proposal never copies ambient LD/compile path overrides into a consumer. Any future consumer needs its own environment wiring and review.

This static consistency gate is neither necessary nor sufficient for actual loader success. Actual runtime loader failure remains possible under the named procedural job-image/OS tier. Current-job observed hashes are not publisher provenance or reusable pins. Historical cleaned receipts remain unusable.

## Budget proposal

The installer starts one1200-second absolute deadline. There is no second budget. Session evidence records elapsed time, remaining deadline, proposed30-second cleanup reserve, process maximum RSS and current free bytes. Snapshot receipt adds total elapsed/remaining before and after cleanup. Host reads use deadline-minus30. The reserve is an UNMEASURED proposal, not guaranteed cleanup duration; snapshot inventory/copy still uses the original shared deadline. Abrupt kill and installer interruption limitations remain unchanged. Existing1230-second outer helper timeout and25-minute job timeout do not grant more qualification work.

Snapshot historical mechanics took about14.29s plus2.12s installation and0.045s emergency cleanup. Those observations do not qualify combined A2 timing. No A1 reacquisition, guest assets, compiler/link command, version/readelf/ldd, or producer execution is present. Full A2 budget and command compatibility remain unqualified.

## Tests

68 distinct synthetic tests pass. Existing45 snapshot/literal/fd/binding/root-child tests plus16 parser/descriptor tests plus7 integrated tests. Added reserve boundary, config preload/absence, absence-to-present mutation, config cap before read, lookup cap, standalone namespace cap isolation, callback failure cleanup and callback success cleanup tests. Deterministic generation checks all six literal outputs. Source modules are imported only to test pure/synthetic behavior; no real host/snapshot bytes were parsed. The helper is compiled/imported with a non-main name, never invoked against its fixed paths.

No visual deliverable is involved. Source inspection, synthetic tests and deterministic hashes are the applicable checks.
