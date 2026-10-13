#!/bin/sh
# SLM entry: prepare only this module in existing tmpfs; never switch USB on boot.
set -eu
umask 077
BASE=/base/app/dip-instrument
RUN=/fs/tmpfs/dip-usb
[ -d /fs/tmpfs ] || { echo TMPFS_UNAVAILABLE >&2; exit 1; }
[ ! -L "$RUN" ] && [ ! -e "$RUN/lock" ] || exit 1
mkdir -p "$RUN"
exec > "$RUN/usb.log" 2>&1
echo DASHCAST_DCV1_STARTUP
for name in instrument-usb usb-mode.ksh; do
    [ -f "$BASE/$name" ] && [ ! -L "$BASE/$name" ] || exit 1
    cp "$BASE/$name" "$RUN/$name"
    chmod 700 "$RUN/$name"
done
# Enable video only after all dependencies copy successfully. USB control is independent.
if [ -f "$BASE/instrument-receiver" ] && [ -f "$BASE/run-cast.ksh" ] && [ -f "$BASE/cast-mode.ksh" ]; then
    if cp "$BASE/instrument-receiver" "$RUN/instrument-receiver" &&
       cp "$BASE/run-cast.ksh" "$RUN/run-cast.ksh" &&
       chmod 700 "$RUN/instrument-receiver" "$RUN/run-cast.ksh" &&
       cp "$BASE/cast-mode.ksh" "$RUN/cast-mode.ksh" && chmod 700 "$RUN/cast-mode.ksh"; then
        echo DCV1_FILES_READY
    else
        # This RAM entry is owned by the optional module; never remove a ROM/system file.
        rm -f "$RUN/cast-mode.ksh"
        echo DCV1_COPY_FAILED
    fi
fi
unset SOCK || :
exec "$RUN/instrument-usb" --serve "$RUN/usb-mode.ksh"
