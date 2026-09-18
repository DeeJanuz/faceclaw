/*
In NativeScript, the app.ts file is the entry point to your application.
You can use this file to perform app-level initialization, but the primary
purpose of the file is to pass control to the app’s first module.
*/

import { Application } from '@nativescript/core'
import { registerShareIntentHandler } from './native/share-intents'
import { installNativeUserAgent } from './util/http'
import { dashboardController } from './g2/dashboard-controller'

installNativeUserAgent()
registerShareIntentHandler()

Application.run({ moduleName: 'app-root' })

// A sticky foreground-service restart can recreate the NativeScript process
// without loading the phone Activity. Reconcile the persisted session intent
// after the runtime boots; the controller keeps this path permission- and
// pairing-safe and never opens a background permission prompt.
void dashboardController.restoreBackgroundSessionIfRequested()

// Don't place any code after the application has been started
