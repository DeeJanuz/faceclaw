# Assistant messaging

T3's desktop agent can prepare text messages for Signal and SMS. Every send requires the host's full-message review and a final tap on the glasses. The desktop bridge remains required. Initial Android permissions and Signal linking require the phone; routine drafting, history consent, review and sending do not.

## Setup

1. Install matching builds of the general Faceclaw host and standalone T3 APK. Enable T3's device-tool role and select its assistant project. Pair and connect the existing desktop bridge, and enable its dictation role for voice commands.
2. For Signal, install and link the native Signal APK. Select this host, then explicitly enable **Assistant messaging** in Faceclaw's permissions for Signal. This new permission defaults off, including on upgrades. The first implementation uses native Signal mode and the linked contact/group directory; it does not add bridge-mode account access or import pre-link phone history.
3. For SMS, open Faceclaw's phone **Manage Android applications** screen and choose **SMS setup**. Grant SMS, phone-state (SIM selection) and optional contacts permissions. The installer must allowlist Android's restricted SMS permissions. The existing default SMS app remains selected. The `messaging.status` tool reports granted permissions and available SIMs. A full international number works without contact access.

SMS means carrier SMS, including multipart text. It does not include RCS, MMS, attachments or group creation. Existing Signal groups support text. On multi-SIM phones, recipient choices include the sending account; the review displays the selected SIM. The assistant must resolve ambiguous names or SIM choices with the user.

## Use on the glasses

Ask T3 to message a named Signal contact/group or an SMS contact/number. The agent resolves the recipient and saves a draft. It may request access to recent conversation history for context. Only a separate physical **Allow this session** confirmation grants that access; recipient lookup and drafting do not grant history access.

The host displays the channel, resolved recipient, sending account and complete message across measured pages. Tap or scroll to advance. After the last page, tap **Send**. Double tap cancels and retains the draft. To edit, cancel, dictate the correction to T3, and review the updated draft. Updating the draft invalidates the old review.

History access lasts for the connected assistant session. Reconnect, re-pairing, host changes and permission revocation remove that access. **Settings > Messaging > Revoke assistant history access** revokes it directly on the glasses. Approved history goes to the desktop assistant and its configured model; revocation prevents further reads but cannot retract previously supplied context. Reads return bounded recent messages, not an inbox export.

The glasses show submission and final outcomes. SMS callbacks and Signal operation records reconcile status without replaying sends. A native Android notification reply reports that it was handed to the app; that is not a delivery receipt. Partial or unknown sends block another send to that recipient until there is definitive evidence. Reconnect never sends a saved draft automatically.

## Implementation boundary

The existing bridge relay transports `messaging.*` tools. T3 adds a short-lived desktop-session heartbeat, pairing fingerprint and project binding. The host additionally binds saved drafts to the approved APK signing identity. Its broker owns history consent, draft versions, expiry, review and durable operation evidence. Tool results and drafts are not permission to send.

The SDK negotiates a separate messaging capability. The selected, approved Signal host sends bounded requests over the existing authenticated AIDL session; no ADB forwarding, new phone server or exported admin endpoint is needed. Revocation, session identity, request expiry and response identity are checked on both sides. Signal rechecks the host authorization after loading send metadata and retains its existing encrypted native send ledger. SDK sends do not clear an unrelated manual Signal draft.

SMS runs in a host-private Java provider. It uses Android Keystore encryption and non-backup atomic files for drafts and send evidence, records operations before dispatch, selects a specific subscription, and counts multipart callbacks once. Its result receiver is non-exported. Neither message bodies nor credentials are logged. Storage damage disables messaging rather than silently resetting send evidence.

Generic notification tools expose app/action metadata only, so they cannot bypass conversation history consent. Agent notification actions cannot send directly; replies use the same host review. Updated T3 refuses legacy notification read/reply tools when the host lacks the new review capability. Ordinary non-messaging device tools remain available.

## Verification and remaining device acceptance

Automated checks cover project/pairing isolation, physical review, stale edits, duplicate confirmation, history revocation during reads, reconnect, persistence failure, expiry, unknown outcomes and status reconciliation. Native emulator checks cover encrypted storage/tamper rejection, SDK permission and cancellation boundaries, SMS permission availability and multipart callback persistence. They never send a real message.

The emulator installation route accepted SMS/history permissions and exposed one synthetic SIM. This does not establish permissions on every phone/installer. Physical acceptance still requires authorized Signal and SMS sends, with the phone screen off and real glasses connected, plus live Signal linking, carrier callback, multi-SIM and background-lifecycle checks. Three pre-existing pinball/touch tests fail on the original host HEAD as well as this work; they are unrelated to messaging.
