# Notification wake and session recovery: Sol implementation handoff

## Objective and boundaries

Fix the host notification-wake readiness stall and repeated layout creation after firmware exit. Preserve notification privacy, genuine sleep/session suspension, bounded transport recovery, and existing wake-lease behavior. Work in the existing `faceclaw-app-platform` checkout, branch `design/apk-app-platform`. Inspected HEAD: `c7195f8a0215c8913f9ae690b02f4f61c48e264d`, with substantial existing uncommitted changes, including dashboard-controller.ts and extension-platform.ts. Preserve those changes. Reinspect the diff before editing.

This document began as the implementation handoff. The first bounded host-side implementation is now present in the working tree: readiness is split into nonblocking preparation/frame checks, notification wake work is generation-bound, and unexpected firmware exits use one generation-fenced in-place relaunch before reconnect. Android compilation, APK installation, and device acceptance remain pending because this checkout's configured WSL environment has no JDK or Android SDK. No firmware flash, broad BLE rewrite, APK protocol change, notification filtering change, or microphone-permission change is included. No T3 APK changes are needed. Use synthetic notification content for tests. Do not log message bodies, titles, credentials, or raw notification keys.

## Evidence and corrections

Windows ADB target: `RFGL111YEYY`, using `/mnt/c/Users/daeno/AppData/Local/Android/platform-tools/adb.exe`. Times below are September 20, 2026, MDT, from retained logcat inspected directly.

| Time | Observation |
| --- | --- |
| 08:28:38.535–.542 | NotificationArrival admits a non-ongoing notification while screen is off. |
| 08:28:38.624–.777 | Resume prelude and first create-layout both receive ACKs. |
| 08:28:38.792–.793 | Firmware event later decoded as ABNORMAL_EXIT (type 6); worker immediately sends another CREATE. |
| 08:28:38.986–.989 | SYSTEM_EXIT (type 7); another CREATE follows. |
| 08:28:42.499–.500 | create-layout times out; host invokes transport failure and closes connections. |
| 08:28:42.689–.690 | JavaScript finally processes the exit events, roughly 3.9 and 3.7 seconds after native receipt. |
| 08:29:03.943–04.194 | Session becomes ready again and a blank frame is ACKed. |

The prior answer overclaimed three points. The timed-out message was CREATE, not an image. The charging notification posted nearby is not established as the admitted source: it was ongoing, whereas the admitted source logged ongoing=false. The host's microphone FGS crashes were September 18 historical records, not this incident. There is no proof of a full hardware reboot or notification traffic overload.

## Validated code findings

1. **Notification preparation uses the wrong readiness contract and blocks JS.** In `app/g2/dashboard-controller.ts`, the pendingNotificationWake branch of `ensureEvenHubSessionActive` calls `awaitEvenHubSessionReady` before notification submission/unblank. In native `FaceclawBleCommunicator.awaitEvenHubSessionReady`, readiness requires layout creation AND desired/displayed fingerprint equality. That is a visible-frame barrier, not session preparation. `app/native/faceclaw-communicator.ts:enqueueJavaCall` invokes Java synchronously inside a JS setTimeout; it does not move blocking Java waits to a background thread. Frame submission and JS exit handling cannot progress during that wait. A blank ACK can sometimes satisfy the barrier, masking the mismatch. `tests/notification-wake.test.cjs` currently stubs readiness to unconditional true; all three existing tests pass and do not exercise this failure.

2. **Firmware exits trigger incomplete session recovery.** The native onNotification exit branch unconditionally sets fixedLayoutCreated=false and clears messages. The worker then sees shutdownRequested=false and immediately queues CREATE. It does not first establish that a plugin task remains alive or relaunch the task. `resumeEvenHubSession` explicitly documents that a terminated task requires prelude before CREATE. An ABNORMAL_EXIT followed by SYSTEM_EXIT can clear the first recovery CREATE and start another. CREATE timeout escalates to full BLE reconnect. This code path matches the observed sequence. The original reason for ABNORMAL_EXIT remains unknown; do not label it a stale event or suppress it on timing alone.

3. **The proposed active-session guard already exists.** `resumeEvenHubSession` returns true when shutdownRequested=false. It correctly replays prelude for an intentionally suspended plugin even though BLE remains connected. Removing this replay would break wake.

4. **Historical FGS defect is already addressed in current source.** FaceclawForegroundService explicitly requests CONNECTED_DEVICE only; its manifest declares connectedDevice. It catches typed-start SecurityException and handles fallback rejection. Commit `f72f8d1` (September 18, 09:13 MDT) touched this fix, after the last observed microphone startup crash at 09:02. A versionCode of 605 alone cannot identify exact installed source. Verify build provenance rather than adding permissions already declared.

## Implementation work packages

### A. Split readiness and remove blocking notification readiness waits

Files: `FaceclawBleCommunicator.java`, `app/native/faceclaw-communicator.ts`, `app/g2/dashboard-controller.ts`, notification wake tests.

- Add a quick native session-readiness query, under the existing lock: transport active, not charging, not shutdown, current layout acknowledged. It must not require a displayed frame and must not wait.
- Add a separate quick visible-frame readiness query that retains the current layout/fingerprint requirements. Preserve the READY-to-both-arms wake-lease operation and its delivery semantics; inspecting readiness alone must not prematurely release a deferred wake lease.
- Implement bounded asynchronous readiness waiting in the bridge using nonblocking queries and a timer (for example 25 ms polling). Do not run a native wait loop through enqueueJavaCall. Do not occupy the serialized queue across timer waits. Reuse the existing wake deadline constants. A native callback alternative is acceptable if it is smaller and already supported locally.
- The notification preparation phase powers on and resumes once, then waits only for session readiness while remaining blank. The reveal phase submits the current valid notification, unblanks, and waits for visible-frame completion before starting its preview duration or marking the deferred wake READY.
- Bind both phases to communicator identity plus a wake/presentation generation. After every await, reject completion if disconnected, charging, replaced, dismissed, locked, or superseded by a newer sleep/wake. Joining concurrent calls must share one preparation operation; stale cleanup must not clear a newer promise or close a newer presentation.
- Keep the ordinary wake contract intact. Scope changes to readiness operations used by these paths; do not redesign all Java bridge calls. Prelude still contains a shorter synchronous wait: measure it, but a general background-thread bridge migration is outside this patch.

### B. Make post-exit recovery explicit and bounded

Files: `FaceclawBleCommunicator.java`; preferably one small plain-Java lifecycle helper with executable unit tests if needed for testability.

- Track a session generation and explicit lifecycle phase, sufficient to distinguish intentional suspension, launching, active, exit recovery, and disconnected state. Reuse existing fields where possible; avoid creating two competing state authorities.
- Centralize exit processing and layout invalidation. Expected shutdown exits complete suspension without initiating CREATE. Unexpected ABNORMAL_EXIT/SYSTEM_EXIT transitions invalidate the current layout and image/texture assumptions and request recovery through the worker.
- While recovery is pending, prevent the ordinary !fixedLayoutCreated branch from issuing CREATE against an exited task. Use one recovery operation for the generation: relaunch the plugin with prelude, then one CREATE, then the desired safe frame. Perform blocking I/O outside the callback and outside the state lock.
- Coalesce the abnormal/system exit pair while recovery is pending. If firmware events carry no usable session identity, document that limitation: local generations protect callbacks and ACKs but cannot prove a received exit belongs to an older firmware task. Do not blanket-ignore exits after a successful CREATE. A new exit during relaunch must invalidate that attempt and consume its bounded recovery budget.
- Allow at most one in-place relaunch attempt per failed session before the existing full reconnect path. Reset that budget only after successful session/frame recovery, not after each exit packet or CREATE ACK. Failed relaunch must lead to bounded reconnect/backoff, never endless CREATE/prelude traffic.
- Guard layout ACK/timeout callbacks by generation so retired work cannot mark a new layout ready or tear down a recovered session. Preserve message-magic retirement, completion accounting, wake claims, and current transport-owner isolation.
- Add compact lifecycle logs: monotonic time, generation, phase, event type/source/systemExitReasonCode, recovery attempt, readiness stage, timeout label. Add a presentation correlation ID to notification admission/reveal logs without notification content or raw keys. These logs should explain whether the first abnormal exit predates host recovery and whether it recurs after A/B.

### C. Verify FGS provenance; no speculative runtime change

- Inspect the installed APK's manifest and service implementation using existing APK tooling, or install a newly identified build during approved device validation. Record APK SHA-256, source commit plus dirty patch identity, build timestamp and install timestamp; do not equate versionCode with source identity.
- Add a narrow regression check for connectedDevice-only transport FGS type and manifest agreement if no equivalent check exists. A service-level test should cover rejected starts returning cleanly when the existing harness supports it.
- Exercise existing background reconnect and explicit transport start with microphone permission unavailable on a test target. Do not revoke permissions on the user's phone merely for this check. If the installed build already contains the fix and there are no fresh failures, close this issue as historical. Keep actual microphone capture service architecture outside this work.

## Required regression cases

- Preparation succeeds with a live layout and no displayed frame, without waiting for a blank ACK. A JS timer and subsequent frame submission continue running while readiness is pending.
- Reveal waits for current notification content and actual display readiness. Retained private app pixels never flash on notification-only wake.
- Concurrent notification/user wake, replacement, dismissal, timeout, disconnect, charging and resleep cancel stale completions and preserve the latest presentation.
- Active session does not replay prelude; genuinely suspended session does.
- Replay the incident order: CREATE ACK -> ABNORMAL_EXIT -> SYSTEM_EXIT. Assert no bare CREATE against the terminated task, one bounded recovery operation, and reconnect fallback when recovery fails.
- Delayed old CREATE ACK/timeout cannot satisfy or fail the next generation. Intentional suspend exits cause no recovery. A real exit during recovery cannot be silently ignored.

Run focused notification-wake, notification-presentation, extension-surfaces and extension-navigation tests, plus executable Java lifecycle tests. Then run `npm test` and `./build.sh` using the repository's documented Android environment. Record pre-existing failures separately; a source-text assertion alone is insufficient for lifecycle behavior.

## Device acceptance and handoff

Capture fresh logs with Windows ADB before testing; preserve relevant lifecycle logs under workspace output with a unique filename. Do not clear logcat. Use the connected serial explicitly. No test should send real messages or flash firmware.

On an identified candidate APK, perform 20 sequential screen-off notification wake/expiry cycles, 10 awake notification cycles, and 5 replacement/concurrent-user-wake cycles. Confirm privacy, correct preview duration, no unexpected CREATE loop or ACK-timeout reconnect, and no host FGS crash. Include one suspend/resume and one disconnect/reconnect case. This is bounded acceptance, not proof that every periodic restart is fixed.

If ABNORMAL_EXIT still occurs, collect its reason and exact preceding commands and stop expanding this patch. Report the residual firmware/plugin failure separately; do not mask it with broad event suppression, enlarged timeouts, or disabled power saving. Firmware root-cause investigation requires a separate scope.

Deliver the focused diff, test results, build provenance, device trace summary, and remaining uncertainty. Keep changes reviewable as A (readiness), B (recovery), and C (historical FGS verification). Do not overwrite unrelated working-tree edits or publish/merge without the requested workflow authority.
