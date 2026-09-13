# Window animation patterns

Engineering guidance for SDK 1.0 applications and the host. These patterns do not
add public APIs or promise a display refresh rate. Proposed instrumentation below
is a logging convention, not an already implemented SDK facility.

## A1 — Credit-paced, elapsed-time motion

Sample geometry from the current render credit's presentation time. Start the
motion after preparing its first lightweight frame. A newly started transition
must not inherit an expired credit timestamp. Keep one outstanding credit per
surface and retain the newest desired state, including the final state.

Host pacing is a grant-to-grant interval: subtract elapsed rendering and intake
work instead of adding the full interval after every submission. Do not replace
backpressure with an independent fixed-rate application timer. Firmware ACKs are
not optical presentation timestamps.

## A2 — Lightweight dismissal, stable background

Release the outgoing list/body raster at dismissal. Animate a heading/outline
over a prepared destination backdrop; do not sort or lay out the disappearing
list on every frame. Cache the backdrop once per transition and preserve stable
background coordinates. Reuse a surface-sized scratch raster only with explicit
ownership: preview consumers and pending submissions must not observe mutation.

When the card shrinks, restore newly exposed background strips and erase the old
outline. Full-raster copying can remain a correctness fallback. A retained
background plus a moving foreground node is a longer-term optimization, not a
reason to retain private outgoing content after revocation or content invalidation.

## A3 — Classify invalidation; do not cancel on every state message

| Event | Recommended behavior |
| --- | --- |
| Identical host snapshot or unrelated battery/clock refresh | Keep motion and caches; avoid repaint if no visible change |
| Visible destination data changes | Refresh affected background content without resetting progress |
| Outgoing sensitive data changes or is revoked | Discard stale content immediately; continue only with a safe neutral representation, or settle safely |
| Surface generation/geometry changes, session loss, explicit navigation | Cancel or retarget with a classified reason; reject stale submissions |

Do not weaken privacy invalidation to improve smoothness. Separate harmless host
state refreshes from source-content invalidation rather than treating all updates
as one `contentChanged` event. Coalesce equivalent updates on both sides of Binder.

## A4 — Preserve sparse damage through packing

Damage includes old and new moving bounds and newly exposed backdrop pixels.
Compare against retained pixels, mark actual changed 32x16 display tiles, and pack
their runs. Do not collapse distant outline edges into one large rectangle before
composition/packing. The downstream planner may choose its own wire rectangles.
Partial client damage requires an authoritative baseline and recovery after a
rejected frame; never assume an unsuccessful submission became retained content.

## A5 — Keep resource identity where clipping is correct

Baking cached glyphs/images into a transition raster removes their structured
draw identity. Preserve eligible stationary resource placements alongside baked
fallback pixels, but only when occlusion/clipping semantics match the raster.
Do not send an unclipped background glyph through an opaque moving card. If the
metadata format cannot express the required clipping, retain raster fallback or
use a supported clipped scene. Avoid a large all-at-once body reveal where possible.

## A6 — Diagnose transitions, not aggregate frame rates

Record a content-free transition ID and direction (`open`, `close`, `retarget`),
surface/generation, cause trace ID, frame ID, target sample time, and progress.
Record timestamps for credit request/grant, app paint start/end, submission,
host intake, composition, planning, transport enqueue/write, and terminal outcome.
Record changed tile count, changed area, allocations, payload/upload bytes,
fragment count, cache rebuild reason, and explicit motion cancellation reason.
Never log text, pixels, resource contents, backend payloads, or restoration tokens.

Measure separately:

- Submission spacing: sparse samples before transport indicate app/credit work.
- Submitted versus acknowledged frames: distinguish supersession from cancellation
  before submission and harmless identical-frame deduplication.
- Write/ACK spacing: measures transport feedback, not optical frame pacing.
- Cache rebuilds and invalidation reasons: distinguish expensive painting from a
  transition that was intentionally terminated early.

Check screen, selected window, and resulting screen before and after each ADB
gesture. Exclude failed gestures, sleeping displays, setup submenus, concurrent
physical input, and cold reconnects from steady-state comparisons. Compare
equivalent content before attributing differences to built-ins versus APKs.

## T3 closing investigation — 2026-09-13

Source baseline: host `648426c`, T3 `e38fd9c`. No behavior changes were made during
this investigation.

Confirmed by source and eight existing targeted T3 tests:

- `T3Layer.animate` captures only a heading on close. `paintMotionBackdrop`
  rasterizes/caches a backdrop once, then copies it. Repeated Sessions list layout
  or dashboard text layout is **not** established as the closing bottleneck.
- `contentChanged()` terminates closing motion, whereas opening invalidates its
  cached content and continues. `onHostState` calls this same method. The host
  external-app platform publishes host state every 1,000 ms, and the application
  notifies listeners without comparing relevant values. Thus even an unrelated
  host refresh can take the close-cancellation path.
- Existing tests deliberately require stale outgoing content to be discarded on
  source changes. Preserve that security behavior while splitting event categories.
- Transition backdrops/headings are baked before flattening, so their structured
  draw identities do not survive into transition submissions. This is a candidate
  payload optimization; its benefit has not been benchmarked.

ADB observation at 09:57:04: frame 1021 followed the synthetic back input and was
acknowledged in 194 ms; host intake/copy/composition/packing took 15 ms, the planned
payload was 1,816 bytes, and changed packed bytes were 16,725. Only one application
frame appeared for this return. That is consistent with an early-terminated
transition but **does not identify its cancellation reason**. Later attempts
included a sleeping display/setup navigation and are excluded from FPS claims.

Priority for a follow-up implementation: classify/coalesce host-state updates and
add cancellation/paint instrumentation first; then reuse correctly owned scratch
rasters and restore only exposed regions; evaluate clipped resource/scene reuse
last. Do not declare a firmware limit or a measured closing speedup from this
investigation alone.

Additional observation: the 10:00:53 export's recent-frame window was dominated
by `ui.notifications` submissions rejected at external surface intake (0–1 ms
each). This displaced the later opening/closing frames from the bounded export,
so that pair cannot provide a complete timing comparison. It is a separate
potential source of contention, not proof of the Sessions closing cause.

## A7 — Stop animation credit churn after terminal surface failure

Do not immediately request another animation frame indefinitely when a surface
is unavailable or consistently rejected. Preserve the desired final state, record
a bounded diagnostic and retry category, and resume on a valid surface/generation
or transport recovery event. Distinguish supersession during healthy delivery
from invalid geometry or missing surface registration. A rejected frame must not
generate an unbounded retry loop that competes with the foreground animation.

## A3 reference implementation follow-up

T3's host integration now classifies ordinary host-state refreshes as `ambient`
and suppresses value-identical refresh notifications. An ambient refresh may
invalidate the destination backdrop but preserves the closing motion's clock and
geometry. Notification snapshots, content updates, revocation and lifecycle
events retain their existing invalidation paths. Regression tests cover repeated
ambient updates during collapse, unchanged state received via both window and
extension routes, and immediate content invalidation. This is application-side
reference behavior, not a new SDK wire event or public lifecycle contract.

The notification rejection-loop observation and proposed scratch-buffer/resource
optimizations remain separate follow-ups; this change does not implement them.

## Lifecycle correction and on-device validation follow-up

Content-free T3 motion logs distinguish skipped transitions (including inactive
overlay state), explicit cancellation, safe content invalidation and completion.
An ADB run at 10:11:43 acknowledged eight opening frames; a closing run at
10:11:42 logged content cancellation about 190 ms after starting. Thus the
remaining failure was not universally an absence of render credits.

The reference implementation now discards outgoing content/heading pixels on
closing invalidation and continues the existing trajectory with a neutral outline
and refreshed destination backdrop. Privacy invalidation erases pixels immediately
without requiring geometry cancellation. Real input/lifecycle cancellation remains.

A pending notification preview is an overlay only while its host notification
surface is active. Closing that surface or losing the session clears the local
preview so stale state cannot disable unrelated window animations. Active capture
continues to count as an overlay. Tests cover inactive previews, surface reopening,
disconnect and safe continuation after content invalidation.
