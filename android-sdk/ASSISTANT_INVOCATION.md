# App-owned assistant invocation

Status: implemented in the local host/SDK change audited on 2026-09-13. Android
build and physical-device acceptance are still pending. This document describes
the control contract; it does not establish a released SDK or host version.

## Purpose and ownership

An assistant provider can own the complete wakeword interaction in its normal
SDK window. The host owns wakeword admission, provider selection, window focus,
and microphone authorization. The app owns recording presentation, transcript
review, conversation creation, and response presentation.

The host must not contain the provider's project, model, backend, or UI logic.
Apps should route this invocation to the same application action as their own
Ask Assistant button. Whether that action starts a fresh conversation or resumes
one is app policy. T3 starts a fresh conversation.

This uses the existing session-scoped control transport and window lifecycle.
It does not add AIDL methods, a rendering API, an Android activity launch, or a
T3-specific package check.

## Declaration

Publish an `assistant` extension with this configuration:

```json
{
  "feature": "assistant",
  "enabled": true,
  "configuration": {
    "label": "My assistant",
    "invocation": "app"
  }
}
```

On a host supporting this contract, `invocation` accepts `app` or `host`.
Omitting it preserves `host` behavior. For compatibility with older validators,
legacy providers should omit the field rather than explicitly send `host`.

| Mode | Wakeword behavior |
| --- | --- |
| Field omitted or `host` | Faceclaw records/reviews speech and presents its assistant overlay. The provider receives a text request. |
| `app` | Faceclaw opens the selected provider's normal SDK window and sends the invocation event. The app owns the interaction. |

The app still needs host approval and the effective `assistant` grant/priority.
It does not need to own `ui.launcher`, `ui.navigation`, or any other global UI
feature. Existing independent dictation, transcription, and device-tool grants
remain required for the operations the app uses.

## Host-to-app event

The service receives `onControlEvent` with `ControlEvent.type` equal to
`extension-event` and this `ControlEvent.data` envelope:

```json
{
  "feature": "assistant",
  "generation": 42,
  "type": "event",
  "data": { "event": "invoke" }
}
```

`generation` is the current effective assistant feature generation, not a
constant or a window-surface generation. There is no prompt, request ID, or
provider response associated with this event. Do not call `respondExtension`
for it. Existing assistant `request`, `progress`, `result`, and `cancel` behavior
remains separate; providers supporting those requests must continue to handle
them. This option currently routes the wakeword only, not every host assistant
entry point such as phone keyboard input or Send to Assistant.

An app's handler should:

1. Check the outer event type, feature, inner type, and `data.event`.
2. Match the envelope generation and selected component against the current
   effective extension snapshot. Reject stale or unavailable ownership.
3. Confirm the app window is visible and preserve any active recording, draft,
   review, or other protected flow. Do not queue the event for later replay.
4. Call the same app action used by its Ask Assistant button.

Use the approved SDK service callback, not an exported broadcast or a custom
unauthenticated interface. The current T3 example validates envelopes in
[`app/extensions/host.ts`](../../faceclaw-t3-app/app/extensions/host.ts), dispatches
in [`app/runtime.ts`](../../faceclaw-t3-app/app/runtime.ts), and shares
`askAssistant()` in [`t3-app.ts`](../../faceclaw-t3-app/app/apps/t3/t3-app.ts).
These are sibling-workspace examples, not required dependencies for another app.

## Admission, ordering, and failures

The host first honors its wakeword preference: `off` does nothing; wake-only
behavior wakes without invoking an assistant. For voice input, the host resolves
the available assistant provider before opening a host voice dialog.

For an app-owned invocation, the host:

1. Rejects takeover while locked, while an invocation is opening, or while the
   foreground APK has a protected flow, review, or refinement.
2. Dismisses extension presentation surfaces without restoring sleep. A
   remaining shell modal blocks invocation. Existing voice/keyboard capture is
   not interrupted.
3. Opens and focuses the selected provider's normal SDK window. It waits for
   window setup, then checks visibility and current provider ownership again.
4. Records the wakeword as recent user intent for that window and sends `invoke`.

Window `open` can arrive before focus/visibility. Apps must not start recording
merely because they received `open`; use the subsequent invocation event. The
host grants the same five-second recent-input opportunity checked by existing
capture requests. The usual dictation grant still applies. Selecting the app's
own transcription provider also requires its separate transcription grant.
Capture results are draft text, not authorization to send a message or run a
remote assistant turn without the app's intended review/submit action.

Provider generation, lock, visibility, and protected-state checks are repeated
after asynchronous window setup. A revoked or changed provider does not receive
the invocation. Duplicates while opening are swallowed; after opening, the app
must preserve its active flow and publish its window-protection state.

If no available app-owned provider exists, normal host assistant behavior remains
available. Once an app-owned attempt is admitted, blocked or failed delivery does
not automatically start another capture or retry through a different provider.
The event has no acknowledgement or exactly-once/replay guarantee. A user can
retry explicitly. The current implementation does not show a dedicated launch
failure message.

## Compatibility and deployment

This is a control-schema enhancement within wire protocol major 2, not evidence
that every protocol-2 host supports it. The public dependency remains `1.0.0` in
this local repository; that version alone cannot identify support. There is
currently no negotiated capability flag for app-owned assistant invocation.

Both the app-side `ExtensionContract` validator and the host validator must
understand `invocation` before an app publishes it. Older validators reject the
unknown key rather than ignoring it. Because declaration validation applies to
the supplied array, an unsupported field can reject the whole publication,
including unrelated features. A successful local send is not proof of host
acceptance; inspect the effective extension snapshot.

For the initial coordinated deployment, update the host and build the adopting
app against the updated SDK. Existing apps that omit the field need no rebuild.
Subsequent app changes to layout, review, project/model selection, or backend
behavior require only the app update while this contract remains unchanged.

Before distributing an adopting app across mixed host versions, define a
reliable host-support signal or an explicit supported-host requirement. Do not
infer support from protocol major 2, the `1.0.0` dependency, an empty effective
snapshot, or successful Binder connection. Automatic negotiation and an
older-host fallback declaration are not implemented by this patch.

## Audit decision and alternatives

The 2026-09-13 audit found that host routing support is necessary for an app to
own the wakeword UI from outside its window:

| Existing mechanism | Why it is insufficient alone |
| --- | --- |
| Window/input callbacks | `Shell.handleInput` consumes the wakeword before forwarding input to a foreground window. A background app never receives it. |
| `ui.navigation` configuration | Declares fixed gesture mappings. It does not subscribe an app to global wakeword events. |
| `requestOpenWindow` | Lets an app ask to open itself, but supplies no wakeword signal or recent capture authorization. Shell overlays can also reject it. |
| Assistant text provider | Receives text after Faceclaw has already recorded/reviewed speech. Redirecting then cannot remove the original host UI. |
| Tool/interface capabilities | Advertise callable operations; no existing host path dispatches the wakeword to those operations before capture. |

The selected design adds only the missing declaration and invocation semantics,
reusing existing window opening, generation checks, control events, and capture.
A different future app can use the same contract without host package-specific
routing. Extend host semantics only when an app requires behavior this contract
does not express; ordinary app UI work stays in the app.

Source anchors:

- [`ExtensionContract.java`](sdk/src/main/java/com/faceclaw/sdk/ExtensionContract.java): allowed configuration fields and declaration validation.
- [`shell.ts`](../app/ui/shell/shell.ts): wakeword interception before host voice UI.
- [`extension-platform.ts`](../app/apps/external/extension-platform.ts): provider selection, admission, generation checks, invocation dispatch.
- [`platform.ts`](../app/apps/external/platform.ts): window opening and recent-input capture authorization.
- [`FaceclawExternalApps.java`](../App_Resources/Android/src/main/java/com/faceclaw/app/FaceclawExternalApps.java): connected/selected-provider checks at the native send boundary.

## Verification and remaining acceptance

Automated coverage in `tests/extension-lifecycle.test.cjs` checks selected-provider
invocation, duplicate opening, generation changes, legacy host fallback, lock,
and protected-flow admission. `tests/extension-capture.test.cjs` covers capture
grants and request isolation. T3's `tests/t3-ui.test.cjs` checks that invocation
and the dashboard button share the fresh compose flow.

Before release, build both initial adopting APKs and verify on a device: wakeword
from a built-in app, another APK, launcher, and sleeping display; capture/review
and submit in the provider UI; repeated wakeword during a protected flow;
revocation/disconnection during opening; provider switching; and a legacy
provider with no invocation field. Remaining shell modals intentionally block
this patch, so it does not promise takeover from every Faceclaw screen.
