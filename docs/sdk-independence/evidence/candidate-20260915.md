# Candidate verification evidence, 2026-09-15

Mode: local JVM/Node tests and Android compilation only. No emulator or physical
device tests. Source commits: platform `4755777`, T3 `39a014b`, Signal `b2fbc59`,
Spotify `39b0d0a`. See the delivery manifest for full SHAs and APK hashes.

| Check | Command | Outcome |
| --- | --- | --- |
| SDK | `./gradlew :sdk:testDebugUnitTest :sdk:lintDebug :sdk:assembleRelease` | Exit 0; 64 tests, no failures/errors/skips. |
| SDK JS | `node --test android-sdk/javascript/test.cjs android-sdk/javascript/*.test.cjs` | Exit 0; 14 tests. |
| Host | `npm test` | Exit 1; 424/427, same 3 baseline touch failures. |
| Host APK | `npx nativescript build android` | Exit 0. |
| T3 | `npm run check` and `npm run build` | Exit 0; typecheck and 213 tests. |
| Signal | `./gradlew testDebugUnitTest lintDebug assembleDebug` | Exit 0; 71 tests. |
| Spotify | `./gradlew testDebugUnitTest lintDebug assembleDebug` | Exit 0; 2 tests. |
| Frozen consumer | `python3 android-sdk/scripts/build-frozen-consumer.py` | Exit 0; A1 and A2 built with artifact-only dependency. |

Logs are preserved in workspace `output/sdk-independence/logs/`. Frozen consumer
logs are `output/sdk-independence/frozen-consumer-A1.log` and `-A2.log`.
SDK test totals come from JUnit XML, not source annotations. Host count includes
six new production TypeScript contract-dispatch tests.

The H1 hash before and after A1/A2 builds is
`e9428f4a527be6eafcb5238ca3f7c1ba0a582cd9176378f43345ce92f51ff2a7`.
The independent Gradle project references only the immutable Maven coordinate.
No SDK project inclusion or host build task is present in that project.

Coverage added since the audited checkpoint includes production request admission,
duplicate/conflicting requests, stale/expired/over-quota rejection, dropped result
-> unknown, old-host no-new-wire fallback, renderer failure suspension/recovery,
bounded diagnostic delivery, ordered state, 5,000-resource SDK registry churn,
scene/writer pins, legacy resource replay, capture terminal races, cancellation
without host acknowledgement, JavaScript terminal cleanup, protected shell control
rejection, and async policy rechecks.

Limitations: tests do not establish actual Binder failure delivery, microphone
release, native resource memory plateau, physical input semantics or device
compatibility. See STATUS.md for incomplete software requirements. Build success
and mocks do not satisfy G1-G4 or release acceptance.
