#!/bin/sh
# TEST FIXTURE ONLY. Never invokes real kill/pidin/netstat/USB or any QNX program.
set -eu
case "$1" in
    --pause)
        if [ -f "$FAKE_DIR/cleanup-pending" ]; then
            read -r remaining < "$FAKE_DIR/cleanup-pending"
            remaining=$((remaining - 1))
            if [ "$remaining" -gt 0 ]; then
                printf '%s\n' "$remaining" > "$FAKE_DIR/cleanup-pending"
            else
                : > "$FAKE_DIR/process-state"
                printf 'DCV1 123 0\n' > "$FAKE_DIR/cast-cleanup"
                /usr/bin/rmdir "$FAKE_DIR/cast-guard"
                rm -f "$FAKE_DIR/cleanup-pending"
            fi
        fi
        exit 0 ;;
    --exec)
        [ "$2" = 4 ] || exit 90
        shift 2
        printf '%s\n' "$*" >> "$FAKE_DIR/trace"
        case "$1" in
            /bin/pidin) cat "$FAKE_DIR/process-state" ;;
            /usr/bin/netstat)
                [ ! -f "$FAKE_DIR/netstat-failure" ] || exit 2
                cat "$FAKE_DIR/listener-state" ;;
            /bin/kill)
                [ "$2:$3" = -TERM:123 ] || exit 91
                [ ! -f "$FAKE_DIR/deferred-cleanup" ] || exit 0
                printf '' > "$FAKE_DIR/process-state"
                if [ -f "$FAKE_DIR/missing-cleanup" ]; then exit 0; fi
                rc=0
                [ ! -f "$FAKE_DIR/failed-cleanup" ] || rc=7
                printf 'DCV1 123 %s\n' "$rc" > "$FAKE_DIR/cast-cleanup"
                /usr/bin/rmdir "$FAKE_DIR/cast-guard"
                ;;
            "$FAKE_DIR/instrument-usb")
                [ "$2:$3" = "--rmdir:$FAKE_DIR/cast-guard" ] || exit 94
                [ ! -f "$FAKE_DIR/guard-release-failure" ] || exit 1
                # Simulate libc rmdir, NOT the missing target shell command.
                /usr/bin/rmdir "$3"
                ;;
            *) exit 92 ;;
        esac
        ;;
    *) exit 93 ;;
esac
