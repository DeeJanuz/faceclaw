# Shared-composer SDK candidate handoff

Updated: 2026-09-19. This is a local debug/test candidate. It has not been
published or accepted on physical glasses hardware.

## Artifacts

- Maven: `com.faceclaw:sdk:1.1.0-rc.1.9544fd1852be`
- AAR SHA-256: `63fe4fc49bd25046bb09b7fc04a68fcef70b44d9a1232339c17dd358c696212f`
- npm: `faceclaw-motion-1.1.0-rc.1.9544fd1852be.tgz`
- npm SHA-256: `e5f0af28db60ae686c4b9bfd8ead39c459abf86bfc0a353f65b94691b6e179a5`
- Workspace manifest: `output/sdk-independence/current-sdk.json`

The artifacts add `composer.session`, `ComposerSession`, the JavaScript
`controls.composer` wrapper, and the `ui.composer` extension feature. The AAR
and npm package are immutable local files. Source mode remains explicit opt-in.

## Behavior

T3 publishes `ui.composer` by default and exposes its enablement plus the
host's permission/priority page in phone settings. The host and user remain the
authority for grants and provider order. Selecting T3 for this feature does not
select T3 for assistant, notification, transcription, or refinement features.

Signal prefers the negotiated composer for new messages and saved drafts. It
receives one terminal exact-text confirmation, then revalidates account,
conversation, generation, expiry, persisted draft, and unresolved-send state
before entering its existing send pipeline. Older hosts retain the legacy
capture/review path. Notification replies, host messaging reviews, existing
reviewed app dictation, and T3's own composition use the same coordinator when
the feature is available.

The provider receives a bounded display label and current draft. It does not
receive the caller's account, conversation history, reply token, send handle,
or microphone authority. The host owns capture, the authoritative final,
revision tracking, refinement routing, physical confirmation, expiry, and
lifecycle cancellation. New notification previews and assistant invocation are
suppressed while a composer is active.

## Verification

- SDK unit tests and the standalone example assemble pass.
- SDK JavaScript tests pass, 10/10.
- Host full suite: 471/474 pass. The three failures are the unchanged baseline
  touch/pinball tests in `tests/touch-input.test.cjs`; all composer and messaging
  tests pass. The host Android APK builds.
- T3 typecheck and tests pass, 269/269. Its Android APK builds against the pinned
  AAR and npm candidate.
- Signal assemble, unit tests, lint, and Android-test APK build pass, 122 Gradle
  tasks. Explicit sibling-SDK source compilation also passes.
- `git diff --check` passes in platform, T3, and Signal.

No Android device was attached for this candidate. Physical input, rendered
glasses evidence, permission/priority selection, lock/disconnect races, and a
fixture-backed end-to-end send remain pending. Do not use a real Signal
conversation for acceptance without explicit authorization.

Rollback is to disable T3's message-composer preference between sessions or
install a matched prior host/app/SDK set. Never replay a pending confirmation
or clear Signal data as part of rollback.
