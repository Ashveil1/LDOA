# Executed only inside run-as dev.phonelinux.proto sh, user0 fresh fixture.
# HOME is reset from verified package metadata by Android14 run-as itself.
case "$HOME" in /data/data/dev.phonelinux.proto|/data/user/0/dev.phonelinux.proto) ;; *) exit 73;; esac
[ ! -L "$HOME" ] && [ -d "$HOME" ] || exit 74
u=$(id -u) || exit 71
g=$(id -g) || exit 71
case "$u:$g" in *[!0-9:]*|:|*:|:*) exit 72;; esac
[ "$u" -ge 10000 ] && [ "$u" -lt 100000 ] && [ "$g" = "$u" ] || exit 75
here=$(stat -Lc '%d:%i:%u:%g' .) || exit 71
home=$(stat -Lc '%d:%i:%u:%g' "$HOME") || exit 71
case "$here:$home" in *[!0-9:]*|'' ) exit 72;; esac
[ -n "$here" ] && [ -n "$home" ] || exit 72
[ "$here" = "$home" ] || exit 76
oldifs=$IFS;IFS=:;set -f;set -- $home;IFS=$oldifs
[ "$#" -eq 4 ] || exit 72
[ -n "$1" ] && [ -n "$2" ] && [ -n "$3" ] && [ -n "$4" ] || exit 72
[ "$3" = "$u" ] && [ "$4" = "$g" ] || exit 77
