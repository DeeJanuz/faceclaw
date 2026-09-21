import { Application } from '@nativescript/core'
import { registerShareIntentHandler } from './native/share-intents'
import { installNativeUserAgent } from './util/http'
import { registerPhoneRotation } from './native/phone-rotation'
import { dashboardController } from './g2/dashboard-controller'

import { runKotlinBridgeSmokeTest } from './native/kotlin-bridge'

declare const __DEV__: boolean;

if (__DEV__) runKotlinBridgeSmokeTest()

installNativeUserAgent()
registerShareIntentHandler()
registerPhoneRotation()

Application.run({ moduleName: 'app-root' })

// A sticky foreground-service restart can recreate the NativeScript process
// without loading the phone Activity. Reconcile the persisted session intent
// after the runtime boots; the controller keeps this path permission- and
// pairing-safe and never opens a background permission prompt.
void dashboardController.restoreBackgroundSessionIfRequested()
