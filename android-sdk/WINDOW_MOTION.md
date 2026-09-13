# Credit-driven window motion

`WindowMotion` is a timer-free geometry model shared by the Java and JavaScript SDKs. Create or retarget a motion when state changes, invalidate the surface once, and sample it from the host-provided presentation time inside the renderer.

```java
surface.setCanvasRenderer(renderExecutor, (canvas, request) -> {
  WindowMotion.Frame frame = motion.sample(request.credit.targetPresentationTimeNanos / 1_000_000L);
  if (frame == null) return;
  Ui.transitionCard(canvas, frame, "TITLE", bodyCanvas -> drawBody(bodyCanvas));
  if (!frame.done) request.requestNextFrame();
});
```

The scheduler grants the next credit according to display availability, BLE queue/ack timing, shell work, visibility, and priority. A delayed renderer samples the current target time and skips obsolete intermediate states. The terminal state is still submitted once. Cancel motion on content invalidation; recoverable host loss may retain the model because the next credit supplies a new presentation target.

Opening body content becomes visible at 90% progress. Closing content is hidden immediately, preventing stale private pixels from surviving a reverse transition. Retargeting begins at the last submitted rectangle.

Install the local JavaScript package as `@faceclaw/motion`; it exports `WindowMotion`, `FrameRequest`, and the same geometry constants. It intentionally exports no fixed-rate animation driver.
