# Native background controller — implementation, not a flashable HMI adapter

2026-10-20: `background-poc.thumb` adds a separate, exact-version, five-second
render-time PoC. It does not compile/link the C99 controller below, enable
fullscreen, or certify vehicle safety. It defaults to native drawing, accepts
only an explicit bounded request through the matching receiver, validates the
background scene structure and restores via an HMI-owned deadline. Firmware
builds must use the matching overlay/startup variant, not the old App packer.

The C99 controller implements the ownership, preparation, output-freshness,
lease and restoration part of native-foreground/fullscreen composition. It has
no Kanzi dependency, no file/network access, no allocation and no worker thread.
**It is not linked into the instrument HMI or receiver. It does not enable
fullscreen, change a firmware image, or establish that a display is safe.**

## Implemented behavior

- Cold start is normal/native background. PREPARE does not hide anything.
- COMMIT requires all readiness bits, the same scene epoch and fresh actual
  output evidence. Submission ACKs, network heartbeats and cached timestamps
  are not substitutes for output evidence.
- Receiver identity, HMI boot epoch and increasing session generation must all
  match. An old STOP/renew cannot affect a later session, including PID reuse.
- Preparation expires at 500 ms; active control lease expires at 1000 ms.
  Output age must stay below 250 ms. These are **unvalidated PoC budgets**.
- `dc_bg_tick` on every HMI frame restores on expiry, missing output, loss of
  readiness, unsupported page or scene reload, even without a new IPC request.
- Restoration first requests withdrawal of this session's video, then restores
  the latest normal background even if withdrawal fails. Either failure blocks
  a new session; only explicit owner recovery can unblock it.
- A failed suppression may have partially applied: suppression remains unknown
  until restoration is confirmed. Clock rollback/overflow and generation
  exhaustion fail closed. Reentrant callbacks are rejected.

```text
Native --prepare--> Preparing --fresh output + ready + commit--> Active
  ^                     |                                       |
  |                     +------ failure/expiry ------------------+
  |                                                             |
  +-- restore confirmed <-- Restoring <-- stop/output loss/expiry-+
                              |
                           unconfirmed
                              v
                            Blocked --explicit owner recovery--> Native
```

## Port boundary: still to implement against the real HMI

`DcBgPort` is our internal interface, **not an existing Kanzi or QNX interface**.
It deliberately has no fake production implementation. A usable instrument
adapter must implement every callback:

| Callback | Required real behavior |
|---|---|
| `onMainThread` | Verify the actual HMI rendering/main thread; never assume an IPC worker is that thread. |
| `inspect` | Resolve current, version-matched scene bindings; verify foreground retention, actual alpha, effective composition and supported page. Unknown is not ready. |
| `observeOutput` | Observe this session's actual video output/post serial and original monotonic observation time. Never timestamp an old FRAME ACK as new output. |
| `suppressBackground` | Apply an epoch-checked, decoration-only rendering policy. Do not change whole-window alpha, common brushes or the normal theme/business model. |
| `restoreBackground` | Disable that policy against the CURRENT scene, retaining latest theme/page updates; do not dereference stale scene objects or restore a historical theme snapshot. |
| `withdrawVideo` | Hide only the identified session's video, or verify its absence; never hide/kill the HMI or shared graphics/media services. |

The bridge must be nonblocking and serialize calls on the HMI thread. The
controller is **not a thread-safe IPC queue**; the reentry guard is not a lock.
Only the bridge supplies trusted local peer identity and a real monotonic clock,
not values from an APK payload. The HMI epoch must change on each HMI instance.
Call init once on fresh storage, never to reset a live/blocked controller.
If the rendering thread itself stops, this controller cannot execute recovery;
it is not a replacement for the original process supervisor or a safety system.

No lease/session token is a video activation or device-binding mechanism.
No transport, USB, MCU, OS, foreground data source or native warning logic changes
are introduced by these files.

## Host tests

From the repository root, with an existing C99 compiler and Python 3:

```bash
python3 tools/test_native_background.py --output build/native-background
```

Use a NEW output directory for each run. The default enables AddressSanitizer
and UndefinedBehaviorSanitizer; `--no-sanitize` is available for compilers without
those facilities and is recorded in the report. Tests use an explicitly fake
render port. **Host compilation/test success is not QNX compilation, HMI
integration, background pixel verification or instrument/vehicle validation.**

The matched Kanzi/QNX build and main-thread bridge, receiver coordination,
fullscreen profile and physical alpha/plane validation are still outstanding.
Do not package this source into firmware and label it a working fullscreen build.
