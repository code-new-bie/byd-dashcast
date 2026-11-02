#!/bin/sh
# TEST FIXTURE ONLY. No native process or media operation is executed.
set -eu
printf 'cast %s\n' "$1" >> "$FAKE_DIR/trace"
if [ "$1" = stop ] && [ -f "$FAKE_DIR/cast-stop-failure" ]; then exit 1; fi
if [ "$1" = start ] && [ -f "$FAKE_DIR/cast-start-failure" ]; then exit 1; fi
exit 0
