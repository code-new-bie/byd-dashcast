#!/bin/sh
# Reference-2 private USB backend, with bounded/idempotent role recovery fixes.
# No persistent mode, MCU changes, automatic boot switch, or U-disk log collector.
set -f
umask 077
MODE=$1
SELF=$2
RUN=/fs/tmpfs/dip-usb
DCD=$RUN/dcd/io-usb
NET=$RUN/net
HOST=/dev/io-usb/devu-omap5-xhci.so
HOSTARGS='-d omap5-xhci ioport=0x48890000,irq=108,ioport=0x488d0000,irq=110'
DCDARGS="-n $DCD -d usbncm-omap543x-dwcotg3 ioport=0x488d0000,irq=110,linkdown"
NETARGS="-i7 -d usbdnet protocol=ncm,path=$DCD,mac=020000000701,usbdnet_mac=020000000702,wait=2 -ptcpip prefix=$NET"
case "$MODE:$SELF" in host:/*|ncm:/*|status:/*) ;; *) exit 2 ;; esac
COMMAND_TIMEOUT=0
run() {
    [ "$COMMAND_TIMEOUT" = 0 ] || return 124
    "$SELF" --exec 4 "$@"
    command_rc=$?
    if [ "$command_rc" = 124 ]; then
        COMMAND_TIMEOUT=1
        echo USB_BACKEND_COMMAND_TIMEOUT >&2
    fi
    return "$command_rc"
}
probe() { run "$SELF" --probe "$1"; }
fail() { echo "$1" >&2; return 1; }
finish_operation() {
    operation_rc=$?
    trap - 0
    if [ "$COMMAND_TIMEOUT" = 1 ]; then
        echo USB_OPERATION_QUARANTINED_RESTART_REQUIRED >&2
        exit 124
    else
        # No rmdir executable exists in the target OS/IFS. The native helper
        # calls existing libc rmdir for this exact, owned EMPTY directory only.
        run "$SELF" --rmdir "$RUN/role-operation" || {
            echo USB_OPERATION_GUARD_RELEASE_FAILED >&2
            exit 1
        }
    fi
    exit "$operation_rc"
}
# Role changes are serialized by the native receiver. This additional owned RAM
# guard survives a timed-out helper/outer shell: never overlap an unknown child.
# It is not a diagnostic/log gate and it is reset only by normal tmpfs reboot.
if [ "$MODE" != status ]; then
    [ ! -L "$RUN/role-operation" ] && mkdir "$RUN/role-operation" || {
        fail USB_OPERATION_BUSY_OR_UNCONFIRMED
        exit 1
    }
    trap 'finish_operation' 0
    trap 'COMMAND_TIMEOUT=1; exit 124' 1 2 15
fi
scan() {
    HPID= DP= NP= CONFLICT=0
    run /bin/pidin ar > "$RUN/processes" || return 1
    while read -r pid program args; do
        case "$pid" in ''|*[!0-9]*) continue ;; esac
        case "${program##*/}" in
            io-usb)
                [ "$args" = "$HOSTARGS" ] && [ -z "$HPID" ] || { CONFLICT=1; continue; }
                HPID=$pid ;;
            io-usb-dcd)
                [ "$args" = "$DCDARGS" ] && [ -z "$DP" ] || { CONFLICT=1; continue; }
                DP=$pid ;;
            io-pkt-v6-hc)
                if [ "$args" = "$NETARGS" ]; then
                    [ -z "$NP" ] || { CONFLICT=1; continue; }
                    NP=$pid
                else
                    case " $args " in *' -i7 '*|*"$DCD"*|*"$NET"*) CONFLICT=1 ;; esac
                fi ;;
            io-usb-otg|usb-service) CONFLICT=1 ;;
        esac
    done < "$RUN/processes"
    [ "$CONFLICT" = 0 ] && [ -n "$HPID" ] || fail USB_STACK_CONFLICT
}
wait_node() {
    node_attempt=0
    while [ "$node_attempt" -lt 60 ]; do
        [ -e "$1" ] && return 0
        "$SELF" --pause
        node_attempt=$((node_attempt + 1))
    done
    return 1
}
stop_owned() {
    kind=$1
    scan || return 1
    if [ "$kind" = net ]; then victim=$NP; else victim=$DP; fi
    [ -n "$victim" ] || return 0
    run /bin/kill -TERM "$victim" || return 1
    stop_attempt=0
    while [ "$stop_attempt" -lt 60 ]; do
        scan || return 1
        if [ "$kind" = net ]; then current=$NP; else current=$DP; fi
        [ -z "$current" ] && return 0
        [ "$current" = "$victim" ] || return 1
        "$SELF" --pause
        stop_attempt=$((stop_attempt + 1))
    done
    fail USB_STOP_UNCONFIRMED
}
interface_present() {
    # A failed ifconfig is UNKNOWN, never proof that an interface disappeared.
    run /sbin/ifconfig -a > "$RUN/interfaces" || return 2
    while read -r line; do
        case "$line" in ncm0:*) return 0 ;; esac
    done < "$RUN/interfaces"
    return 1
}
remove_interface() {
    SOCK=$NET; export SOCK
    interface_present
    interface_rc=$?
    case "$interface_rc" in
        1) unset SOCK; echo NCM_INTERFACE_ALREADY_REMOVED; return 0 ;;
        0) : ;;
        *) unset SOCK; fail NCM_INTERFACE_UNKNOWN; return 1 ;;
    esac
    run /sbin/ifconfig ncm0 down || { unset SOCK; return 1; }
    # devnp-usbdnet refuses destruction while its USB disconnect is pending.
    # Keep the real unplug prerequisite; retry only this idempotent private
    # interface removal, without KILL or stopping shared multimedia/USB services.
    destroy_attempt=0
    while [ "$destroy_attempt" -lt 3 ]; do
        run /sbin/ifconfig ncm0 destroy
        destroy_rc=$?
        interface_present
        interface_rc=$?
        case "$interface_rc" in
            1) unset SOCK; echo NCM_INTERFACE_REMOVED; return 0 ;;
            0) : ;;
            *) unset SOCK; fail NCM_INTERFACE_UNKNOWN; return 1 ;;
        esac
        [ "$destroy_rc" != 0 ] || { unset SOCK; fail NCM_INTERFACE_STILL_PRESENT; return 1; }
        "$SELF" --pause
        "$SELF" --pause
        destroy_attempt=$((destroy_attempt + 1))
    done
    unset SOCK
    fail NCM_DESTROY_UNCONFIRMED
}
host() {
    scan || return 1
    if [ -x "$RUN/cast-mode.ksh" ]; then
        "$RUN/cast-mode.ksh" stop "$SELF" || { fail HOST_VIDEO_STOP_UNCONFIRMED; return 1; }
    fi
    if [ -n "$DP" ]; then
        [ ! -L "$RUN/ncm-connected" ] || return 1
        rm -f "$RUN/ncm-connected" || return 1
        run /sbin/ulink_ctrl -s "$DCD" -w 2 -l 0 || { fail HOST_LINK_DOWN_FAILED; return 1; }
        # Allow asynchronous DCD/class-driver disconnect notifications to drain.
        for settle in 1 2 3 4 5 6; do "$SELF" --pause; done
    fi
    if [ -n "$NP" ]; then
        remove_interface || { fail HOST_NET_DETACH_FAILED; return 1; }
    fi
    stop_owned net && stop_owned dcd || { fail HOST_PRIVATE_STOP_FAILED; return 1; }
    [ ! -e "$DCD" ] && [ ! -e "$NET/dev/socket" ] || { fail HOST_PRIVATE_NODES_REMAIN; return 1; }
    if [ ! -e "$HOST" ]; then
        probe host-none || return 1
        run /bin/mount -Tio-usb -o ioport=0x48890000,irq=108,ioport=0x488d0000,irq=110 /lib/dll/devu-omap5-xhci.so /dev/io-usb/io-usb || return 1
        wait_node "$HOST" || return 1
    fi
    probe host-ready && scan || return 1
    [ -z "$DP" ] && [ -z "$NP" ] || return 1
    echo HOST_STACK_READY
}
network_ready() {
    SOCK=$NET; export SOCK
    run /sbin/ifconfig ncm0 > "$RUN/interface" || { unset SOCK; return 1; }
    unset SOCK
    FOUND=0
    UP=0
    while read -r line; do
        case "$line" in *'inet 192.168.77.1 '*'netmask 0xfffffffc'*) FOUND=1 ;; esac
        case "$line" in ncm0:*'<'*'UP,'*|ncm0:*'<UP>'*) UP=1 ;; esac
    done < "$RUN/interface"
    [ "$FOUND" = 1 ] && [ "$UP" = 1 ]
}
connect_ncm() {
    SOCK=$NET; export SOCK
    run /sbin/ifconfig ncm0 192.168.77.1 netmask 255.255.255.252 up || { unset SOCK; return 1; }
    unset SOCK
    network_ready || return 1
    run /sbin/ulink_ctrl -s "$DCD" -w 2 -l 1 || return 1
    probe host-none && scan && [ -n "$DP" ] && [ -n "$NP" ] || return 1
    [ ! -L "$RUN/ncm-connected" ] || return 1
    printf 'NCM_SOFT_CONNECTED\n' > "$RUN/ncm-connected" || return 1
    # This confirms stack setup/soft-connect only, NOT peer enumeration or video.
    echo NCM_STACK_READY
}
ncm() {
    scan || return 1
    if [ -n "$DP" ] && [ -n "$NP" ] && [ ! -e "$HOST" ] && [ -e "$DCD" ]; then
        # A duplicate request must not soft-connect/reset an existing live
        # gadget. The RAM marker records OUR last successful link-up, not USB
        # peer enumeration or video readiness; Host invalidates it before down.
        connected_tag= connected_extra=
        if [ ! -L "$RUN/ncm-connected" ] && [ -f "$RUN/ncm-connected" ]; then
            read -r connected_tag connected_extra < "$RUN/ncm-connected"
        fi
        if [ "$connected_tag" = NCM_SOFT_CONNECTED ] && [ -z "$connected_extra" ] && network_ready; then
            probe host-none || return 1
            echo NCM_ALREADY_CONFIGURED_NO_RECONNECT
            return 0
        fi
        # Even an existing stack may have been left DOWN by a failed Host request.
        # Never return solely because the old IP address is still present.
        probe host-none && connect_ncm
        return $?
    fi
    [ -z "$DP" ] && [ -z "$NP" ] && [ -e "$HOST" ] || return 1
    [ ! -e "$DCD" ] && [ ! -e "$NET/dev/socket" ] || return 1
    for file in /sbin/io-usb-dcd /sbin/io-pkt-v6-hc /sbin/ulink_ctrl /sbin/ifconfig /lib/dll/devu-usbncm-omap543x-dwcotg3.so /lib/dll/devnp-usbdnet.so; do
        [ -e "$file" ] || return 1
    done
    probe host-empty || fail USB_REMOVE_ALL_DEVICES || return 1
    run /bin/mkdir -p "$RUN/dcd" "$NET" || return 1
    run /bin/umount "$HOST" || return 1
    [ ! -e "$HOST" ] && probe host-none || return 1
    run /sbin/io-usb-dcd $DCDARGS || return 1
    wait_node "$DCD" && scan && [ -n "$DP" ] || return 1
    run /sbin/io-pkt-v6-hc $NETARGS || return 1
    wait_node "$NET/dev/socket" && scan && [ -n "$NP" ] || return 1
    connect_ncm
}
if [ "$MODE" = status ]; then
    scan || exit 1
    if [ -e "$HOST" ] && [ -z "$DP" ] && [ -z "$NP" ]; then
        probe host-ready || exit 1
        echo HOST_STACK_READY
    elif [ ! -e "$HOST" ] && [ -n "$DP" ] && [ -n "$NP" ] && [ -e "$DCD" ]; then
        probe host-none && network_ready || exit 1
        # Status cannot prove soft-connect or a physically enumerated peer.
        echo NCM_NETWORK_UP_PEER_UNKNOWN
    else
        fail USB_STATUS_UNKNOWN
        exit 1
    fi
elif [ "$MODE" = host ]; then
    host || { fail HOST_FAILED; exit 1; }
else
    ncm || {
        echo NCM_FAILED >&2
        [ "$COMMAND_TIMEOUT" = 0 ] || exit 124
        host || { fail HOST_RECOVERY_UNCONFIRMED; exit 3; }
        exit 1
    }
    if [ -x "$RUN/cast-mode.ksh" ]; then
        "$RUN/cast-mode.ksh" start "$SELF" || echo CAST_UNAVAILABLE >&2
    fi
fi
