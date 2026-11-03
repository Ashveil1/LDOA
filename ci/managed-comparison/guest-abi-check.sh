#!/bin/sh
# LOCAL UNEXECUTED exact guest ABI check candidate. Must run INSIDE target
# Noble guest with its own libs/loader, never host ldd on guest path.
# ldd -r invokes trusted loader: separate execution approval required.
set -eu
: "${GUEST_ABI_EXEC_APPROVED:?separate guest loader execution approval required}"
[ "$GUEST_ABI_EXEC_APPROVED" = yes ]
P=/opt/ldoa/pcmanfm/1.3.2-4build2-floor1
[ "$(dpkg --print-architecture)" = amd64 ]
[ -f "$P/bin/pcmanfm" ] && [ -d "$P/lib/pcmanfm" ]
command -v readelf >/dev/null # explicit diagnostic tool provisioning gap
command -v timeout >/dev/null
# Mandatory exact pre-pinned inventory verification BEFORE any loader call.
: "${PINNED_INVENTORY_SHA256:?independent runner-provided pin required}"
case "$PINNED_INVENTORY_SHA256" in *[!0-9a-f]*|'') exit 70;; esac
[ "${#PINNED_INVENTORY_SHA256}" = 64 ]
[ -f "$P/inventory.sha256" ]
actual=$(sha256sum "$P/inventory.sha256"); actual=${actual%% *}
[ "$actual" = "$PINNED_INVENTORY_SHA256" ]
# Inventory syntax, exact entry set, traversal/symlink and ELF checks are
# enforced by prelaunch provisioner; sha256sum independently rechecks bytes.
(cd "$P"; timeout -k 2 10 sha256sum -c inventory.sha256)
timeout -k 2 10 readelf -h -l -d --version-info "$P/bin/pcmanfm"
timeout -k 2 10 env -u LD_LIBRARY_PATH -u LD_PRELOAD ldd -r -v "$P/bin/pcmanfm"
echo 'ABI RAW CAPTURE ONLY; parser must verify full streams/exit/eof, never this echo.'
