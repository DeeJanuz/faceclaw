# Candidate verification evidence, 2026-09-15

Mode: local JVM/Node tests, Android compilation, and a phone smoke pass. Source
commits: platform `42ca250`, T3 `ce46a59`, Signal `aa1189a`, Spotify `a9ade3f`.
See the delivery manifest and [device evidence](device-20260915.md) for full
hashes and device results.

| Check | Command | Outcome |
| --- | --- | --- |
| SDK | `./gradlew :sdk:testDebugUnitTest :sdk:lintDebug :sdk:assembleRelease` | Exit 0; 66 tests, no failures/errors/skips. |
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
`b7ee5760ac9b0fb9001ab3c28373130ab0d0adc79ebbb1c8805e92275799ac0e`.
The independent Gradle project references only the immutable Maven coordinate.
No SDK project inclusion or host build task is present in that project.

Coverage added since the audited checkpoint includes production request admission,
duplicate/conflicting requests, stale/expired/over-quota rejection, dropped result
-> unknown, old-host no-new-wire fallback, renderer failure suspension/recovery,
bounded diagnostic delivery, ordered state, 5,000-resource SDK registry churn,
scene/writer pins, legacy resource replay, capture terminal races, cancellation
without host acknowledgement, JavaScript terminal cleanup, protected shell control
rejection, and async policy rechecks.

Limitations: the phone pass does not establish Binder failure delivery, microphone
release, native resource memory plateau, physical ring/temple/watch semantics or
full host compatibility. See STATUS.md for incomplete software requirements. Build
success, smoke checks, and mocks do not satisfy G1-G4 or release acceptance.
