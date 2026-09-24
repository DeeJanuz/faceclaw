const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');

const { transpile } = require('./helpers/transpile.cjs');
function harness() {
  let snapshot = { generation: 1, features: [] },
    sequence = 0;
  const sent = [],
    captures = [],
    closed = [],
    results = [];
  const module = { exports: {} };
  const shell = {
    isScreenOn: () => true,
    canShowExtensionOverlay: () => true,
    getBatteryLevels: () => ({}),
    getWindows: () => [],
    foregroundWindow: () => ({ windowId: 'apk:signal' }),
    startExternalAppCapture(...args) {
      captures.push(args);
      assert.equal(args[4](), true, 'capture admission must not depend on the handle assigned after startup');
      return {
        finish() {},
        cancel() {
          args[3]('cancelled');
        },
      };
    },
  };
  const imports = {
    '../../assistant/messaging-runtime': {},
    '../../assistant/messaging': { messagingTools: [] },
    '../../ui/shell/shell': { shell },
    '../../assistant/tool-registry': { toolRegistry: { onToolsChanged() {}, listTools: () => [] } },
    '../../native/notification-icons': {
      readActiveNotifications: () => [],
      onAndroidNotificationPosted() {},
      onAndroidNotificationRemoved() {},
      onAndroidNotificationsChanged() {},
    },
    '../../native/external-notifications': {},
    '../../native/notification-apps': { readNotificationApps: () => [] },
    '../../native/shared-style': { initializeSharedHostStyle() {} },
    './extension-policy': require('../.test-build/app/apps/external/extension-policy.js'),
    './app-capabilities': {
      AppCapabilityRegistry: class {
        constructor() {}
        tools() {
          return [];
        }
        event() {
          return false;
        }
        remove() {}
        has() {
          return false;
        }
      },
    },
  };
  vm.runInNewContext(transpile('app/apps/external/extension-platform.ts'), {
    module,
    exports: module.exports,
    require: (name) => {
      assert.ok(name in imports, name);
      return imports[name];
    },
    java: { util: { UUID: { randomUUID: () => ({ toString: () => `id-${++sequence}` }) } } },
    setInterval() {},
    setTimeout,
    clearTimeout,
  });
  const native = {
    extensionsJson: () => JSON.stringify(snapshot),
    isExtensionGranted: () => true,
    sendExtension: (owner, feature, type, json) => {
      sent.push({ owner, feature, type, data: JSON.parse(json) });
      return true;
    },
  };
  const platform = new module.exports.ExtensionPlatform(
    native,
    {
      showSurface: () => true,
      closeSurface: (feature) => {
        closed.push(feature);
        return true;
      },
    },
    () => false,
  );
  snapshot = {
    generation: 2,
    features: [{ feature: 'ui.composer', component: 'T3', generation: 7, available: true, configuration: {} }],
  };
  platform.onNativeEvent('', 'extensions-changed', {});
  const open = () =>
    platform.startComposer({
      caller: 'Signal',
      id: 'composer-1',
      purpose: 'message',
      target: 'thread-1',
      label: 'Signal message',
      initialText: '',
      maxText: 8000,
      originWindowId: 'apk:signal',
      complete: (...value) => results.push(value),
    });
  const action = (name, data = {}) => {
    platform.lastGesture.set('ui.composer', Date.now());
    return platform.action('T3', 'ui.composer', 7, name, {
      callId: `call-${++sequence}`,
      sessionId: 'composer-1',
      ...data,
    });
  };
  return { platform, sent, captures, closed, results, open, action };
}

test('selected composer owns presentation while the host owns transcript and exact confirmation', async () => {
  const h = harness();
  assert.equal(typeof h.open(), 'function');
  assert.equal(h.sent[0].data.event, 'composer-open');
  await h.platform.action('T3', 'ui.composer', 7, 'composer-start-capture', {
    callId: 'open-capture',
    sessionId: 'composer-1',
    mode: 'message',
  });
  assert.equal(h.captures.length, 1);
  h.captures[0][1]({ text: 'Partial private text', isFinal: false });
  await h.action('composer-finish-capture');
  h.captures[0][1]({ text: 'Exact reviewed text', isFinal: true });
  h.captures[0][3]('complete');
  const transcript = h.sent.find((item) => item.data.event === 'composer-transcript' && item.data.isFinal);
  assert.equal(transcript.data.text, 'Exact reviewed text');
  assert.equal(transcript.data.revision, 1);
  await h.action('composer-confirm', { text: 'Changed by provider', revision: 1 });
  assert.deepEqual(h.results, []);
  await h.action('composer-confirm', { text: 'Exact reviewed text', revision: 1 });
  assert.deepEqual(h.results, [['confirmed', 'Exact reviewed text', undefined]]);
  assert.equal(h.closed.at(-1), 'ui.composer');
  await h.action('composer-confirm', { text: 'Exact reviewed text', revision: 1 });
  assert.equal(h.results.length, 1);
});

test('provider cancellation and ownership replacement close without producing text', async () => {
  const h = harness();
  h.open();
  await h.action('composer-cancel');
  assert.deepEqual(h.results, [['cancelled', undefined, 'user_cancelled']]);
  const other = harness();
  other.open();
  other.platform.features = [
    { feature: 'ui.composer', component: 'Other', generation: 8, available: true, configuration: {} },
  ];
  other.platform.finishComposer('cancelled', undefined, 'provider_changed');
  assert.equal(other.results[0][0], 'cancelled');
  assert.equal(other.results[0][1], undefined);
});
