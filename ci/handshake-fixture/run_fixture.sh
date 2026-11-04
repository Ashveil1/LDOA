#!/bin/bash
set -euo pipefail
# No APK/AVD execution authority carried by this candidate.
printf '%s\n' 'HSFAIL1 PREIMPLEMENTATION_BLOCKED' >&2
exit 78
