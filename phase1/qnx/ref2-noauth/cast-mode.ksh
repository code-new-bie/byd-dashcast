#!/bin/sh
# Own-process lifecycle; a vanished PID alone NEVER confirms window/media cleanup.
set -f
umask 077
MODE=$1
SELF=$2
RUN=/fs/tmpfs/dip-usb
PROGRAM=$RUN/instrument-receiver
ARGS='--serve 192.168.77.1 31707 --peer 192.168.77.2'
case "$MODE:$SELF" in start:/*|stop:/*) ;; *) exit 2 ;; esac
run() { "$SELF" --exec 4 "$@"; }
safe_files() {
    for name in cast-owner cast-cleanup cast-cleanup.tmp cast-guard cast-processes cast-listeners; do
        [ ! -L "$RUN/$name" ] || return 1
    done
}
scan() {
    PID=
    run /bin/pidin ar > "$RUN/cast-processes" || return 1
    while read -r pid program args; do
        case "$pid" in ''|*[!0-9]*) continue ;; esac
        [ "${program##*/}" = instrument-receiver ] || continue
        [ "$program" = "$PROGRAM" ] && [ "$args" = "$ARGS" ] && [ -z "$PID" ] || return 1
        PID=$pid
    done < "$RUN/cast-processes"
}
owner() {
    OWNER=
    [ -f "$RUN/cast-owner" ] || return 1
    read -r tag OWNER extra < "$RUN/cast-owner" || return 1
    [ "$tag" = DCV1 ] && [ -z "$extra" ] || return 1
    case "$OWNER" in ''|0|*[!0-9]*) return 1 ;; esac
}
clean() {
    [ ! -e "$RUN/cast-guard" ] && owner && [ -f "$RUN/cast-cleanup" ] || return 1
    read -r tag done_pid done_rc extra < "$RUN/cast-cleanup" || return 1
    [ "$tag" = DCV1 ] && [ "$done_pid" = "$OWNER" ] && [ "$done_rc" = 0 ] && [ -z "$extra" ]
}
listening() {
    run /usr/bin/netstat -an > "$RUN/cast-listeners" || return 2
    while read -r line; do
        case "$line" in
            *'192.168.77.1.31707 '*'LISTEN'*|*'192.168.77.1:31707 '*'LISTEN'*) return 0 ;;
        esac
    done < "$RUN/cast-listeners"
    return 1
}
# --pause is 50ms. Startup remains 4s; owned cleanup may take up to 15s because
# context destruction stops hardware playback. Never bypass cleanup on timeout.
WAIT_LIMIT=80
[ "$MODE" != stop ] || WAIT_LIMIT=300
wait_step() {
    wait_count=$((wait_count + 1))
    [ "$wait_count" -le "$WAIT_LIMIT" ] || return 1
    "$SELF" --pause
}
safe_files && scan || exit 1
if [ "$MODE" = stop ]; then
    if [ -z "$PID" ]; then
        if [ ! -e "$RUN/cast-owner" ] && [ ! -e "$RUN/cast-guard" ]; then exit 0; fi
        clean && { echo DCV1_CLEANUP_REPORTED; exit 0; }
        echo DCV1_CLEANUP_UNCONFIRMED >&2
        exit 1
    fi
    owner && [ "$OWNER" = "$PID" ] || exit 1
    victim=$PID
    run /bin/kill -TERM "$victim" || exit 1
    wait_count=0
    while wait_step; do
        scan || exit 1
        [ -z "$PID" ] && clean && { echo DCV1_CLEANUP_REPORTED; exit 0; }
        [ -z "$PID" ] || [ "$PID" = "$victim" ] || exit 1
    done
    echo DCV1_STOP_UNCONFIRMED >&2
    exit 1
fi
[ -e "$RUN/net/dev/socket" ] && [ -x "$PROGRAM" ] && [ -x "$RUN/run-cast.ksh" ] || exit 1
SOCK=$RUN/net; export SOCK
if [ -n "$PID" ]; then
    owner && [ "$OWNER" = "$PID" ] && listening && { echo DCV1_LISTENING_NOT_RENDERED; exit 0; }
    exit 1
fi
[ ! -e "$RUN/cast-guard" ] || exit 1
if [ -e "$RUN/cast-owner" ]; then
    clean || { echo DCV1_PRIOR_CLEANUP_UNCONFIRMED >&2; exit 1; }
fi
listening
listener_rc=$?
case "$listener_rc" in
    0) echo DCV1_PORT_CONFLICT >&2; exit 1 ;;
    1) : ;;
    *) echo DCV1_LISTENER_UNKNOWN >&2; exit 1 ;;
esac
# Remove only our validated previous RAM records, never files outside RUN.
rm -f "$RUN/cast-owner" "$RUN/cast-cleanup" "$RUN/cast-cleanup.tmp"
(trap '' HUP; exec /bin/sh "$RUN/run-cast.ksh") </dev/null > "$RUN/cast.log" 2>&1 &
wait_count=0
while wait_step; do
    scan || exit 1
    if [ -n "$PID" ]; then
        owner && [ "$OWNER" = "$PID" ] && listening && { echo DCV1_LISTENING_NOT_RENDERED; exit 0; }
    fi
done
echo DCV1_START_UNCONFIRMED >&2
exit 1
