const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');

const { transpile } = require('./helpers/transpile.cjs');
function setup() {
  const module = { exports: {} },
    sent = [];
  let now = 100000,
    locked = false,
    screenOn = true,
    request,
    cancelled = 0;
  const shell = { isScreenOn: () => screenOn, canShowExtensionOverlay: () => true };
  const imports = {
    '@nativescript/core': {},
    '../../ui/shell/shell': { shell },
    '../../native/anthropic': {},
    '../../ui/dashboard-settings': {},
    './extension-policy': require('../.test-build/app/apps/external/extension-policy.js'),
  };
  vm.runInNewContext(transpile('app/apps/external/platform.ts'), {
    module,
    exports: module.exports,
    require: (name) => imports[name] || {},
    Date: { now: () => now },
    global: { isAndroid: false },
    setTimeout,
    clearTimeout,
  });
  const platform = Object.create(module.exports.ExternalAppPlatform.prototype),
    state = { window: { windowId: 'apk:signal' }, ready: true, visible: true, lastInput: now };
  platform.extensions = {
    onNativeEvent: () => false,
    clearOwnNotifications: () => {},
    startComposer: (value) => {
      request = value;
      return () => {
        cancelled++;
        value.complete('cancelled', undefined, 'caller_cancelled');
      };
    },
    cancelComposer: () => false,
  };
  platform.windows = new Map([['signal', state]]);
  platform.options = { isLocked: () => locked };
  platform.native = {
    allows: () => true,
    send: (_component, type, json) => {
      sent.push({ type, data: JSON.parse(json) });
      return true;
    },
  };
  const data = {
    composerId: 'composer-1',
    purpose: 'message',
    target: 'thread-1',
    label: 'Signal message',
    initialText: '',
    maxText: 8000,
  };
  return {
    platform,
    state,
    sent,
    data,
    get request() {
      return request;
    },
    get cancelled() {
      return cancelled;
    },
    advance: (value) => (now += value),
    lock: (value) => (locked = value),
    screen: (value) => (screenOn = value),
  };
}

test('composer admission binds the visible caller and returns one terminal result', () => {
  const h = setup();
  h.platform.onEvent('signal', 'composer-start', h.data);
  assert.equal(h.state.reviewPurpose, 'composer');
  assert.equal(h.state.lastInput, 0);
  assert.equal(h.request.originWindowId, 'apk:signal');
  h.request.complete('confirmed', 'Exact reviewed text');
  assert.deepEqual(h.sent, [
    { type: 'composer-status', data: { composerId: 'composer-1', status: 'confirmed', text: 'Exact reviewed text' } },
  ]);
  assert.equal(h.state.reviewId, undefined);
  h.request.complete('confirmed', 'Replay');
  assert.equal(h.sent.length, 1);
});

test('composer rejects stale gestures, invalid bounds, lock, and overlapping reviews', () => {
  for (const deny of [
    (h) => h.advance(5001),
    (h) => h.lock(true),
    (h) => h.screen(false),
    (h) => (h.state.visible = false),
    (h) => (h.state.cancelReview = () => {}),
    (h) => (h.data.maxText = 20001),
    (h) => (h.data.initialText = 'x'.repeat(8001)),
  ]) {
    const h = setup();
    deny(h);
    h.platform.onEvent('signal', 'composer-start', h.data);
    assert.equal(h.request, undefined);
    assert.equal(h.sent[0].data.status, 'rejected');
  }
});

test('caller cancellation cannot settle a later composer', () => {
  const h = setup();
  h.platform.onEvent('signal', 'composer-start', h.data);
  h.platform.onEvent('signal', 'composer-cancel', { composerId: 'wrong' });
  assert.equal(h.cancelled, 0);
  h.platform.onEvent('signal', 'composer-cancel', { composerId: 'composer-1' });
  assert.equal(h.cancelled, 1);
  assert.equal(h.sent[0].data.status, 'cancelled');
  h.request.complete('confirmed', 'Late');
  assert.equal(h.sent.length, 1);
});

test('synchronous provider-open failure emits one terminal rejection', () => {
  const h = setup();
  h.platform.extensions.startComposer = (value) => {
    value.complete('rejected', undefined, 'provider_unavailable');
    return null;
  };
  h.platform.onEvent('signal', 'composer-start', h.data);
  assert.deepEqual(h.sent, [
    { type: 'composer-status', data: { composerId: 'composer-1', status: 'rejected', reason: 'provider_unavailable' } },
  ]);
  assert.equal(h.state.reviewId, undefined);
});
