# Faceclaw capability protocol v1

Faceclaw lets approved local apps and paired remote bridges publish and invoke capabilities. A capability can perform an operation or open an interface. The provider controls its workflow, including required input, presentation, confirmation, and recovery. A caller cannot turn a tool argument into a physical gesture or bypass provider policy.

Faceclaw is the host, registry, router, and presentation authority. Typed, session-scoped Android AIDL carries the protocol for installed APKs. Remote bridge transports use the same declarations, requests, results, and lifecycle states. T3 is an adapter to this protocol, not part of its authority model.

## Trust and identity

Faceclaw is designed for one owner and self-hosted infrastructure. Approving an app or pairing a bridge admits that participant to the owner's environment. Participant identity is still retained for routing, revocation, operation ownership, and audit behavior.

Installation or network reachability does not establish identity. Local participants are pinned to their package signing identity and UID through the SDK handshake. Remote participants must use a separately scoped pairing credential. Removing approval, changing signing identity, disconnecting, or replacing a capability catalog invalidates active routing authority.

Android permissions, lock state, protected interfaces, and provider-required user actions remain independent checks. Capability arguments never contain an approval flag recognized by the host.

## Discovery

Each provider publishes at most 64 declarations in a catalog no larger than 48 KiB. Faceclaw assigns the provider identity and monotonically increasing catalog generation. Capability IDs are globally namespaced lowercase identifiers, for example `com.faceclaw.signal.message.compose`.

```json
{
  "id": "com.example.player.play",
  "version": 1,
  "kind": "operation",
  "title": "Play music",
  "description": "Play an unambiguous selection or open the provider picker.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": { "type": "string", "maxLength": 200 }
    },
    "required": ["query"],
    "additionalProperties": false
  },
  "resultVisibility": "status-only",
  "durability": "reconnect",
  "operationClass": "control",
  "profiles": [
    { "id": "org.faceclaw.profile.media.play", "version": 1 }
  ]
}
```

`kind` is `operation` or `interface`. Both are invoked the same way; an interface entry point declares that local presentation is its primary result.

`resultVisibility` is:

- `agent`: validated result content can return to the caller.
- `status-only`: state, operation ID, message, and continuations return; provider content stays local.
- `local`: equivalent wire disclosure to `status-only`, with the stronger expectation that useful data is shown only in the provider UI.

`durability` is `transient`, `reconnect`, or `durable`. Side-effecting capabilities must expose idempotent operation handling or a durable status capability before claiming recoverability.

`operationClass` is `read`, `control`, or `side-effect`. It informs timeout and unknown-outcome handling; it grants no authority.

Profiles are optional, versioned interoperability contracts. They make equivalent features findable without forcing provider screens or safeguards to match. Custom capabilities remain valid without a profile.

Faceclaw exposes only current, unambiguous capabilities in the callable catalog. Duplicate IDs and collisions with host tools fail closed. A separate availability surface may explain disconnected or unapproved providers without exposing accounts, contacts, libraries, or other private content.

## Invocation

Faceclaw binds every request to the exact caller, provider, capability ID and version, catalog generation, request ID, issue time, and expiry. Arguments are validated against bounded transport rules and the published schema before provider logic uses them.

```json
{
  "requestId": "2b7d...",
  "capabilityId": "com.example.player.play",
  "capabilityVersion": 1,
  "catalogGeneration": 4,
  "arguments": { "query": "Blue Train" },
  "caller": {
    "participant": "com.example.bridge/.BridgeService",
    "origin": "bridge",
    "project": "personal-assistant",
    "session": "bridge-session-7"
  },
  "issuedAt": 1789230000000,
  "expiresAt": 1789230025000
}
```

Dispatch is at-most-once. A timeout or transport loss after dispatch is an unknown outcome, not permission to repeat a side effect. Providers recheck request currency immediately before an irreversible action.

Providers answer with a structured state:

- `running`
- `waiting_for_user`
- `waiting_for_presentation`
- `completed`
- `failed`
- `cancelled`
- `unknown`

Results may include a bounded `operationId`, a human-readable `message`, provider content, and capability IDs that can continue the workflow. Progress may use only nonterminal states. Faceclaw filters content according to `resultVisibility` before it reaches a bridge or model.

Returning `waiting_for_user` or `waiting_for_presentation` finishes the immediate invocation without ending the provider-owned operation. The caller can later invoke a declared status, resume, or cancel capability with the returned operation ID. Cancellation is a request and never claims to recall a dispatched action.

## Bridge adapters

A bridge adapter is an ordinary approved Faceclaw app. It declares the
separately granted `device-tools` extension, receives the effective tool catalog,
and maps that catalog into MCP or another authenticated agent protocol. Faceclaw
passes calls back through that extension and binds the bridge component, remote
project, and bridge session into the provider's caller record.

The reference computer relay uses these version-neutral RPC names on its native
WebSocket:

- `faceclaw.capabilities.register` with `protocolVersion`, increasing
  `catalogRevision`, `projectId`, and `tools`.
- `faceclaw.capability.call` for server-to-adapter calls.
- `faceclaw.capabilities.result` for the one matching result.

The adapter must reject the wrong project, stale or duplicate call IDs, expired
calls, and calls received after it loses `device-tools`. The relay validates tool
arguments before dispatch. Catalog revisions increase on one connection; a new
authenticated connection starts a new revision epoch. The older
`faceclaw.tools.*` names remain temporary compatibility aliases and carry no
additional authority.

The Android SDK's `CapabilityBridge` helper defines the host-facing adapter
envelopes. Catalogs arrive through `onHostEvent("extension-event", envelope)` as:

```json
{
  "feature": "device-tools",
  "generation": 7,
  "type": "event",
  "data": {
    "event": "tool-catalog",
    "tools": [{
      "name": "com.example.player.play",
      "description": "Play an unambiguous selection or open the provider picker.",
      "inputSchema": { "type": "object" },
      "_meta": { "org.faceclaw/capability": {
        "protocolVersion": 1,
        "provider": "com.example.player/.PlayerService",
        "id": "com.example.player.play",
        "version": 1,
        "kind": "operation",
        "resultVisibility": "status-only",
        "durability": "reconnect",
        "operationClass": "control",
        "profiles": [{ "id": "org.faceclaw.profile.media.play", "version": 1 }]
      }}
    }]
  }
}
```

The namespaced MCP `_meta` value retains the public declaration fields needed for
profile matching and audit. Bridges that do not understand it can ignore it and
still call the exact tool name and schema.

An authenticated remote call is returned to the same host generation with the
`tool-call` extension action. Its data contains `projectId`, `bridgeSession`,
`callId`, `name`, object `arguments`, `issuedAt`, and `expiresAt`. Results use the
same outer envelope and `{event:"tool-result", callId, result}`. Rejected calls
use `{event:"action-result", callId, ok:false, error}`. The adapter consumes only
the one result matching the pending call and generation.

## Presentation

Providers may request their own app window or use host-rendered interaction primitives. Faceclaw decides whether a request can receive focus. Remote requests cannot unlock the glasses, forge a gesture, displace an active confirmation, or reveal a protected surface.

Opening an interface may carry bounded initial state such as a recipient handle, search query, or draft. Presentation does not authorize the action displayed inside it. The provider remains responsible for binding input and confirmation to the current operation.

## Delegation

Version 1 supports one authenticated bridge-adapter hop into an installed provider.
The host preserves the bridge component, remote project, and bridge session in the
provider caller record. Recursive provider-to-provider delegation is reserved for
a later protocol version; it must add an explicit bounded chain and cannot let an
intermediary substitute its identity to amplify the original caller's access.

## Messaging profile v1

Messaging providers should expose app-namespaced capabilities that implement these profile IDs:

- `org.faceclaw.profile.messaging.status`
- `org.faceclaw.profile.messaging.compose`
- `org.faceclaw.profile.messaging.operation`
- `org.faceclaw.profile.messaging.cancel`

Compose accepts a recipient query or opaque provider recipient handle and optional message text. Missing or ambiguous data opens the provider interface and returns `waiting_for_user`. A complete request may open an exact draft review. The provider decides whether Send needs confirmation. Signal's first implementation requires physical confirmation.

## Media profile v1

Media providers should expose app-namespaced capabilities for:

- `org.faceclaw.profile.media.status`
- `org.faceclaw.profile.media.play`
- `org.faceclaw.profile.media.pause`
- `org.faceclaw.profile.media.resume`
- `org.faceclaw.profile.media.next`
- `org.faceclaw.profile.media.previous`
- `org.faceclaw.profile.media.queue`
- `org.faceclaw.profile.media.open-library`
- `org.faceclaw.profile.media.operation`

An unambiguous playback command may complete immediately. An ambiguous selection opens the provider's picker and returns `waiting_for_user`. Library rows, account data, and provider-restricted metadata may remain local while the caller receives only operation state.

## Conformance

A conforming provider must pass catalog validation, request binding, cancellation, version change, disconnect, result visibility, and at-most-once side-effect tests. A conforming bridge must discover catalog changes, invoke by exact provider capability, preserve operation states, avoid retries after uncertain dispatch, and reconnect without assuming completion.
