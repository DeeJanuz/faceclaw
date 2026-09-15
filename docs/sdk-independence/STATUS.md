# SDK app-independence implementation status

Updated: 2026-09-15.

This ledger records implementation commits against the contract in
[`CONTRACT.md`](CONTRACT.md) and the task definitions in
[`SDK-APP-INDEPENDENCE-IMPLEMENTATION-PLAN.md`](../SDK-APP-INDEPENDENCE-IMPLEMENTATION-PLAN.md).

| Task | State | Commit | Evidence | Notes |
| --- | --- | --- | --- | --- |
| P00 baseline/toolchain | accepted with correction | `1f2b5fd`, corrected `8ab8147` | `evidence/baseline.md` | Local JDK 21 and Android SDK work when selected explicitly; no device/emulator. |
| P01 contract | accepted | `bffa22f` | `CONTRACT.md` | Contract v1 freezes catalog, results, window policy, invocation, capture and resource rules. |
| P02 vectors/seams | accepted | `2ac57d0` | Shared Java/JavaScript vectors; full SDK unit suite | Catalog, duplicate/conflict, stale-window and expiry fixtures are shared; runtime dispatch is still absent. |
| P03 window cache | accepted | `0d2b404`, replay correction `e296311` | SDK unit tests | Desired menu state is generation-scoped; protection is transient. |
| P04 snapshot reducer | ready for review | `a3e4897` | `HostSnapshotStateTest`, full SDK unit/lint suite | Snapshot-derived style, extension and explicit grant state is copied before `onHostSnapshot`; missing optional fields remain denied. |
| P05 host snapshot/deltas | not started | — | — | No host routing or catalog advertisement has landed. |
| P06 diagnostics/disconnect | ready for review | `81914b5` | `SdkDiagnosticTest`, full SDK unit suite | Adds bounded content-free diagnostics, main-looper delivery, local-validation vs transport categories, and renderer/executor reporting. Host/instrumentation failure injection remains for G1. |
| P07 catalog value types | accepted | `b6af6c6` | `AppIndependenceCatalogTest`, full SDK unit suite | SDK parses optional `capabilities.appIndependence`; host does not advertise it yet. |
| P08-P20 | not started | — | — | Negotiation, acknowledged controls, window policy and invocation/capture remain pending. |
| P21 resource lifetime model | accepted | `28a60b8` | `ResourceLifetimeModelTest`, `RESOURCE-LIFETIME-MODEL.md` | Pure test reference model only; no host resource wiring. |
| P22-P23 | not started | — | — | Host and SDK release/usage integration remain pending. |
| P24-P32 | not started | — | — | Standalone adapter, bindings, artifacts, app migrations and fixed-host acceptance remain pending. |

## Verification at this checkpoint

With JDK 21 and the local Android SDK selected:

```sh
JAVA_HOME=/home/deej/.local/jdk-21 \
ANDROID_HOME=/home/deej/.local/android-sdk \
PATH=/home/deej/.local/jdk-21/bin:/home/deej/.local/android-sdk/platform-tools:$PATH \
./gradlew :sdk:testDebugUnitTest
```

Result: **PASS**, 44 SDK unit tests.

After P06/P07 changes, `:sdk:lintDebug :sdk:assembleDebug` also passes with
the same explicit JDK/Android SDK selection. Release assembly and device
instrumentation remain pending.

Host TypeScript checks with the JDK on `PATH` report **418/421 passing**. The
remaining three failures are the pre-existing touch-input assertions in
`tests/touch-input.test.cjs` (temple provenance, pinball contact response and
held-contact clearing). They are not part of the SDK slices above and remain
unfixed. The two earlier `javac` failures disappear when the local JDK is
selected.

The bridge repository remains clean at commit `626700f`; its required
`npm run check:all` passed before this SDK work began.

## Next bounded handoff

P08 and P09 can now implement catalog advertisement and negotiation. Do not advertise
`appIndependence` from the host until the host-side feature is complete and
P08's rejection/atomicity tests pass.
