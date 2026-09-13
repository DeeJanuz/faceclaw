#!/usr/bin/env bash
# Local-only checks for the documented animation starter patterns.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
animation_android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$animation_android_sdk" || ! -f "$animation_android_sdk/platforms/android-35/android.jar" ]]; then
  echo 'Set ANDROID_HOME (or ANDROID_SDK_ROOT) to an SDK with Android API 35 installed.' >&2
  exit 1
fi
node --test javascript/test.cjs javascript/animation-example.test.cjs
./gradlew :sdk:testDebugUnitTest
animation_classes=$(mktemp -d "${TMPDIR:-/tmp}/faceclaw-animation-examples.XXXXXXXX")
trap 'rm -rf -- "$animation_classes"' EXIT
javac -cp "sdk/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:$animation_android_sdk/platforms/android-35/android.jar" \
  -d "$animation_classes" examples/AnimatedCardAppService.java
echo 'Animation model tests, starter-controller tests, and Java example compilation passed.'
