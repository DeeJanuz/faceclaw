# Merge upstream into the Faceclaw SDK host

Prepared September 21, 2026 using `/home/deej/.codex/skills/planning/SKILL.md`.

Status: M01-M10 implemented and software-validated locally. M11 device acceptance remains pending and was not authorized.

## Outcome and boundaries

Merge upstream commit `313ccd86d9c99a230142521e4d11308b284b2d68` into the existing `design/apk-app-platform` branch in `/home/deej/projects/Even Realities/faceclaw-app-platform`. The final history must contain both the local work and the pinned upstream commit. Adopt upstream's shared Kotlin implementation, updated pairing, display transport, firmware compatibility and new features while preserving our independent Android APK platform.

Success means existing T3, Signal and Spotify clients still work with the merged host; animation frames use upstream's new transport and cache without violating SDK ownership or outcomes; pairing handles both lenses; and recoverable display failures cannot become an endless relaunch loop. Hardware reboots are not established as fixed by this merge.

Work in this checkout and local branch. No worktrees, branch reset, history rewrite, force push, or use of archived checkouts. Preserve all existing work and ignored build artifacts. The implementation scope includes a local merge commit and local verification; remote publication, installing on a personal phone and flashing glasses are separate rollout operations. This planning invocation performs none of them.

Import upstream iOS code with the merge, but do not promise an iOS port of our APK SDK or iOS device acceptance. Do not redesign the SDK, bump its wire major, repackage sibling applications, or add a second BLE stack to applications. No separate legacy-firmware transport will be built: the merged host requires the upstream firmware contract and must explain incompatible firmware through onboarding. Existing APK client compatibility and glasses firmware compatibility are separate contracts.

## Starting context and evidence

- Local HEAD: `1a4ca9fe4d114892ba682c9ab2626d6c25c18216`.
- Shared base: `9b70880a5b5a2a2ba32400ab1aff8842483a6ce2`.
- Upstream remote `origin`: `https://github.com/jimrandomh/faceclaw.git`; personal remote `fork`: `https://github.com/DeeJanuz/faceclaw.git`.
- The September 21 fetch found 133 upstream-only and 81 local-only commits. The pinned target includes release 0.7.2 and unreleased 0.7.3 work. Do not silently advance the target during execution.
- Existing modified files: `app/apps/external/platform.ts`, `app/graphics/icons.ts`, `tests/external-system-menu.test.cjs`. The first audit also created untracked `docs/UPSTREAM-AUDIT-20260921.md`; this plan is another new document. These are not disposable merge debris.
- Governing instructions: workspace `../AGENTS.md` and the personal policy provided in the session. Main-thread execution; stable sibling checkout paths; preserve uncommitted and ignored work. The planning skill authorizes this artifact only.
- Trial merge: 38 conflict paths, 11 modify/delete conflicts. Upstream's migration manifest contains 49 Java-to-Kotlin moves. Trial output: `../output/upstream-audit-20260921/merge-tree.txt`. The trial excludes dirty edits.
- Current `npm test`: TypeScript compilation passed; 483 tests, 473 passes, 10 failures. Seven native fixtures failed because `javac` was absent. Three assertions failed in `tests/touch-input.test.cjs`. Log: `../output/upstream-audit-20260921/baseline-tests.log`.
- Additional planning finding: upstream commit `183adf5` deletes `touch-input.test.cjs` and other experimental tests. The failed temple `touch-press`/`touch-release` expectations are not an implemented local contract. Do not restore experimental event IDs to make these tests pass; replace useful coverage with supported input semantics.
- No host/SDK build, upstream Kotlin test run, flash or live device test has been completed for this merge.

Build facts: local `build_paths.sh` still selects macOS paths unavailable in this shell. `scripts/before_build.sh` sources that file, so environment exports alone are insufficient for scripts that source it. Upstream requires JDK 21, Android SDK 35, NativeScript Android 9.1.1, Kotlin 2.4.20 and Android KMP plugin 8.13.2. Signal additionally documents SDK 36. The host retains minSdk 27, its `:faceclaw-sdk` inclusion and arm64-only packaging. Keep the existing public SDK library's toolchain settings unless a demonstrated compatibility error requires a bounded change.

## Chosen approach

Use one real merge with subsystem-by-subsystem conflict resolution. This replaces the audit's optional small-backport-first route because the requested outcome is now a full upstream merge. Do not cherry-pick the same fixes before the merge or repeatedly import intermediate firmware blobs.

Upstream owns the shared native implementation and final wire format. Our fork owns APK identity/grant enforcement, shared-memory rendering, render credits, extensions, notification/composer integration and bounded task recovery. Port our behavior into upstream replacements; do not select an entire conflicting communicator, compositor or shell from either side.

Keep these invariants:

| Contract | Required merged behavior |
| --- | --- |
| APK authority | Existing user/package/component/signing-identity approval; session, grant, feature generation, request and expiry checks remain effective. No token or transport callback bypasses Binder authority. |
| Rendering | SharedMemory slots remain bounded and host-read-only; accepted content is retained; credits use target presentation time; obsolete samples may be superseded; each accepted frame gets exactly one terminal outcome. |
| Composition | Dirty tiles, authoritative pixels, retained-copy hints, prefetch, scene/resource lifetime and neutral/hidden underlays survive the Kotlin move. Every hidden surface loses both visible pixels and deferred draw identities. |
| Transport | SID `0xf0`, actual negotiated MTU, write-ordered persistent compression, both-lens ordered ACK completion, 500 ms custom ACK deadline and at most three replay retries, as implemented upstream. |
| Firmware | Require `Faceclaw/22` or newer using upstream compatibility checks, with base `2.3.0.24`, matching hashes/patches/font descriptors and 256 KiB cache modes 18/19/20 using 32-bit offsets. |
| Recovery | Retain our generation-fenced `SessionRecoveryPolicy`, one in-place prelude/layout/frame relaunch, paired-exit coalescing, reconnect fallback, transport-owner isolation and nonblocking notification preparation. |
| Navigation | Preserve `android-sdk/NAVIGATION_AND_WAKE.md` and approved provider precedence. Glanceboard remains disabled by default, as upstream already specifies; it is available through explicit settings. Lock and protected notification/capture flows win over Glanceboard. |
| Input | Preserve SDK clients' existing gesture semantics. Raw ring press is available to upstream built-ins/diagnostics, not a second implicit click in APK windows. Forward compatible metadata on existing gestures without changing the AIDL schema. |
| Voice and notifications | Keep accepted-audio drain, suppression sample preservation, capture-specific `onStopped(captureId)` delivery, notification removal callbacks, arrival/read/reply ownership, composer confirmation and foreground-service restrictions. |

Firmware fingerprinting remains an opaque equality token. Update its input to include both reported base versions and the extension revision with unambiguous separators; clear it on unknown/disconnected state. Firmware-font shortcuts require the matching known bundled font identity, not merely the fact that a newer revision was accepted. Default to raster rendering when that identity is unverified.

The new remote-input service remains stopped when no tokens exist. Preserve upstream's interface selection, hashed token permissions, request bounds and lock checks. Route authorized remote gestures through the same shell policy; remote text/assistant operations must not bypass our protected review or grant boundaries. Do not provision tokens or open listeners during the merge.

## Ordered tasks

### M01. Preserve the starting state and establish the build baseline

Dependencies: none. Files: local build-path configuration, existing tests, and ignored evidence under `../output/upstream-merge-20260921/` (proposed directory).

1. Compare branch, HEAD, upstream target and dirty paths with this record. Inspect any drift before editing. Keep the target pinned unless the user changes scope.
2. Save binary-capable tracked diffs, untracked planning documents and an inventory of ignored artifacts outside the checkout. Record hashes and the pre-merge HEAD. Never use blanket `git clean`, hard reset, or automatic stash/pop.
3. After reviewing the three dirty edits, preserve them in a distinct local checkpoint commit when implementation is authorized. Keep the audit and plan in a separate documentation checkpoint. The checkpoint records existing work, not new upstream behavior. Confirm tracked state is clean before starting a real merge. If unexpected work cannot be safely checkpointed, pause the merge operation while retaining the backups.
4. Locate or provision a usable JDK 21 and Android SDK 35/36 for this environment; correct only machine-local build paths and retain their previous contents. Check existing NDK/CMake requirements in `App_Resources/Android/app.gradle`; do not copy another machine's absolute paths. No credentials or signing changes.
5. Rerun the local baseline with Java available. Record native test results separately from the known experimental touch assertions. Preserve current APK artifacts and hashes where available for later rollback and A/B comparison.

Completion: restorable checkpoints and evidence exist; `java`/`javac` and SDK paths work; baseline failures are explicitly classified. If toolchain setup is unavailable, mark build validation blocked rather than treating missing-JDK tests as passes. Source integration may proceed only with that limitation recorded; completion remains gated.

### M02. Start the pinned merge and allocate every conflict

Dependency: M01 preservation complete. Work in the existing branch with `git merge --no-ff --no-commit 313ccd86d9c99a230142521e4d11308b284b2d68` only during authorized implementation.

Keep the merge uncommitted through M03–M09. These tasks resolve one pending merge, not separate intermediate merges. Record newly conflicted checkpoint files too. The existing conflict groups are:

- Build/tests: `.gitignore`, `App_Resources/Android/app.gradle`, `tests/README.md`, `tests/tsconfig.json` (M03/M09).
- Native migration: `FaceclawNoiseSuppressor`, `FaceclawNotificationListener`, `FaceclawVoiceControllerListener`, `GlyphAtlas`, `ImageAtlas`, `SurfaceCompositor`, `g2protocol/BleImageOptimizer`, `g2protocol/ConnectionOptions`, `g2protocol/TextureCacheState`, `g2protocol/TexturePlanner`, `util/BmpUtil` under `App_Resources/Android/src/main/java/com/faceclaw/app/` (M04/M07).
- Native runtime: `FaceclawBleCommunicator.java`, `FaceclawBleManager.java`, `FaceclawForegroundService.java`, `FaceclawVoiceController.java` in the same directory (M05–M07).
- Startup/navigation: `app/app.ts`, `app/apps/launcher/index.ts`, `app/g2/dashboard-controller.ts`, `app/ui/dashboard/settings-menus.ts`, `app/ui/gestures.ts`, `app/ui/window-menu.ts`, and `app/ui/shell/{chrome-layer,in-process-window,shell,voice-input,worker-window}.ts` (M06–M08).
- Other integrations: `app/apps/{minesweeper/minesweeper-app,pinball/pinball-app}.worker.ts`, `app/assistant/session.ts`, `app/graphics/ttf-font.ts`, `app/native/{notification-icons,notification-types,voice-control}.ts`, `app/ui/notifications.ts` (M04/M07/M08).

Use `native/kotlin/migrated-java-sources.json` to track every deleted Java replacement, including clean deletions. Review auto-merged callers too. Stage a path only after its responsible task has reconciled both behaviors. Full builds need all relevant syntax conflicts resolved; a blocked intermediate build is not a subsystem regression verdict.

Completion: all unmerged paths have an owner in this plan; no broad ours/theirs resolution has discarded fork behavior.

### M03. Reconcile build plumbing and shared Kotlin packaging

Dependency: M02. Existing incoming files: `package.json`, lockfile, `hooks/before-prepare/faceclaw-kotlin.js`, `scripts/kotlin-build.cjs`, `scripts/test-kotlin.sh`, `native/kotlin/`, Android Gradle files. Retain local `App_Resources/Android/settings.gradle` and its `:faceclaw-sdk` dependency.

Adopt upstream runtime/plugin preparation and explicit migrated-source cleanup. Preserve host minSdk 27, SDK inclusion, JNI preparation and local AIDL requirements. Do not leave deleted Java sources in generated NativeScript output or package duplicate Java/Kotlin classes. Preserve ignored local artifacts before any targeted generated-platform refresh. Lockfile changes must reflect the resolved manifest.

`android-sdk/host-tests/build.gradle.kts` copies Java callback sources, including `FaceclawSettingsListener.java`, which upstream migrates. Adapt this harness to compile the required production Kotlin callback source with a test-module Kotlin plugin, keeping Java/Kotlin JVM targets compatible. Keep its deliberate compositor/atlas/communicator stubs. Do not inject the whole shared AAR into that stub harness, which would introduce duplicate classes; actual graphics/protocol validation belongs in upstream's production-source Kotlin suite. The published SDK module remains independent of the shared host implementation.

Completion: native Android artifact preparation works; generated-source cleanup points to actual replacements; host and test classpaths retain SDK integration and contain no duplicates. Confirm with M09 builds after remaining source tasks finish.

### M04. Port our renderer and resource behavior into shared Kotlin

Dependency: M03. Existing upstream targets under `native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/`: `graphics/{SurfaceCompositor,GlyphAtlas,ImageAtlas}.kt`, `g2protocol/{BleImageOptimizer,ConnectionOptions,TextureCacheState,TexturePlanner}.kt`, `util/BmpUtil.kt`. Existing local integration: `RenderBroker.java`, `DisplayScheduler.java`, `RenderCadence.java`, `DisplayTransport.java`, `FaceclawExternalApps.java`, `app/graphics/ttf-font.ts` and the communicator's frame submission/prefetch methods.

Port local changes relative to the shared base, including dirty composition/packed output, retained copies with repair rectangles, retained draw identities, atlas lifetime, resource prefetch/working-set replacement and neutral underlay rules. Use upstream `AndroidByteReader` at Android ByteBuffer boundaries; keep platform APIs out of common Kotlin. Preserve Java-callable static members/fields and callback signatures used by the SDK host. Carry 32-bit texture offsets through table writes, planner records and prefetch accounting.

Move or adapt local native fixtures that compile the deleted Java implementation into `tests/kotlin` so they execute the production Kotlin code. Extend existing `SharedGraphicsTest.kt`, `TextureCacheTest.kt`, `JavaCompatibilityTest.kt` and the Android host Java fixture as appropriate. Preserve overlap-copy correctness, >64 KiB cache addresses, dirty-versus-full equivalence, hidden-draw isolation, stale-generation rejection and raster fallback. Do not make tests pass by retaining a second Java renderer.

Completion: shared Kotlin tests prove the retained local behavior; host submission still flows through the single broker/scheduler/compositor; API callers compile once M05/M07 are complete.

### M05. Merge the transport, firmware contract and pairing probe

Dependency: M04. Files: `FaceclawBleCommunicator.java`, `FaceclawBleManager.java`, `FaceclawDeviceInfoProbe.java`; shared `CfwTransport`, `CfwMessageWindow`, `MessageBuilder`, `OutboundMessage`, `BleProtocol`; `app/g2/firmware-{compat,builder,fonts}.ts`, `app/g2/firmware/cfw-patches.ts`, onboarding firmware view models and `app/native/faceclaw-communicator.ts`.

Adopt the final upstream SID/MTU/compression/ACK implementation and final firmware metadata as one contract. Keep local manager closed-state guards, counters and owner identity. Import sequential two-arm probe authentication, Android bond waits/retries and ACK-waiter notification. Preserve cancellation and bounded failures; do not interpret non-success auth replies as success or claim the separate runtime authentication path was redesigned.

Map SDK frame completion to the final ordered both-lens ACK for all commands belonging to that frame, including texture uploads and split pixel bands. A replay retains one logical frame/outcome; retry exhaustion completes it once and invalidates relevant compression, delta and texture assumptions. Buffer release remains independent of display ACK. Preserve latest-state coalescing and measured credit pacing.

Replace capability-token checks with the numeric firmware contract, including retained-copy eligibility and the fingerprint policy above. Incompatible/unknown firmware must not receive the new private display commands; keep probe/onboarding controls available. Do not fake old-firmware compatibility by accepting its capability string. Keep patch hashes, CDN base image and font descriptors synchronized.

Add production-path tests for one-lens ACK loss, late/duplicate ACKs, NACK, three-retry exhaustion, replay ordering, MTU bounds, compression reset, large frame splitting, cancellation and cache invalidation. New proposed common test file: `tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/CfwRecoveryTest.kt`. Adapt existing protocol/cache tests where equivalent coverage already exists rather than duplicating it.

Completion: protocol/recovery tests pass and existing APK frame outcomes remain exactly once. Pairing behavior is locally tested as far as the platform harness allows; Android dialog/link acceptance remains M11.

### M06. Preserve task recovery and notification wake sequencing

Dependency: M05. Files/symbols: `g2protocol/SessionRecoveryPolicy.java`, `FaceclawBleCommunicator` exit handling/relaunch/layout ACK callbacks, `app/g2/dashboard-controller.ts`, `app/native/faceclaw-communicator.ts`; tests `session-recovery-policy`, `communicator-readiness`, `notification-wake`.

Keep our nonblocking distinction between prepared session and displayed frame. Retain one generation-bound recovery operation, bounded prelude/layout/frame relaunch, paired abnormal/system exit coalescing and reconnect escalation. A new exit during recovery must not silently start another unlimited CREATE cycle. Reset compression/cache state along with layout state; ensure transport replay cannot revive a retired generation or complete its frame twice.

Notification wake must prepare while blank, submit authorized current notification content, reveal after readiness and retain lock/privacy rules. Cancellation, charging, sleep and owner replacement invalidate pending work. Expected intentional shutdown must not relaunch. ACK wake notification supplements these rules; it does not replace them with blocking Java waits on the JS thread.

Completion: executable recovery/notification tests cover duplicate exits, exit during retry, stale ACK, notification replacement, user sleep and successful recovery; no unbounded reconnect or CREATE traffic is introduced.

### M07. Reconcile SDK services, notifications, voice and trust boundaries

Dependency: M03; final compilation also depends on M04–M06. Files: native `FaceclawForegroundService`, `FaceclawVoiceController`, Kotlin `audio/FaceclawNoiseSuppressor.kt`, Kotlin notification/voice callbacks, local `DrainableAudioQueue`/`Pcm16StreamAdapter`; `app/native/{notification-icons,notification-types,voice-control}.ts`, `app/ui/notifications.ts`, `app/ui/shell/voice-input.ts`, `app/assistant/session.ts`, external platform and extension code.

Retain notification removal/catalog synchronization, content-free diagnostics, local audio fixes and capture-stop delivery while adopting upstream Whisper/transcription additions. Preserve connected-device foreground-service behavior rather than restoring microphone foreground-service crashes. Keep composer exact-text confirmation and ambiguous side-effect outcomes non-retryable without new user intent.

Trace the affected Binder inputs, caller identity checks, grants, SharedMemory mappings, notification reply handles and capture callbacks before edits. Preserve all existing permission and expiry checks through callback migration. Confirm security invariants through the existing host boundary tests and `tests/host-voice-notification-security.test.cjs`; no broad security refactor.

Completion: local SDK/extension/notification/composer/voice tests pass; migrated callbacks preserve local methods including `onNotificationRemoved` and capture-specific `onStopped(captureId)`; no authority checks or sample-drain behavior are dropped.

### M08. Merge shell, features and input without changing client contracts

Dependencies: M04, M06, M07. Files: startup, launcher, shell/window/menu files listed in M02; `app/g2/{dashboard-controller,glance-host,glance-state,ring-input}.ts`; incoming Glanceboard settings, `app/ui/input-monitor.ts`, games, `app/remote/` and native remote-input adapter.

Preserve APK launcher discovery/icons, provider priority, host navigation policy, layout sizing, overlay ordering and gameplay-hold exceptions. Respect the three checkpointed local edits. Keep Glanceboard default off; when enabled it uses the common compositor and may not expose a private retained frame, steal notification focus, or override protected awake navigation. Preserve configured choices during settings migration; no reset of grants or user state.

Adopt raw ring metadata/filtering and built-in ring-press handling. Do not infer unsupported temple contact events from experimental IDs. Existing APKs continue receiving their documented gestures; raw ring-press is consumed by the host for built-ins/diagnostics rather than forwarded as an extra action to APK windows or extension input. Unknown additive metadata remains safe in `FaceclawInputEvent.extras`.

For remote input, preserve no-token/no-listener behavior and upstream authorization checks; connect text and assistant operations to the existing host policy without bypassing lock, protected capture or destination-bound confirmation. Do not activate the service as a test shortcut.

Completion: focused navigation, extension, launcher, input, remote-input and notification tests pass. Replace the removed experimental touch tests with supported ring/gameplay/held-state cancellation coverage; preserve valid fork-specific coverage from any other upstream-deleted tests.

### M09. Run integration checks and close coverage gaps

Dependencies: M03–M08. Files: `tests/tsconfig.json`, `tests/README.md`, migrated fixtures, SDK host-test wiring, docs for animation/firmware behavior and build instructions.

Require zero unresolved merge entries before broad compilation. Run the commands below, classify failures against M01, and repair merge-induced regressions. Do not merely remove Java fixtures because their sources moved. The old 483-test count is not the target: record tests moved/deleted/replaced and require valid current coverage to pass.

Verify old SDK candidate consumers as well as current source clients. Signal and Spotify default to the local Maven candidate `1.1.0-rc.1.9544fd1852be`; explicit `-PfaceclawSdkSource=true` selects this SDK source. T3 uses the matching local JavaScript artifact. Do not silently republish or overwrite those pinned artifacts to hide a compatibility failure. Source-SDK checks supplement, not replace, old-client compatibility.

Completion: host APK and SDK artifacts build; host Node/Kotlin and SDK checks pass; sibling compatibility checks have recorded results; missing device/macOS checks are stated explicitly. Update SDK documentation from 64 KiB to the negotiated/new host behavior without implying older hosts changed.

### M10. Record the local merge and deliver a testable candidate

Dependency: M09 software checks pass. Inspect the staged merge diff against both parents, including clean auto-merges, and confirm every fork contract above. Create the local merge commit only after software checks pass. Do not push.

Record the resulting SHA, pinned parents, APK/AAR hashes, toolchain, test results, preserved dirty-work checkpoints, deviations and remaining hardware acceptance in this plan's execution record. Verify `git merge-base --is-ancestor 313ccd86d9c99a230142521e4d11308b284b2d68 HEAD` succeeds. Ensure no unintended sibling edits or generated artifacts entered commits.

Completion: a reproducible local merged candidate exists. Label it software-validated, device-pending until M11 completes; do not call throughput gains or reboot fixes validated.

### M11. Hardware acceptance and rollout, when separately authorized

Dependency: M10, a verified phone/glasses target and authorization for installation/firmware operations. First capture existing host/firmware identities, recoverable artifacts and representative metrics. Install the host candidate and use its supported onboarding path for the coordinated firmware update; do not send new private commands to old firmware.

Exercise fresh/delayed/rejected pairing, one missing arm, sleep/reconnect, both-lens display, ring holds/swipes, ordinary and notification wake, protected capture, composer, T3/Signal/Spotify and EvenHub. Exercise unexpected task exit and retry exhaustion without intentionally causing destructive device faults. Collect logs that distinguish task exit, link reconnect and actual hardware reboot.

Compare the same dense-text/retained-copy scenes and upstream bandwidth benchmark against the recorded baseline: median/p95 ACKed-frame latency, ACKed bytes, retries/timeouts, cache resets, frame outcomes and battery activity. Repeat under the same scene, phone and link conditions. Upstream's 3–10x/30 fps claims are not acceptance numbers for our SDK. Require correct pixels and stable recovery; report performance honestly rather than inventing a universal frame-rate guarantee.

Recovery: before a merge commit, use `git merge --abort` only with M01 backups verified and preserve any resolution work first. After committing, keep the merged history and use a deliberate corrective/revert change if needed. App rollback after a firmware update is not necessarily compatible: establish a matched host/firmware recovery path before flashing. Do not automatically downgrade firmware or discard phone/app data.

Completion: hardware evidence supports the claimed benefits, or remaining failures block rollout with reproducible evidence. No full-reboot fix claim without an identified reboot scenario and successful reproduction before/after.

## Verification commands

These entry points were inspected, not run for the candidate. Execute only during implementation at the stated task stage. `build_paths.sh` must already select the correct environment.

| Working directory | Command | Purpose |
| --- | --- | --- |
| Host root | `npm ci` | Install resolved lockfile dependencies after M03. |
| Host root | `npm test` | TypeScript and Node host tests, including retained Java fixtures. |
| Host root | `npm run native:android` | Build/stage production Kotlin AAR through incoming upstream script. |
| Host root | `npm run test:kotlin` | Incoming production-source Android host/common Kotlin tests. |
| Host root | `bash build.sh --no-hmr` | Build Android host; does not install it. |
| `android-sdk/` | `./gradlew build` | Existing multi-module SDK/harness build and checks. |
| `android-sdk/` | `./gradlew :sdk:assembleDebug :sdk:assembleRelease :sdk:testDebugUnitTest :sdk:lintDebug` | Targeted SDK verification during relevant edits. Avoid redundant reruns after a passing broad build unless needed for evidence. |
| `android-sdk/` | `bash scripts/check-animation-examples.sh` | Existing JS animation tests, SDK unit test and Java animation example compile. |
| `../faceclaw-t3-app/` | `npm run check` and `npm run build` | Existing client type/test/build scripts against its pinned dependencies. |
| `../faceclaw-signal-native/` | `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` | Existing pinned-candidate consumer checks. |
| `../faceclaw-spotify-native/` | `./gradlew :app:testDebugUnitTest :app:assembleDebug` | Existing pinned-candidate consumer checks. |
| Signal/Spotify roots | Same Gradle checks with `-PfaceclawSdkSource=true` | Additional current-source compatibility checks; existing property inspected. |
| Host root | `git diff --check` and `git ls-files -u` | No whitespace errors or unresolved index entries before commit. |

Focused Node suites use `node --test <existing-test-files>` after TypeScript test compilation. Compile migrated native cases via the Kotlin suite instead of invoking javac on removed files. Device instrumentation commands must be selected from the resulting harness flavors and a verified device inventory at M11; no generic install command is authorized by this plan. iOS common/native compilation is an optional additional gate on a macOS runner, and remains explicitly unverified when unavailable.

## Authorization and deviations

Implementation through M10 was authorized after the planning-only invocation ended. The work stayed in the existing checkouts and branches. No push, phone install, firmware flash or glasses command was authorized or performed.

## Execution record

Implemented locally on September 21, 2026. M01-M10 are complete. M11 remains device-pending; no phone install, firmware flash or glasses command was performed.

- **M01:** Preserved the existing work in checkpoint commits `323a7cc` (`fix: show Signal icon for external app`) and `f9ebce5` (`docs: plan upstream reconciliation`). The original recorded HEAD remains reachable at `1a4ca9f`. The local build environment now uses Temurin JDK 21.0.12.1, Android SDK 35/36 at `/home/deej/.local/android-sdk`, Node 24.19.0, npm 11.17.0 and Gradle 8.14.3. Machine-local paths remain ignored.
- **M02:** Started one no-commit merge of pinned upstream `313ccd86d9c99a230142521e4d11308b284b2d68` over local parent `f9ebce56783d7104f316d0d4df764542644edfeb`, with shared base `9b70880a5b5a2a2ba32400ab1aff8842483a6ce2`. All conflict groups were reconciled individually; no whole-file ours/theirs resolution was used for the communicator, compositor, shell or controller.
- **M03:** Adopted upstream's Kotlin/native preparation and migrated-source cleanup while keeping minSdk 27, the local SDK inclusion, AIDL, JNI preparation and arm64 host packaging. The SDK host-test module now compiles the production Kotlin callbacks and Android adapters without retaining duplicate Java implementations.
- **M04:** Ported the fork's Gray8 and packed-frame paths, dirty-tile composition, retained overlap copies and repairs, draw identities, atlas lifetime, prefetch and hidden-surface clearing into shared Kotlin. Added production Kotlin regression coverage for retained copies, compositor damage/visibility, noise-suppressor tail flushing and BMP header correctness. Corrected the upstream BMP magic byte regression (`BM`, not `B\x04`).
- **M05:** Combined upstream's bonded two-arm probe, MTU/PHY negotiation, SID `0xf0` CFW transport, ordered ACK/replay and 256 KiB texture protocol with the fork's closed-state guards, traffic counters, exactly-once SDK outcomes and render recovery. Firmware identity now includes length-delimited left base, right base and extension revisions; unknown identity clears the token. Firmware text remains raster-baked unless both arms report the known `2.3.0.24` base and a compatible custom extension.
- **M06:** Preserved generation-fenced, bounded display-session recovery, notification preparation/reveal ordering and transport-owner isolation. This improves recovery from plugin task exits and lease/session loss. It does not establish a fix for a physical glasses reboot; that claim remains gated on M11 reproduction and hardware evidence.
- **M07:** Preserved variable-length suppression output, final suppression drain, capture ownership, notification removal/catalog callbacks, reply confirmation and connected-device foreground-service restrictions while adopting upstream Whisper/Moonshine and iOS additions. The callback name changed from the plan's provisional `onCaptureStopped` to the shared Kotlin abstract method `onStopped(captureId)`, because that form survives the NativeScript/Kotlin bridge and preserves capture-specific completion.
- **M08:** Reconciled launcher/external-app discovery, extension priorities, shell/navigation/wake policy, gameplay holds, raw ring filtering, Glanceboard and remote input. Existing APK authority, generation, expiry, protected capture, lock and destination-review checks remain in the execution paths. Unsupported experimental temple event IDs were not restored; supported ring and held-state tests replace that coverage.
- **M09:** Software checks passed after `npm ci`: `npm test` (836 tests, 835 passed, 1 skipped), `npm run native:android`, `npm run test:kotlin`, `bash build.sh --no-hmr`, `android-sdk/gradlew build` (853 tasks), and `android-sdk/scripts/check-animation-examples.sh` (10 tests). The T3 client passed `npm run check` (278 tests) and `npm run build`. Signal passed its pinned and `-PfaceclawSdkSource=true` assemble/unit/lint/androidTest checks. Spotify passed pinned checks; its source check exposed a pre-existing circular Gradle build-directory provider in the sibling checkout. Local Spotify commit `29f0ed31382769272b9aa59f40e2893c3089e78a` uses the same concrete `rootProject.file("build/faceclaw-sdk")` isolation as Signal, after which source unit/assemble checks passed. iOS-facing Node tests passed, but native iOS/Kotlin compilation remains unverified because this runner is Linux.
- **Artifacts:** Host debug APK `bb8ad1020b1578e1c0c0de9779166d822ed89991523fb07ea937d1c613ee644a`; SDK debug AAR `4ebb2164fd43cc787477e0a03e61ae5b3dc49b670f36a1bde609ae319cd4eb48`; SDK release AAR `63fe4fc49bd25046bb09b7fc04a68fcef70b44d9a1232339c17dd358c696212f` (SHA-256). Build logs are under `../output/upstream-merge-20260921/`.
- **M10:** Created local merge commit `998030df351ecf09ec950d0de3fda56d76f36182` with parents `f9ebce56783d7104f316d0d4df764542644edfeb` and pinned upstream `313ccd86d9c99a230142521e4d11308b284b2d68`. `git merge-base --is-ancestor 313ccd86d9c99a230142521e4d11308b284b2d68 998030df351ecf09ec950d0de3fda56d76f36182` passed. The candidate is software-validated and device-pending. No push was performed.
- **M11:** Pending separate authorization and verified hardware. Detection/pairing behavior, animation throughput, forced task recovery and physical reboot behavior are software-reviewed but not device-validated.
