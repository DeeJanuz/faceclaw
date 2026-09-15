# SDK candidate build handoff

This is a local test candidate, not a release accepted against all original goals.
See [status](STATUS.md) for the software gaps and pending device matrix.

## Prepared artifacts

Workspace directory: `output/sdk-independence/delivery/1.1.0-rc.1.9dc05298f50a/`.
It contains `host-H1.apk`, `t3.apk`, `signal.apk`, `spotify.apk`, `counter-A1.apk`,
`counter-A2.apk`, `SHA256SUMS`, `release-manifest.json`, and fixed-host build evidence.
All APKs are debug/test builds. The four existing app packages were upgraded in
place during the authorized smoke pass; no package was uninstalled or cleared.

SDK distribution is in `output/sdk-independence/maven/` and `npm/`:

- Maven: `com.faceclaw:sdk:1.1.0-rc.1.9dc05298f50a`.
- npm: `faceclaw-motion-1.1.0-rc.1.9dc05298f50a.tgz`, package `@faceclaw/motion`.
- SDK AAR SHA-256: `bfaddfd9a92043f9f411df277cf36b98b2d3af8663fbac2cac4b4f8e3fe27544`.
- npm SHA-256: `15099ef5515f8b599bbd7281b33ebe439c2170a6c1a2bc5c26896be261972f81`.

The packaging script refuses to replace an existing artifact with different bytes.
Previous candidate versions are retained. Copy the Maven repository and npm tarball
along with app source when building on another machine. These local artifacts have
not been remotely published and are not embedded in Git.

## Building consumers

T3, Signal and Spotify default to the pinned Maven artifact. For native Gradle
consumers, add the copied Maven directory to repositories and depend on the exact
coordinate above. The three migrated apps accept
`-PfaceclawSdkRepository=/absolute/path/to/maven`; sibling SDK source is enabled only
by explicit `-PfaceclawSdkSource=true`. T3's package.json pins the npm tarball through
a local file dependency; preserve its relative output directory when copying.

Use JDK 21 and Android SDK 35. In this workspace the verified toolchain is:

```sh
export JAVA_HOME=/home/deej/.local/jdk-21
export ANDROID_HOME=/home/deej/.local/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Run `npx nativescript build android` in the host checkout. Its legacy `build.sh`
sources local build_paths.sh, which can override the explicit Linux SDK path.
T3 uses `npm run build`; Signal and Spotify use
`./gradlew testDebugUnitTest lintDebug assembleDebug`.

From the SDK directory, build with `./gradlew :sdk:assembleRelease`, then run
`python3 scripts/package-candidate.py`. Only publish the resulting new coordinate
after rerunning checks and updating consumers. From the workspace root,
`python3 faceclaw-app-platform/android-sdk/scripts/build-frozen-consumer.py` builds
both independent sample variants and records the host hash before and after.
That script uses this workspace's local toolchain paths. It invokes the Gradle
wrapper as a launcher with an isolated project; no host or SDK project is included.

## Public API and compatibility

`FaceclawAppService.controls()` exposes negotiated controls. Wait for `onReady`,
check `supports`, then apply `WindowPolicy`. A control callback may report applied,
rejected, cancelled or unknown. Unknown is not permission to retry a side effect.
Legacy boolean-returning helpers still mean transport submission, not host approval.

JavaScript imports `appControls` from `@faceclaw/motion`. It wraps the Java SDK's
state machine; call `dispose()` when the owning adapter is torn down. Captures
produce draft text only. Preserve existing explicit review and source-bound reply
checks before sending any message.

Catalog support does not grant microphone, notification, provider or window
permissions. Current host checks remain authoritative. An absent/invalid catalog
makes the new control API unsupported without emitting new protocol messages.
T3 selects its existing capture path on old hosts. Old/new host runtime compatibility
is not yet certified by the full device matrix.

The standalone counter shares one app controller between LocalPresentation and
HostPresentation. The phone adapter has no microphone or messaging authority.
A1 increments by one; A2 increments by two. Compilation and the phone smoke pass
prove artifact isolation and persisted state across this debug APK upgrade; they do
not cover the full host/ring acceptance path.

For negotiated resource release, the host uses raster fallback instead of placing
releasable resources into global firmware atlases that cannot reclaim individual
entries. This preserves reclamation at a possible performance cost. Legacy hosts
retain legacy resource behavior and quota limits; close cannot promise remote
reclamation when release was not negotiated.

## Device smoke result and remaining acceptance

The authorized phone smoke pass is recorded in
`docs/sdk-independence/evidence/device-20260915.md`. The phone was reached through
the Windows ADB server's wireless transport. Existing packages were upgraded with
`adb install -r`; first-install timestamps stayed unchanged. No uninstall, clear,
or live-account action was used. The SDK zero-viewport startup crash found on the
first pass was fixed in the `9dc05298f50a` candidate before the successful pass.

The original P31 matrix is still required after the software gaps in STATUS.md are
closed. Physical ring/temple/watch input, Binder replacement, revocation, capture,
resource plateau, and protected-flow misuse checks are not established by this
phone launch/persistence smoke test.
