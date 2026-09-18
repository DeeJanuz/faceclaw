import { getBooleanSetting, setBooleanSettingSync } from "../native/settings-store";

// This is an intent to restore the user's active glasses session, not a
// credential or a command queue. It is cleared before deliberate teardown and
// never authorizes an action on its own.
const RESTORE_INTENT_KEY = "g2.backgroundRestoreIntent";

export function backgroundSessionRestoreRequested(): boolean {
  try {
    return getBooleanSetting(RESTORE_INTENT_KEY, false);
  } catch {
    return false;
  }
}

export function setBackgroundSessionRestoreRequested(value: boolean): void {
  try {
    setBooleanSettingSync(RESTORE_INTENT_KEY, value);
  } catch {
    // Settings are advisory for recovery. A storage failure must not break a
    // foreground connect or disconnect path.
  }
}
