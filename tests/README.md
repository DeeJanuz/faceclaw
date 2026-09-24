# Tests

Faceclaw's pairing logic (`app/g2/glasses-hardware-identity.ts`,
`ble-proximity.ts`, `even-advertisement.ts`, `pairing-candidates.ts`,
`glasses-artwork.ts`) is written without NativeScript imports so it can run
under plain node. The tests here cover hardware-identity decoding, proximity
estimation, the advertisement parsers, and left↔right pair grouping.

Run with:

    npm test

That compiles the pure modules with the project's TypeScript into
`.test-build/` (git-ignored) and runs `node --test tests/`. Most tests are
plain `.test.cjs` files. Tests for the small Java-native helpers also invoke
`javac` and `java`, so a JDK must be on `PATH` for the complete suite.

The shared Kotlin suite runs separately from the Node tests:

    npm run test:kotlin
    npm run test:kotlin:ios

It includes the migrated compass, R1 battery, gesture and texture Java fixtures,
which now call the Kotlin implementation from Android host tests. Common tests
also run as native iOS simulator binaries. See [the Kotlin README](../native/kotlin/README.md)
for SDK requirements, source layout and coverage. The compass and battery
fixtures retain the golden packets shared with firmware tests.

The native iOS EvenHub probe runs separately on a booted simulator:

    node tests/ios-evenhub-native.cjs <simulator-UUID>

It compiles the production WKWebView host into an isolated app and checks local
and remote loading, document-start injection, assets, storage and navigation.
It then opens Safari to background the probe and verifies that injected timeouts,
intervals and animation callbacks still fire, and cancelled timers stay cancelled.
A short UIKit background task keeps the test process running without glasses.
This covers the ticker's background behavior; sustained execution with BLE and a
locked physical phone still requires device testing.

## Writing host tests

Tests that exercise NativeScript-facing TypeScript load the production file in
a `vm` context with its imports stubbed. Use `transpile(path)` from
`helpers/transpile.cjs` to compile a file once per run, or `transpileSource`
for an extracted snippet. Assert on behavior (calls, state, output), not on
source text; configuration files such as manifests and layout XML are the
exception.

Format test files before committing:

    npx prettier@3 --print-width 120 --single-quote --write <files>
    java -jar google-java-format-1.23.0-all-deps.jar --aosp --replace <files>
