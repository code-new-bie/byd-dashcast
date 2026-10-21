# Native background controller — implementation, not a flashable HMI adapter

2026-10-21 current 1.1.2: HMI binary and this local Thumb remain EXACTLY 1.1.1.
`analysis/navigation_layout_refine.py` changes only KZB coordinates and two owned
band subtrees. Each band keeps its 0/1 opacity anchor and uses 33 non-overlapping
native black-brush rectangles, a 32-step/64-pixel cosine feather, max alpha .94.
No new bitmap/shader/library, Screen layer or warning-parent suppression.
The 1.1.1 photo proves the layout applied but shows clipping and hard edges;
new top-row offsets are removed and bottom coordinates composed explicitly.
24 offline resource/native regression tests do NOT measure the real GPU cost
or certify physical layout/all dynamic alerts. See docs/PRODUCT_1.1.2.md.

2026-10-21 inherited 1.1.1 mechanism: `navigation-layout-local.thumb` and
`analysis/navigation_layout_fix.py` put the control and StateManager on the
same Content owner. The bridge reads back BOTH band opacities with the original
native optional-float getter before publishing NLC0/NLC1; NLC2 means pending.
Old NAV0/1 cannot satisfy this capability. The 1.1.0 photo showed its layout
did not apply; setter-only simulation was not a Kanzi-runtime test. New 19
offline tests include the original getter instructions and 1000 background
cycles, but the state engine is simulated. Actual layout and every dynamic
alert remain unverified; warning/car safety parents are not hidden. See
docs/PRODUCT_1.1.1.md in the public source tree. MCU/OS/receiver/role unchanged.

2026-10-21 historical 1.1.0: `navigation-layout.thumb` is appended to the exact
`background-unlimited.thumb` by `analysis/navigation_layout.py`. It sets one
private inherited int on the render thread; KZB state groups apply/retract the
native navigation layout. Two initially invisible owned contrast bands are
required before NAV0/NAV1 status is published (word29, inside the HMI seqlock).
OEM numeric/alert resources, MCU/OS/receiver/role script remain unchanged.
The explicit HMI/KZB changes and hardware limits are in docs/PRODUCT_1.1.0.md
in the public source tree. The historical claims below describe their versions.

2026-10-21 current adapter: `background-unlimited.thumb` (1.0.1) publishes
RWN2 and word30=0 for the matching DCV1/3 receiver. It retains the owned renewable
five-second expiry; zero means no product total budget, NOT no failure recovery.
Only two capability-publication instructions differ from the frozen 1.0 adapter.
The MCU/OS/KZB/foreground and USB role script are unchanged. These adapters are
separate from the portable C99 implementation below. ARM/Thumb/API-mocked tests
are not target QNX loading, physical composition or road-safety certification.
`background-product.thumb`/RWN1/7200 remains a frozen 1.0 source, not current policy.

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
