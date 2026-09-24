const test = require('node:test');
const assert = require('node:assert/strict');

const vm = require('node:vm');

const { transpile } = require('./helpers/transpile.cjs');
function setup() {
  const module = { exports: {} },
    opened = [];
  let locked = false,
    screenOn = true,
    overlayAllowed = true,
    foreground = 'own-window',
    now = 100000;
  const shell = {
    isScreenOn: () => screenOn,
    foregroundWindow: () => ({ windowId: foreground }),
    canShowExtensionOverlay: () => overlayAllowed,
    openSystemMenu(id) {
      assert.equal(state.lastInput, 0, 'Consume gesture before entering host menu');
      opened.push(id);
    },
  };
  vm.runInNewContext(transpile('app/apps/external/platform.ts'), {
    module,
    exports: module.exports,
    require: (name) => (name === '../../ui/shell/shell' ? { shell } : {}),
    Date: { now: () => now },
    global: { isAndroid: false },
  });
  const platform = Object.create(module.exports.ExternalAppPlatform.prototype);
  const state = { window: { windowId: 'own-window' }, ready: true, visible: true, lastInput: now };
  platform.extensions = { onNativeEvent: () => false };
  platform.windows = new Map([['own-app', state]]);
  platform.options = { isLocked: () => locked };
  return {
    platform,
    state,
    opened,
    request: (component = 'own-app', data = {}) => platform.onEvent(component, 'request-system-menu', data),
    setLocked: (value) => (locked = value),
    setScreen: (value) => (screenOn = value),
    setOverlayAllowed: (value) => (overlayAllowed = value),
    setForeground: (value) => (foreground = value),
    advance: (value) => (now += value),
  };
}

test('external packages receive exact icons while unknown APKs retain the package icon', () => {
  const module = { exports: {} };
  vm.runInNewContext(transpile('app/apps/external/platform.ts'), {
    module,
    exports: module.exports,
    require: () => ({}),
    global: { isAndroid: false },
  });
  assert.equal(module.exports.externalAppIcon('com.faceclaw.t3/com.faceclaw.t3.FaceclawExternalAppService'), 't3');
  assert.equal(module.exports.externalAppIcon('com.faceclaw.signal/com.faceclaw.signal.ExternalAppService'), 'message');
  assert.equal(module.exports.externalAppIcon('com.faceclaw.signal.beta/com.faceclaw.signal.SignalService'), 'package');
  assert.equal(module.exports.externalAppIcon('com.faceclaw.signal'), 'message');
});

test('system menu uses only the requesting foreground window and a single fresh gesture', () => {
  const h = setup();
  h.request('own-app', { windowId: 'foreign-window', action: 'close' });
  assert.deepEqual(h.opened, ['own-window']);
  h.request();
  assert.equal(h.opened.length, 1);
  h.state.lastInput = 100000;
  h.advance(5000);
  h.request();
  assert.equal(h.opened.length, 2);
});

test('system menu rejects hidden, stale, locked, protected and competing host work', () => {
  for (const deny of [
    (h) => (h.state.ready = false),
    (h) => (h.state.visible = false),
    (h) => h.setForeground('foreign-window'),
    (h) => h.setLocked(true),
    (h) => h.setScreen(false),
    (h) => (h.state.protected = true),
    (h) => (h.state.reviewId = 'review'),
    (h) => (h.state.cancelReview = () => {}),
    (h) => (h.state.refinement = {}),
    (h) => h.setOverlayAllowed(false),
    (h) => (h.state.lastInput = 0),
    (h) => h.advance(5001),
    (h) => h.advance(-1),
    (h) => h.platform.windows.delete('own-app'),
  ]) {
    const h = setup();
    deny(h);
    h.request();
    assert.deepEqual(h.opened, []);
  }
  const h = setup();
  h.request('unrelated-app');
  assert.deepEqual(h.opened, []);
  assert.equal(h.state.lastInput, 100000);
});

test('external menu handoff finishes input ownership on every no-render return', async () => {
  const module = { exports: {} },
    windows = [],
    finishes = [];
  const native = {
    installedJson: () => JSON.stringify([{ component: 'own-app', name: 'Own', connected: true }]),
    setListener() {},
    send() {},
  };
  class ExtensionPlatform {
    constructor() {}
    feature() {
      return undefined;
    }
    windowInput() {}
  }
  const shell = {
    registerWindow: (window) => windows.push(window),
    focusWindow() {},
    foregroundWindow: () => undefined,
    isScreenOn: () => true,
    openSystemMenu() {},
    canShowExtensionOverlay: () => true,
  };
  const imports = {
    '../glanceboard/app-content': { configureAppGlanceRequest() {}, clearAppGlance() {}, applyAppGlanceRegistry() {} },
    './extension-platform': { ExtensionPlatform },
    './extension-policy': {},
    '@nativescript/core': { Application: { android: {} }, Utils: { android: { getApplicationContext: () => ({}) } } },
    '../../ui/shell/shell': { shell },
    '../../ui/shell/chrome-layer': { windowIcon: () => ({}) },
    '../../ui/shell/geometry': { appViewportSize: () => ({ width: 576, height: 452 }) },
    '../../native/notification-icons': { publishExternalNotificationPosted() {} },
    '../../native/external-notifications': {
      configureExternalNotifications() {},
      configureExternalNotificationReplies() {},
      expireExternalNotifications: () => false,
    },
    '../../native/anthropic': {},
    '../../ui/dashboard-settings': { anthropicApiKeySetting: {} },
    '../../native/frame-timings': { finishFrame: (id, outcome) => finishes.push([id, outcome]) },
  };
  vm.runInNewContext(transpile('app/apps/external/platform.ts'), {
    module,
    exports: module.exports,
    require: (name) => imports[name] || {},
    global: { isAndroid: true },
    com: {
      faceclaw: { app: { FaceclawExternalApps: { get: () => native }, FaceclawExternalAppListener: function () {} } },
    },
    setInterval: () => 1,
    Date,
  });
  const platform = new module.exports.ExternalAppPlatform({
    configureSurface: async () => {},
    setSurfaceVisible() {},
    removeSurface() {},
    submitRaster: async () => {},
    requestRender() {},
    isLocked: () => false,
  });
  await platform.open('own-app');
  await windows[0].handleInput({ type: 'short-then-long-press' }, 41);
  await windows[0].handleInput({ type: 'system-menu-opened' }, 42);
  assert.deepEqual(
    finishes.map((item) => item[0]),
    [41, 42],
  );
  assert.ok(finishes.every((item) => /system menu/.test(item[1])));
});
