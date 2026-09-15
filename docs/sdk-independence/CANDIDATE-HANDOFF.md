# SDK candidate build handoff

This is a local test candidate, not a release accepted against all original goals.
See [status](STATUS.md) for the software gaps and pending device matrix.

## Prepared artifacts

Workspace directory: `output/sdk-independence/delivery/1.1.0-rc.1.a9881121c93e/`.
It contains `host-H1.apk`, `t3.apk`, `signal.apk`, `spotify.apk`, `counter-A1.apk`,
`counter-A2.apk`, `SHA256SUMS`, `release-manifest.json`, and fixed-host build evidence.
All APKs are debug/test builds. Installation compatibility with an existing signed
installation has not been checked. No install or data-clear commands were run.

SDK distribution is in `output/sdk-independence/maven/` and `npm/`:

- Maven: `com.faceclaw:sdk:1.1.0-rc.1.a9881121c93e`.
- npm: `faceclaw-motion-1.1.0-rc.1.a9881121c93e.tgz`, package `@faceclaw/motion`.
- SDK AAR SHA-256: `c364909e2dd80fb18abe7fdffe2f4771e5e0ac1b60d983c0057f2ac719d34284`.
- npm SHA-256: `04db5e647178f41739f5c63a05fbdc0207a975f1b6182022a04db08c1c4463bb`.

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
is not yet certified by a device run.

The standalone counter shares one app controller between LocalPresentation and
HostPresentation. The phone adapter has no microphone or messaging authority.
A1 increments by one; A2 increments by two. Compilation proves artifact isolation;
it does not prove persisted state across an installed upgrade.

For negotiated resource release, the host uses raster fallback instead of placing
releasable resources into global firmware atlases that cannot reclaim individual
entries. This preserves reclamation at a possible performance cost. Legacy hosts
retain legacy resource behavior and quota limits; close cannot promise remote
reclamation when release was not negotiated.

## Resume device acceptance

Device work remains pending by user direction. Use the original P31 matrix after
closing the software gaps in STATUS.md. Keep H1 fixed, use synthetic identities,
and preserve existing app data and live accounts. Do not infer authorization to
replace a signed installation or operate a live account from this build handoff.
