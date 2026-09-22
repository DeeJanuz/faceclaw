# Display transport and animation contract

Applies to the upstream-integrated host based on `313ccd8` and SDK wire protocol
major 2. This updates the host/firmware requirements without changing AIDL,
`Protocol.VERSION`, SDK 1.0.0, or app-independence contract version 1.

## Compatibility and ownership

The host requires Faceclaw custom firmware revision 22 or newer. Legacy `EVENCFW`
and stock firmware are incompatible with this host. The bundled image uses base
`2.3.0.24`; installing a host APK does not update either lens. Install the matching
custom firmware through the host's phone UI. The Android toolbar overflow menu
contains **Install / update custom firmware**; the firmware warning also offers
**Install custom firmware**. The updater suppresses ordinary reconnects, requests
confirmation on the glasses, checks battery, prepares a verified image, and
flashes both lenses. Keep Faceclaw open until it finishes.

SDK APKs must never open their own glasses BLE link, send CFW packets, or flash
firmware. They negotiate application features through the authenticated host
catalog. Firmware revisions and application protocol versions are separate.
An installed SDK or successful Binder connection does not prove glasses readiness.
Firmware mismatch is a host connection/update condition, not an APK protocol bump.

## Host-owned transport

The host uses dedicated SID `0xf0` framing, negotiated MTU packet sizes, persistent
zlib compression and an ordered three-message window. Recovery resets compression
and replays unresolved messages; exhausted retries fail transport and reconnect.
An application must not duplicate those retries or resend old leases. The host
retains the latest desired pixels and restores a keyframe after BLE recovery.

The glasses texture cache is 256 KiB. Upload/image/string modes 18/19/20 use 32-bit
cache offsets. These are host implementation details, not SDK resource IDs or
app quotas. Applications retain the negotiated SDK quotas (default 4 MiB and
2,048 registered resources), and the host chooses which resources remain resident.

## Application requirements

- Invalidate desired state once. Render only with a current visible-surface credit;
  sample `targetPresentationTimeNanos`, then request another frame only while active.
  Never add a fixed 25/30/60 fps timer to the glasses rendering path. Sparse activity
  deadlines may invalidate once when their state changes; credits still own rendering.
- Submit the complete authoritative Gray8 target and valid damage/trace metadata.
  Cached draws and retained copies are optional optimizations. Missing caches,
  unsupported hints and reconnect must preserve correct final pixels.
- Register stable glyph identities with `registerGlyph(fontKey, encoding, ...)`.
  Prefetch both outgoing and incoming resources before visible translation using
  negotiated `resource.prefetch`. Requests contain at most 128 unique live resources.
  `prefetchWorkingSet` is for a known complete set and may replace an idle cache;
  ordinary prefetch appends and is preferable when other surfaces share resources.
- A `true` prefetch return means the control was submitted, not that resources are
  resident. Inspect `resource-prefetch-result` when residency matters. A failed or
  unsupported prefetch must not block motion or remove baked raster pixels.
- Derive copy hints from the last successfully submitted frame of the same surface
  generation and transition. Clear the baseline on rejection, resize, generation
  replacement, hide/loss, or transition replacement. The host verifies and repairs
  the hint against authoritative pixels.
- Skip obsolete samples on delayed credits and always submit settled final content.
  Prepare layouts once per transition. Do not replay a queue of intermediate frames.
- Treat firmware fingerprints as opaque equality tokens. Firmware-font shortcuts
  require the host's verified matching font identity; otherwise bake glyphs to raster.

`BUFFER_RELEASED` permits shared-slot reuse only. `DISPLAY_ACKED` means the host's
firmware acknowledgement contract completed, not optical presentation/vsync.
Each accepted raster frame keeps its existing exactly-once terminal outcome.
No fixed FPS or speedup is guaranteed by this contract. Compression, cache reuse
and sparse repairs improve available transport capacity; phone, payload and lens
behavior still determine observed animation smoothness.

See [the animation recipe](WINDOW_MOTION.md) and
[app-independence contract](../docs/sdk-independence/CONTRACT.md).

## Optional application cadence hint

A service may declare integer manifest metadata
`com.faceclaw.ANIMATION_FRAME_INTERVAL_MS` (T3's dense-text experiment uses `17`).
The host clamps positive requests to 17–1000 ms. Missing, non-integer or nonpositive
values retain the ordinary transport cadence. During light transport activity the
requested period replaces the default 24 ms interval; backlog/unavailability
periods of at least 48 ms remain lower bounds. Direct input remains immediate.
This is a scheduling preference, not render authority or a display FPS guarantee.
Older hosts ignore it. Applications still need credits, current generations and
visible surfaces; queue and buffer limits remain enforced.

## Switching between raster animation and retained scenes

A retained scene's node state can survive intervening raster frames, but its
fingerprint does not then describe the currently displayed pixels. Committing
that scene again, even with an empty transaction, must restore its pixels and
mark the full surface damaged after raster use. Only an unchanged scene with no
intervening raster frames may consume a credit without redrawing. Applications
supporting older hosts should issue a full scene commit when returning from
raster animation. Keep that restoration pending after a rejected local commit.

### Display ACK deadlines

Do not infer an ACK failure deadline from a small animation benchmark. A real Messages frame produced valid per-lens ACKs at 536–558 ms, beyond the former 500 ms custom-message deadline. The host now allows 1,500 ms after writes complete, retaining immediate NACK handling and bounded retries. This does not add a minimum delay to successful frames. A timeout still indicates incomplete acknowledgement, not proof that Android Bluetooth pairing failed. Diagnose layout acknowledgements separately from image acknowledgements and record payload sizes when investigating large frames.
