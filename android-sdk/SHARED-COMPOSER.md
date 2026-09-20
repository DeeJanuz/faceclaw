# Shared message composer

`composer.session` lets an application ask the host to present the selected
`ui.composer` extension. The application remains the destination and owns any
draft persistence or send operation. The selected extension renders the
capture and review UI. The host owns microphone routing, the authoritative
transcript, the reviewed revision, and the physical confirmation gesture.

This contract covers outgoing text composition. Conversation lists, message
history, recipients, setup, and delivery status remain application-specific.

## Caller contract

Negotiate `composer.session`, then call `AppControls.composer` (Java) or
`controls.composer` (JavaScript). A request contains:

| Field | Rule |
| --- | --- |
| `purpose` | `MESSAGE`/`message` for send authorization or `GENERIC`/`generic` for accepted text only. |
| `target` | Opaque destination binding, 1-512 characters. It is returned only to the calling app's own authority. |
| `label` | Bounded destination label shown in the review UI, 1-100 characters. Do not include conversation history. |
| `initialText` | Optional draft, at most `maxText`. A non-empty value opens directly in review with the microphone off. |
| `maxText` | 1-20,000 characters. Applications should choose their own smaller transport limit. |
| identity and expiry | The SDK adds the request ID, current window generation, authenticated app session, and a five-minute monotonic deadline. |

Only one composer may be active for an SDK client. Opening requires the
calling window to be visible, its dictation grant, a fresh host-delivered input,
and an available selected `ui.composer` provider. The provider needs its own
separate `ui.composer` grant and current priority. Selecting a composer does
not select a transcription or refinement provider.

```java
if (controls.supports("composer.session")) {
  ComposerSession composer = controls.composer(
    ComposerSession.Purpose.MESSAGE,
    conversationId,
    "Signal: Alice",
    savedDraft,
    8000,
    event -> {
      String status = event.optString("status");
      if (status.equals("confirmed")) {
        String exactReviewedText = event.optString("text");
        // Revalidate the account, destination, expiry, saved revision, and
        // unresolved-send ledger here, then enter the app's send pipeline.
      }
    });
}
```

```js
const composer = controls.composer(
  'message', conversationId, 'Signal: Alice', savedDraft, 8000,
  event => {
    if (event.status === 'confirmed') sendAfterAppValidation(event.text);
  },
);
```

Keep the returned handle and call `cancel()` when the origin closes, changes
account or destination, loses permission, or supersedes the request. Cancel is
idempotent. A terminal result releases the handle automatically.

## States and results

| State | Accepted transition | Authority |
| --- | --- | --- |
| opening | host starts message capture, or presents non-empty initial text | No accepted text and no send authority. |
| capturing | physical finish or cancellation | Partial transcripts are display-only. |
| finalizing | one authoritative non-empty final | Empty final rejects the session; earlier partials cannot replace it. |
| review | physical confirm, physical edit gesture, page navigation, or cancel | Host stores the exact text and revision shown. |
| capturing edit | physical finish or cancel | Edit speech is an instruction, not replacement text. |
| finalizing edit | authoritative edit final | Empty/failed edits restore the last reviewed draft. |
| refining | current selected refinement result or failure | A valid result creates a new host-owned review revision. Failure restores the last draft. |
| committing | host consumes the current physical confirmation once | Provider callbacks alone cannot confirm. |
| closed | terminal | Late frames, actions, transcripts, and results are ignored. |

Terminal statuses are:

| Status | Meaning |
| --- | --- |
| `confirmed` | A `message` draft was physically confirmed. This authorizes only the calling app's next validation step; it does not report delivery. |
| `accepted` | A `generic` draft was physically accepted. It never authorizes a message send. |
| `cancelled` | User, caller, or lifecycle cancellation. Preserve any pre-existing caller draft. |
| `rejected` | Admission, capture, transcript, provider, or validation failed before acceptance. |
| `expired` | The five-minute session deadline passed. |
| `unknown` | The terminal transport outcome cannot be established. Do not infer acceptance or retry a send. |

The host trims the initial and authoritative final text once before review.
Wrapping and pagination never change the returned string. Confirmation is
bound to the exact current text and revision. Duplicate or stale provider
actions cannot create another result.

## Provider contract

An app may declare the live surface feature `ui.composer`. The selected
provider receives only the session ID, purpose, label, initial text, text limit,
and current revision. It can request the following session-scoped actions:

- `composer-start-capture`
- `composer-finish-capture`
- `composer-refine`
- `composer-confirm`
- `composer-cancel`

The host validates provider component, grant, selected generation, visible
surface, session identity, phase, physical gesture, exact text, and revision.
The provider never receives the caller's account, history, send handle,
notification token, or raw microphone authority.

T3 declares `ui.composer` by default and exposes a separate "Message composer
permissions and priority" control. The host/user priority list decides the
winner. A later explicit user choice remains authoritative.

## Entry points and compatibility

| Entry point | Shared result consumer |
| --- | --- |
| Signal new message or saved draft | Signal revalidates conversation, account, host generation, expiry, persisted exact draft, and send ledger. |
| SDK legacy reviewed dictation | Existing caller callback; the legacy transport remains destination-bound. |
| Android or APK notification reply | Existing notification/version/reply-token authority, followed by its original send or handoff status. |
| Assistant-created message draft | Host messaging broker updates the saved exact draft and revalidates recipient before dispatch. |
| T3's own message input | T3 uses the same SDK caller path and the same provider surface. |
| Generic text insertion | Caller receives `accepted`; no send authority is created. |
| Recipient search | Remains on the search-only capture contract and cannot send. |

Hosts without `composer.session` keep the existing bounded capture/review
path. Apps must branch only on negotiated support. A current host with a
selected but unavailable composer rejects the request; it does not switch UI
or providers during a session.

Lock, sleep, origin loss, caller/provider disconnect, grant loss, provider
change, expiry, or cancellation closes the surface and releases capture,
refinement, timers, and confirmation authority. A confirmed draft that an app
cannot send may be saved under that app's rules, but it requires a fresh review
before any later send.
