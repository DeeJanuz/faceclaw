# P00 baseline evidence

Captured 2026-09-15 in the shared WSL2 checkout. This is a read-only baseline;
no product source, build configuration, credentials, or device data was changed.
The only file added by P00 is this evidence record.

## Checkout state

The task started with these clean repository heads:

| Checkout | Branch | Starting HEAD | Dirty state at task start |
| --- | --- | --- | --- |
| `faceclaw-app-platform` | `design/apk-app-platform` | `74cb519bd42462e806679442d7dd26bd6e834a78` | clean |
| `faceclaw-t3-app` | `main` | `9c89adc09250833a3329f49cd0cccc983fb89c1c` | clean; ahead 14 of `origin/main` |
| `faceclaw-signal-native` | `feature/native-signal` | `a853885ae648b32a5eb51080d1440f72e6c161d2` | clean |
| `faceclaw-spotify-native` | `main` | `5bb829aa180e13020722c3c9e71fd81455bee4ae` | clean |

The host checkout subsequently advanced to contract freeze commit
`bffa22f2d7038390e7567099fef1a1c601af71cd`. At evidence capture it also had an
uncommitted `android-sdk/sdk/src/main/java/com/faceclaw/sdk/FaceclawAppService.java`
change owned by the P03 implementation worker. That file was not staged or
modified by P00. The other three checkouts remained at the heads above.

## Toolchain inventory

| Tool or requirement | Observed value |
| --- | --- |
| OS/runtime | Linux WSL2, kernel `6.6.87.2-microsoft-standard-WSL2`, x86_64 |
| Node.js | `v24.19.0` |
| npm | `11.17.0` |
| NativeScript CLI | `9.1.1` from `npm exec` |
| TypeScript | `5.4.5` in the host and T3 app node modules |
| Java/JDK | JDK 21 at `/home/deej/.local/jdk-21`; not exported in the default shell |
| `JAVA_HOME` | unset by default; explicit `/home/deej/.local/jdk-21` works |
| `ANDROID_HOME` | unset by default; explicit `/home/deej/.local/android-sdk` works |
| `ANDROID_SDK_ROOT` | unset by default; explicit `/home/deej/.local/android-sdk` works |
| Gradle on PATH | unavailable; use repository wrappers |
| Android SDK candidate | `/home/deej/.local/android-sdk`: platforms 35 and 36; build-tools 35.0.0 and 35.0.1; NDK 28.2.13676358; CMake 3.31.6 |
| Alternate SDK candidate | `/home/deej/.local/share/even-realities/android-tools/sdk`: platforms 35 and 36; build-tools 35.0.0 and 36.0.0 |
| Android platform tools | ADB 1.0.41, version `37.0.1-15733141` at both candidate SDKs |
| Connected devices | none (`adb devices -l` listed no devices) |

The SDK README requires Android SDK 35 and JDK 17+. The host uses compile/target
SDK 35 and min SDK 27. T3's build script requires JDK 21 and an Android SDK.
Signal requires JDK 21, compile SDK 36, target SDK 35 and min SDK 27. Spotify
requires JDK 17+, compile SDK 36, target SDK 35 and min SDK 27. The SDK, Signal
and Spotify wrappers use Gradle 8.14.3; the generated host wrapper also declares
Gradle 8.14.3. SDK AGP is 8.9.2; Signal and Spotify use AGP 8.13.2, with Signal
Kotlin 2.3.20.

The local SDK candidates and JDK are sufficient for the declared Android API
levels when selected explicitly. No system settings were changed and no
toolchain was installed as part of P00. The remaining setup issue is environment
discovery in a default shell, not an absent local toolchain.

## Executed checks

Commands below were run from the listed checkout. Exit codes are the command
exit codes. A blocked check is recorded separately from a test failure.

| Area | Command | Result |
| --- | --- | --- |
| SDK JavaScript examples | `node --test javascript/test.cjs javascript/animation-example.test.cjs` from `faceclaw-app-platform/android-sdk` | PASS, 9/9 |
| SDK animation examples | `JAVA_HOME=/home/deej/.local/jdk-21 ANDROID_HOME=/home/deej/.local/android-sdk ANDROID_SDK_ROOT=/home/deej/.local/android-sdk bash scripts/check-animation-examples.sh` | NOT RUN in P00; JavaScript portion passed and the explicit JDK path is now known |
| SDK unit tests | `JAVA_HOME=/home/deej/.local/jdk-21 ANDROID_HOME=/home/deej/.local/android-sdk ./gradlew :sdk:testDebugUnitTest` from `android-sdk` | PASS, Gradle build successful |
| SDK lint/build | `JAVA_HOME=/home/deej/.local/jdk-21 ANDROID_HOME=/home/deej/.local/android-sdk ./gradlew :sdk:lintDebug :sdk:assembleDebug :sdk:assembleRelease` from `android-sdk` | NOT RUN in P00; schedule after implementation changes |
| Host focused extension/external tests | `node --test tests/extension-*.test.cjs tests/external-*.test.cjs` | PASS, 91/91 |
| Host full suite | `npm test` | FAIL, 416/421 passed, 5 failed; see failures below |
| Host APK build | `JAVA_HOME=/home/deej/.local/jdk-21 ANDROID_HOME=/home/deej/.local/android-sdk npm run build` | BLOCKED, exit 127: NativeScript did not discover a compatible SDK/build-tools through its configured environment |
| Host generated Gradle build | `./gradlew :app:assembleDebug` from `faceclaw-app-platform/platforms/android` | BLOCKED, exit 1 at wrapper startup: no `JAVA_HOME`/`java` |
| T3 checks | `npm run check` | PASS, typecheck plus 213/213 tests |
| T3 Android build | `JAVA_HOME=/home/deej/.local/jdk-21 ANDROID_HOME=/home/deej/.local/android-sdk npm run build` | NOT RUN in P00; default invocation is blocked by unset `JAVA_HOME` |
| Signal browser QR contract | `node --experimental-vm-modules --test app/src/test/qr-display/contract.test.mjs` | PASS, 11/11 |
| Signal native APK validator | `python3 scripts/verify-native-apk.py app/build/outputs/apk/debug/app-debug.apk` | PASS against the pre-existing APK artifact; SHA-256 `d8ceacef5449a8e80431ee155c9a9e83382493c63c62c12084f48189899a1c6d` |
| Signal Gradle checks | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | BLOCKED, exit 1 at wrapper startup: no `JAVA_HOME`/`java` |
| Spotify Gradle checks | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | BLOCKED, exit 1 at wrapper startup: no `JAVA_HOME`/`java` |

The pre-existing Signal APK validator result does not establish a fresh build;
the app and instrumentation APKs could not be rebuilt without Java. Existing
Gradle reports and APKs under ignored `build/` directories were treated as stale
artifacts, not current baseline passes.

## Current host failures

The five failures from the full host suite are:

1. `tests/notification-compositor.test.cjs`: `javac ENOENT` while compiling
   `NotificationCompositorCheck.java`.
2. `tests/render-cadence.test.cjs`: `javac ENOENT` while compiling
   `RenderCadenceCheck.java`.
3. `tests/touch-input.test.cjs`: `wire edges require explicit left/right temple
   provenance`, actual `unknown`, expected `touch-press`.
4. `tests/touch-input.test.cjs`: `pinball responds on contact, holds
   independently, and releases before tap`, actual `rest`, expected `rising`.
5. `tests/touch-input.test.cjs`: `pinball clears held contacts on pause, focus
   loss, screen-off and menus`, actual `undefined`, expected `false`.

The first two are missing-prerequisite failures. The three touch-input failures
remain current test failures and were not changed or waived by P00. They match
the three historical touch failures called out in the implementation plan.

## Native boundary runner

The SDK project includes `:fixture`, `:host-tests`, and `:priority-demo`. The
host-tests module is an Android application with `standalone`, `upstream`, and
`t3` flavors, compile/target SDK 35, and runner
`com.faceclaw.sdk.hosttest.BoundaryTest`. The source explicitly checks separate
Android UIDs and invokes a shell-installed demo instrumentation package. The
priority runner refuses physical devices and requires an emulator serial.

The inspected build/install sequence is:

```sh
./gradlew :sdk:testDebugUnitTest :fixture:assembleDebug \
  :host-tests:assembleStandaloneDebug :host-tests:assembleStandaloneDebugAndroidTest \
  :priority-demo:assembleAlphaDebug :priority-demo:assembleBetaDebug \
  :priority-demo:assembleAlphaDebugAndroidTest :priority-demo:assembleBetaDebugAndroidTest
```

The custom runner then installs the fixture, host-test APKs and both demo APK
variants and invokes:

```sh
adb -s <emulator-serial> shell am instrument -w \
  com.faceclaw.sdk.hosttest.test/com.faceclaw.sdk.hosttest.BoundaryTest
```

It prepares each demo through
`com.faceclaw.demo.{a,b}.test/com.faceclaw.demo.DemoSetup` and runs the seven
independent-APK scenarios with `-e demos true -e only <testName>`. This was not
run because the wrapper cannot start without Java and no device/emulator is
connected. No separate-UID execution evidence is claimed here.

## Host artifact observation

An ignored pre-existing debug APK existed at
`platforms/android/app/build/outputs/apk/debug/app-debug.apk`:

```text
package: com.faceclaw.app
size: 105316574 bytes
sha256: 8efe78cafb0ca5ba32d5ee47fb8427ccc688c2db00994899750746fb500732c8
```

`apksigner verify --print-certs` could not inspect its signing identity because
`apksigner` also requires Java. This artifact is not a newly built candidate and
was not installed.

## Baseline conclusion

JavaScript-only verification is runnable and currently green for the SDK motion
examples, host extension/external boundary tests, T3 checks, and Signal QR
contract. The SDK Java unit suite also runs when the local JDK and Android SDK
are selected explicitly. Lint, APK rebuilds, instrumentation, signing identity
inspection, and physical/emulator checks remain pending. Default-shell builds
still need `JAVA_HOME` and Android SDK variables configured. A device or
emulator is additionally required for the separate-UID/native boundary and
Android instrumentation checks.
