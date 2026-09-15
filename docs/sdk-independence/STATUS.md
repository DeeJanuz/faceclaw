# SDK app-independence candidate status

Updated: 2026-09-15. Candidate: `1.1.0-rc.1.a9881121c93e`.

Build preparation is complete. Full plan acceptance is **not complete**. The user
explicitly requested: "Prepare the builds; leave device checks pending". Nothing
was installed, published, pushed, or exercised against live accounts.

Code: platform `4755777`, T3 `39a014b`, Signal `b2fbc59`, Spotify `39b0d0a`.
Bridge remains unchanged at `626700f`. See [candidate handoff](CANDIDATE-HANDOFF.md)
and [verification evidence](evidence/candidate-20260915.md).

## Implementation against the original plan

"Implemented" below identifies code present in the candidate, not acceptance of
all adversarial cases or device behavior. The original task definitions and gates
remain authoritative. No G1-G4 acceptance is inferred from a successful build.

| Tasks | Candidate state | Evidence and remaining acceptance |
| --- | --- | --- |
| P00-P01 | Baseline corrected; contract retained | Historical baseline and contract. |
| P02 | Partial | Shared requests now exercise production ControlLedger; exact UTF-8 boundary vectors execute in Java/JS. Complete native Binder fault matrix remains open. |
| P03 | Existing cache fixes retained | Window lifecycle unit tests; physical reopen checks pending. |
| P04-P05 | Implemented | Ordered snapshots/deltas, explicit grants and host-state availability. StateOrder/reducer tests; native callback ordering needs runtime proof. |
| P06 | Implemented, partial acceptance | Production RenderFailureGate, bounded diagnostic queue and transport-loss callback. Failure gate tested; end-to-end Binder death/replacement pending. |
| P07-P09 | Implemented | Host catalog, atomic required-feature negotiation, explicit unsupported SDK fallback. Contract/catalog unit tests. |
| P10-P11 | Implemented | Host admission/dedup, trusted shell completion, client deadlines/unknown outcomes. Production ledger and TS dispatch tests. Native admission integration remains to exercise. |
| P12 | Partial | Desired WindowPolicy replays after negotiation/visibility. Legacy menu/protection helpers remain; full acknowledged desired/accepted reconciliation is not complete. |
| P13 | Partial | Local geometry, chrome and menu policy work in shell dispatch tests. Resize carries actual dimensions; terminal policy results do not yet include the contract's actual-bounds/adjustment fields. |
| P14-P15 | Partial | Semantic back, 500ms fallback and lifecycle cancellation implemented. Gesture claim expiry and source-specific physical acceptance remain open; baseline touch failures remain. |
| P16-P18 | Partial | Typed invocation validation/dedup plus wakeword and text-entry routing. Complete app completion/cancellation lifecycle and distinct host app-button route need acceptance work. |
| P19-P20 | Implemented, partial acceptance | Typed draft-only captures, ownership, cancellation, deadline and late-callback guards. SDK lifecycle and shell cancellation tests; microphone/runtime races pending. |
| P21 | Existing model retained | Reference model adversarial tests. |
| P22-P23 | Implemented, partial acceptance | Explicit release, scene/writer pins, monotonic IDs and legacy replay. Production SDK 5,000-resource churn passes; native memory plateau and all reset/close sweep paths need runtime proof. |
| P24 | Implemented | Shared AppPresentation with host and phone adapters. |
| P25 | Partial | Standalone example and artifact-only A1/A2 consumer builds pass. Full deterministic local preview clock/credit test runtime is not implemented. |
| P26 | Implemented, partial acceptance | Typed JS wrapper delegates to Java state machine. Unknown result and terminal capture cleanup tested; complete native JS parity matrix pending. |
| P27 | Built | Immutable content-addressed Maven AAR/npm tarball, hashes recorded. |
| P28 | Migrated, partial acceptance | T3 pinned artifact, local policy/back, typed capture and invocation route; 213 tests pass. Device behavior pending. |
| P29 | Migrated, partial acceptance | Signal pinned artifact, coherent flags, local policy/back; 71 tests/lint pass. Existing reviewed-send authority retained; full capture-handle migration and device checks remain. |
| P30 | Migrated, partial acceptance | Spotify pinned artifact and local policy/back; 2 tests/lint pass. Device selection/reopen/revoke checks pending. |
| P31 | Build portion only | Independent A1/A2 builds preserve H1 hash. No device acceptance performed. |
| P32 | Candidate handoff prepared | Artifacts, compatibility limits and checks documented. Release signoff depends on open software work and P31. |

## Current verification

- SDK: 64/64 unit tests, lintDebug and assembleRelease pass.
- JavaScript: 14/14 tests pass.
- Host: 424/427 tests pass; APK build passes.
- T3: typecheck, 213/213 tests and APK build pass.
- Signal: 71/71 tests, lint and APK build pass.
- Spotify: 2/2 tests, lint and APK build pass.
- Artifact-only standalone variants A1 and A2 build without SDK project inclusion
  or host build tasks; H1 SHA-256 is unchanged.

The three host failures match the audited baseline: explicit temple provenance,
pinball contact response, and held-contact clearing. Signal's loopback QR shutdown
test failed once during an earlier run; its isolated rerun and subsequent full
runs passed. Treat that test as potentially flaky, not a proven SDK regression.

## Remaining delivery gates

First close the software gaps identified above, especially acknowledged state,
policy result shape, claim expiry, invocation completion and deterministic local
runtime. These are not device-only blockers. Then run the plan's H0/H1 compatibility,
Binder failure, revocation, capture and resource matrix on an authorized test device.
Record physical ring/temple/watch evidence separately from mocks. A host change
requires a new frozen candidate and rerun of affected acceptance checks.

## Historical audit correction

`38705ff` also synchronized seven production WindowStateCache methods; review it
with P03. `1f2b5fd` is not in the final checkpoint ancestry. The initial P02 fixtures
were structural, and initial P06 diagnostics did not complete recovery. The audit
is preserved in workspace `output/sdk-independence/session-dafbe1a9-audit-20260915.md`.
