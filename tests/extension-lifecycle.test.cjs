const test = require('node:test');
const assert = require('node:assert/strict');

const vm = require('node:vm');

const { transpile } = require('./helpers/transpile.cjs');
function harness() {
  let snapshot = { generation: 20, features: [] },
    sequence = 0,
    granted = true;
  const sent = [],
    closed = [],
    module = { exports: {} };
  const imports = {
    '../../assistant/messaging-runtime': {},
    '../../assistant/messaging': { messagingTools: [] },
    '../../ui/shell/shell': {
      shell: {
        canShowExtensionOverlay: () => true,
        isScreenOn: () => true,
        getBatteryLevels: () => ({}),
        getWindows: () => [],
        foregroundWindow: () => undefined,
      },
    },
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
    java: { util: { UUID: { randomUUID: () => ({ toString: () => `request-${++sequence}` }) } } },
    setInterval() {},
  });
  const native = {
    extensionsJson: () => JSON.stringify(snapshot),
    isExtensionGranted: () => granted,
    sendExtension: (owner, feature, type, json) => {
      sent.push({ owner, feature, type, data: JSON.parse(json) });
      return true;
    },
    sendAppProvider: (owner, feature, type, json) => {
      sent.push({ owner, feature, type, data: JSON.parse(json) });
      return true;
    },
  };
  const platform = new module.exports.ExtensionPlatform(
    native,
    { closeSurface: (feature) => closed.push(feature) },
    () => false,
  );
  const state = (feature, component = 'A', generation = 7) => ({
    feature,
    component,
    generation,
    available: true,
    configuration: {},
  });
  return {
    platform,
    state,
    sent,
    closed,
    grant: (value) => (granted = value),
    update: (features) => {
      snapshot = { generation: snapshot.generation + 1, features };
      platform.onNativeEvent('', 'extensions-changed', {});
    },
    result: (request, owner, feature, generation) =>
      platform.onNativeEvent(owner, 'extension-event', {
        type: 'result',
        requestId: request.requestId,
        feature,
        generation,
        data: { text: 'synthetic result' },
      }),
  };
}

test('metadata and unrelated feature changes preserve pending requests, menus and notification reviews', async () => {
  const h = harness();
  const assistant = h.state('assistant'),
    launcher = h.state('ui.launcher'),
    notifications = h.state('ui.notifications'),
    menu = h.state('ui.app-menu');
  h.update([assistant, launcher, notifications, menu]);
  let cancelled = 0,
    menuClosed = 0;
  h.platform.reviews.set('review', { cancel: () => cancelled++, current: () => true });
  h.platform.menu = { onClosed: () => menuClosed++ };
  const request = h.platform.provider('assistant', { text: 'synthetic' });
  h.update([assistant, launcher, notifications, menu, h.state('ui.typography', 'B', 9)]);
  h.result(request, 'A', 'assistant', 7);
  assert.equal((await request.promise).text, 'synthetic result');
  assert.deepEqual(h.closed, []);
  assert.equal(cancelled, 0);
  assert.equal(menuClosed, 0);
});

test('real ownership change rejects only affected requests and ignores their late replies', async () => {
  const h = harness();
  const refinement = h.state('refinement');
  h.update([h.state('assistant'), refinement]);
  const old = h.platform.provider('assistant', { text: 'synthetic' }),
    unrelated = h.platform.provider('refinement', {});
  const rejected = assert.rejects(old.promise, /outcome may be unknown/);
  h.update([h.state('assistant', 'B', 8), refinement]);
  h.result(old, 'A', 'assistant', 7);
  h.result(unrelated, 'A', 'refinement', 7);
  await rejected;
  assert.equal((await unrelated.promise).text, 'synthetic result');
  assert.equal(h.sent.filter((item) => item.type === 'request').length, 2); // No automatic replay.
});

test('notification ownership change cancels its review and surface without touching an assistant request', async () => {
  const h = harness(),
    assistant = h.state('assistant');
  h.update([assistant, h.state('ui.notifications')]);
  let cancelled = 0;
  h.platform.reviews.set('review', { cancel: () => cancelled++, current: () => true });
  const request = h.platform.provider('assistant', {});
  h.update([assistant, h.state('ui.notifications', 'B', 8)]);
  assert.equal(cancelled, 1);
  assert.deepEqual(h.closed, ['ui.notifications']);
  h.result(request, 'A', 'assistant', 7);
  await request.promise;
});

test('own transcription remains grant-bound when its app does not own the global feature', async () => {
  const h = harness(),
    transcription = h.state('transcription');
  h.update([transcription]);
  const request = h.platform.provider('transcription', {}, undefined, 'B');
  const rejected = assert.rejects(request.promise, /outcome may be unknown/);
  h.grant(false);
  h.update([transcription]);
  await rejected;
  h.grant(true);
  h.result(request, 'B', 'transcription', 7);
  assert.equal(h.platform.pending.size, 0);
});

test('wakeword opens selected app assistant once and drops revoked invocations', async () => {
  const h = harness(),
    provider = { ...h.state('assistant'), configuration: { invocation: 'app' } };
  let complete,
    opens = 0;
  h.platform.hooks.openAssistant = async () => {
    opens++;
    return new Promise((resolve) => {
      complete = resolve;
    });
  };
  h.update([provider]);
  assert.equal(h.platform.openAssistant(), true);
  assert.equal(h.platform.openAssistant(), true);
  assert.equal(opens, 1);
  complete(true);
  await new Promise(setImmediate);
  assert.equal(h.sent.filter((item) => item.feature === 'assistant' && item.data.event === 'invoke').length, 1);
  h.platform.openAssistant();
  h.update([{ ...provider, generation: 8, component: 'B' }]);
  complete(true);
  await new Promise(setImmediate);
  assert.equal(h.sent.filter((item) => item.feature === 'assistant' && item.data.event === 'invoke').length, 1);
});
test('host fallback remains available and locked or protected flows never open the app', () => {
  const h = harness();
  let opens = 0;
  h.platform.hooks.openAssistant = async () => {
    opens++;
    return true;
  };
  h.update([h.state('assistant')]);
  assert.equal(h.platform.openAssistant(), false);
  h.update([{ ...h.state('assistant'), configuration: { invocation: 'app' } }]);
  h.platform.isLocked = () => true;
  assert.equal(h.platform.openAssistant(), true);
  h.platform.isLocked = () => false;
  h.platform.isProtected = () => true;
  assert.equal(h.platform.openAssistant(), true);
  assert.equal(opens, 0);
});
