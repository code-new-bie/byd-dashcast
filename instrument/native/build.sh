#!/bin/bash
# Builds the instrument programs for QNX 6.5 ARM with plain clang and lld.
# Usage: build.sh CLANG_USR_DIR OUTPUT_DIR NOTICE_GLYPHS_H
# The notice lettering header is made once by notice_glyphs.py from your own firmware font.
# The system's libraries are not needed at build time: a stub library names what is imported.
set -e
USR=$(realpath "$1")
OUT=$2
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
cp "$3" "$OUT/notice_glyphs.h"
export LD_LIBRARY_PATH=$USR/lib
CC="$USR/bin/clang --target=armv7a-unknown-none-eabi -mfloat-abi=soft"
CFLAGS="-mthumb -Oz -ffreestanding -fno-builtin -fno-pic -fno-stack-protector -fno-unwind-tables \
  -fno-asynchronous-unwind-tables -ffunction-sections -fdata-sections -Wall -Wextra -Werror ${DASHCAST_EXTRA_CFLAGS:-}"
IMPORTS="_init_libc exit _exit __get_errno_ptr open close read write pipe fcntl poll mkdir rmdir mkfifo unlink realpath fork execv
  waitpid kill signal nanosleep clock_gettime TimerTimeout alarm getpid dlopen dlsym dlclose memset memchr memcmp strlen strcmp strncmp strtoul snprintf
  usleep malloc free memcpy lseek mmap munmap name_open name_close MsgSendvs_r dup2 access readlink setenv strchr strrchr strstr umask rename unsetenv"
SOCKET_IMPORTS="socket setsockopt bind listen accept recv send inet_pton"
{
  echo '.syntax unified'; echo '.text'
  for name in $IMPORTS; do echo ".global $name"; echo ".type $name, %function"; echo "$name: bx lr"; done
} > "$OUT/libc_stub.s"
$CC -c "$OUT/libc_stub.s" -o "$OUT/libc_stub.o"
"$USR/bin/ld.lld" -shared -soname libc.so.3 --hash-style=sysv -o "$OUT/libc.so.3" "$OUT/libc_stub.o"
{
  echo '.syntax unified'; echo '.text'
  for name in $SOCKET_IMPORTS; do echo ".global $name"; echo ".type $name, %function"; echo "$name: bx lr"; done
} > "$OUT/socket_stub.s"
$CC -c "$OUT/socket_stub.s" -o "$OUT/socket_stub.o"
"$USR/bin/ld.lld" -shared -soname libsocket.so.3 --hash-style=sysv -o "$OUT/libsocket.so.3" "$OUT/socket_stub.o"
$CC -c "$HERE/crt0.S" -o "$OUT/crt0.o"
$CC $CFLAGS -I"$OUT" -c "$HERE/dashcast_usb.c" -o "$OUT/dashcast_usb.o"
$CC $CFLAGS -c "$HERE/dashcast_receiver.c" -o "$OUT/dashcast_receiver.o"
# link OUTPUT EXTRA_FLAGS OBJECT LIBRARIES...
link() {
  "$USR/bin/ld.lld" -o "$1" --dynamic-linker=/usr/lib/ldqnx.so.2 --hash-style=sysv --image-base=0x100000 \
    -z norelro -z noseparate-code -z nognustack -z max-page-size=4096 --no-rosegment --gc-sections $2 \
    --no-eh-frame-hdr --build-id=none -e _start "$OUT/crt0.o" "$3" "${@:4}"
}
# The .sym copy keeps its symbol table for offline checks; same code, never shipped.
link "$OUT/dashcast-usb.sym" "" "$OUT/dashcast_usb.o" "$OUT/libc.so.3"
link "$OUT/dashcast-usb" --strip-all "$OUT/dashcast_usb.o" "$OUT/libc.so.3"
link "$OUT/dashcast-receiver.sym" "" "$OUT/dashcast_receiver.o" "$OUT/libsocket.so.3" "$OUT/libc.so.3"
link "$OUT/dashcast-receiver" --strip-all "$OUT/dashcast_receiver.o" "$OUT/libsocket.so.3" "$OUT/libc.so.3"
# Same ELF flags as the programs already running on the instrument.
python3 - "$OUT/dashcast-usb" "$OUT/dashcast-receiver" <<'PY'
import struct, sys
for name in sys.argv[1:]:
    data = bytearray(open(name, 'rb').read())
    assert struct.unpack_from('<I', data, 36)[0] == 0x5000200
    struct.pack_into('<I', data, 36, 0x5000202)
    open(name, 'wb').write(data)
PY
ls -l "$OUT/dashcast-usb" "$OUT/dashcast-receiver"
