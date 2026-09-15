const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');

function harness({ component = 'com.faceclaw.t3/Service', sources = [] } = {}) {
  let sequence = 0;
  const sent = [], shown = [], closed = [];
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
  let snapshot = { generation: 1, features: [{ feature: 'ui.notifications', component, generation: 1, available: true, configuration: {} }] };
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
  };
  const platform = new module.exports.ExtensionPlatform(native, {
    showSurface: (_feature, _owner, target) => { shown.push(target); return true; },
    closeSurface: (_feature, _restoreSleep, presentationId) => { closed.push(presentationId); return true; },
  }, () => false);
  return {
    platform, sent, shown, closed,
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
