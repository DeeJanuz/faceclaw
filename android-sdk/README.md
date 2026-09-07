# Faceclaw Android application SDK

Install applications as ordinary Android APKs. Faceclaw discovers an exported application service, asks the user to approve the installed package/signers, and binds it without loading its code. Each app keeps Android permissions and data under its own UID. The app's SDK asks the user to select one verified host. Switching hosts clears the old host's sessions, app approval, notification cache, and capability grants; it does not revoke the app's separate backend account credential.

New app approvals default notifications, dictation/review, message text previews and declared-source suppression to enabled. The approval dialog shows these choices before the user confirms. Defaults are written with the initial approval, not applied as runtime fallbacks: upgrades and reapproval of an existing record preserve explicit choices and legacy missing-key behavior. Discovery and phone setup Intents never grant capabilities. Revoking approval clears its settings; a later fresh approval uses the new defaults. Source suppression still requires a declared source and a connected approved app.

## Build and consume

Requires Android SDK 35 and JDK 17 or newer for Gradle. The Java API targets Java 11, Android 24+, AGP 8.9.2. Build from this directory:

```sh
./gradlew :sdk:assembleDebug :sdk:testDebugUnitTest :sdk:lintDebug
```

An Android application may include this build in `settings.gradle.kts`:

```kotlin
includeBuild("../faceclaw-app-platform/android-sdk")
```

Then depend on `implementation("com.faceclaw:sdk:0.1.0")`. Public artifact publication is a separate release task. Faceclaw's NativeScript build compiles the same SDK Java sources through `App_Resources/Android/app.gradle`.

Declare one service extending `com.faceclaw.sdk.FaceclawAppService`:

```xml
<service android:name=".GlassesService" android:exported="true">
  <intent-filter><action android:name="com.faceclaw.action.APP_SERVICE" /></intent-filter>
  <meta-data android:name="com.faceclaw.PROTOCOL_MAJOR" android:value="1" />
  <!-- Optional: enables the host's HTTPS endpoint / one-time code setup form. -->
  <meta-data android:name="com.faceclaw.CONFIGURATION" android:value="bridge" />
  <!-- Optional: one declared source the user can explicitly suppress on glasses. -->
  <meta-data android:name="com.faceclaw.SUPPRESS_PACKAGE" android:value="example.original.app" />
</service>
```

The SDK manifest merges its non-exported host-selection activity. SDK approval stores are bound to a marker in Android no-backup storage: restoring preferences without the installation marker clears grants. Apps must separately protect their own credentials, message caches, and backup policy. Do not export a service that exposes private functionality through a second unauthenticated interface.

## Phone setup entry points

An app may provide its own Android launcher Activity for setup and connection status. Keep credential entry in that app's private configuration path; a public launcher Intent must not configure credentials or authorize a host from caller-provided extras.

Current Faceclaw hosts advertise an Activity for `com.faceclaw.action.MANAGE_APPS`. An app can declare that action under its manifest `<queries>`, discover available hosts, and launch the selected Activity with an explicit component. The optional `appPackage` string is only a navigation hint: the host validates it, discovers installed app services itself, and shows its normal approval controls. Opening this Activity never grants app approval, selects a host, or enables capabilities automatically. Older hosts can still be configured through **Settings > Installed apps > Manage Android applications**.

The SDK exposes the selected host package, its verified installed status and its friendly application label for an app's local setup UI. Saved host selection is not proof of an active connection. Bridge pairing and backend availability remain the individual app's responsibility.

## Authoring

The [Kotlin Canvas example](examples/CanvasAppService.kt) shows a complete service mixing Android drawing with Faceclaw helpers, handling viewport/visibility changes and ring clicks. Copy it into an Android app that depends on this SDK and declare its service as above. It uses the SDK's normal host approval flow.

Override `onHostConnected()`, `onHostDisconnected()`, and `onHostEvent(String, JSONObject)`. Callbacks run on the main looper. `submitBitmap(Bitmap)` copies the bitmap synchronously and must also run on the main looper; recycling after return is safe. Handle `render` and visible `visibility` events to submit the first frame after opening. Pause animations when hidden/asleep, but keep approved background message receiving independent of the visible window.

Use ordinary Canvas drawing, optional `Ui.text`, `Ui.card`, `Ui.wrap`, and `Ui.layers` helpers, or mix them. `Ui.layers` composites ordered ARGB bitmaps using standard Canvas source-over alpha against black; submission converts the final result to grayscale. Pixel 1 represents opaque black and 255 white. This version sends pixels for both styles; it has no remote glyph-cache commands. The host owns its shell, viewport, BLE link, sleep policy, reserved gestures, and final incremental display updates.

`FrameAnimator` is an optional main-thread clock for app-owned transitions. Construct it with a progress callback, call `start(durationMs)`, and render/submit your own bitmap for each callback. It emits eased progress from 0 to 1 at intervals of up to 40 ms; delivery can be slower under load. Call `cancel()` when hidden, disconnected, resized, or when the source content becomes invalid. A callback can safely cancel or replace its own animation. It does not retain scene bitmaps or guarantee display frame rate.

| Host event | Data |
| --- | --- |
| `capabilities` | Current `notifications`, `dictation`, `previews`, optional `notificationReplies`, plus `maxWidth`, `maxHeight`, `maxText`, `maxNotificationText`; handle again when grants change |
| `open` | `width`, `height`, `generation`, opaque `target` (empty for launcher entry) |
| `resize` | `width`, `height`, new `generation` |
| `input` | Existing Faceclaw `type`, timestamp and input source |
| `visibility` | `visible`, `screenOn` |
| `render`, `close` | Empty object |
| `configure` | HTTPS `endpoint`, short-lived single-use `code`; exchange in the app, never log/save the code |
| `dictation-result` | `requestId`, opaque `target`, exact reviewed `text`, `confirmed: true` |
| `dictation-rejected` | `requestId`, displayable `reason` |
| `notification-reply` | `id`, opaque `target`, `replyToken`, exact reviewed `text`, `confirmed: true` |

Ring input includes `scroll-up`, `scroll-down`, `click`, and `double-click`; the shell reserves system gestures. The viewport is negotiated on each open/resize; never assume 576×288. Typical default content is 576×260 after shell chrome. The maximum supported allocation is 640×480.

`postNotification(id,target,title,text,expiresAtMs)` and `removeNotification(id)` require the user's notification grant. Limits: ID 128, target 512, title 160, body 4096 characters; 32 active entries per app and 128 total. Notification bodies stay in memory, expire no later than 30 days or the supplied earlier time, and disappear on disconnect/revocation. Set the earlier true message expiry and withdraw deleted content. Do not replay synchronized backlog as new alerts. Message previews default to sender/text; users can choose sender-only. Alerts enter Faceclaw's existing popup, inbox, and tray. Opening an alert passes its opaque target to the same app. App notification content is excluded from Faceclaw's generic assistant notification tools; assistant access belongs to the backend's separate grants.

Hosts may replace their notification presentation without changing an APK. To support replies from a host notification reader, check the negotiated `notificationReplies` capability and publish with `postNotification(id,target,title,text,expiresAtMs,replyToken)`. Use a new opaque, unguessable token of at most 128 characters for each actionable publication/revision. Empty or omitted tokens leave the notification with only its Open conversation action. Replies require both notification and dictation grants. Older hosts that do not advertise this feature remain usable without notification replies.

The host owns dictation and the explicit Send review. A `notification-reply` event contains that exact reviewed text, up to 8000 characters. The SDK consumes the current published token once before delivering the event. The app must additionally verify its source content, account, conversation, expiry and current authorization, then use its normal draft/send path. This event can arrive while the app's own window is hidden; it must not require opening that window or a second Send review. Replacing/removing a notification, losing authorization or switching hosts invalidates its action.

Report one final outcome with `reportNotificationReplyResult(id,replyToken,status)`, where `status` is `sent`, `draft-saved`, `unknown` or `rejected`. Use `sent` only after your transport confirms the send; it does not mean recipient delivery or read. Save offline drafts without automatically sending later. The host initially displays submission, applies a 20-second result deadline, and treats a missing result as unknown. Results for stale, removed, foreign or already completed actions are ignored. Unknown outcomes must never trigger an automatic retry.

`requestDictation(requestId,target,label[,initialText])` requires a dictation grant and a recent user gesture in that app's visible window. It returns local acceptance; handle `dictation-rejected` as well. Existing drafts up to 8000 characters open at review without starting the microphone. The host shows a single explicitly named Send action, never auto-sends, and returns the exact text only after user selection. Cancel pending reviews via `cancelDictation(requestId)` when the account, target, source message, or authorization changes. Closing/hiding/locking the window or revoking access cancels host review. The backend must still enforce its account/conversation and operation authorization at dispatch.

## Voice search

Hosts may advertise `searchDictation: true` in their capabilities. With that support and the dictation grant, an app can call `requestSearchDictation(requestId, target, label)` from a recent gesture in its visible window. The host records an authoritative final transcript and presents **Search**, **Try again** and **Cancel**. It has no message destination, send action or draft refinement.

Handle `search-dictation-result` with `{requestId,target,text,confirmed:true}` as a local query only. Queries are nonempty and limited to 256 characters. Match the current request, target and app state before consuming the result once. Handle `search-dictation-rejected` for unavailable or cancelled searches, and call `cancelSearchDictation(requestId)` when leaving the search state. Search and message-review events and cancellation purposes are distinct; a search result must never authorize a message send. The host cancels search on lost visibility, revoked dictation access, screen lock or connection loss.

## Protocol and trust boundary

Messenger control messages use Android's actual `Message.sendingUid`, an unambiguous package identity, the current complete signing set, an unguessable connection session, and a negotiated major version. Shared-UID packages are rejected. Approvals are specific to the Android user/UID, package, component, and signing identity. A signature or installation identity change invalidates approval. Controls are limited to 65536 UTF-16 characters. External code cannot obtain host native objects, foreign notification actions, host BLE access, or backend credentials.

Each frame has negotiated dimensions, monotonically increasing sequence, and window generation. Android 27+ uses `SharedMemory`, read-only in the sender after writing; the host always copies into private memory before native composition, including with malicious senders. Android 24–26 has a bounded byte-array fallback. Maximum frame size is 307200 bytes. The SDK keeps one in-flight frame and coalesces to one pending frame, the host coalesces pending presentation, and stale/invisible frames are dropped. Hosts reject abusive message rates. These are memory/queue bounds, not measured performance guarantees.

Source suppression is an explicit per-app host grant, restricted to the package declared in the manifest and the host's current Android profile. It changes only glasses presentation; underlying official notification preferences and phone notifications remain untouched. Host connection loss releases the temporary suppression. Disabling a separate assistant grant must never disconnect this app.

## Verification fixtures

`fixture` and `host-tests` are disposable synthetic APKs, excluded from the production SDK artifact. The fixture authorizes only known test host packages with its own debug signer through a fixture-only setup service; never ship these test subclasses in a product. The tests exercise real separate-UID Messenger IPC, SharedMemory drawing, hidden/asleep rendering, malformed/stale frames, capability rejection, revoked signer pins, and forged sender identity.

```sh
./gradlew :fixture:assembleDebug :host-tests:assembleStandaloneDebug :host-tests:assembleStandaloneDebugAndroidTest
adb -s emulator-5554 install -r fixture/build/outputs/apk/debug/fixture-debug.apk
adb -s emulator-5554 install -r host-tests/build/outputs/apk/standalone/debug/host-tests-standalone-debug.apk
adb -s emulator-5554 install -r host-tests/build/outputs/apk/androidTest/standalone/debug/host-tests-standalone-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.faceclaw.sdk.hosttest.test/com.faceclaw.sdk.hosttest.BoundaryTest
```

The parent repository's `npm test` covers notification expiry, namespace isolation, retention, and bounded cache behavior. Physical glasses performance, manufacturer background restrictions, and side-by-side host switching also require device acceptance before release.

The `upstream` and `t3` instrumentation flavors target already-installed, debug-signed Faceclaw APKs. Build only their `assembleUpstreamDebugAndroidTest` / `assembleT3DebugAndroidTest` tasks and install only the resulting **androidTest APKs**. Never install those flavors' stub application APK over a real Faceclaw install. The tests touch only synthetic fixture grants, preserve other app approvals, and report submission-to-private-frame-copy timing from a monotonic timestamp encoded in synthetic pixels. That timing includes SDK grayscale conversion, IPC and host authorization; it excludes BLE delivery and the display panel.
