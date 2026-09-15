import { Application, Utils } from "@nativescript/core";

import { loadDeviceAddresses } from "../g2/device-addresses";

declare const com: any;
declare const global: any;

let resultListenerInstalled = false;

function context(): any {
  return Utils.android.getApplicationContext();
}

function activity(): any {
  return Application.android?.foregroundActivity ?? Application.android?.startActivity ?? null;
}

function helper(): any {
  return com.faceclaw.app.FaceclawCompanionTrust;
}

/** Whether this Android version exposes Companion Device Manager. */
export function isCompanionTrustSupported(): boolean {
  if (!global.isAndroid) return false;
  try {
    return helper().isSupported() === true;
  } catch {
    return false;
  }
}

/** Whether Android still has Faceclaw's user-approved association. */
export function hasCompanionTrust(): boolean {
  if (!isCompanionTrustSupported()) return false;
  try {
    return helper().isAssociated(context()) === true;
  } catch {
    return false;
  }
}

/**
 * Launch Android's device chooser. The chooser and consent dialog are owned by
 * Android; Faceclaw never marks itself trusted from a local setting.
 */
export function requestCompanionTrust(): boolean {
  if (!isCompanionTrustSupported()) return false;
  const current = activity();
  if (!current) return false;
  installLegacyResultListener();
  const addresses = loadDeviceAddresses();
  const address = addresses.right || addresses.left || "";
  try {
    return helper().requestAssociation(current, address) === true;
  } catch {
    return false;
  }
}

/** Remove only Faceclaw's saved OS association after an explicit user action. */
export function removeCompanionTrust(): boolean {
  if (!isCompanionTrustSupported()) return false;
  try {
    return helper().disassociate(context()) === true;
  } catch {
    return false;
  }
}

/** API 26–30 returns the selected Bluetooth address through the activity result. */
function installLegacyResultListener(): void {
  if (resultListenerInstalled || !global.isAndroid) return;
  resultListenerInstalled = true;
  const requestCode = helper().requestCode();
  const listener = (args: { requestCode: number; resultCode: number; intent: any }) => {
    if (args.requestCode !== requestCode) return;
    Application.android.off(Application.android.activityResultEvent, listener);
    resultListenerInstalled = false;
    if (args.resultCode !== android.app.Activity.RESULT_OK || !args.intent) return;
    try {
      const device = args.intent.getParcelableExtra("android.companion.extra.DEVICE");
      const address = device?.getAddress?.();
      if (address) helper().recordLegacyResult(context(), String(address));
    } catch {
      // Android 12+ persists AssociationInfo from the native callback instead.
    }
  };
  Application.android.on(Application.android.activityResultEvent, listener);
}
