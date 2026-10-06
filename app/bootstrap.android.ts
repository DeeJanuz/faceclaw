import { Application } from '@nativescript/core'
import { registerShareIntentHandler } from './native/share-intents'
import { installNativeUserAgent } from './util/http'
import { registerPhoneRotation } from './native/phone-rotation'
import { dashboardController } from './g2/dashboard-controller'
import { weatherBridge } from './native/weather'

installNativeUserAgent()
registerShareIntentHandler()
registerPhoneRotation()

// With while-in-use location permission, Android only gives fresh fixes while
// the Faceclaw screen is open, so take one for glasses weather whenever it opens.
Application.on(Application.resumeEvent, () => weatherBridge.refreshIfLocationStale())

Application.run({ moduleName: 'app-root' })

// A sticky foreground-service restart can recreate the NativeScript process
// without loading the phone Activity. Reconcile the persisted session intent
// after the runtime boots; the controller keeps this path permission- and
// pairing-safe and never opens a background permission prompt.
void dashboardController.restoreBackgroundSessionIfRequested()
