# SDK app independence implementation plan

Status: implementation in progress; current acceptance is tracked in sdk-independence/STATUS.md.
Prepared: 2026-09-15.
Source: [SDK independence audit](SDK-APP-INDEPENDENCE-AUDIT-20260915.md).

## Outcome and scope

Ship a versioned SDK/host contract that lets independently installed T3, Signal,
Spotify and future APKs change their own presentation, navigation, editors and
workflows without rebuilding the host. Prove this by updating app APKs against
an unchanged, checksummed host APK.

Deliver these capabilities:

1. Reliable window state, snapshot reconstruction and local failure diagnostics.
2. Feature negotiation and acknowledged control operations.
3. Per-app window geometry, input handling and semantic navigation.
4. App-owned invocation and capture lifecycle, with host-enforced authority.
5. Bounded resource recovery, typed JavaScript bindings and a local test runtime.
6. Versioned artifacts and three independent app migrations.

Standalone means an independently installed Android app whose phone/backend
functionality survives host absence. Glasses access still requires a compatible
host. Reimplementing BLE, firmware support, a full phone UI for each existing app,
or the host compositor inside the SDK is outside this plan.

## Checkpoints and current evidence

- Audit committed in `faceclaw-app-platform`: `ba06777`.
- Host base at planning time: `b2ef68d`, already newer than the audited `4cd862c`.
  Revalidate every finding against the task's actual starting commit.
- Unrelated dirty bridge work committed in `faceclaw-t3-bridge`: `76856d4`.
  Its required `npm run check:all` passed. Bridge work is outside this plan.
- Audit-time host checks: 407/412 passed. Two tests could not find `javac`;
  three touch-input assertions failed. Focused external/extension tests: 86/86.
  These are historical observations, not a waiver for future failures.
- Audit-time Java SDK tests could not start because Java was unavailable.
  A baseline environment task below must establish real Java/device evidence.

## Execution rules for workers

Work in the existing primary checkouts and current local branches. Do not create
worktrees, reset branches, amend another worker's commits, or include unrelated
dirty files. No push, deployment, production data changes, credential handling,
or account-side effects are required by these tasks.

These are handoff tickets, not permission to spawn agents automatically. Follow
the session's delegation policy. When workers are authorized, the coordinator
assigns one ticket at a time and owns integration and final review.

Every ticket inherits this contract:

- Record starting HEAD, dirty status, prerequisite commit IDs and actual symbols
  found by `rg`. Read applicable AGENTS.md. File paths below are ownership hints;
  if a symbol moved, record its replacement before editing.
- Read the approved contract and supplied test vectors. Do not invent wire names,
  feature versions, timeouts, fallback behavior or security policy independently.
- Edit only the listed production area, its directly related tests and ticket
  documentation. A new cross-layer dependency means stop and request a bounded
  follow-up from the coordinator, not expand the patch.
- One ticket should produce one independently reviewable behavior change, normally
  one commit. If it needs several unrelated changes, split before implementation.
- Add tests that exercise externally observable behavior and failure cases. Source
  regex assertions alone cannot prove lifecycle, authorization or delivery behavior.
- Report exact commands and pass/fail/blocked results. Missing tools are blocked
  verification, never a pass. Do not alter unrelated tests to make a baseline green.
- Recheck the diff for package-specific host routing, unbounded queues, discarded
  failures, content in logs, weakened permission checks and accidental API breaks.
- Return a commit, changed-file list, requirement-to-test mapping, known limitations
  and remaining verification. Do not self-certify physical behavior from mocks.

### Required handoff prompt

Copy this and the complete ticket, not just its title:

> Implement ticket `<ID>` from this plan at starting commit `<SHA>`. Prerequisite
> commits: `<SHAs>`. Read the approved contract revision `<SHA>` and vectors
> `<path>`. Own only `<files/modules>`. Deliver the specified observable behavior
> and all listed acceptance cases. Preserve the global invariants below. Do not
> implement adjacent tickets or change the contract. If prerequisites, paths or
> assumptions are wrong, report the mismatch before widening scope. Return exact
> validation results, a diff summary, limitations and your commit SHA.

### Non-negotiable invariants

- The host authenticates UID, complete signing identity and selected-host consent.
  Session IDs, provider generations, window generations, request IDs and expiry
  have distinct meanings. No global lookup may replace scoped authorization.
- Host lock/privacy policy, microphone admission, grants, protected flows and
  the system escape gesture override app preferences. User preference is not
  implied consent to a new permission.
- An invocation/capture result is draft data, not message-send authority. Preserve
  current trusted review, source-bound reply tokens and explicit send intent.
- Unknown side-effect outcomes are never automatically retried. Desired state
  may be reconciled; commands that cause actions may not be replayed on reconnect.
- Old clients keep their old behavior. New clients treat missing support as
  unsupported. Never infer support from SDK version, connection success or grant.
- Queues, payloads, retries, retained state and diagnostic history are bounded.
  Diagnostics contain IDs, categories and timings, not transcripts, images, tokens
  or arbitrary exception strings that could contain app data.

## Design decisions to freeze before ordinary implementation

Ticket P01 owns the specification. These are the intended defaults; P01 must
check feasibility and explicitly document any correction. All later workers use
the reviewed specification, not this prose as an invitation to redesign.

| Concern | Intended rule |
| --- | --- |
| Wire compatibility | Keep existing AIDL methods unchanged where feasible. Add bounded versioned control envelopes and Bundle fields. Do not append incompatible positional parcel fields or silently change existing command semantics. If wire major must change, stop for an explicit migration decision. |
| Feature catalog | Advertise support/schema versions and limits independently of grants and selected providers. Missing entry = unsupported. Cache only for the current authenticated session. |
| Extension publication | Existing publication remains atomic. A new negotiated API treats declared required features atomically and reports optional features individually. Unsupported optional settings may be omitted only by an explicit SDK fallback, never silently reinterpreted. |
| Control results | State transitions: submitted locally -> accepted nonterminal -> applied or rejected terminal. Timeout/disconnect after transmission yields unknown, not proof of failure. Result includes request ID, session scope and applicable window generation. |
| Reliability | Admission and state mutation must complete before applied. Deduplicate request IDs within bounded session retention; identical repeats reuse results, conflicting payloads reject. Report rate limits without creating an unbounded response flood. |
| Desired window state | Keep desired and applied values separate. Key applied state to window generation and policy revision. Replay safe layout/menu state; clear protection on close/revocation and require fresh app assertion for a new protected flow. Reconnect replay policy must be explicit. |
| Input precedence | Host protected actions -> permitted app-local policy -> user global defaults. App claims end on hide/focus loss/lock/revocation; emit cancellation so held inputs cannot stick. Preserve one host escape path. |
| Back behavior | App handles internal back; explicit at-root handoff invokes host root policy. Explicit sleep and explicit close remain separate. Back timeout cannot execute a delayed second action. |
| Invocation | One expiring, non-replayable invocation envelope for advertised entry points. Admission includes current provider/window authority and protected-flow checks. Exactly-once execution across process death is not promised. |
| Capture | One typed capture session owns start/progress/final/cancel. App owns editor and presentation; host owns microphone authority. Legacy helpers remain adapters with their existing review distinctions. |
| Resource release | Immutable IDs are not reused within a session. Explicit release drops application ownership; host disposal waits for in-flight, retained-frame and accepted-scene references. Reconnect only replays still-live resources. |
| Release | Publish versioned local Maven artifacts first; never overwrite a released coordinate. Remote publication is a separate action. Source-project development remains an explicit opt-in. |

P01 must specify exact field names, types, byte limits, numeric ranges, status codes,
timeout values, duplicate retention and all reset rules. It must resolve clock
semantics: local monotonic deadlines must not be compared across process-specific
origins; transmitted expiry must have a documented basis and skew handling.
It must define when scope is session-level versus window-level, including the
open-window request that has no new window generation yet.

## Tasks

Path shorthand: `SDK` = `android-sdk/sdk/src/main/java/com/faceclaw/sdk`;
`HOST` = `App_Resources/Android/src/main/java/com/faceclaw/app`;
`EXT` = `app/apps/external`. Unless stated otherwise, paths are in
`faceclaw-app-platform`. Current task status is recorded in `docs/sdk-independence/STATUS.md`.

### P00. Reproduce baseline and make verification runnable

- **Depends:** none. **Owner:** build/test worker.
- **Scope:** build environment notes and `docs/sdk-independence/evidence/baseline.md`;
  no product changes.
- **Work:** inventory JDK/Android SDK/Node requirements for all four repositories;
  locate or provision a task-local toolchain without overwriting user settings.
  Run the check groups below. Record current failing tests separately from missing
  prerequisites. Inspect custom host-test instrumentation before choosing a runner.
- **Accept:** exact tool versions, commands, starting SHAs and logs; real SDK Java
  tests execute, or a precise external prerequisite is recorded. Identify whether
  the three historical touch failures still exist. Never bundle their fixes here.

### P01. Freeze protocol and ownership specification

- **Depends:** P00 source/baseline inventory; device access need not block design.
  **Owner:** coordinator or experienced contract reviewer, not an unsupervised worker.
- **Scope:** `docs/sdk-independence/CONTRACT.md`, compatibility tables and threat-boundary table.
- **Work:** turn the intended rules above into exact schemas/state machines for
  features, controls, window policies, input, invocation, capture and release.
  Enumerate existing helpers and their legacy behavior. Specify supported assistant
  entry points and reject undeclared ones. Define old-host fallbacks per feature.
- **Accept:** no unresolved field, authority, cancellation, ordering or replay rules;
  examples of valid/invalid messages; old/new client-host matrix; reviewer sign-off.
  High-risk policy decisions remain here, not scattered among implementation tickets.

### P02. Add shared protocol vectors and deterministic test seams

- **Depends:** P01. **Scope:** new bounded fixtures under `android-sdk/test-vectors/`,
  Java tests, TypeScript test helpers; production seams only for injected clock/transport.
- **Work:** encode approved valid/invalid envelopes, stale generations, unsupported
  features, duplicates, timeout and payload limits. Provide fake clock and controlled
  send/delivery ordering. Reuse existing host-tests rather than replacing them.
- **Accept:** Java and TypeScript consume the same fixtures; malformed UTF-8 byte-size
  and boundary cases are covered. Tests can delay/drop/reorder messages without
  sleeping. No tests depend on a live account or actually send a message.

### P03. Fix menu and protection caching on window lifecycle

- **Depends:** P00 runnable Java checks; P01 lifecycle rules. **Scope:**
  `SDK/FaceclawAppService.java`, `SDK/FaceclawSession.java`, focused SDK lifecycle tests.
- **Work:** reproduce set-before-open and close/reopen first. Remove transport-success
  caching as proof of application. Preserve desired menu configuration and apply the
  approved protection reset rule. Handle both snapshot and control-driven open paths.
  Keep existing public methods and old-host behavior usable; no new wire API here.
- **Accept:** failing-before/passing-after tests for set-before-open, equal repeated
  values, close/reopen, reconnect and explicit false. No stale protected flow is
  reinstated after close; no toggle workaround is required in an app.

### P04. Reconstruct SDK state before snapshot callbacks

- **Depends:** P01, P02, P03. **Scope:** `SDK/HostSnapshot.java`,
  `SDK/FaceclawAppService.java`, shared style application and focused tests.
- **Work:** centralize state reduction for snapshots and compatible deltas; update
  SDK accessors before app callbacks. Missing optional legacy fields have explicit
  defaults and cannot synthesize permissions. Copy mutable JSON/byte inputs.
- **Accept:** snapshot-only initialization populates accessors consistently; repeated
  snapshots are idempotent; revocation/disconnect clears authority before callbacks;
  stale deltas cannot restore it. No app-specific callback ordering workaround.

### P05. Supply coherent host snapshots and ordered deltas

- **Depends:** P04. **Scope:** `HOST/FaceclawExternalApps.java`, `EXT/platform.ts`,
  focused host snapshot/lifecycle tests.
- **Work:** populate the approved initial support/grant/host/window state; define the
  handoff between native connection and TypeScript state publication. Attach the
  approved revision/epoch to deltas. Do not pretend unavailable state is complete:
  implement the specified ready boundary or explicit availability representation.
- **Accept:** initial/recovering/hidden/locked windows expose coherent state before
  eligible credits; delayed old deltas are ignored. Old clients still receive their
  existing controls. Test both native and TypeScript halves, not just serialization.

### P06. Expose local failures and unify disconnect transitions

- **Depends:** P01, P02, P04. **Scope:** `SDK/FaceclawSession.java`,
  `SDK/FaceclawAppService.java`, `SDK/RenderSurface.java`, focused SDK tests.
- **Work:** add the approved content-free diagnostic callback. Classify local invalid
  submissions separately from Binder failure. Route genuine connection failures
  through one service/session transition. Suspend repeated renderer failures until
  explicit recovery; release every acquired lease on failed rendering/dispatch.
- **Accept:** throwing renderer, rejected executor, serialization failure and dead
  Binder each produce the specified category and bounded work; service/session state
  agrees; one failure produces no duplicate disconnect callbacks or pixel leakage.

### P07. Implement feature catalog value types and validation

- **Depends:** P01, P02. **Scope:** new SDK catalog/value types, snapshot optional fields,
  Java vector tests. No host routing changes.
- **Work:** encode/decode exact catalog/schema versions and limits; keep support,
  grant and provider status distinct. Unknown entries remain safely representable;
  malformed known entries reject according to the spec.
- **Accept:** all shared vectors pass; missing catalog means legacy/unsupported;
  no API returns supported because a permission is granted. Deep-copy mutable data.

### P08. Advertise host capabilities and negotiate extension declarations

- **Depends:** P05, P07. **Scope:** `HOST/FaceclawExternalApps.java`,
  `HOST/FaceclawExtensions.java`, relevant extension validation/policy tests.
- **Work:** advertise only implemented features. Add the specified new publication
  path with required atomicity and per-optional results; keep old publication unchanged.
  Capability flags for later tickets remain absent until their routes are complete.
- **Accept:** unsupported optional invocation does not remove supported launcher;
  an invalid required bundle does not partially mutate provider state; revocation
  still wins. Unknown fields and oversized payloads follow shared vectors.

### P09. Add SDK negotiation and explicit fallback helpers

- **Depends:** P07, P08. **Scope:** SDK publication helpers, `ExtensionContract.java`,
  compatibility tests; no consumer migration.
- **Work:** construct declarations from supported schema versions, return negotiated
  results, and expose supported/unsupported/denied/not-selected distinctly. On a host
  without the new contract, use only explicitly safe legacy declarations.
- **Accept:** new client/old host and new/new tests; unsupported invocation field is
  never sent to an old validator; required functionality returns a useful failure;
  reconnect to a different supported host invalidates prior catalog assumptions.

### P10. Implement acknowledged host control dispatch

- **Depends:** P01, P02, P05, P08. **Scope:** `HOST/FaceclawExternalApps.java`,
  `EXT/platform.ts`, small new result/dispatch helpers and related tests.
- **Work:** implement scoped requests and results for the exact P01 initial command
  list: menu/protection state, open-window, explicit sleep and system menu. Preserve
  legacy command behavior. Carry the request through asynchronous admission to the
  actual mutation; emit applied only there. Bound duplicate/result tracking.
- **Accept:** hidden/locked/protected/rate-limited/unsupported requests return specified
  results; identical duplicate causes one action; mismatched duplicate rejects;
  stale-session callbacks cannot mutate a new window. No log includes payload text.

### P11. Implement SDK control handles, deadlines and result dispatch

- **Depends:** P02, P07, P10. **Scope:** new SDK request/result classes and
  `FaceclawSession.java`/`FaceclawAppService.java`, deterministic tests.
- **Work:** expose typed handles with terminal result callbacks on documented threads.
  Bound pending work; settle on applied/rejected, timeout or disconnect. Retain old
  boolean APIs with documented transport-only meaning. Old-host fallback returns
  legacy-unconfirmed, never fabricated applied.
- **Accept:** duplicate/out-of-order/stale results cannot settle another request;
  timeout after send is unknown; no automatic side-effect retries; pending handles
  cannot leak across revocation/reconnect. Test completion races with a fake clock.

### P12. Reconcile acknowledged desired window state

- **Depends:** P03, P10, P11. **Scope:** SDK desired-window-state helper and host
  state acknowledgement fields; focused lifecycle tests.
- **Work:** replace the temporary legacy cache strategy with desired/applied revisions.
  Send only current desired state, coalesce updates, and scope acknowledgements to
  generation. Respect P01's different rules for persistent menu/layout preferences
  and transient flow protection. Preserve the P03 old-host fallback.
- **Accept:** dropped/rejected updates remain distinguishable from applied; a late ack
  for revision N cannot erase N+1; reopen/reconnect converges without render/control
  loops; expired protected state does not survive a new flow.

### P13. Implement per-window geometry and chrome policy

- **Depends:** P09, P12. **Scope:** new SDK window-policy types,
  `EXT/platform.ts`, window geometry helpers, layout tests.
- **Work:** support the approved preferred height/chrome/inset fields independently
  of global provider ownership. Clamp requests to host-safe viewport constraints;
  return actual bounds and an explicit adjustment reason. Reuse existing surface
  generation/resize machinery; do not replace the compositor.
- **Accept:** three unrelated APK windows request different supported geometry while
  T3 remains global provider; policy changes resize only the owner; focus and surface
  generations remain valid; denied requests keep last applied layout. Cover lock,
  close during async resize, minimum/maximum bounds and old-host fallback.

### P14. Implement SDK input and semantic back contract

- **Depends:** P01, P02, P11. **Scope:** `SDK/FaceclawInputEvent.java`, new
  input-policy/back-result helpers, SDK tests. No host gesture routing yet.
- **Work:** expose source, sequence, cancellation and acknowledged back disposition
  without losing existing fields. Add scoped claims and distinct handled/at-root
  handoff; explicit sleep/close must not alias root-back. Unknown sources stay unknown.
- **Accept:** shared vectors, duplicate sequence, cancellation and expired back
  responses; a stale response cannot drive navigation. Existing onInput overrides
  still compile and receive their legacy semantics.

### P15. Route host input through per-window policy

- **Depends:** P13, P14. **Scope:** `EXT/platform.ts`, `app/ui/shell/shell.ts`,
  existing gesture translation boundaries and navigation/input tests.
- **Work:** apply the P01 precedence table and back deadline/fallback exactly. Cancel
  claimed contacts on focus loss, hide, lock, revocation and disconnect. Keep wake
  consumption and protected escape behavior host-owned. Advertise support only after
  all entry routes are covered. Do not fix unrelated game logic in this ticket.
- **Accept:** foreground app handles internal back, root handoff uses approved policy,
  explicit sleep stays explicit; timeout cannot cause double navigation. Test ring,
  temple and watch source paths separately, plus unknown source. Host escape remains
  available to recover from a hung app. Physical acceptance remains a release gate.

### P16. Implement typed app invocation lifecycle

- **Depends:** P01, P02, P09, P11. **Scope:** new SDK invocation types/helpers,
  `FaceclawAppService.java`, focused tests.
- **Work:** decode approved trigger/target/text envelopes; validate session/provider
  generation and expiry; deliver once within bounded current-session tracking.
  Expose acknowledged acceptance, completion and cancellation. Preserve the old
  wakeword event adapter without inventing exactly-once behavior after process death.
- **Accept:** stale provider, expired invocation, duplicate, cancellation and repeated
  wakeword during active compose follow spec. Typed results never grant permission
  to send messages or bypass explicit app submission.

### P17. Route wakeword to the new invocation contract

- **Depends:** P15, P16. **Scope:** `EXT/extension-platform.ts`,
  `app/ui/shell/shell.ts`, native provider dispatch, lifecycle tests.
- **Work:** migrate only wakeword admission/open/focus/dispatch. Recheck provider,
  grants, lock and protected work after each asynchronous boundary. Return explicit
  blocked/failed/accepted results; retain documented old-provider fallback.
- **Accept:** provider switches during opening, revoke/disconnect, duplicate wakeword,
  sleeping display and competing protected flow. Never start fallback recording
  after a new invocation was admitted but its result became unknown.

### P18. Route text assistant entry points through invocation

- **Depends:** P17. **Scope:** only phone-keyboard and Send-to-Assistant call sites
  enumerated in P01, invocation adapter and targeted tests.
- **Work:** route these entry points to the selected app-owned handler with bounded
  text and source metadata. Preserve host mode for legacy providers. Reject an
  unsupported trigger explicitly; do not silently fall back after partial execution.
- **Accept:** each supported entry point reaches the same fixture app handler once;
  text bounds, revocation and cancellation hold; supplied text remains draft data.
  No T3 project/model/backend logic enters host routing.

### P19. Add typed SDK capture session adapter

- **Depends:** P01, P02, P11, P16. **Scope:** new capture-session SDK classes,
  existing dictation helper adapters and focused tests.
- **Work:** expose capture start/status/transcript/final/cancel with a single owner
  and documented terminal states. Initially adapt the approved existing capture
  controls; expose negotiated options only when advertised. Preserve message/search
  review distinctions and existing helper signatures.
- **Accept:** cancel-before-start, final-after-cancel, duplicate final, hide/disconnect,
  permission revocation and two competing sessions. Callers cannot reuse a final
  transcript as send authorization. No indefinite pending session.

### P20. Implement host capture ownership and generic lifecycle

- **Depends:** P19, P17. **Scope:** `EXT/platform.ts` capture methods, shell capture
  adapter and existing transcription-provider boundary; capture/review tests.
- **Work:** replace duplicated lifecycle bookkeeping with the specified capture
  owner/session state while preserving existing microphone/review checks. Advertise
  options only if fully implemented. Do not add arbitrary raw device access, new
  transcription backends, or change message review policy.
- **Accept:** late provider callbacks, provider change, grant loss, focus loss,
  finish/cancel race and disconnect release microphone ownership exactly once.
  Message, search and generic capture cannot complete or cancel one another.

### P21. Implement resource lifetime model and adversarial vectors

- **Depends:** P01, P02. **Owner:** rendering reviewer with bounded worker support.
  **Scope:** resource/scene lifetime tests and a small pure reference tracker,
  no host disposal wiring.
- **Work:** model app ownership, accepted/pending scenes, retained raster metadata,
  in-flight frames, replay and terminal frame outcomes. Freeze release transitions
  and fallback semantics with executable vectors. Count unique resident bytes,
  not handles, where deduplication shares content.
- **Accept:** releasing a resource still referenced by accepted content never makes
  it unavailable; failed scene commits retain old references; duplicates and reconnect
  never double-release. Reviewer approves lifetime model before wiring real memory.

### P22. Implement host resource release and quota accounting

- **Depends:** P10, P21. **Scope:** native resource registration/storage and
  compositor/scene reference hooks strictly required by the approved model.
- **Work:** add acknowledged release, usage reporting and deferred disposal. Bound
  released-but-referenced memory by existing quotas; do not reuse IDs. Wire every
  reference acquisition/release path identified in P21, including disconnect cleanup.
- **Accept:** shared vectors plus actual host retention tests; no use-after-release,
  stale-generation disposal or quota bypass. Resource churn reclaims unreferenced
  memory without dropping retained pixels. Run compositor comparison/fixtures where
  supported. No transport tuning or unrelated atlas rewrite.

### P23. Implement SDK resource ownership and fallback

- **Depends:** P06, P22. **Scope:** `SDK/ResourceRegistry.java`, resource handles,
  scene/draw helpers needed for reference reporting, focused SDK tests.
- **Work:** expose explicit close/release and usage/fallback state; retain replay data
  only while live. Make quota recovery possible after acknowledgement. Distinguish
  requested release from host-confirmed disposal; use approved raster fallback on
  older hosts. Never silently invalidate scene references to meet an eviction target.
- **Accept:** changing image/glyph workload recovers quota without reconnect; active
  scenes survive release; reconnect replays exactly live content; old-host fallback
  stays bounded. Application can observe exhaustion and recovery without host logs.

### P24. Extract an app presentation/input interface

- **Depends:** P12, P13, P14, P16, P19. **Scope:** new SDK-facing presentation
  abstraction and a Faceclaw adapter; no existing app migration.
- **Work:** expose the minimum renderer/input/lifecycle operations needed by the
  reference app without public construction of authenticated Binder sessions.
  Reuse current renderers and timing contract. Keep Android service identity and
  host authorization inside the real adapter.
- **Accept:** an app controller compiles against the interface without host internals;
  existing SDK callers compile unchanged; local mode cannot create host grants,
  notification authority or production capture tokens.

### P25. Add deterministic local preview/test runtime and standalone example

- **Depends:** P24. **Scope:** new `android-sdk` example/test-support module plus
  its explicit settings entry and documentation.
- **Work:** implement a local adapter with controlled time, geometry, input and
  disconnect simulation; provide a phone Activity example with simple local state
  that can attach to a real host later. Use synthetic notifications/capture only.
- **Accept:** example launches without a host, retains its own state through attach,
  detach and revoke, and renders via both adapters. Local tests reproduce resize,
  input cancellation and renderer error deterministically. Clearly mark simulated
  capabilities; do not claim fake rendering proves BLE/display correctness.

### P26. Add typed JavaScript bindings for the new public APIs

- **Depends:** P09, P11, P13, P14, P16, P19, P23, P24.
  **Scope:** `android-sdk/javascript/`, JavaScript examples and tests.
- **Work:** wrap actual Java SDK APIs for NativeScript callers with matching types,
  lifecycle disposal and normalized results. Keep pure animation helpers usable
  in Node. Use a narrow injected transport seam for non-Android tests, not a second
  independently invented implementation of protocol authorization.
- **Accept:** declaration/type tests and shared vector parity; unavailable host,
  callback disposal, generation changes and unknown results match Java behavior.
  One runnable example covers policy, input and capture without app-local JSON routing.

### P27. Produce immutable SDK candidate artifacts

- **Depends:** P08-P26 implementation tickets and gate G3 below.
  **Scope:** SDK Gradle publication configuration, artifact packaging script,
  JavaScript package metadata and release documentation.
- **Work:** publish to a task-local Maven repository with the exact approved new
  coordinate; package matching JavaScript bindings. Include schema/version manifest,
  checksums and compatibility notes. Fail rather than overwrite an existing different
  artifact. Add a minimal external consumer build with source substitution disabled.
- **Accept:** external consumer resolves only candidate artifacts; public legacy
  APIs still compile; release/rebuild provenance recorded. A second publication of
  different bytes to the same version is refused. Do not publish remotely.

### P28. Migrate T3 to the candidate SDK

- **Depends:** P26, P27. **Repository:** `faceclaw-t3-app` only.
  **Scope:** SDK dependency, `app/runtime.ts`, `app/extensions/host.ts`,
  `app/native/voice-control.ts`, related settings and integration tests.
- **Work:** replace new-contract JSON/lifecycle workarounds with typed bindings;
  use local window policy without dropping intentionally selected global providers.
  Route supported assistant entry points through one app compose action. Pin the
  candidate dependency; retain explicit supported old-host behavior.
- **Accept:** fresh compose, protected draft, menu reopen, policy negotiation and
  capture cancellation pass. Existing T3 backend/project/model behavior stays inside
  the app. No host/bridge files may change to make T3 pass this ticket.

### P29. Migrate Signal to the candidate SDK

- **Depends:** P27. **Repository:** `faceclaw-signal-native` only.
  **Scope:** dependency configuration, `SignalService.kt`, SDK presentation/capture
  integration and focused tests. No native backend or schema changes.
- **Work:** use coherent SDK state, per-window reader policy and capture handles;
  remove only workarounds made obsolete by tested SDK behavior. Pin the candidate.
- **Accept:** reader/menu reopening, host absence, reconnect, revoke and text layout;
  existing source-bound notification reply, reviewed send, cancellation and unknown
  operation tests continue passing. Use synthetic accounts; never send real messages.
  No host or Signal backend workaround changes are allowed.

### P30. Migrate Spotify to the candidate SDK

- **Depends:** P27. **Repository:** `faceclaw-spotify-native` only.
  **Scope:** dependency configuration, SDK service/presentation integration and
  selection/navigation tests. No OAuth or playback backend changes.
- **Work:** adopt per-window policy, typed lifecycle/results and explicit root-back
  semantics. Pin the candidate and preserve selected global provider behavior.
- **Accept:** selection survives resize/reopen, host absence and revoke do not corrupt
  app state, unsupported policy falls back explicitly. Playback controls use fakes;
  no real account operations or host-file changes are needed.

### P31. Run the fixed-host independence and security acceptance matrix

- **Depends:** P27-P30 and gates G0-G3. **Owner:** integration/device tester plus
  independent boundary reviewer. **Scope:** tests, fixture APK variants, evidence only.
- **Work:** build and checksum one candidate host H1. Install it once on an authorized
  test device. Record initial app fixtures A1, then build changed A2 apps from pinned
  SDK artifacts with the host source/build disabled. Exercise the matrix below.
  Use isolated test identities and synthetic data. Do not replace a user's signed
  installation, clear app data or access live accounts without explicit authority.
- **Accept:** H1 checksum unchanged throughout A1->A2; all required behaviors and
  misuse cases have evidence. Distinguish mocks, emulator, and physical ring/temple/
  watch results. Any required host patch fails the freeze: file a bounded defect,
  create a new candidate and restart the affected matrix on a new recorded H1.

### P32. Finalize release handoff and compatibility documentation

- **Depends:** P31 accepted. **Scope:** SDK README, navigation/invocation guides,
  migration guide, release manifest and evidence index.
- **Work:** reconcile all docs with shipped behavior; document legacy boolean meaning,
  supported host versions, fallback, standalone limits and local-source opt-in.
  Record artifact hashes, commits, required checks and remaining operational steps.
- **Accept:** a fresh consumer follows the guide and builds without sibling SDK
  source; no unsupported feature is advertised; all eight audit findings map to
  completed tasks or an explicit reviewed limitation. No remote publishing here.

## Order, ownership and integration gates

The dependency list in each ticket is authoritative. This conservative schedule
adds serialization where shared files would otherwise collide. Logical independence
does not authorize simultaneous edits to the same checkout or file.

| Stage | Tickets and order | Exit |
| --- | --- | --- |
| Baseline/specification | P00 -> P01 -> P02 | G0 |
| SDK reliability | P03 -> P04 -> P05 -> P06 | SDK regression checks |
| Negotiation/results | P07 -> P08 -> P09 -> P10 -> P11 -> P12 | G1 |
| Window/input | P13 -> P14 -> P15 | Navigation regression checks |
| Invocation/capture | P16 -> P17 -> P18 -> P19 -> P20 | G2 |
| Resource recovery | P21 -> P22 -> P23 | Rendering review |
| Standalone/bindings | P24 -> P25 -> P26 | G3 |
| Artifacts | P27 | Frozen candidate coordinates |
| Independent apps | P28, P29, P30, separately | All app checks |
| Fixed-host acceptance | P31 | G4 |
| Release handoff | P32 | Final review |

P21's pure model/tests may run earlier after P02 if explicitly assigned disjoint
files. After P27, P28/P29/P30 are natural independent assignments in different
repositories. Otherwise default to serial handoffs. If agents are authorized to
work concurrently, the coordinator records a file ownership lease before starting
each task. No two workers edit `FaceclawAppService.java`, `FaceclawSession.java`,
`FaceclawExternalApps.java`, `platform.ts` or `shell.ts` concurrently.

### Gate definitions

- **G0, contract review:** coordinator verifies P01 against actual call sites,
  security boundaries and P02 vectors. Reject unresolved schema or replay decisions.
  A worker may not resolve those by adding an app-specific exception.
- **G1, reliability review:** independent review of P03-P12; exercise actual SDK and
  host dispatch, including drop/reorder/reopen, unknown outcomes and revocation.
  Confirm old-client behavior and new-client fallback. Do not rely solely on mocks
  that bypass native admission.
- **G2, authority review:** trace input/capture/invocation from untrusted app requests
  through host checks to final side effects. Verify lock, focused window, provider
  generation, permission changes and protected escape before and after asynchronous
  work. Compare message/reply authority tests with baseline. Reviewer owns any
  disputed policy decision; ordinary workers implement the frozen result.
- **G3, candidate review:** review memory/reference lifetimes, bounded diagnostics,
  public API compatibility, JavaScript parity and standalone separation. Run all
  build/test groups with tools available. No unresolved new regression may pass.
- **G4, release acceptance:** review P31 evidence, unchanged host hash and app-only
  commits. Missing required physical cases are pending, not waived by simulator
  results. Record a deliberate limitation only if the coordinator/user accepts a
  scope change; workers cannot lower the gate to finish their ticket.

An existing unrelated baseline failure can be tracked separately with its exact
signature and a reviewer determination. A failure touching a new route, security
invariant or rendering lifetime blocks the relevant gate, even if it resembles a
historical failure. No general "known failures" exemption.

## Validation command groups

Use the repository's current instructions if commands change. P00 records the
resolved commands and versions; later workers must not guess task names. Run the
focused checks for a ticket, then the required repository checks. Repeat only for
new changes or unresolved failures.

| Group | Working directory | Commands/evidence |
| --- | --- | --- |
| SDK unit/lint/build | `faceclaw-app-platform/android-sdk` | `./gradlew :sdk:testDebugUnitTest :sdk:lintDebug :sdk:assembleDebug :sdk:assembleRelease` |
| SDK examples | same | `node --test javascript/test.cjs javascript/animation-example.test.cjs`; `bash scripts/check-animation-examples.sh` |
| Host TypeScript | `faceclaw-app-platform` | `npm test`; focused `node --test tests/extension-*.test.cjs tests/external-*.test.cjs` when relevant |
| Native boundary | `faceclaw-app-platform/android-sdk` | Build fixture and host-test flavors; P00 derives exact build/install/instrumentation commands from the custom runner and README. Record separate-UID execution, not merely APK compilation. |
| T3 | `faceclaw-t3-app` | `npm run check`; `npm run build` after verifying the script does not deploy or mutate credentials |
| Signal | `faceclaw-signal-native` | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; relevant nativeprotocol checks per repository instructions |
| Spotify | `faceclaw-spotify-native` | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` |
| Host APK | `faceclaw-app-platform` | P00 identifies the build-only path; do not run `build_and_run.sh` as a substitute for a build-only check. Record package, signing test identity, commit and SHA-256. |
| Patch hygiene | each changed repository | `git diff --check`, exact changed-file review, no unrelated tracked/untracked artifacts staged |

Do not assume all Gradle projects use the same JDK/Android SDK. The audit found
SDK compileSdk 35 and distinct app requirements. Configure toolchains explicitly
per build. Do not change declared minimum/target SDK versions just to get a local
build working.

## Required acceptance matrix for P31

H0 is a preserved pre-feature host build. H1 is the candidate host. A0 is a
preserved old client. A1 is an initial new-contract fixture/app; A2 changes app
behavior using the same published contract. Record hashes and signing identities
for all artifacts. Compatibility fixtures must reflect real H0 behavior rather
than a fake host that accepts fields H0 would reject.

| Case | Required result | Evidence owner |
| --- | --- | --- |
| A0 + H1 | Legacy app starts, renders and keeps its documented control behavior | P09/P31 |
| A1 + H0 | Missing support is explicit; optional features fall back without destroying supported extension declarations | P09/P31 |
| A1 + H1 -> A2 + same H1 | App-only geometry/menu/back/editor changes work; host hash unchanged | P28-P31 |
| Two apps, one global provider | Both use independent window preferences within allowed bounds; global provider remains selected | P13/P31 |
| Set-before-open and reopen | Correct menu/protection state for current lifecycle; no lost equal-value update | P03/P12/P31 |
| Reject/drop/delay control | No fabricated applied state; bounded timeout/unknown result; stale ack ignored | P10/P11/P31 |
| Duplicate action | One side effect within documented session deduplication window; conflicting duplicate rejects | P10/P31 |
| Binder death and host replacement | Coherent state and live-resource replay; no replay of send/capture/navigation actions | P04-P06/P23/P31 |
| Lock/revoke during async open | No unauthorized invocation/capture or exposed private frame | P17/P20/P31 |
| Ring and temple wake separately | Sleeping wake is consumed once; intended focus restored; no unintended back/sleep | P15/P31 |
| Watch and unknown input source | Source-specific routing preserved; unknown source cannot fabricate contact ownership | P15/P31 |
| Hung app/held gesture | Host escape remains usable; hide/focus loss emits cancellation; no stuck contact | P15/P31 |
| Wakeword/text/app compose | Advertised entry points reach one app-owned flow; repeat invocation preserves protected draft | P16-P18/P28/P31 |
| Cancel capture near final | One terminal state; microphone released; no transcript from previous capture affects next flow | P19/P20/P31 |
| Reviewed reply/send regression | Stale/replayed token rejected; unknown send outcome not retried; use fake backend | P29/P31 |
| Resource churn | Bounded memory, reclaimed unreferenced resources, retained content intact | P21-P23/P31 |
| Throwing renderer | Content-free diagnostic and bounded suspension/recovery, not a busy retry loop | P06/P31 |
| No host installed | Standalone example and app-owned phone/backend state remain usable; attach later | P25/P28-P31 |
| Frozen artifact consumer | A2 builds without sibling SDK source or host build tasks | P27/P31 |

## Artifact and handoff layout

Use `docs/sdk-independence/` for reviewed specifications, migration notes and a
small evidence index. Use the workspace `output/sdk-independence/` for large
logs, APKs, local Maven repository and checksums. Do not commit binaries, account
data or full environment dumps. Each evidence entry records command, exit status,
tool version, artifact hash, commit and test mode (unit/emulator/device).

The coordinator maintains this per-ticket ledger in
`docs/sdk-independence/STATUS.md` when implementation begins:

| ID | Owner | Start SHA | Dependencies verified | Allowed files | Commit | Tests | Review | State |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Pxx | assigned worker | exact SHA | exact SHAs | explicit paths | exact SHA | evidence link | reviewer/result | not started / active / blocked / ready for review / accepted |

On blocked work, include the missing fact/tool/contract decision and the smallest
next task. Do not label partially implemented code complete. A worker completion
message must answer:

1. Which observable behavior changed, and which ticket requirements does it meet?
2. Which production files changed, and why is every change in scope?
3. Which adversarial cases ran against real implementation paths?
4. What could not be tested, and what remains blocked?
5. What exact commit and artifacts should the next worker consume?

## Audit coverage and definition of done

| Audit finding | Primary implementation | Proof |
| --- | --- | --- |
| 1. Feature compatibility | P07-P09, P27 | H0/H1 matrix and immutable artifacts |
| 2. App policy/global coupling | P13-P15 | Independent windows under one global provider |
| 3. Silent control failure | P10-P12 | Applied/rejected/unknown and deduplication tests |
| 4. Window cache defect | P03, P12 | Before-open/reopen regression reproduction |
| 5. Voice/invocation coupling | P16-P20, P28 | One app handler, ownership/cancellation tests |
| 6. Snapshot reconstruction | P04-P05 | Snapshot-first accessors and stale delta rejection |
| 7. Resource/failure recovery | P06, P21-P23 | Churn/reference lifetime and renderer failure tests |
| 8. Standalone runtime/distribution | P24-P30 | No-host example and artifact-only consumer builds |

Done means all gates accepted, all eight findings accounted for, old/new
compatibility documented, and the A1->A2 app-only upgrade proven against unchanged
H1. A successful build, a set of new interfaces, or three coordinated app/host
rebuilds alone does not satisfy this plan.
