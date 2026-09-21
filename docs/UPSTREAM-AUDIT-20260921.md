# Faceclaw upstream reconciliation audit

Audit date: September 21, 2026. Recommendation: take the small pairing/wake fixes first, then integrate the display transport and firmware together. A full upstream merge is a substantial host migration, not an SDK dependency update.

## Fetched state and scope

- Fetched `origin`, the upstream repository at https://github.com/jimrandomh/faceclaw. `fork` remains https://github.com/DeeJanuz/faceclaw.
- Local branch: `design/apk-app-platform`, HEAD `061905f`. Shared base: `9b70880a5b5a2a2ba32400ab1aff8842483a6ce2`.
- Fetched upstream main: `313ccd8` (September 20). Latest fetched release tag: `0.7.2`, `50431c7`; main also contains unreleased 0.7.3 work.
- Divergence: 81 local-only commits, 133 upstream-only commits. Upstream changes since the shared base span 628 files, 53,211 insertions and 8,473 deletions.
- `git merge-tree --write-tree --name-only HEAD origin/main` reports 38 conflicted files, including 11 modify/delete conflicts. This computes a trial merge without changing the checkout or index. It excludes uncommitted edits.
- The existing edits in `app/apps/external/platform.ts`, `app/graphics/icons.ts`, and `tests/external-system-menu.test.cjs` were preserved. No source integration, branch switch, APK installation or firmware flash was performed.
- Upstream has no `android-sdk/` tree. Benefits reach our SDK through the host renderer, BLE transport, firmware and input adapters. Our APK/AIDL/session architecture must be retained.

## Changes worth taking

### Detection, pairing and input

**Both-lens pairing is a useful reliability fix.** [5c17498](https://github.com/jimrandomh/faceclaw/commit/5c17498) and [d630587](https://github.com/jimrandomh/faceclaw/commit/d630587) change the firmware-check probe. It authenticates both arms sequentially, waits through Android's pairing dialog, only treats the firmware success result as authentication success, retries dropped links, and reconnects the right arm if it drops while the left pairs. The implementation allows three arm attempts and extends the pairing wait to 90 seconds. Our current probe already authenticates an arm and can query the left as fallback, but lacks this complete two-arm orchestration.

Scope matters: these changes are in `FaceclawDeviceInfoProbe`, primarily onboarding and firmware detection. They do not establish that every runtime reconnection problem is fixed. The main communicator still has a separate soft-timeout authentication path. The probe also tolerates connected-but-unconfirmed authentication; its comments explicitly leave custom-firmware authentication behavior unverified on hardware.

The advertisement parser, hardware-identity decoder and serial-based pair aggregator have no changes in this upstream range. `buildAddressSet` moved to a shared module. Do not describe this as a new general discovery algorithm or an APK-service discovery fix.

**Lost ring events have an explicit upstream fix.** [b972cd7](https://github.com/jimrandomh/faceclaw/commit/b972cd7) rebases the custom firmware onto stock `2.3.0.24` and identifies lost R1 input as the problem addressed. [7b19c00](https://github.com/jimrandomh/faceclaw/commit/7b19c00) forwards ring events before filtering and includes swipe information. Useful for press/hold responsiveness and gesture diagnostics, but requires coordinating firmware, native event decoding, shell routing and our SDK input mapping. Preserve our direct-ring routing and notification/navigation behavior; avoid duplicate press/click actions when exposing lower-level events.

### Animation streaming

| Change | Expected SDK benefit | Integration requirement |
| --- | --- | --- |
| [1b16389: faster BLE firmware settings](https://github.com/jimrandomh/faceclaw/commit/1b16389) | More transport capacity for animated frames | Enables the LE 2M feature bit, sets 7.5 ms connection intervals and disables idle slow requests. Requires matching firmware, real link measurement and battery checks. Enabling 2M support does not prove that a phone negotiated 2M. |
| [33bfdcf: dedicated SID `0xf0`](https://github.com/jimrandomh/faceclaw/commit/33bfdcf) | Display/control messages no longer depend on stock image-container transport | Port framing, negotiated-MTU tracking, message construction, ACK parsing, large-frame splitting and firmware as a unit. |
| [720f6af: streaming transport compression](https://github.com/jimrandomh/faceclaw/commit/720f6af) | Persistent zlib history can reduce repeated animation traffic | Encode in actual write order. Reset compression state on replay, link/session loss and destination changes. Preserve ordered completion and both-lens ACK semantics. |
| [5698c58: 256 KiB texture cache](https://github.com/jimrandomh/faceclaw/commit/5698c58) | Larger text/image working sets; fewer cache resets during dense transitions | Changes offsets from 16 to 32 bits and upload/image/string modes from 12/13/14 to 18/19/20. Increasing the size constant alone is incorrect. Port our prefetch and retained-copy support too. |
| [6f304fc: notify ACK waiters](https://github.com/jimrandomh/faceclaw/commit/6f304fc) | Removes up-to-100 ms polling latency for remaining native readiness waiters | Small `lock.notifyAll()` change in ACK completion. `git apply --check` succeeds against our working tree. Our newer asynchronous notification-readiness fix remains necessary. |
| Developer BLE bandwidth benchmark | Separates transport limits from expensive raster/scene work | Measures ACKed payload/wire bytes, timeout counts, pipeline depth and link modes; later upstream uses incompressible benchmark input. Useful alongside our text-density and diagnostics apps. |

Upstream's [changelog at the audited revision](https://github.com/jimrandomh/faceclaw/blob/313ccd8/CHANGELOG) claims 3–10x faster transfers and a 30 fps full-screen Flappy demonstration. These are upstream claims, not measurements of our SDK, dense text scenes, phone or glasses. The changelog's estimated battery cost is also not a measured result for our use case.

The ordinary pipeline window is already three messages in both trees. This is not simply a larger queue or higher app frame-rate setting. Keep our host-issued render credits, presentation-time sampling, coalescing, authoritative raster fallback, damage tracking and exactly-once frame outcomes. Recalibrate measured transport pacing after the transport port; do not replace it with a fixed 30 fps timer.

### Forced restarts, exits and recovery

**No confirmed fix for full hardware reboots was found in the reviewed upstream changes.** Distinguish a glasses reboot, a BLE reconnect, and an EvenHub task exit. Our September 20 incident evidence established abnormal/system task exits followed by a CREATE timeout, not a full hardware reboot; see [the local recovery record](NOTIFICATION-WAKE-RECOVERY-PLAN-20260920.md).

Upstream does add useful transport recovery: `CfwMessageWindow` uses a 500 ms ACK timeout, waits for both lenses, completes messages in order and replays the unresolved custom-message window after a NACK/missing ACK, with at most three retries before transport failure. Compression resets accompany replay. This could reduce reconnects caused by recoverable message failures, but does not prove that the original firmware exit or any hardware crash is fixed.

Our [965059a](https://github.com/DeeJanuz/faceclaw/commit/965059a) recovery is ahead of upstream in another respect. Upstream's exit handler still clears layout readiness and messages on foreground/abnormal/system exit; it lacks our `SessionRecoveryPolicy`. Preserve our generation fencing, coalescing of paired exit events, single in-place prelude/layout/frame relaunch, bounded reconnect fallback, asynchronous notification readiness and transport-owner isolation. Porting upstream's communicator wholesale would lose these protections.

Firmware is distributed here as byte patches and hashes. Descriptions and host source support the protocol findings, but do not provide a complete firmware-source crash audit or hardware proof.

## Reconciliation work

### 1. Small host fixes first

Port the probe/authentication changes and ACK notification fix while retaining the current Java architecture and firmware contract. Add focused checks for delayed pairing acceptance, rejected/non-success auth replies, arm disconnects, cancellation and both-arm completion. Preserve closed-manager callback guards and local transport counters when merging `FaceclawBleManager` changes.

This is the smallest useful package. Keep authentication fixes separate from the newer numeric firmware contract so a minor host update does not accidentally require a flash.

### 2. Display transport and firmware together

Port the dedicated transport, compression/window recovery, larger cache, latest patch set, firmware download/hash metadata, font offsets and ring decoding as a coordinated change. Earlier transport commits still contain Java implementations, making a targeted backport possible without adopting the entire Kotlin/iOS migration immediately. Adapt their behavior to the final upstream protocol rather than independently cherry-picking old firmware blobs.

Current upstream checks for `Faceclaw/22` or newer, replacing our `EVENCFW` feature tokens. Its header comments mention exact revisions, but executable compatibility checks use `>= 22`. Update the SDK-facing firmware fingerprint and retained-copy feature gates: ours currently require tokens such as `directfb`, `texcache12`, `texstr14` and `font15`. Carry the new `2.3.0.24` font descriptors with the firmware so font fast paths remain correct.

Existing glasses on the old firmware will require the coordinated firmware update for this path. If continued old-firmware operation is required, implement an explicit dual-transport compatibility path; upstream does not supply that path. Keep application APK protocol compatibility separate from glasses firmware compatibility.

Reconcile retries with our frame outcomes and texture residency. A replay must not produce duplicate terminal outcomes, reuse released resources, acknowledge half a display update, or preserve a stale delta base after recovery. Validate both ACK loss and actual link/session loss.

### 3. Full mainline reconciliation

Upstream migrates 49 Java sources to shared Kotlin. Eleven of our locally modified classes are deleted by that migration, including `SurfaceCompositor`, atlases, image optimizer, texture planner/cache, connection options, notification/voice callbacks and noise suppression. Manually port our behavior into their replacements; keeping both versions would cause duplicate classes. Upstream's generated-source cleanup manifest also means retaining the old files is not a durable workaround.

Other conflicts include `FaceclawBleCommunicator`, `FaceclawBleManager`, foreground service, voice controller, app startup, launcher, dashboard controller, notification code, shell layers and gesture routing. Even clean textual merges require review: firmware metadata and new callback contracts interact with our SDK code that upstream does not contain.

The build migration adds a local `@faceclaw/kotlin` plugin and preparation hooks, NativeScript Android `9.1.1`, Kotlin `2.4.20`, Android KMP plugin `8.13.2`, and a documented JDK 21/SDK 35 requirement. Our current SDK instructions target JDK 17 or newer. Reconcile host and SDK toolchains, generated NativeScript sources, test fixtures, ByteBuffer-to-AndroidByteReader adapters and callback signatures. The Kotlin migration is not itself required to expose faster streaming through the existing SDK API.

Retain independent-APK service discovery, Binder identity/grant checks, shared-memory ownership, render credits, extension surfaces, composer, notification replies, voice drain/stop fixes and restoration semantics. Glanceboard and new gestures need deliberate precedence decisions around our existing shell; they are optional for the targeted SDK improvements.

Assessment: the small host fixes are bounded; the transport/firmware backport is a substantial integration with hardware acceptance; the full merge is larger again because it combines that integration with native architecture and shell changes. Conflict count alone understates the semantic work. No elapsed-time estimate is justified before compiling the port and running devices.

## Validation and acceptance

Ran the current working tree's `npm test`: TypeScript compilation succeeded; 483 tests ran, 473 passed and 10 failed. Seven failures are `spawnSync javac ENOENT`, including native recovery, cadence and retained-copy fixtures. Three assertion failures are in `tests/touch-input.test.cjs`: temple provenance, pinball press/hold/release, and clearing held contacts. These predate any upstream integration in this audit. They need classification before treating the suite as a clean migration baseline.

No Android host/SDK build, upstream Kotlin suite, firmware flash or live glasses test was performed. No merge candidate was installed. The available shell lacks `javac`; source inspection and a merge simulation are not hardware acceptance.

Before shipping an integration:

1. Establish a passing or explicitly classified baseline with the required JDK/Android SDK. Run host tests, shared Kotlin tests if adopted, SDK unit/lint/build checks and sibling APK builds.
2. Test discovery and pairing with fresh bonds, one missing arm, delayed pairing confirmation, rejection, cancellation and reconnect after sleep.
3. Run the existing dense-text and retained-copy A/B demos plus the upstream bandwidth benchmark. Compare ACKed bytes, frame latency, timeout/replay rates, cache resets and battery activity on the same hardware.
4. Exercise 64 KiB-crossing cache offsets, upload ordering, compression history resets, NACKs, one-lens ACK loss, retries, reconnect keyframes and frame-outcome uniqueness.
5. Repeat notification wake/exit recovery, intentional sleep, rapid wake/sleep, ring press/hold/swipe and sustained animation. Collect enough event/link evidence to distinguish plugin exit from a true reboot.

Audit artifacts: [trial merge output](../../output/upstream-audit-20260921/merge-tree.txt), [baseline test log](../../output/upstream-audit-20260921/baseline-tests.log). The merge output lists all 38 conflicts. Git fetch updated remote refs and tags only; production source files and the three pre-existing edits remain unchanged.
