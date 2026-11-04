# Fixed case to operation/result/reason mapping

Evidence result is observed; expected values below are host assessment, never overwrite observation.
Each session5s outer interval. Cleanup separate. Any post-rename fsync failure overrides classification to INCONCLUSIVE / POST_RENAME_FSYNC_FAILED, never REJECTED.

|Case|Fixed fixture operation|Expected result|Snapshot reason|Acceptance requirement|
|---|---|---|---|---|
|positive|valid session, normal READY/request rename|ACCEPTED|COMPLETE|seen1/published1/accepted1|
|wrong_token|replace token in request only|REJECTED|NOT_PUBLISHED|accepted0|
|wrong_pin|replace pin in request only|REJECTED|NOT_PUBLISHED|accepted0|
|request_symlink|JNI negative1 request->session|REJECTED|REJECTED|accepted0|
|snapshot_symlink|JNI negative2 snapshot->session before request|REJECTED|REJECTED|accepted0; session unchanged|
|request_nonregular|JNI negative3 directory request|REJECTED|REJECTED|accepted0|
|snapshot_nonregular|JNI negative4 directory snapshot|REJECTED|REJECTED|accepted0|
|bad_mode|create request, JNI negative5 chmod0644 before producer notification|REJECTED|REJECTED|accepted0; needs fixture monitor-start coordination|
|foreign_owner_if_constructible|JNI negative6 fchown real Android uid+1|UNSUPPORTED or REJECTED|UNAVAILABLE or REJECTED|EPERM/EACCES only this construct -> UNSUPPORTED; constructible case currently intentionally exercises missing guest owner authentication: accepted1 preserved -> MISMATCH, never called owner rejection|
|partial_tmp_only|create request.tmp without publication|UNAVAILABLE|NOT_PUBLISHED|no accepted snapshot; timeout|
|published_partial|fixture inject malformed truncated final snapshot|INCONCLUSIVE|PARTIAL|accepted0|
|oversized_snapshot|fixture inject524289byte final|INCONCLUSIVE|CAP_EXCEEDED|accepted0|
|zero_records|fixture inject exact header count0|INCONCLUSIVE|PARTIAL|accepted0|
|second_request|valid positive then second distinct inode request|REJECTED|COMPLETE|seen2/published1/accepted1; second never accepted|
|preexisting_final|create regular snapshot before normal request|REJECTED|REJECTED|accepted0; bytes unchanged|
|monitor_disabled|producer test mode refuses monitor, no polling|UNAVAILABLE|UNAVAILABLE|accepted0|
|no_request|normal READY but no request|UNAVAILABLE|NOT_PUBLISHED|accepted0; timeout|

This matrix is not an execution report. Native producer lacks test-mode injection/monitor-disable hooks and executor is closed. Do not mark cases implemented merely because JNI primitives/matrix exist. GFileMonitor duplicate delivery counts callback events separately from distinct request inodes. Bad-mode/foreign-owner mutation must occur before observable request publication, not race producer. Producer and Android exact result/reason binding still requires implementation.

Constructible foreign-owner branch currently has no Android fd-owner rejection before producer read; this is a designed known-negative integrity gap, not a passing route. Fake-root owner equality is never acceptance authority. Unsupported construction uses NOT_LAUNCHED process cleanup label, with file cleanup separately unimplemented.
