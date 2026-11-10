#!/bin/sh
# TEST FIXTURE ONLY: stands in for the USB helper. Every QNX command is simulated with
# marker files in FAKE_DIR; no real process, USB or network operation happens.
set -eu
RUN=$FAKE_DIR
PLATFORM=$RUN/platform
DCD=$RUN/dcd/io-usb
NET=$RUN/net
HOST=$PLATFORM/dev/io-usb/devu-omap5-xhci.so

if [ "$1" = --tick ]; then
    if [ -f "$RUN/stop-pending" ]; then
        read -r pending remaining < "$RUN/stop-pending"
        remaining=$((remaining - 1))
        if [ "$remaining" -le 0 ]; then
            if [ "$pending" = net ]; then rm -f "$NET/dev/socket"; else rm -f "$DCD"; fi
            rm -f "$RUN/stop-pending"
        else
            printf '%s %s\n' "$pending" "$remaining" > "$RUN/stop-pending"
        fi
    fi
    if [ -f "$RUN/cleanup-pending" ]; then
        read -r remaining < "$RUN/cleanup-pending"
        remaining=$((remaining - 1))
        if [ "$remaining" -gt 0 ]; then
            printf '%s\n' "$remaining" > "$RUN/cleanup-pending"
        else
            : > "$RUN/process-state"
            printf 'DCV1 123 0\n' > "$RUN/cast-cleanup"
            /usr/bin/rmdir "$RUN/cast-guard"
            rm -f "$RUN/cleanup-pending"
        fi
    fi
    exit 0
fi

[ "$1:$2" = --bounded:4 ] || exit 90
shift 2
printf '%s\n' "$*" >> "$RUN/trace"
command=$1
shift
[ ! -f "$RUN/command-timeout" ] || exit 124
[ ! -f "$RUN/slow-command" ] || sleep 1

if [ "$command" = "$0" ]; then
    if [ "$1" = --rmdir ]; then
        case "$2" in "$RUN/role-operation"|"$RUN/cast-guard") ;; *) exit 91 ;; esac
        [ ! -f "$RUN/guard-release-failure" ] || exit 1
        # Simulates the helper's libc call; the target has no rmdir command.
        /usr/bin/rmdir "$2"
        exit $?
    fi
    [ "$1" = --usb-check ] || exit 91
    case "$2" in
        no-controller) [ ! -e "$HOST" ] ;;
        no-devices) [ -e "$HOST" ] && [ ! -f "$RUN/attached-storage" ] ;;
        controllers-up) [ -e "$HOST" ] && [ ! -e "$DCD" ] && [ ! -e "$NET/dev/socket" ] ;;
        *) exit 92 ;;
    esac
    exit $?
fi

case "${command##*/}" in
    pidin)
        [ ! -f "$RUN/pidin-failure" ] || exit 2
        if [ -f "$RUN/usb-world" ]; then
            echo '10 /sbin/io-usb -d omap5-xhci ioport=0x48890000,irq=108,ioport=0x488d0000,irq=110'
            [ ! -e "$DCD" ] || echo "20 /sbin/io-usb-dcd -n $DCD -d usbncm-omap543x-dwcotg3 ioport=0x488d0000,irq=110,linkdown"
            [ ! -e "$NET/dev/socket" ] || echo "30 /sbin/io-pkt-v6-hc -i7 -d usbdnet protocol=ncm,path=$DCD,mac=020000000701,usbdnet_mac=020000000702,wait=2 -ptcpip prefix=$NET"
            [ ! -f "$RUN/foreign-stack" ] || echo '40 /sbin/usb-service --serve'
        fi
        [ ! -f "$RUN/process-state" ] || cat "$RUN/process-state"
        ;;
    netstat)
        [ ! -f "$RUN/netstat-failure" ] || exit 2
        cat "$RUN/listener-state"
        ;;
    ifconfig)
        [ "${SOCK:-}" = "$NET" ] || exit 93
        [ ! -f "$RUN/ifconfig-failure" ] || exit 2
        if [ "$1" = -a ]; then
            echo 'lo0: flags=8049<UP,LOOPBACK,RUNNING> mtu 33192'
            [ -f "$RUN/interface-present" ] || exit 0
            set -- ncm0
        fi
        [ "$1" = ncm0 ] && [ -f "$RUN/interface-present" ] || exit 3
        case "${2:-}" in
            '')
                flags=BROADCAST,RUNNING
                [ ! -f "$RUN/interface-up" ] || flags=UP,BROADCAST,RUNNING
                echo "ncm0: flags=8843<$flags> mtu 1500"
                echo '    inet 192.168.77.1 netmask 0xfffffffc broadcast 192.168.77.3'
                ;;
            down) rm -f "$RUN/interface-up" ;;
            destroy)
                if [ -f "$RUN/physical-peer" ]; then
                    # The cable stays attached; destruction needs the host to have released it.
                    [ -f "$RUN/android-alt0-released" ] && [ ! -f "$RUN/link-up" ] || exit 4
                fi
                if [ -f "$RUN/destroy-failures" ]; then
                    read -r remaining < "$RUN/destroy-failures"
                    if [ "$remaining" -gt 0 ]; then
                        printf '%s\n' "$((remaining - 1))" > "$RUN/destroy-failures"
                        exit 5
                    fi
                fi
                rm -f "$RUN/interface-present" "$RUN/interface-up"
                ;;
            192.168.77.1)
                [ "$*" = 'ncm0 192.168.77.1 netmask 255.255.255.252 up' ] || exit 94
                printf '' > "$RUN/interface-up"
                ;;
            *) exit 95 ;;
        esac
        ;;
    ulink_ctrl)
        [ "$1:$2:$3:$4:$5" = "-s:$DCD:-w:2:-l" ] || exit 96
        case "$6" in
            0) rm -f "$RUN/link-up" ;;
            1) [ ! -f "$RUN/linkup-failure" ] || exit 6; printf '' > "$RUN/link-up" ;;
            *) exit 97 ;;
        esac
        ;;
    kill)
        [ "$1" = -TERM ] || exit 98
        case "$2" in
            30|20)
                if [ "$2" = 30 ]; then kind=net; else kind=dcd; fi
                if [ -f "$RUN/stop-delay" ]; then
                    read -r remaining < "$RUN/stop-delay"
                    printf '%s %s\n' "$kind" "$remaining" > "$RUN/stop-pending"
                elif [ "$kind" = net ]; then rm -f "$NET/dev/socket"; else rm -f "$DCD"; fi
                ;;
            123)
                [ ! -f "$RUN/deferred-cleanup" ] || exit 0
                : > "$RUN/process-state"
                [ ! -f "$RUN/missing-cleanup" ] || exit 0
                rc=0
                [ ! -f "$RUN/failed-cleanup" ] || rc=7
                printf 'DCV1 123 %s\n' "$rc" > "$RUN/cast-cleanup"
                /usr/bin/rmdir "$RUN/cast-guard"
                ;;
            *)
                # A receiver started by fake-receiver-live.sh.
                [ -f "$RUN/receiver-pid" ] && [ "$2" = "$(cat "$RUN/receiver-pid")" ] || exit 99
                : > "$RUN/process-state"
                : > "$RUN/listener-state"
                : > "$RUN/receiver-stop"
                ;;
        esac
        ;;
    mkdir)
        [ "$*" = "-p $RUN/dcd $NET" ] || exit 100
        mkdir -p "$RUN/dcd" "$NET/dev"
        ;;
    umount) [ "$1" = "$HOST" ] || exit 101; rm -f "$HOST" ;;
    mount)
        [ ! -e "$DCD" ] && [ ! -e "$NET/dev/socket" ] || exit 102
        printf '' > "$HOST"
        ;;
    io-usb-dcd) printf '' > "$DCD" ;;
    io-pkt-v6-hc)
        printf '' > "$NET/dev/socket"
        printf '' > "$RUN/interface-present"
        ;;
    *) exit 103 ;;
esac
exit 0
