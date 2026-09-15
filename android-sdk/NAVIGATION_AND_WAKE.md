# Navigation and display wake

This document defines the boundary between the Faceclaw host, an APK navigation provider, and an EvenHub application. It covers the host behavior implemented by Faceclaw; it does not change the upstream EvenHub SDK contract.

## Navigation provider fields

The `ui.navigation` extension is a static, approved host override. The host defaults are used when no effective provider supplies a value.

| Field | Values | Host default | Meaning |
| --- | --- | --- | --- |
| `doubleTap` | `back`, `sleep` | `back` | What an awake physical double-tap does. `sleep` is consumed by the host before app routing. |
| `rootBack` | `sleep`, `switcher` | `switcher` | What semantic back does after the current app reaches its root. |
| `tapHold` | `switcher`, `app-menu` | `app-menu` | What the tap-then-hold gesture selects. |
| `hold` | `app-menu`, `system-menu` | `system-menu` | What a plain hold selects. |
| `wakeFocus` | `window`, `sidebar` | `window` | Which existing host target receives focus when the display wakes. |

T3 currently declares `doubleTap: back`, `rootBack: sleep`, `tapHold: switcher`, `hold: app-menu`, and `wakeFocus: window`. These are provider settings, not SDK defaults. Static navigation remains effective while an approved provider is installed; provider grants, ordering, dependencies, revocation, and service-outage behavior follow [APK extensions](../docs/APK-EXTENSIONS.md).

The first eligible provider in the user's priority order owns the complete navigation policy across the Faceclaw shell. The same policy therefore governs built-in apps, worker apps, native APK windows, Settings, and EvenHub compatibility windows at host-owned input boundaries. Priority does not grant permission: a disabled, ungranted, or dependency-ineligible provider is skipped. Revoking T3 navigation immediately restores the host defaults above.

## Input precedence

The host owns display power and protected gestures before forwarding app input.

1. A sleeping display consumes a double-tap as wake. It applies `wakeFocus` and does not forward the same gesture as app back, sleep, or quit.
2. A directional `display-wake` from a suspended EvenHub session wakes the same focus target, restores the session, and leaves the retained foreground app selected. Repeated directional wake while already awake does not sleep or open the switcher.
3. An awake physical double-tap follows `doubleTap`. With `back`, it reaches the focused app. With `sleep`, the host sleeps before app or menu routing.
4. Watch swipe-left remains directional back. It is not physical double-tap power behavior.
5. A semantic root-back follows `rootBack`. This is distinct from an explicit switcher command and from explicit display-off.

When `hold: app-menu` is selected, the host consumes the hold and its release as
one opening gesture. The resulting App actions menu starts on a safe app action
when `Display off` is present; power is reached only by a later deliberate
selection. The common menu may include the host-owned `Close app` action for a
closeable foreground window. That action is bound to the window that opened the
menu and cannot be redirected after focus changes.

`wakeFocus: window` focuses the retained foreground window when one exists. This is also the host fallback, so a revoked or missing provider does not turn a wake gesture into an app-switcher gesture. If there is no foreground window, the host wakes without inventing an app launch; the fallback is a host state that must be covered by device acceptance. `wakeFocus: sidebar` selects the app switcher only when the effective provider explicitly requests it.

Locked displays, protected flows, and notification-only wake keep their existing privacy and sequencing rules. A notification wake does not expose a retained private app frame before notification content is ready.

## EvenHub compatibility

The native Faceclaw SDK exposes `FaceclawAppService.requestSleep()` for an explicit display-off request. It is not a general root-back API and does not promise knowledge of an app's internal navigation depth. SDK clients should keep explicit display-off separate from their own app-root back behavior.

For legacy EvenHub compatibility, Faceclaw interprets `shutDownPageContainer(exitMode=1)` as a semantic root-back request. The host applies `rootBack` and retains the running session. Faceclaw's `returnToAppSwitcher()` extension remains an explicit switcher action regardless of `rootBack`; `quit()` remains an actual close. This interpretation is a Faceclaw adapter behavior, not an upstream EvenHub SDK guarantee.

Screen state, shell focus, app foreground state, and session connectivity are separate. Waking the display does not itself mean that a new app session was created or that an Android SDK `FOREGROUND_ENTER` callback will be emitted. Faceclaw extension apps receive their `windowLifecycle` visibility/focus events according to the host window state; native SDK apps receive the callbacks documented in [README](README.md).

## Compatibility and verification

Older Faceclaw hosts may wake suspended EvenHub content into the sidebar because they pre-woke the shell before applying `wakeFocus`. The corrected host applies the selected focus before session restoration. No wire or SDK version change is required for this host-only correction.

Validate both the ring and glasses-arm double-tap paths separately. Cover an ordinary sleeping display, a suspended EvenHub session, a retained Snake window, Settings root and inner panes, legacy EvenHub exit mode 1, explicit switcher, explicit quit, and explicit display-off. Record physical results separately from automated tests. A game-specific wake report is not an SDK guarantee unless its exact app and lifecycle path have been reproduced.
