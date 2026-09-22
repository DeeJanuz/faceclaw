# Portable Glanceboard widgets

Apps register widgets through the SDK. Faceclaw discovers approved Android services, stores each service's declared catalog, and exposes those widgets in the contents picker. No Faceclaw source edits, application-specific host code, or hardcoded package IDs are needed.

The host advertises `glanceboardRegistry: 1` in capabilities. This is registry version 1 and widget-content version 2. Registration is independent of extension negotiation and never invalidates a window, extension, or negotiated capability.

## Quick start: JavaScript / TypeScript

Install the `@faceclaw/motion` SDK package and connect an Android service as described in the SDK README. Register after `connected` or `capabilities`; repeated identical registration is deduplicated by the native SDK.

```ts
import { appControls, type GlanceWidgetRegistry } from '@faceclaw/motion';
const widgets: GlanceWidgetRegistry = {
  version: 1,
  widgets: [{ id: 'default', label: 'My tasks', kind: 'list', rows: 1, refreshMs: 30000 }],
};
function onHostEvent(service: any, type: string, data: any) {
  const sdk = appControls(service);
  if (type === 'connected' || type === 'capabilities') sdk.registerGlanceboardWidgets(widgets);
  if (type === 'glanceboard-request' && data.widgetId === 'default') {
    sdk.publishGlanceboardWidget({
      version: 2, widgetId: 'default', expiresAt: Date.now() + 60000,
      title: 'My tasks', emptyText: 'Caught up',
      entries: [{ id: 'review', title: 'Review changes', detail: 'Ready' }],
    });
  }
}
```

Register at service startup, not when the application's main window opens. Also publish a replacement list when its attention state changes. Use stable IDs. Do not mark content viewed merely because it appears on Glanceboard.

## Native Android

Override `FaceclawAppService.glanceboardWidgets()` to return the catalog. The SDK registers it on host capabilities and re-registers after reconnection. Alternatively, call `registerGlanceboardWidgets(JSONObject)` for a dynamic catalog. The declaration is cached even before connection.

Respond in `onControlEvent` to `glanceboard-request`. Call `publishGlanceboardWidget(JSONObject)` with a snapshot. The SDK validates the snapshot against your registered widget. A `true` return means the transport accepted the send, not that the widget is visible. Before registration/connection it can return `false`. Malformed registered content throws `IllegalArgumentException`; do not retry it unchanged.

[widget-starter](widget-starter/README.md) is a complete standalone Android example with a list widget and a two-slot scene. It consumes a Maven SDK artifact and builds without a host checkout. Java's `GlanceCanvas` and JavaScript's `GlanceCanvas` create the same wire format.

## Registry

```json
{
  "version": 1,
  "widgets": [
    { "id": "default", "label": "Tasks", "kind": "list", "rows": 1, "refreshMs": 30000 },
    { "id": "home", "label": "Home", "kind": "scene", "rows": 2, "refreshMs": 60000,
      "uses": ["battery", "weather", "time-format"] }
  ]
}
```

The complete catalog replaces the previous catalog. An empty `widgets` array unregisters everything. IDs are local to the authenticated service; apps cannot choose another service's identity. Keep IDs stable across upgrades.

| Field | Contract |
| --- | --- |
| `id` | 1–64 ASCII letters, digits, `.`, `_`, or `-`; starts with a letter or digit; unique within service |
| `label` | Nonempty public catalog label, at most 80 UTF-16 code units; do not put private message content here |
| `kind` | `list` or `scene` |
| `rows` | Exactly 1 or 2; each slot is 288 × 144 pixels |
| `refreshMs` | Integer 5000–60000 milliseconds; only while the widget is visible |
| `uses` | Optional unique subset of `battery`, `weather`, `time-format` |

At most eight widgets per service, 8192 serialized characters per catalog. The host catalog is bounded to 64 approved providers. Two-row widgets require matching vertically adjacent slot selections and receive one 288 × 288 region without an internal divider. A lone selection displays setup guidance. One-row widgets do not merge.

## Requests, context, and lifecycle

A registered widget receives:

```json
{"version":2,"widgetId":"home","width":288,"height":288,"context":{"timeFormat":"24h"}}
```

The host supplies only declared ambient context:

- `battery`: host battery snapshot; `headset` percentage and `headsetCharging` can be null.
- `weather`: `phase`, `locationName`, `current`, and `lastUpdatedMs`. Current weather can be null; location permission is still required on the host. Requests use the shared weather cache and coalesced refresh, not a separate location poll per app.
- `time-format`: `context.timeFormat`, either `12h` or `24h`.

No notifications, messages, credentials, raw location coordinates, or action authority are included. Handle absent context gracefully. Publish from cached application state when possible. Coalesce necessary network fetches. A request never opens an app window.

The host requests on display/preview entry and on declared refresh intervals. Frames expire within 60 seconds; an earlier expiry can align a clock with the next minute. Weather-dependent visible widgets can receive another request when weather changes. Hidden widgets do not poll; the SDK does not create a background refresh service for widgets.

Registered public metadata survives host restarts, scoped to the approved service and signing identity. Private frames are memory-only. Disconnect/recovery, catalog withdrawal/change, permission changes, and expiry remove cached frames. Uninstall/revocation removes the provider from the available catalog. Reconnection and a visible request supply new content.

## Content: lists

Use `version: 2`, `widgetId`, `expiresAt`, `title`, `emptyText`, and `entries`. Every entry contains nonempty unique `id`, `title`, and `detail`; optional row `expiresAt` may expire it earlier. Empty `entries` clears the list. Expired rows are omitted; row expiry is capped to the snapshot expiry.

Limits: 12 entries; 16,384 serialized characters; title 80 characters; empty text 160; row ID 256; row title 160; detail 256. Text fields are required strings and control characters are replaced with spaces. Times are absolute Unix milliseconds, finite positive safe integers. Snapshot expiry must be strictly after publication time and no more than 60 seconds ahead.

## Content: portable scenes

Scenes use generic commands. Their design and data belong to the app; the host has no app-specific renderer.

```ts
import { GlanceCanvas } from '@faceclaw/motion';
const scene = new GlanceCanvas('home', Date.now() + 60000)
  .text(16, 16, 'My dashboard', 256, 235)
  .rect(16, 52, 256, 2, 190)
  .text(16, 88, 'Custom layout', 256, 190)
  .build();
sdk.publishGlanceboardWidget(scene);
```

`GlanceCanvas` supports:

- `text(x, y, text, width, value)`: host text font, clipped to width; at most 256 characters. Match a custom font by publishing a bitmap instead.
- `rect(x, y, width, height, value)`: solid rectangle.
- `bitmap(x, y, width, height, grayPixels, value)`: packs nonzero pixels as one-bit ink; zero bits are transparent. Java accepts `byte[]`; JavaScript accepts an array-like pixel buffer. The wire field is canonical Base64, packed MSB-first across rows without per-row padding.

Commands are painted in order on a black canvas. `value` is an integer 0–255. Coordinates are nonnegative integers inside the declared viewport; sizes must fit. At most 96 commands, 32,768 serialized characters, and a combined bitmap area no greater than the viewport area. External resources, URLs, scripts, input handlers, and arbitrary native drawing code are not accepted. This is a passive dashboard contract, not an animation transport.

## Privacy and gestures

The existing mutually approved, certificate-pinned Android service connection and rate limits apply. The host binds every publication to the sending service. Undeclared IDs and malformed frames are rejected. With the app's previews grant disabled, the host shows a generic message instead of content, for both renderer types.

The host owns sleep gestures and dismissal. With Glanceboard enabled, sleep-time tap/hold/head-tilt follow existing settings; hold release closes it. T3's `navigation.tapHold = "glanceboard"` keeps awake tap-and-hold as the app switcher. Widget registration does not change navigation or enable Glanceboard automatically. Users select placements; apps cannot replace another app's slots.

## Tooling

The npm package installs `faceclaw-widget`:

```sh
faceclaw-widget init ./my-widget
faceclaw-widget validate ./my-widget/widgets.json ./my-widget/content.json
faceclaw-widget preview ./my-widget/widgets.json ./my-widget/content.json --out ./preview.html
```

`init` writes a declaration, sample content, and a JavaScript service handler without overwriting existing files. `validate` checks the same constraints as the native SDK. `preview` writes a standalone, escaped HTML/SVG artifact without calling a host or opening a browser. Font rendering is approximate. Sample expiry is generated at creation; update it before a later validation or pass `--now EPOCH_MS` for deterministic fixtures. Neither command operates the glasses.

`validateWidgetRegistry` and `validateWidgetContent` are also importable JavaScript functions. Native and JavaScript conformance tests share `test-vectors/glanceboard.json`. The standalone Android starter is built against the packaged AAR as an integration check.

## Packaging and compatibility

Development SDK candidates are immutable local Maven and npm artifacts, not remotely published releases. `scripts/package-candidate.py` packages the built release AAR, CLI, types, and this guide, and writes hashes/version metadata. Provide those artifacts or publish them to your own package repositories so app developers do not need Faceclaw source.

The existing `publishGlanceboard` v1 method is deprecated but remains supported as one generic default list. New native SDK clients can fall back to v1 for their `default` list on an older host; custom scenes and multiple widgets require registry support. The SDK advertises registry and content versions separately from the service protocol.

Previous `app:<service>` default-list placements continue to address widget `default`. Other widgets use `app:<service>#<widgetId>`. The former host-built `t3-home` choice must be reselected as the registered T3 home widget in both slots. There are no T3, Messages, or Spotify widget IDs or default placements embedded in the host.

## Integration lessons and release checks

Keep layout, attention state, and navigation in the provider app. A host should discover declarations and render the generic list/scene contract without knowing the provider package or widget ID. Use `rows: 2` for a full-height scene; require matching adjacent placements rather than changing the user's slots. New applications and layouts within this contract do not require host source edits. New permissions, transports, or wire operations can still require coordinated host/SDK changes.

For attention lists, select the newest incoming item or completed turn for each conversation first, then evaluate whether it remains unseen, unexpired, and unanswered. Do not search backwards for an older unread item after the newest has been handled. Keep receipts scoped to account/environment and conversation. Acknowledge content actually displayed, not background fetches or widget previews. Capture the item being replied to when the reply begins; failed or uncertain sends are not acknowledgement. Retention expiry and widget freshness are separate deadlines. An empty attention list should publish empty content to retract stale rows. Navigation focus is app-owned: returning from an attention item may focus the next waiting item without reopening the outer card.

Bound work before text layout, not only before transport. Page retained histories, preserve reading anchors across arrivals, coalesce refresh bursts, and avoid repainting identical snapshots. Cache unchanged artwork and layout. Pause widget timers when hidden. Full-screen raster updates can remain expensive even when retained-copy hints or animation credits are available; credits are backpressure, not a frame-rate guarantee. Keep authoritative pixels and a static-navigation fallback.

Registration belongs to connection/capability events. Do not republish extension declarations on every host snapshot: declaration changes can invalidate negotiation and create feedback/reconnect loops. Catalog changes must not reset unrelated extension authority. Reconnect backoff should reset only after a stable connection, not merely a successful bind.

For NativeScript, a successful Java/TypeScript build does not prove that Java methods are exposed in the packaged bridge metadata. The npm SDK includes this offline check:

```sh
python3 node_modules/@faceclaw/motion/verify_apk.py path/to/app.apk
# Additional required methods can be checked explicitly:
python3 node_modules/@faceclaw/motion/verify_apk.py path/to/app.apk --require registerGlanceboardWidgets --require publishGlanceboardWidget
```

This checks metadata presence, not signing, permission enforcement, or end-to-end behavior. Run it alongside your app's package/signature checks. If an incremental build kept stale metadata, regenerate the Android build tasks (for example, `./gradlew :app:assembleDebug --rerun-tasks` in the generated Android project), then verify the resulting APK again. Prefer immutable packaged SDK dependencies; source checkouts must be explicit opt-in. Never treat a missing bridge method as proof the host lacks a feature.

## Optional media artwork

The passive `host-state.media` field is optional and may be null. It contains bounded title/artist strings and an optional `{width, height, gray4}` image, with dimensions 1–64 and one lowercase grayscale hexadecimal digit per pixel. The host supplies it to ready, visible app windows with preview permission while the display is on. It uses the same active Android media session and photo tone as the host music UI. It confers no playback or notification authority.

Use the SDK's `HostMedia` type and `decodeMediaArtwork(art)` helper. The decoder returns `{width,height,pixels}` with Gray8 bytes, or null for missing/invalid data. Cache by artwork content, use a fallback icon for null, and clear app state when disconnected. Keep media-key controls independent so missing artwork does not break playback controls. Track changes and artwork arriving after metadata must invalidate the cached image; avoid continuous bitmap conversion.
