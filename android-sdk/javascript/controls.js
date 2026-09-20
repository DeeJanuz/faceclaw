'use strict';
/** NativeScript binding. Tests inject conversions; wire behavior lives in the Java SDK. */
function appControls(service, bridge) {
  const b = bridge || {
    json: value => new org.json.JSONObject(JSON.stringify(value)),
    plain: value => JSON.parse(String(value.toString())),
    consumer: callback => new java.util.function.Consumer({accept: callback}),
    runnable: callback => new java.lang.Runnable({run: callback}),
    purpose: name => com.faceclaw.sdk.CaptureSession.Purpose.valueOf(name.toUpperCase()),
    policy: value => new com.faceclaw.sdk.WindowPolicy(com.faceclaw.sdk.WindowPolicy.Height.valueOf(value.height.toUpperCase()), !!value.menuAvailable, !!value.compact, !!value.hostBack, !!value.longPress, !!value.directional),
  };
  let disposed = false;
  const captures = new Set();
  const composers = new Set();
  const native = () => !disposed && service?.controls?.();
  return {
    supports(feature) { return !!native()?.supports(feature); },
    onReady(callback) { native()?.onReady(b.runnable(() => { if (!disposed) callback(); })); },
    request(operation, payload = {}, timeoutMs = 5000) {
      return new Promise(resolve => {
        const controls = native();
        if (!controls) return resolve({state: 'rejected', reason: 'host_unavailable'});
        controls.request(operation, b.json(payload), timeoutMs, b.consumer(value => resolve(b.plain(value))));
      });
    },
    policy(value) {
      return new Promise(resolve => {
        const controls = native();
        if (!controls) return resolve({state: 'rejected', reason: 'host_unavailable'});
        controls.setWindowPolicy(b.policy(value), b.consumer(result => resolve(b.plain(result))));
      });
    },
    capture(purpose, label, listener, providerGeneration = 0) {
      const controls = native();
      if (!controls) { listener({type: 'capture-status', status: 'rejected', reason: 'host_unavailable'}); return {id: '', finish() {}, cancel() {}}; }
      let handle, terminal = false;
      handle = controls.capture(b.purpose(purpose), label, providerGeneration, b.consumer(value => {
        const event = b.plain(value);
        if (event.type === 'capture-status' && ['complete', 'cancelled', 'rejected'].includes(event.status)) { terminal = true; captures.delete(handle); }
        if (!disposed) listener(event);
      }));
      if (!terminal) captures.add(handle);
      return {id: String(handle.id), finish: () => { if (!disposed) handle.finish(); }, cancel: () => { captures.delete(handle); handle.cancel(); }};
    },
    composer(purpose, target, label, initialText, maxText, listener) {
      const controls = native();
      if (!controls) { listener({status: 'rejected', reason: 'host_unavailable', composerId: ''}); return {id: '', cancel() {}}; }
      let handle, terminal = false;
      handle = controls.composer(com.faceclaw.sdk.ComposerSession.Purpose.valueOf(purpose.toUpperCase()), target, label, initialText, maxText, b.consumer(value => {
        if (terminal) return;
        const event = b.plain(value);
        if (['confirmed', 'accepted', 'cancelled', 'rejected', 'expired', 'unknown'].includes(event.status)) { terminal = true; composers.delete(handle); }
        if (!disposed) listener(event);
      }));
      if (!terminal) composers.add(handle);
      return {id: String(handle.id), cancel: () => { composers.delete(handle); handle.cancel(); }};
    },
    dispose() { disposed = true; for (const handle of captures) handle.cancel(); captures.clear(); for (const handle of composers) handle.cancel(); composers.clear(); },
  };
}
module.exports = { appControls };
