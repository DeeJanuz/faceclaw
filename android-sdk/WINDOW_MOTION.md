# Building reliable animations

Start here for every new animated SDK application. This is the current integration
recipe; [ANIMATION_PATTERNS.md](ANIMATION_PATTERNS.md) contains engineering rationale
and historical T3 findings. No firmware changes or additional SDK API are required.
A fixed frame rate or optical presentation time is not guaranteed.

## Ownership

| Owner | Responsibility |
| --- | --- |
| Host | Credits, visibility gating, grant-to-grant pacing, retained pixels, sparse composition/packing, latest desired frame, BLE recovery and outcomes |
| SDK RenderSurface | Gray8 leases or Canvas conversion, credit callbacks, slot release and generations |
| SDK WindowMotion | Timer-free geometry, elapsed-time sampling, retargeting and body-reveal flag; no content or overlay state |
| Application | Desired state, authorized content, caches, overlay lifecycle, serialized access, final-state redraw and safe retries |

The geometry model alone does not prevent stale overlays, coalesce host snapshots,
or manage private content. The example hooks below are application helpers, not
new SDK lifecycle methods or wire events.

## Standard transition lifecycle

1. Update desired open/closed state on input. Ignore redundant input. Reverse from
   the last sampled rectangle, not a guessed future position. That rectangle is
   not necessarily already displayed on the glasses.
2. Prepare a lightweight heading and destination background. Release outgoing body
   pixels on close. Do not sort or lay out a list on every animation frame.
3. Invalidate once to request a credit. Do not start a fixed-rate animation timer.
4. Render on credit: sample once, paint, and submit even the terminal sample.
   Request another frame only while motion is active.
5. Retain the desired final state, not a completed motion object. Later invalidation
   or recovery must still be able to redraw settled content.
6. On hide/loss/removal, stop work and release unsafe caches. On return, redraw the
   authorized desired state using current geometry/generation, not old intermediate frames.

## Dense text translations

Keep the complete source and destination content prepared during a list or reader
transition. Do not replace either page with a loading/unloading label solely to
reduce animation work. Register printable characters with
`registerGlyph(fontKey, encoding, ...)`, attach their `DrawBatch` placements to
every authoritative Gray8 frame, and call `resources.prefetch(...)` while the
first frame is still at the settled source position. If the next animation has a
known complete cache set, `prefetchWorkingSet(...)` may replace an idle cache.

For each later translation frame, add a `retainedCopy(...)` for the overlap with
the last **successfully submitted** frame. Clear that baseline after rejection,
resize, generation change, session loss, or a different transition. The Gray8
target remains authoritative, so the host can repair exposed edges and any cache
miss. A horizontal delta `dx < 0`, for example, copies
`(-dx, 0, width + dx, height)` to `(0, 0)`; the newly exposed right strip is then
supplied by cached glyph draws or sparse raster repair.

A delayed render credit can otherwise jump across many new characters at once.
Bound translation progress by the amount of newly exposed content, then continue
requesting credits after the nominal duration until the final offset is reached.
`FrameAnimator.limitTranslationStep(previous, desired, maxStep)` implements the
integer pacing rule. Choose `maxStep` from measured glyph density; about 100-150
new glyph placements per submitted frame is a useful starting budget. This cap
may lengthen a delayed transition, but it avoids a single large resource/payload
burst and produces steadier visible motion.

### One clock domain

In Java, sample `request.credit.targetPresentationTimeNanos / 1_000_000L`.
Initialize the motion clock on the first render, after preparing lightweight
caches, not from an earlier idle credit. Never mix epoch milliseconds, elapsed
realtime milliseconds and nanoseconds. Never start a new motion at an expired target.

Bridges exposing epoch-millisecond targets must normalize to a current,
nondecreasing clock. T3's wall-clock floor fixes its idle-credit problem, but new
native SDK apps should use the monotonic credit clock directly.

### Submission and ownership

Canvas: paint a valid frame and call `request.requestNextFrame()` only if unfinished.
The adapter submits the canvas. Returning without painting does not skip submission;
paint settled content when no motion is active.

Gray8: paint through the current `FrameLease`; submit `FrameMetadata` with the
credit trace ID, valid damage and `requestNextFrame(!frame.done)`. Do not hold a
lease between samples. `BUFFER_RELEASED` permits slot reuse, not display confirmation.
Application scratch and phone-preview buffers need their own ownership discipline.

Start with full client damage for correctness: the host detects changed pixels.
Partial client damage needs a baseline correct after rejection. Include old/new
edges and newly exposed background. Never blindly retry an old lease after failure.

## Standard invalidation policy

| Event | Cache action | Motion action |
| --- | --- | --- |
| Identical host refresh | None | Suppress repaint notification |
| Relevant battery/weather/background change | Refresh affected destination backdrop | Keep clock and trajectory |
| Body content changes while opening | Drop body cache; rebuild from current model when needed | Keep motion |
| Outgoing content changes/revocation during closing | Erase body and sensitive heading immediately; use safe background and neutral outline | Keep safe geometry or settle safely |
| Explicit navigation/reversal | Drop obsolete content | Retarget from last sampled geometry |
| Hide, resize/generation change, session loss, removal | Release unsafe caches | Cancel/suspend; restore desired state on valid return |

Compare relevant values/versions before notifying listeners. Never route every
heartbeat through blanket cancellation. Conversely, do not classify notification
text changes, revocation or disconnect as ambient updates. Neutral continuation
must not retain stale text in bitmaps, glyph lists, preview buffers or draw batches.

## Standard overlay lifecycle

A queued preview is not a visible overlay. Gate notification previews on
`preview != null && notificationSurfaceActive`. Clear preview state on surface
close, disconnect and removal; reopening must not resurrect it. Real active
capture is independently an overlay and must still block conflicting transitions.
Use one authoritative overlay state or clear every mirror through one lifecycle function.

## Starter examples

- [AnimatedCardAppService.java](examples/AnimatedCardAppService.java): pooled Canvas,
  serialized state access, redundant-input handling, current-state redraw, cached
  body release and neutral dismissal.
- [animated-card.cjs](examples/animated-card.cjs): injectable timer-free controller.
  Submit its `render()` result through your SDK surface; make `invalidate()` request
  a credit. `ambientChanged()`, `invalidateContent()`, `cancel()` and `resume()`
  deliberately have different meanings.

The examples draw over black. Add your destination-background cache and overlay
ownership using this guide. The T3 reference is in sibling `faceclaw-t3-app`:
`app/extensions/host.ts`, `app/ui/shell/shell.ts`, `app/apps/t3/t3-app.ts` and their tests.

Current shared-model policy: 360 ms motion, opening body reveal at 90%, immediate
closing body removal. These are visual policies, not a promise of 60 Hz or a
particular number of transmitted frames. `FrameRequest` coalesces requests; it
does not authorize rendering without credits or implement BLE backpressure.

## New-application acceptance checklist

- [ ] Opening after idle does not finish on its first credit.
- [ ] Redundant open/close commands are no-ops; reversal is continuous.
- [ ] Ambient updates during open/close do not restart or cancel progress.
- [ ] Changed/revoked content is erased immediately, including cached headings.
- [ ] Pending/closed previews do not suppress window animations.
- [ ] Active capture still blocks conflicts; disconnect clears local overlay state.
- [ ] Delayed credits skip obsolete samples and submit the final state.
- [ ] Dense text prefetches its glyph working set before visible translation.
- [ ] Translation copy hints use the last accepted frame and reset on lifecycle changes.
- [ ] Catch-up frames cap newly exposed text and continue until the final offset.
- [ ] Settled content redraws after invalidation/recovery.
- [ ] Hidden surfaces stop work; resize/reconnect use current generations.
- [ ] Sparse output matches full composition, including newly revealed background.
- [ ] Overload and unavailable-surface retries remain bounded.
- [ ] Scratch/cache/preview ownership is safe across callbacks and executors.

## Verification and diagnostics

With JDK 17+ and Android API 35 configured, run from `android-sdk`:

```sh
bash scripts/check-animation-examples.sh
```

This local-only command runs the JavaScript and SDK unit tests and compiles the
Java starter against the actual SDK classes and Android API. It does not install,
publish, or change device state.

Example tests cover final redraw, invalidation during close, ambient updates,
reversal and lifecycle suspension. Host tests cover cadence/sparse composition;
T3 tests cover actual overlay integration. None proves optical smoothness.

Record bounded content-free events: `started`, `skipped` with reason, `invalidated`
with category/continuation policy, `cancelled` with reason, and `completed`. Associate
transition ID/direction and surface/generation with frame trace IDs where supported.
The full A6 schema remains guidance, not automatic SDK instrumentation. Never log
application text or pixels.

Before tuning pacing, distinguish skipped/cancelled motion, sparse submissions,
superseded frames and uneven transport feedback. Measure opening and closing
separately. ACKs are not optical-vsync measurements; average app FPS alone cannot
establish a firmware ceiling.
