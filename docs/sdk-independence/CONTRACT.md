# Faceclaw app-independence contract v1

Status: implementation contract for P02 and later. This document freezes the
wire shape and ownership rules for the first app-independence implementation.
It does not change protocol major 2 by itself. Existing protocol-2 clients keep
their current behavior.

## Scope and compatibility

The authenticated Android app session remains the transport boundary. Existing
AIDL methods remain usable. New messages use the existing bounded `ControlEvent`
transport until a future protocol-major decision proves that an AIDL addition is
necessary. The host and SDK advertise this contract independently of
`Protocol.VERSION`, `Protocol.SDK_VERSION`, Android package identity and user
grants.

Contract version `1` is the version defined here. A host supports a contract only
when its authenticated catalog contains the feature ID and a version greater than
or equal to the app's required minimum. A missing, malformed, or stale catalog
means unsupported. Binder connection, SDK version, protocol major, extension
publication success, and a grant do not imply support.

The app may declare a feature as `required` or `optional`:

- An unsupported required feature makes that declaration's negotiation fail
  atomically. Existing legacy declarations remain active according to their old
  rules unless the app explicitly selected the new contract as required.
- An unsupported optional feature is reported individually and is not sent to an
  old validator. The SDK may use only the fallback named in the declaration.
  It must not reinterpret a new option as an older option with different meaning.
- A grant authorizes a supported feature. It does not select the provider, change
  a feature's schema, or grant a different capability.
- Provider selection and priority are host policy. An app can learn whether it is
  selected, but cannot claim global ownership by publishing a declaration.

All JSON size limits count UTF-8 bytes, not Java `String.length()`. Integers must
be safe signed 64-bit values where the type below says `int64`; IDs are opaque
tokens and must not contain transcripts, package secrets, or user content.

## Common values

### IDs and time

`requestId`, `revisionId`, `invocationId`, and `captureId` use the existing SDK
token grammar `[A-Za-z0-9_-]{1,128}`. An app must use a new value for a different
side effect. A duplicate with the same ID and byte-identical body is idempotent
within the retention window; a duplicate ID with a different body is rejected.

Deadlines use Android boot-relative `elapsedRealtime` milliseconds in the field
`expiresAtElapsedMs`. Both processes run on the same device, so this clock has a
shared origin. Receivers reject a deadline that is in the past, more than five
minutes in the future, or absent where required. They may reject a request early
when the local clock has advanced. A receiver never extends a deadline on behalf
of an app. JavaScript adapters convert their local monotonic source to the Android
value at the authenticated transport boundary; wall-clock time is never used for
authorization or expiry.

### Result states

Control and invocation results use exactly one of:

`submitted`, `accepted`, `applied`, `rejected`, `cancelled`, `unknown`, `expired`.

`submitted` and `accepted` are nonterminal. `applied`, `rejected`, `cancelled`,
`unknown`, and `expired` are terminal. `unknown` means the request may have
reached a side-effect boundary; an app must obtain new user intent before trying
the side effect again. It is not equivalent to `rejected`.

Reasons are stable tokens, not free-form text:

`unsupported`, `malformed`, `too_large`, `rate_limited`, `not_granted`,
`not_selected`, `not_visible`, `locked`, `protected_flow`, `stale_session`,
`stale_window`, `duplicate`, `conflict`, `expired`, `cancelled`, `busy`,
`host_unavailable`, `app_unavailable`, `invalid_state`, `internal_error`.

An error result may include a bounded human-readable `message` of at most 160
characters for diagnostics. It must never include input text, rendered pixels,
tokens, stack traces, or arbitrary exception text.

## Capability catalog

The host advertises a catalog in `HostSnapshot.capabilities` under the key
`appIndependence`:

```json
{
  "contractVersion": 1,
  "features": [
    {
      "id": "window.policy",
      "version": 1,
      "limits": {
        "maxPendingControls": 32,
        "maxPolicyBytes": 4096
      }
    }
  ],
  "epoch": 7
}
```

`contractVersion` is a positive integer. `features` has at most 64 entries; each
ID is lower-case namespaced and at most 128 bytes; feature versions are positive
integers. `limits` contains only known nonnegative integer keys and is at most 16
keys. `epoch` is a positive host catalog revision and is scoped to the current
authenticated session. The catalog itself is limited to 48 KiB.

The initial feature IDs are:

| Feature | Version 1 meaning |
| --- | --- |
| `window.policy` | Per-window bounds, chrome, menu availability and safe input/back policy. |
| `control.result` | Request IDs and terminal control outcomes. |
| `invocation.lifecycle` | App-owned assistant/text entry invocation envelopes. |
| `capture.session` | Generic app-owned capture lifecycle adapted to host microphone authority. |
| `resource.release` | Explicit resource release and usage results. |
| `standalone.adapter` | Local presentation/input adapter API; this is an SDK distribution feature, not a host permission. |

Only a feature fully implemented and tested at both native and TypeScript
boundaries may appear in a host catalog. A feature may be granted separately,
but catalog presence alone never grants it.

## Declaration negotiation

The new SDK helper sends a bounded `publish-contract` control event:

```json
{
  "type": "publish-contract",
  "data": {
    "contractVersion": 1,
    "epoch": 1,
    "features": [
      {
        "id": "window.policy",
        "minVersion": 1,
        "required": false,
        "fallback": "legacy-window"
      }
    ]
  }
}
```

The declaration has at most 32 features and 16 KiB UTF-8 size. `fallback` is a
known SDK token, not executable data. Version 1 permits `legacy-window`,
`legacy-control`, `legacy-assistant`, `legacy-capture`, and `none`. `none` is
valid only for an optional feature. Duplicate IDs, duplicate epochs, unknown
fallbacks, and unsupported required versions reject the declaration atomically.

The host responds with `contract-result`:

```json
{
  "type": "contract-result",
  "data": {
    "epoch": 1,
    "catalogEpoch": 7,
    "features": [
      {
        "id": "window.policy",
        "state": "accepted",
        "version": 1,
        "fallback": "none"
      }
    ]
  }
}
```

Each feature result has state `accepted`, `unsupported`, `denied`, `malformed`,
or `stale_session`. The host never partially applies a required declaration.
The result is scoped to the current authenticated session and catalog epoch.
Changing host, identity, grant, catalog epoch, or provider selection invalidates
the old negotiated result and emits a new snapshot/delta.

## Control requests and results

Supported app requests use a request ID and operation-specific data:

```json
{
  "type": "control-request",
  "data": {
    "requestId": "r_123",
    "operation": "window.menu",
    "windowGeneration": 42,
    "revision": 3,
    "expiresAtElapsedMs": 123456789,
    "payload": { "available": true }
  }
}
```

`operation` is one of `window.menu`, `window.protection`, `window.policy`,
`window.open`, `window.sleep`, `window.system-menu`, `window.back`, or
`capture.cancel` in version 1. Each operation has a fixed payload schema. A
payload cannot name a host preference, Android object, package, Java class, or
arbitrary command.

The host emits `control-result` for every accepted request ID, including
rejection, timeout/unknown and stale-generation cases:

```json
{
  "type": "control-result",
  "data": {
    "requestId": "r_123",
    "operation": "window.menu",
    "state": "applied",
    "reason": "",
    "windowGeneration": 42,
    "revision": 3,
    "catalogEpoch": 7
  }
}
```

The host emits `submitted` only when it has admitted a bounded request into its
queue and `accepted` only after all authorization checks pass. It emits `applied`
after the state mutation or side effect occurs. A one-way transport return value
means only local serialization and send succeeded; legacy boolean helpers retain
that transport-only meaning.

The host retains at most 32 request IDs per session and at most 64 terminal result
records per app. Retention lasts five minutes or until session replacement. A
late result for a forgotten request is ignored. Results are content-free.

## Window policy and ownership

`window.policy` is per app window. It does not make the app a global shell or
navigation provider. The policy payload is limited to 4 KiB:

```json
{
  "preferredHeightMode": "min",
  "preferredWidthMode": "display",
  "chrome": "host",
  "menuAvailable": true,
  "back": "app-then-host",
  "gestureClaims": []
}
```

Version 1 accepts height `min|medium|max`, width `display`, chrome `host|compact`,
back `app-then-host|host-only`, and at most four gesture claims from the host's
published claim list. The host returns actual bounds and an adjustment reason;
the app cannot force unsafe dimensions. Global provider layout remains a separate
feature and is the fallback when no per-window policy is negotiated.

Input precedence is fixed:

1. Host lock/privacy, display power, protected gestures and the system escape path.
2. A granted, negotiated app-local policy for the focused visible window.
3. The host's user-selected global defaults.

Claims end on hide, focus loss, lock, revocation, disconnect, close, or expiry.
The host sends a cancellation event so an app cannot retain a held contact.
Unknown input sources remain unknown and cannot satisfy a claimed source.

Back is a request with a bounded response. The app reports `handled` or `at-root`.
Only `at-root` lets the host apply its root policy. Explicit `sleep`, `close`, and
`switcher` are separate operations. A missing or late response follows the host
fallback once and cannot trigger a later second action.

Safe window state is split into:

- desired menu/layout policy, replayable for the current window generation;
- applied revision, acknowledged by the host;
- transient protected-flow state, cleared on close, revocation, lock and session
  replacement unless a new visible app flow asserts it.

## Invocation lifecycle

An app-owned invocation is delivered as `invocation-event` only for an advertised,
selected provider:

```json
{
  "type": "invocation-event",
  "data": {
    "invocationId": "i_123",
    "entryPoint": "wakeword",
    "providerGeneration": 8,
    "windowGeneration": 42,
    "expiresAtElapsedMs": 123456789,
    "target": ""
  }
}
```

Version 1 entry points are `wakeword`, `text-entry`, and `app-button`. The event
has no transcript and grants no send authority. The app must acknowledge with
`invocation-result` as `accepted`, `rejected`, `completed`, `cancelled`, or
`unknown`. An invocation is delivered once per current session; process death
does not create an exactly-once guarantee. Duplicate delivery with the same body
must not start a second capture.

The host checks lock, focus, visibility, provider generation, grant, protected
flow, display state and expiry immediately before delivery and again after any
asynchronous window setup. A failed delivery does not automatically invoke the
host assistant or another provider.

## Capture session

`capture.session` adapts the existing host microphone/review authority. A start
request contains `captureId`, purpose `generic|message|search`, label, optional
initial draft, provider generation, window generation and expiry. It is limited
to 8 KiB UTF-8 and must have a fresh app gesture under the existing host policy.

The host emits `capture-status` (`starting`, `recording`, `reviewing`, `complete`,
`cancelled`, `rejected`) and bounded `capture-transcript` updates. A session has
one owner and one terminal state. `cancel`, hide, lock, grant loss, provider
change, disconnect and expiry release microphone/review ownership. A final
transcript is draft data. It does not authorize sending a message, invoking a
remote assistant, or using a notification reply token.

Legacy message dictation, search dictation and capture helpers remain separate
adapters until all consumers migrate. Their distinct review purposes and
source-bound notification reply authority cannot be merged by this contract.

## Resource lifetime

Resource IDs are session-scoped, monotonic and never reused. A resource is live
while the application owns it or while any accepted scene, retained raster frame,
in-flight frame, or replay record references it. `resource.release` asks the host
to drop application ownership; it does not force disposal. The host responds with
`released`, `deferred`, `stale_generation`, or `unknown`.

The app may reuse a slot only after the existing `BUFFER_RELEASED` outcome. A
failed scene commit retains the previous accepted scene and its resource refs.
Reconnect replays only live resources and accepted scene state. Quotas remain the
existing 4 MiB/2,048-resource defaults unless the catalog advertises lower limits.
Usage and failure diagnostics contain counts and byte totals, never pixels or
resource content.

## Security and trust boundaries

| Boundary | Enforced by | App guarantee |
| --- | --- | --- |
| Android caller identity | SDK/host UID, package and complete signing identity checks | App cannot select an arbitrary caller through control data. |
| Host selection | SDK approval store and host consent UI | A connected host is not trusted until approval. |
| Feature authority | Host catalog, grants, provider selection and generation | Declarations advertise behavior; they do not grant it. |
| Window actions | Host focus/visibility/lock/protected checks and current generation | A stale app request cannot act on a replacement window. |
| Microphone/review | Host consent, fresh gesture, purpose and capture owner | Transcript is never send authority. |
| Notifications/replies | Existing source-bound single-use token checks | New capture APIs cannot bypass reply review. |
| Rendering | Host bounds, generations, quotas and retained compositor | App cannot write arbitrary host memory or BLE packets. |
| Diagnostics | SDK/host bounded categories and IDs | Logs contain no content, credentials, pixels or transcripts. |

## Legacy behavior and migration

Existing `publishExtensions`, `requestSleep`, `requestOpenWindow`,
`setWindowMenuAvailable`, `setWindowProtected`, dictation helpers, notification
helpers, scene APIs and rendering callbacks remain source-compatible. Their return
values continue to mean only what current documentation says. The new SDK may
implement them on top of desired state/results internally, but it cannot report
`applied` without the new negotiated result feature.

An old host receives only legacy messages. The SDK must not send `publish-contract`,
new `control-request`, invocation envelopes, or resource-release messages when the
catalog does not advertise their feature. A new host continues accepting legacy
messages with existing checks. Migration code must inspect explicit negotiation
results and expose `unsupported`, `denied`, `not-selected`, and `legacy-unconfirmed`
separately.

## Test vectors required by P02

The shared vectors must include:

- exact boundary sizes and one-byte-over limits for every envelope;
- unknown feature, duplicate feature, missing required feature, unsupported
  optional feature and stale catalog epoch;
- same request ID/same body, same request ID/different body and result replay;
- stale session/window/provider generation and late acknowledgement;
- rejected, delayed, dropped and reordered controls;
- lock/revoke/focus loss during async open, invocation and capture;
- duplicate final transcript, cancel/final race and capture ownership conflict;
- release while referenced by pending/accepted scene, retained frame and replay;
- throwing renderer, quota exhaustion and reconnect with stale resource IDs;
- unknown input source, held contact cancellation and back timeout.

The vectors are normative. A production implementation that cannot test one of
these cases is incomplete for the corresponding gate.
