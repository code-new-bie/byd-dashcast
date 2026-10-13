#!/bin/sh
# Owner wrapper stays alive to capture native cleanup exit status; no crypto/random/SSH.
set -f
umask 077
RUN=/fs/tmpfs/dip-usb
SELF=$RUN/instrument-usb
PROGRAM=$RUN/instrument-receiver
[ -e "$RUN/net/dev/socket" ] && [ -x "$PROGRAM" ] && [ -x "$SELF" ] || exit 1
for name in cast-owner cast-cleanup cast-cleanup.tmp cast-guard; do
    [ ! -L "$RUN/$name" ] || exit 1
done
mkdir "$RUN/cast-guard" || { echo DCV1_WRAPPER_CONFLICT; exit 1; }
# Missing Screen/mm-renderer is a video failure, not a USB role failure.
ready=0
attempt=0
while [ "$attempt" -lt 80 ]; do
    if [ -e /dev/screen ] && [ -e /pps/services/multimedia/renderer/control ]; then ready=1; break; fi
    "$SELF" --pause
    attempt=$((attempt + 1))
done
if [ "$ready" != 1 ]; then
    echo DCV1_MEDIA_NOT_READY
    "$SELF" --exec 4 "$SELF" --rmdir "$RUN/cast-guard"
    exit 1
fi
SOCK=$RUN/net; export SOCK
trap 'echo DCV1_WRAPPER_INTERRUPTED; exit 125' INT TERM
"$PROGRAM" --serve 192.168.77.1 31707 --peer 192.168.77.2 &
native_pid=$!
printf 'DCV1 %s\n' "$native_pid" > "$RUN/cast-owner"
wait "$native_pid"
native_rc=$?
# rc=0 means the preserved native display_close path reported no cleanup error.
# KILL/crash/nonzero is explicitly not clean; cast-mode refuses Host/restart in that case.
printf 'DCV1 %s %s\n' "$native_pid" "$native_rc" > "$RUN/cast-cleanup.tmp"
mv "$RUN/cast-cleanup.tmp" "$RUN/cast-cleanup" || exit 1
[ "$native_rc" = 0 ] || exit "$native_rc"
"$SELF" --exec 4 "$SELF" --rmdir "$RUN/cast-guard" || exit 1
printf 'DCV1_NATIVE_EXIT pid=%s rc=%s\n' "$native_pid" "$native_rc"
exit "$native_rc"
