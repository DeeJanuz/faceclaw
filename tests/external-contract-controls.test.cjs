const test = require('node:test');
const assert = require('node:assert/strict');

const vm = require('node:vm');
const { transpile } = require('./helpers/transpile.cjs');
function setup() {
  const module = { exports: {} },
    results = [],
    actions = [];
  let valid = true,
    locked = false,
    screen = true,
    resolveResize;
  const state = {
    window: { windowId: 'own', surfaceId: 'surface', heightMode: 'min' },
    ready: true,
    visible: true,
    lastInput: 10000,
  };
  const shell = {
    isScreenOn: () => screen,
    foregroundWindow: () => ({ windowId: 'own' }),
    canShowExtensionOverlay: () => true,
    returnFromAppRoot: () => actions.push('root'),
    sleepAtAppRoot: () => actions.push('sleep'),
    openSystemMenu: () => actions.push('menu'),
  };
  vm.runInNewContext(transpile('app/apps/external/platform.ts'), {
    module,
    exports: module.exports,
    require: (name) =>
      name === '../../ui/shell/shell'
        ? { shell }
        : name === '../../ui/shell/geometry'
          ? { appViewportSize: () => ({ width: 576, height: 452 }) }
          : {},
    Date: { now: () => 10000 },
    clearTimeout,
    global: { isAndroid: false },
  });
  const platform = Object.create(module.exports.ExternalAppPlatform.prototype);
  platform.windows = new Map([['app', state]]);
  platform.native = {
    isContractRequestCurrent: () => valid,
    completeContractControl: (...args) => results.push(args.slice(3)),
    send: () => actions.push('resize'),
  };
  platform.options = {
    isLocked: () => locked,
    requestRender() {},
    configureSurface: () =>
      new Promise((resolve) => {
        resolveResize = resolve;
      }),
  };
  return {
    state,
    platform,
    results,
    actions,
    request: (operation, payload = {}) =>
      platform.applyContractControl('app', { operation, payload, session: 'session', requestId: 'r' }),
    lock: () => (locked = true),
    invalidate: () => (valid = false),
    resize: () => resolveResize(),
  };
}
test('acknowledgement follows menu mutation and requests preserve host authority', async () => {
  const h = setup();
  await h.request('window.menu', { available: true });
  assert.equal(h.state.menuAvailable, true);
  assert.deepEqual(h.results, [['applied', '']]);
  h.lock();
  await h.request('window.menu', { available: false });
  assert.equal(h.state.menuAvailable, true);
  assert.deepEqual(h.results[1], ['rejected', 'locked']);
});
test('protected flows and consumed gestures cannot trigger sleep', async () => {
  const h = setup();
  h.state.protected = true;
  await h.request('window.sleep');
  assert.equal(h.actions.length, 0);
  h.state.protected = false;
  await h.request('window.sleep');
  await h.request('window.sleep');
  assert.deepEqual(h.actions, ['sleep']);
  assert.deepEqual(h.results.at(-1), ['rejected', 'not_granted']);
});
test('late root response never triggers a second fallback', async () => {
  const h = setup();
  await h.request('window.back', { response: 'at-root' });
  assert.deepEqual(h.actions, []);
  assert.deepEqual(h.results, [['rejected', 'expired']]);
});
test('policy completion waits for resize and rechecks lock after await', async () => {
  const h = setup();
  const pending = h.request('window.policy', {
    preferredHeightMode: 'max',
    chrome: 'compact',
    menuAvailable: true,
    gestureClaims: [],
  });
  assert.equal(h.results.length, 0);
  h.lock();
  h.resize();
  await pending;
  assert.equal(h.state.window.heightMode, 'min');
  assert.deepEqual(h.actions, []);
  assert.deepEqual(h.results, [['unknown', 'invalid_state']]);
});
test('app policy does not need global provider ownership', async () => {
  const h = setup();
  const pending = h.request('window.policy', {
    preferredHeightMode: 'max',
    chrome: 'compact',
    menuAvailable: true,
    gestureClaims: ['directional'],
  });
  h.resize();
  await pending;
  assert.equal(h.state.window.heightMode, 'max');
  assert.equal(h.state.window.acceptsDirectional, true);
  assert.equal(h.state.window.compactChrome, true);
  assert.deepEqual(h.results, [['applied', '']]);
});

test('capture cancellation requires the matching owned capture', async () => {
  const h = setup();
  let cancelled = 0;
  h.state.contractCapture = { id: 'owned' };
  h.state.cancelReview = () => cancelled++;
  await h.request('capture.cancel', { captureId: 'other' });
  assert.equal(cancelled, 0);
  await h.request('capture.cancel', { captureId: 'owned' });
  assert.equal(cancelled, 1);
  assert.deepEqual(h.results, [
    ['rejected', 'invalid_state'],
    ['applied', ''],
  ]);
});
