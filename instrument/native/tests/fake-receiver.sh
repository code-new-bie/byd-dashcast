#!/bin/sh
# TEST FIXTURE ONLY: a receiver whose behaviour is chosen by marker files in FAKE_DIR.
#   early-exit  exit at once with the status in this file, before reporting ready
#   no-ready    stay running without ever reporting ready
#   exit-code   status to exit with after TERM (default 0)
#   exit-delay  seconds to spend cleaning up after TERM
#   ignore-term never react to TERM
D=$FAKE_DIR
printf '%s\n' "$*" > "$D/receiver-args"
printf '%s\n' "${SOCK:-}" > "$D/receiver-sock"
printf '%s\n' "$$" >> "$D/receiver-pids"
echo FAKE_RECEIVER_OUTPUT
if [ -f "$D/early-exit" ]; then
    exit "$(cat "$D/early-exit")"
fi
stopped=0
if [ -f "$D/ignore-term" ]; then trap '' TERM; else trap 'stopped=1' TERM; fi
[ -f "$D/no-ready" ] || printf R >&3
exec 3>&-
while [ "$stopped" = 0 ]; do sleep 0.02; done
[ ! -f "$D/exit-delay" ] || sleep "$(cat "$D/exit-delay")"
code=0
[ ! -f "$D/exit-code" ] || code=$(cat "$D/exit-code")
printf '%s\n' "$$" >> "$D/receiver-exited"
exit "$code"
