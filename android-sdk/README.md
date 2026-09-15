# Faceclaw Android SDK 1.0

Faceclaw applications are ordinary Android APKs running under their own UID. SDK 1.0 uses typed, session-scoped AIDL and persistent Gray8 shared buffers; application pixels do not use Messenger, JSON, or the host's Java-to-NativeScript bridge.

The public SDK version is `1.0.0`. Wire protocol major `2` intentionally rejects prerelease clients with `UPDATE_REQUIRED`. The minimum Android version is API 27. There is no protocol-1 compatibility path.

## Build and consume locally

Use Android SDK 35 and JDK 17 or newer:

```sh
./gradlew :sdk:assembleDebug :sdk:assembleRelease :sdk:testDebugUnitTest :sdk:lintDebug
```

For sibling-project development, add the build to `settings.gradle.kts`:

```kotlin
includeBuild("../faceclaw-app-platform/android-sdk")
```

Then add the stable dependency:

```kotlin
implementation("com.faceclaw:sdk:1.0.0")
```

The generated AARs under `sdk/build/outputs/aar/` are local verification artifacts. This repository does not publish them remotely.

Declare one exported SDK service. Keeping the discovery action and component stable preserves an approval only when Android user, package, component, and complete signing identity also match.

```xml
<service android:name=".GlassesService" android:exported="true">
  <intent-filter><action android:name="com.faceclaw.action.APP_SERVICE" /></intent-filter>
  <meta-data android:name="com.faceclaw.PROTOCOL_MAJOR" android:value="2" />
</service>
```

The SDK manifest contributes the non-exported host-selection UI. Protect application credentials and cached content independently; never add a second unauthenticated exported interface to the service.

## Lifecycle

Read [Navigation and display wake](NAVIGATION_AND_WAKE.md) for the host's
gesture precedence, static navigation-provider fields, suspended EvenHub wake,
and the boundary between explicit `requestSleep()` and semantic root-back.

Extend `FaceclawAppService` and override the stable callbacks:

```java
public final class GlassesService extends FaceclawAppService {
  private final ExecutorService render = Executors.newSingleThreadExecutor();

  @Override protected void onSessionReady(FaceclawSession session) {
    session.windowSurface().setCanvasRenderer(render, (canvas, request) -> {
      Ui.text(canvas, "Hello", 20, 36, 18, Color.WHITE);
    });
  }

  @Override protected void onHostSnapshot(HostSnapshot snapshot) {}
  @Override protected void onInput(RenderSurface surface, FaceclawInputEvent event) {}
  @Override protected void onControlEvent(ControlEvent event) {}
  @Override protected void onSessionLost(DisconnectInfo info) {}
}
```

Callbacks arrive on the main looper. `HostSnapshot` is the atomic starting point for grants, style, host state, capabilities, negotiated limits, restoration token, and every open surface/generation. A recoverable Binder loss retains the `FaceclawSession`, renderers, registered resources, and accepted scene state. Rebinding creates new buffer generations, replays resources and scenes, and rejects stale submissions. Permanent identity, revocation, abuse, and protocol failures enter `PERMANENTLY_REJECTED`.

Recoverable binding attempts use 0 ms, 250 ms, 1 s, 2 s, 5 s, then 10 s intervals. BLE recovery is host-owned and independent of the application session.

## Rendering

For a new animated app, start with [Building reliable animations](WINDOW_MOTION.md):
the ownership model, standard transition lifecycle, invalidation table, starter
examples and acceptance checklist. [Engineering patterns](ANIMATION_PATTERNS.md)
preserve rationale and historical findings; the lifecycle guide is the current recipe.

All shell, built-in, APK-window, and APK-extension surfaces enter the same host `RenderBroker`, retained compositor, planner, and display transport. Each raster surface registers three persistent `SharedMemory` slots. The SDK writes them and the host maps them read-only.

Choose one surface API:

- `setRasterRenderer(Executor, RasterRenderer)` writes Gray8 directly through a `FrameLease`.
- `setCanvasRenderer(Executor, CanvasRenderer)` reuses one Bitmap/Canvas and converts it once to Gray8.
- `scene()` commits atomic retained `SceneTransaction` changes.

Use `windowSurface()` or `extensionSurface(feature)`. Call `invalidate(reason)` when desired state changes. A renderer runs only after a visible surface receives one host render credit. Submit damage rectangles and the current credit trace ID in `FrameMetadata`; call `requestNextFrame(true)` only while another animation sample is useful.

Animations sample `request.credit.targetPresentationTimeNanos`. They must not run an independent frame-rate timer. Delayed work skips obsolete samples, while the final state remains eligible for display. See [WINDOW_MOTION.md](WINDOW_MOTION.md).

The host copies only declared damage once into private retained Gray8, recomposes dirty 32×16 tiles, patches its retained packed framebuffer, and keeps the newest desired state. Geometry, visibility, lost delta state, and BLE reconnect trigger a keyframe. BLE loss never asks an application to repaint.

## Outcomes

Every accepted raster `clientFrameId` gets exactly one terminal `FrameOutcome`:

`DISPLAY_ACKED`, `PREVIEW_COMMITTED`, `DEDUPLICATED`, `SUPERSEDED_BEFORE_COMPOSE`, `SUPERSEDED_BEFORE_SEND`, `THROTTLED`, `HIDDEN`, `STALE_GENERATION`, `TORN_WRITE`, `BLE_TIMEOUT`, `SESSION_LOST`, or `CANCELLED`.

`BUFFER_RELEASED` is separate: it only means the application may reuse that shared slot. `DISPLAY_ACKED` is the strongest acknowledgement exposed by current firmware, not optical-vsync confirmation. Overload supersedes or throttles frames; it does not disconnect a valid app. More than 32 invalid submissions in ten seconds is protocol abuse.

## Resources and retained scenes

`session.resources()` registers immutable Gray8 images and glyphs by SHA-256. Equal content deduplicates within a session. Optional `DrawBatch` placements let the host use its image/glyph atlas and texture planner while the same pixels remain baked into the raster frame as a correctness fallback. Invalid draw metadata is dropped without dropping the raster.

`SceneController` retains complete accepted state. Transactions use monotonic versions and application-scoped 64-bit node IDs. Supported nodes are group, rectangle, rounded rectangle, line, glyph, grayscale image, and raster patch. Properties include integer coordinates, parent translation/clipping, z-order, brightness, and opacity. Rejection is atomic and asks the raster renderer for fallback. Inline raster patches are bounded by the 256 KiB transaction limit and are replayable after recovery.

Default limits are 4 MiB and 2,048 resources per application session, 2,048 scene nodes per surface, 256 KiB per command batch, and eight damage rectangles per raster frame. Firmware-font shortcuts are safe only when `HostSnapshot.capabilities` reports the expected firmware fingerprint.

## Recovery and diagnostics

Store at most 4 KiB with `session.setRestorationToken(bytes)`. The host returns it only to the same approved identity and clears it on close, revocation, or uninstall. On application Binder death, the host replaces its pixels with a neutral reconnecting surface while retaining window context. Accepted in-flight raster frames finish with `SESSION_LOST`.

Connection/disconnect diagnostics classify backend, Binder, host-policy, protocol, and BLE failures. Trace IDs flow from input/invalidation through credits, copying, composition, planning, BLE fragments/acknowledgements, and terminal outcomes. Diagnostics record timing, sizes, queue decisions, and categories, never pixels or application content.

## Controls, notifications, and extensions

Bounded JSON remains only for versioned control/extension data. It is never part of the rendering hot path. Control traffic uses a 60-message-per-second token bucket with burst 120.

The existing notification, dictation, reviewed reply, messaging, host-state, shared-style, and extension methods remain typed SDK helpers. Capability authority is always bound to the current session, Android identity, grant, feature generation, request ID, and expiry. Treat timeouts and unknown side-effect outcomes as non-retryable without new user intent.

The [app-owned assistant invocation contract](ASSISTANT_INVOCATION.md) defines how
a selected assistant provider handles "Hey Even" in its own window, including
declarations, event ordering, permissions, and older-host compatibility limits.

The complete application-owned capability contract is in [CAPABILITY-PROTOCOL.md](../docs/CAPABILITY-PROTOCOL.md). Independent extension surfaces use the same render pool, scheduler, outcomes, recovery, and scene/resource APIs as the main window.

## Examples and verification

- [CanvasAppService.kt](examples/CanvasAppService.kt) demonstrates the pooled Canvas adapter.
- [AnimatedCardAppService.java](examples/AnimatedCardAppService.java) demonstrates presentation-time animation.
- [animated-card.cjs](examples/animated-card.cjs) demonstrates the same timer-free motion model in JavaScript.
- `bash scripts/check-animation-examples.sh` verifies the documented animation
  starters, including compilation of the Java example against the SDK.
- `priority-demo` contains independent installable provider applications.

Run local checks with:

```sh
./gradlew build
node --test javascript/test.cjs javascript/animation-example.test.cjs
```

`fixture` and `host-tests` exercise separate-UID AIDL identity, malformed submissions, generations, revocation, recovery, and bounded protocol behavior. Set `-Dfaceclaw.compositor.compareDirty=true` in a developer build to compare dirty composition against deterministic full composition without changing the SDK API.

## App-independence candidate

The local `1.1.0-rc.1.9dc05298f50a` candidate adds negotiated controls, window policy,
draft capture, explicit resource release and phone/host presentation adapters.
See the [candidate build and migration handoff](../docs/sdk-independence/CANDIDATE-HANDOFF.md)
and [acceptance status](../docs/sdk-independence/STATUS.md) before consuming it.
