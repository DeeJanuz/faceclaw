const test = require('node:test');
const assert = require('node:assert/strict');
const { appControls } = require('./controls');
const bridge = {
  json: (v) => JSON.parse(JSON.stringify(v)),
  plain: (v) => JSON.parse(JSON.stringify(v)),
  consumer: (f) => f,
  runnable: (f) => f,
  policy: (v) => v,
  purpose: (v) => v,
};
test('Java control result stays unknown through the JavaScript binding', async () => {
  let callback, observed;
  const controls = appControls(
    {
      controls: () => ({
        request: (op, payload, ms, done) => {
          observed = { op, payload, ms };
          callback = done;
        },
      }),
    },
    bridge,
  );
  const result = controls.request('window.menu', { available: true });
  callback({ state: 'unknown', reason: 'expired' });
  assert.deepEqual(await result, { state: 'unknown', reason: 'expired' });
  assert.deepEqual(observed, { op: 'window.menu', payload: { available: true }, ms: 5000 });
});
test('unavailable host is explicit and disposal stops capture callbacks', async () => {
  assert.equal((await appControls(null, bridge).request('window.sleep')).reason, 'host_unavailable');
  let callback,
    cancelled = 0,
    events = 0;
  const adapter = appControls(
    {
      controls: () => ({
        capture: (purpose, label, generation, done) => {
          callback = done;
          return { id: 'capture', cancel: () => cancelled++, finish() {} };
        },
      }),
    },
    bridge,
  );
  adapter.capture('generic', 'Draft', () => events++);
  callback({ type: 'capture-transcript', text: 'draft' });
  adapter.dispose();
  callback({ type: 'capture-transcript', text: 'late' });
  assert.equal(events, 1);
  assert.equal(cancelled, 1);
});

test('terminal captures are released for synchronous and asynchronous completion', () => {
  for (const synchronous of [false, true]) {
    let callback,
      cancelled = 0;
    const event = { type: 'capture-status', status: 'complete' };
    const adapter = appControls(
      {
        controls: () => ({
          capture: (purpose, label, generation, done) => {
            callback = done;
            if (synchronous) done(event);
            return { id: 'capture', cancel: () => cancelled++, finish() {} };
          },
        }),
      },
      bridge,
    );
    adapter.capture('generic', 'Draft', () => {});
    if (!synchronous) callback(event);
    adapter.dispose();
    assert.equal(cancelled, 0);
  }
});
