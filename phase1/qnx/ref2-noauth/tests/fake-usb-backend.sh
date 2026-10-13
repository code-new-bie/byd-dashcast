#!/bin/sh
# TEST FIXTURE ONLY: every QNX command is simulated in FAKE_DIR; no real USB/kill.
set -eu
RUN=$FAKE_DIR
PLATFORM=$RUN/platform
DCD=$RUN/dcd/io-usb
NET=$RUN/net
HOST=$PLATFORM/dev/io-usb/devu-omap5-xhci.so
if [ "$1" = --pause ]; then
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
    exit 0
fi
[ "$1:$2" = --exec:4 ] || exit 90
shift 2
printf '%s\n' "$*" >> "$RUN/trace"
command=$1
shift
[ ! -f "$RUN/command-timeout" ] || exit 124
case "${command##*/}" in
    pidin)
        [ ! -f "$RUN/pidin-failure" ] || exit 2
        echo '10 /sbin/io-usb -d omap5-xhci ioport=0x48890000,irq=108,ioport=0x488d0000,irq=110'
        [ ! -e "$DCD" ] || echo "20 /sbin/io-usb-dcd -n $DCD -d usbncm-omap543x-dwcotg3 ioport=0x488d0000,irq=110,linkdown"
        [ ! -e "$NET/dev/socket" ] || echo "30 /sbin/io-pkt-v6-hc -i7 -d usbdnet protocol=ncm,path=$DCD,mac=020000000701,usbdnet_mac=020000000702,wait=2 -ptcpip prefix=$NET"
        [ ! -f "$RUN/foreign-stack" ] || echo '40 /sbin/usb-service --serve'
        ;;
    instrument-usb)
        if [ "$1" = --rmdir ]; then
            [ "$2" = "$RUN/role-operation" ] || exit 91
            [ ! -f "$RUN/guard-release-failure" ] || exit 1
            /usr/bin/rmdir "$2"
            exit $?
        fi
        [ "$1" = --probe ] || exit 91
        case "$2" in
            host-none) [ ! -e "$HOST" ] ;;
            host-empty) [ -e "$HOST" ] && [ ! -f "$RUN/attached-storage" ] ;;
            host-ready) [ -e "$HOST" ] && [ ! -e "$DCD" ] && [ ! -e "$NET/dev/socket" ] ;;
            *) exit 92 ;;
        esac
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
                [ ! -f "$RUN/physical-peer" ] || exit 4
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
        case "$2" in 30) kind=net ;; 20) kind=dcd ;; *) exit 99 ;; esac
        if [ -f "$RUN/stop-delay" ]; then
            read -r remaining < "$RUN/stop-delay"
            printf '%s %s\n' "$kind" "$remaining" > "$RUN/stop-pending"
        elif [ "$kind" = net ]; then rm -f "$NET/dev/socket"; else rm -f "$DCD"; fi
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
