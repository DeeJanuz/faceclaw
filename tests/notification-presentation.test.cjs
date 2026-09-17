const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');

function harness({ component = 'com.faceclaw.t3/Service', sources = [], previewSeconds } = {}) {
  let sequence = 0;
  const sent = [], shown = [], closed = [], nativeClosed = [];
  const imports = {
    '../../assistant/messaging-runtime': {},
    '../../assistant/messaging': { messagingTools: [] },
    '../../ui/shell/shell': { shell: {
      canShowExtensionOverlay: () => true,
      isScreenOn: () => true,
      getBatteryLevels: () => ({}),
      getWindows: () => [],
      foregroundWindow: () => undefined,
    } },
    '../../assistant/tool-registry': { toolRegistry: { onToolsChanged() {}, listTools: () => [] } },
    '../../native/notification-icons': {
      readActiveNotifications: () => sources,
      onAndroidNotificationPosted() {}, onAndroidNotificationRemoved() {}, onAndroidNotificationsChanged() {},
    },
    '../../native/external-notifications': {},
    '../../native/notification-apps': { readNotificationApps: () => [] },
    '../../native/shared-style': { initializeSharedHostStyle() {} },
    './extension-policy': require('../.test-build/app/apps/external/extension-policy.js'),
    './app-capabilities': { AppCapabilityRegistry: class {
      constructor() {}
      tools() { return []; }
      event() { return false; }
      remove() {}
      has() { return false; }
    } },
  };
  const module = { exports: {} };
  let snapshot = { generation: 1, features: [{ feature: 'ui.notifications', component, generation: 1, available: true, configuration: previewSeconds === undefined ? {} : { previewSeconds: String(previewSeconds) } }] };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync('app/apps/external/extension-platform.ts', 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText, {
    module, exports: module.exports,
    require: name => { assert.ok(name in imports, name); return imports[name]; },
    java: { util: { UUID: { randomUUID: () => ({ toString: () => `lease-${++sequence}` }) } } },
    setInterval() {},
  });
  const native = {
    extensionsJson: () => JSON.stringify(snapshot),
    isExtensionGranted: () => true,
    sendExtension: (owner, feature, type, json) => { sent.push({ owner, feature, type, data: JSON.parse(json) }); return true; },
    sendAppProvider: () => true,
    closeExtensionSurface: (...args) => nativeClosed.push(args),
  };
  const platform = new module.exports.ExtensionPlatform(native, {
    showSurface: (_feature, _owner, target) => { shown.push(target); return true; },
    closeSurface: (_feature, _restoreSleep, presentationId) => { closed.push(presentationId); return true; },
  }, () => false);
  return {
    platform, sent, shown, closed, nativeClosed,
    arrive: key => platform.notificationsChanged(key),
    arrivals: () => sent.filter(item => item.data.event === 'notification-arrived'),
    source: value => { sources.splice(0, sources.length, ...value); },
  };
}

function notification(key, fields = {}) {
  return {
    key, packageName: 'fixture.messages', appName: 'Fixture', title: 'Sender', text: 'Hello',
    bigText: '', messages: [], lines: [], subText: '', infoText: '', summaryText: '', category: 'msg',
    groupKey: 'fixture-group', isGroupSummary: false, isForegroundService: false, isOngoing: false,
    userId: 0, conversationId: '', postTime: key === 'child' ? 1 : 2, actions: [], ...fields,
  };
}

test('duplicate and group-summary arrivals leave the current preview presentation intact', () => {
  const sources = [notification('child')];
  const h = harness({ sources });
  h.arrive('child');
  assert.equal(h.shown.length, 1);
  assert.equal(h.arrivals().length, 1);
  h.arrive('child');
  assert.equal(h.shown.length, 1);
  assert.equal(h.arrivals().length, 1);
  sources.push(notification('summary', { isGroupSummary: true }));
  h.arrive('summary');
  assert.equal(h.shown.length, 1);
  assert.equal(h.arrivals().length, 1);
});

test('a provider-owned APK completion opens a host surface without replacing its local preview', () => {
  const sources = [notification('apk:completion', { key: 'apk:completion', packageName: 'com.faceclaw.t3', postTime: 9 })];
  const h = harness({ sources });
  h.arrive('apk:completion');
  assert.equal(h.shown.length, 1);
  assert.equal(h.arrivals().length, 1);
  assert.ok(h.sent.some(item => item.data.event === 'notification-snapshot-fragment'));
});

test('arrival events carry the opaque presentation identity used by cleanup', async () => {
  const h = harness({ sources: [notification('child')] });
  h.arrive('child');
  const arrival = h.arrivals()[0];
  assert.equal(typeof arrival.data.presentationId, 'string');
  assert.equal(arrival.data.presentationId, arrival.data.key);
  h.platform.onNativeEvent(arrival.owner, 'extension-event', {
    feature: 'ui.notifications', generation: 1, type: 'action', action: 'close-surface',
    data: { callId: 'close', presentationId: arrival.data.presentationId },
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.closed, [arrival.data.presentationId]);
});

test('native surface cleanup carries the presentation identity', () => {
  const h = harness();
  h.platform.closeSurface('ui.notifications', 'preview-1');
  assert.deepEqual(h.nativeClosed, [['com.faceclaw.t3/Service', 'ui.notifications', 'preview-1']]);
});

test('reader handoff keeps the display awake only for a fresh bound notification gesture', async () => {
  const h = harness({ sources: [notification('handoff')] });
  h.arrive('handoff');
  const arrival = h.arrivals()[0];
  h.platform.surfaceInput('ui.notifications', { type: 'click' });
  h.platform.onNativeEvent(arrival.owner, 'extension-event', {
    feature: 'ui.notifications', generation: 1, type: 'action', action: 'close-surface',
    data: { callId: 'handoff', presentationId: arrival.data.presentationId, restoreSleep: false },
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.closed, [arrival.data.presentationId]);

  const wakeTap = harness({ sources: [notification('wake-double')] });
  wakeTap.arrive('wake-double');
  const wakeArrival = wakeTap.arrivals()[0];
  wakeTap.platform.surfaceInput('ui.notifications', { type: 'double-click' });
  wakeTap.platform.onNativeEvent(wakeArrival.owner, 'extension-event', {
    feature: 'ui.notifications', generation: 1, type: 'action', action: 'close-surface',
    data: { callId: 'wake-handoff', presentationId: wakeArrival.data.presentationId, restoreSleep: false },
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(wakeTap.closed, [wakeArrival.data.presentationId]);

  const stale = harness({ sources: [notification('stale-handoff')] });
  stale.arrive('stale-handoff');
  const staleArrival = stale.arrivals()[0];
  stale.platform.onNativeEvent(staleArrival.owner, 'extension-event', {
    feature: 'ui.notifications', generation: 1, type: 'action', action: 'close-surface',
    data: { callId: 'stale-handoff', presentationId: staleArrival.data.presentationId, restoreSleep: false },
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(stale.closed, []);
  assert.equal(stale.sent.at(-1).data.ok, false);

  const scrolled = harness({ sources: [notification('scrolled-handoff')] });
  scrolled.arrive('scrolled-handoff');
  const scrolledArrival = scrolled.arrivals()[0];
  scrolled.platform.surfaceInput('ui.notifications', { type: 'scroll-down' });
  scrolled.platform.onNativeEvent(scrolledArrival.owner, 'extension-event', {
    feature: 'ui.notifications', generation: 1, type: 'action', action: 'close-surface',
    data: { callId: 'scrolled-handoff', presentationId: scrolledArrival.data.presentationId, restoreSleep: false },
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(scrolled.closed, []);
  assert.equal(scrolled.sent.at(-1).data.ok, false);

  const mismatched = harness({ sources: [notification('mismatched-handoff')] });
  mismatched.arrive('mismatched-handoff');
  const mismatchedArrival = mismatched.arrivals()[0];
  mismatched.platform.surfaceInput('ui.notifications', { type: 'click' });
  mismatched.platform.onNativeEvent(mismatchedArrival.owner, 'extension-event', {
    feature: 'ui.notifications', generation: 1, type: 'action', action: 'close-surface',
    data: { callId: 'mismatched-handoff', presentationId: 'newer-presentation', restoreSleep: false },
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(mismatched.closed, []);
  assert.equal(mismatched.sent.at(-1).data.ok, false);
});

test('arrival events carry only the supported preview duration and fall back to five seconds', () => {
 for (const seconds of [3, 5, 7, 10]) {
  const h = harness({ previewSeconds: seconds, sources: [notification(`duration-${seconds}`)] });
  h.arrive(`duration-${seconds}`);
  assert.equal(h.arrivals()[0].data.durationMs, seconds * 1000);
 }
 const h = harness({ previewSeconds: 6, sources: [notification('invalid-duration')] });
 h.arrive('invalid-duration');
 assert.equal(h.arrivals()[0].data.durationMs, 5000);
});
