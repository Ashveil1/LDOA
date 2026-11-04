# Offline graph qualification source

Not executed against Gradle yet. Uses no plugins or external services. prepare_and_check.py creates only synthetic local Maven POM/JAR bytes and extracts the exact production graph task, bypassing the production loopback repository setup ONLY for this local fixture. No substitute task implementation.

Once explicitly authorized, ONE controlled job, within the existing approved private Gradle provision, namespace/seccomp and time/resource caps, can run this fixture before the actual AGP graph task. No new distribution download, run or broader permission is implied. The fixture commands must use the already provisioned executable and private HOME/GRADLE_USER_HOME, --offline --no-daemon --max-workers=2 --no-configuration-cache. The production repositories must NOT be redirected to this fixture repo.

Inside graph-fixture after python prepare_and_check.py prepare:

    <already-provisioned-gradle> --offline --no-daemon --max-workers=2 --no-configuration-cache -I graph-only.init.gradle resolveLockedInputs
    python prepare_and_check.py check-success
    <already-provisioned-gradle> --offline --no-daemon --max-workers=2 --no-configuration-cache -I graph-only.init.gradle -PgraphFixtureUnresolved resolveLockedInputs
    # The preceding command MUST fail. A harness must capture and assert its nonzero code.
    python prepare_and_check.py check-failure

These are two fixture invocations in one controlled job, not two payload/CI authorizations. Then actual AGP graph enumeration can run under its existing broker if explicitly approved. Expected assertions: local producer with two outgoing artifact subvariants is graphed, producer task never executes, external->leaf edge retained, platform and constraint retained, file declaration explicit but not materialized, unresolved config fails while other config rows survive. Generation of local synthetic JAR bytes is not a build of project outputs. Artifacts are never requested by graph-only task.

selected-inputs.json now means graph preparation only; schema/scope make that explicit. Resolved graph does not mean external files fetched, artifactType selected, transformations run, producers built, file-dependency contents checked, or a complete build-input lock. It is useful for auditing full config graph, missing constraints/modules, and where external/producer/file qualifications remain. No arbitrary config excluded.

## Reviewed orchestration amendment

resolve_once.py now explicitly copies this fixture because the main project copy excludes resolver. It copies the exact init beside the fixture for extraction. prepare, success Gradle, success assertion, unresolved Gradle, unresolved assertion, then actual AGP all run through the same confinement using one already provisioned Gradle. One shared1200-second monotonic deadline covers all six invocations, not1200 per invocation. Fixture Gradle calls are offline and no-daemon/maxworkers2/noconfigcache; production retains the bounded broker. Stale success inventory is removed before expected-failure run. Expected nonzero alone is insufficient: checker must prove the unresolved edge and healthy probe. Fixture assertion failure prevents production invocation.

Explicit targetConfiguration=multi deliberately tests graph mechanics with multiple artifact subvariants, not production AGP variant-disambiguation compatibility. Only the subsequent actual AGP graph can establish that latter behavior.

Inventory export now uses fixed structural failure codes/classes, keeps selection cause enums without arbitrary selection descriptions, and records file dependency kind without arbitrary dependency.toString(). Public component selectors/identities and attrs remain scoped diagnostic data, not general sanitization of arbitrary Gradle console output. Gradle's existing console/error output itself is not claimed fully sanitized.
