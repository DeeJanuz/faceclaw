# SDK app independence audit

Date: 2026-09-15. Host checkout: `4cd862c`, initially clean.

## Conclusion

The SDK is a useful independent-APK and rendering boundary, but an incomplete behavior boundary. App pixels, backend code, and many workflows can already change independently. App navigation, window geometry, voice entry points, and host integration still depend on a small, evolving vocabulary implemented in the host.

The best next investment is a versioned, per-app behavior contract with acknowledgements and reliable lifecycle state. Implement its host support once, then hold that host build fixed while testing independent app updates. Adding individual host commands for each app bug will perpetuate the coupling.

This is a source audit of the Android SDK, its host adapter, and selected T3/Signal/Spotify integration paths. Findings below distinguish SDK defects from missing public contracts. Host implementation references demonstrate what the SDK cannot currently express. This is not a complete security audit or physical-device certification.

## 1. High: protocol compatibility does not identify feature support

**Evidence:** `android-sdk/sdk/src/main/java/com/faceclaw/sdk/Protocol.java:7` reports SDK `1.0.0` and wire major 2. `ExtensionContract.java:35` accepts the newer assistant `invocation` setting, but rejects unknown configuration keys at line 40. `android-sdk/ASSISTANT_INVOCATION.md`, Compatibility and deployment, explicitly documents that older protocol-2 hosts reject this field, potentially rejecting the entire declaration array. No negotiated invocation capability exists. `FaceclawExternalApps.java:317` advertises rendering capabilities in the initial snapshot; its separate control capabilities at line 137 do not fill that gap.

**Adversarial scenario:** update only T3 to publish app-owned assistant invocation. An older, otherwise compatible host rejects the publication, including unrelated extensions. A successful Binder connection and `publishExtensions()` result do not prove compatibility.

**Add:** a typed feature catalog with per-feature versions, supported configuration schema versions, limits, and explicit unsupported responses. Separate support from user grants and effective provider selection. Validate required features atomically; allow optional declarations to negotiate independently. Version and distribute immutable SDK artifacts, with an older-host compatibility matrix.

**Acceptance:** a new app on an older host retains supported features and identifies the unsupported feature without guessing from an empty extension snapshot.

## 2. High: ordinary app policy is entangled with global provider ownership

**Evidence:** `ExtensionContract.java:8-10,29-30` restricts navigation and layout to fixed static feature configurations. `app/apps/external/platform.ts:89` grants `ownHeightMode` only to the selected global `ui.window-layout` provider. Other APK windows use `min`. `android-sdk/NAVIGATION_AND_WAKE.md` specifies one navigation provider's policy across the shell. `FaceclawAppService.java:241-248` exposes window opening, sleep and system-menu requests, but no semantic root-back or general per-window input policy.

**Adversarial scenario:** Signal needs a taller reader or Spotify wants a different root-back behavior while T3 owns global layout/navigation. The ordinary app cannot express this as its own window policy. Changing global settings affects unrelated apps; adding another enum still requires host changes.

**Add:** a per-window policy API for preferred viewport, chrome/insets, app-menu availability, and input handling. Define precedence explicitly: protected host actions, granted window policy, user-selected global defaults. Add semantic back with handled/at-root results, distinct from explicit sleep and close. Support scoped gesture claims and cancellation on focus loss, while preserving a host escape gesture.

**Acceptance:** T3, Signal and Spotify each choose their own permitted geometry and app navigation without becoming global providers or modifying the host.

## 3. High: SDK success does not mean the host applied a control

**Evidence:** `IFaceclawHostSession.aidl` declares `sendControl` one-way. `FaceclawSession.java:107` returns true after sending. `FaceclawExternalApps.java:324-350` silently drops rate-limited traffic and controls that are ineligible for the current window. `app/apps/external/platform.ts:182-197` silently rejects several window/system controls on admission checks. Some operations already have results, but there is no uniform result contract.

**Adversarial scenario:** an app requests focus or publishes a policy, receives true, and advances its UI. The host rejected it because of an overlay, window state, unsupported field, or traffic limit. Debugging requires host logs or patches.

**Add:** typed request handles and results carrying request ID, session/window generation, accepted/rejected/applied state, stable reason code and timeout. Distinguish transport delivery from application. Provide a content-free app diagnostics callback. Never automatically retry an unknown side-effect outcome.

**Acceptance:** every bounded control either reaches a documented terminal result or a documented unknown/timeout state; unsupported and temporarily blocked are distinguishable.

## 4. High: menu/protection caches can suppress necessary window updates

**SDK defect, established by source path; not device-reproduced.**

**Evidence:** `FaceclawAppService.java:229-230` caches `lastMenuAvailable` and `lastProtected` after transport success, returning true without sending equal values. They reset on session connection at line 167, not window open/close. The host accepts these controls only while open (`FaceclawExternalApps.java:349-350`). A new host window starts with neither state set (`app/apps/external/platform.ts:103-107`).

**Adversarial scenarios:** set menu availability in `onSessionReady` before a window opens, then repeat it on open; the first request can be dropped and the second suppressed. Alternatively, close and recreate the window within the same service session, then repeat the same setting. Protection state has the same cache design.

**Add/fix:** model these values as desired window state, scoped to window generation. Replay on each open/recreation; only mark them applied after acknowledgement. In the short term, clear caches on window lifecycle changes and avoid caching unacknowledged delivery. Recheck admission at the host regardless of app state.

**Acceptance:** set-before-open, close/reopen, reconnect, and delayed/rejected updates all converge to the desired state. This should be fixed centrally in the SDK rather than each app toggling values as a workaround.

## 5. High: voice and invocation remain workflow-specific contracts

**Evidence:** `FaceclawAppService.java:200-228` has separate message review, search dictation, capture dictation, and refinement helpers. `app/apps/external/platform.ts:200-275` routes them to host-owned workflow state and shell capture. App-owned capture/transcription already exists, so it is incorrect to say the SDK cannot support an app-owned editor. However, `android-sdk/ASSISTANT_INVOCATION.md` limits app-owned invocation to wakeword; phone keyboard and Send to Assistant remain separate paths. The invocation event lacks a request ID and acknowledgement.

**Adversarial scenario:** an app fixes its compose flow, but only wakeword uses it. Another host entry point still invokes the host's older capture/review flow. A new voice use case risks becoming another specialized host command.

**Add:** a typed invocation envelope for supported entry points, including trigger, target, generation, expiry, completion and cancellation. Factor capture/transcription into a scoped session primitive with explicit ownership and lifecycle, keeping convenience methods for existing workflows. The app should own draft editing and presentation. Host microphone admission, lock checks, consent and trusted review authority remain enforced.

**Acceptance:** wakeword, explicitly supported text entry and app-button entry reach one app handler; changing the app editor requires only an app release. An expired or cancelled invocation cannot reopen recording.

## 6. Medium: control state is not reconstructed from the advertised atomic snapshot

**Evidence:** `HostSnapshot.java` carries grants, style and extensions. `FaceclawAppService.java:168` forwards snapshots directly to the app. Internal `sharedStyle`, `extensionSnapshot`, and capability booleans are instead updated by later control events at lines 143-145; disconnect clears them. The native snapshot also uses an empty `hostState` (`FaceclawExternalApps.java:317`), while the TypeScript adapter publishes it separately.

**Adversarial scenario:** an app implements the documented snapshot callback, but SDK convenience accessors still expose defaults or rely on subsequent event ordering. Each app must reconcile multiple sources of state.

**Add:** one SDK state reducer that applies a complete negotiated snapshot before callbacks/rendering, then applies ordered deltas. Document a lifecycle state machine for session connectivity, window existence, focus, visibility and display power. Track desired replayable state separately from one-shot actions.

**Acceptance:** initial connection and reconnect provide the same coherent state to accessors, UI helpers and callbacks without application-specific event ordering patches.

## 7. Medium: rendering failures and resource exhaustion lack app recovery tools

**Evidence:** `ResourceRegistry.java:10-25` accumulates resources for the session with no release/eviction API. Quota exhaustion is sticky, and accepted resources replay on reconnect. `RenderSurface.java:123-124` swallows renderer/executor failures and invalidates again. `FaceclawSession.java:104-111` broadly catches local serialization/send failures and detaches. Those local detach paths do not themselves notify `FaceclawAppService` of a connection-state transition.

**Adversarial scenario:** a long-lived app registers changing images/glyphs until it hits the quota, or a persistent renderer exception causes repeated render attempts without a useful app diagnostic. The developer cannot distinguish their renderer bug from host transport trouble.

**Add:** resource usage metrics, safe release with in-flight/retained-scene references, bounded eviction and an explicit raster fallback state. Add renderer failure callbacks and suspend/retry policy. Separate local validation errors from Binder death and route connection failures through one lifecycle transition.

**Acceptance:** resource churn stays bounded without restarting the session; a deliberately throwing renderer reports the cause and stops repeated work until app recovery. A bad local submission does not silently leave service and session connectivity disagreeing.

## 8. Medium: independent APK packaging exists; a reusable standalone app runtime does not

**Evidence:** services are ordinary APK components. Signal and Spotify consume the sibling SDK source project; T3 does so in `App_Resources/Android/settings.gradle`. `FaceclawSession` and `RenderSurface` have package-private constructors and directly depend on the host session. `android-sdk/javascript/index.js` provides animation helpers, not a complete typed client/runtime adapter. The SDK README states that artifacts are not remotely published.

**Meaning:** independently installed APKs already work. Running phone business logic without a host is possible in ordinary Android code. But sharing the same SDK presentation/input lifecycle between a phone-only UI, a fake host, and glasses is not a supplied abstraction. Operating the glasses without the Faceclaw host is a separate device-runtime project, not a small SDK option.

**Add:** a presentation/input interface with a Faceclaw adapter and local preview/test adapter; a reference app whose phone functionality survives host absence and attaches to glasses later; supported JavaScript bindings for the actual protocol; immutable SDK releases rather than sibling-source dependency as the sole distribution path.

**Acceptance:** install and use app phone functionality with no host; attach, revoke and reconnect a host without losing backend state; run app behavior tests against a fixed host contract without building the host.

## Recommended sequence

1. Fix SDK state-cache/reconstruction defects and expose local diagnostics. These are central SDK correctness work; do not wait for a larger redesign.
2. Add feature negotiation and acknowledged controls on both sides. Freeze a documented baseline host after validation.
3. Add scoped window/input policy and generic invocation/capture contracts. Keep global shell replacement an explicit user choice.
4. Add resource recovery, local preview/test runtime, and versioned artifact distribution.

Use extensible, namespaced capability declarations for app-owned operations where possible. The existing `CapabilityContract` and `app/apps/external/app-capabilities.ts` already route dynamically declared operations. Reuse that model; do not create package-specific routing or an unrestricted host reflection/command escape hatch.

The host should continue owning BLE transport, global composition, device power, lock/privacy enforcement, grants, provider arbitration and protected gestures. Bugs in those mechanisms can still require host releases. The realistic success criterion is that ordinary app UI, navigation within granted boundaries, backend, editor and workflow changes do not.

## Verification performed

- `npm test`: TypeScript compilation succeeded; 407/412 tests passed. Two failures require missing `javac` (notification compositor and render cadence). Three touch-input assertions failed (temple provenance, contact behavior, and contact clearing). These are observed baseline failures, not attributed here to an SDK defect.
- `node --test tests/extension-*.test.cjs tests/external-*.test.cjs`: 86/86 passed.
- `./gradlew :sdk:testDebugUnitTest`: blocked before execution because Java/JAVA_HOME is unavailable. No Java runtime was found in the inspected local locations.
- No device/browser tests, application fixes or host implementation changes were performed. The cache defect and other source-derived failure scenarios need focused Java/instrumentation coverage before claiming runtime reproduction.

Passing current extension tests does not establish app independence. Add a release gate that holds an older host APK fixed while newer app APKs change window geometry, back handling, menus, voice editing, capability declarations and resource usage. Include unsupported features, dropped controls, close/reopen, Binder recovery, revocation and competing providers. That gate directly measures the goal of this audit.
