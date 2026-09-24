const test = require('node:test');
const assert = require('node:assert/strict');

const vm = require('node:vm');
const { transpile } = require('./helpers/transpile.cjs');
function harness() {
  const module = { exports: {} },
    timers = new Map();
  let next = 0,
    now = 1000;
  vm.runInNewContext(transpile('app/apps/glanceboard/app-content.ts'), {
    module,
    exports: module.exports,
    Date: { now: () => now },
    setTimeout: (fn) => {
      timers.set(++next, fn);
      return next;
    },
    clearTimeout: (id) => timers.delete(id),
  });
  return { api: module.exports, timers, advance: (value) => (now += value) };
}
const widget = (id = 'default', rows = 1) => ({ id, label: 'Example', kind: 'list', rows, refreshMs: 30000, uses: [] });
const provider = (component, widgets = [widget()], connected = true) => ({ component, widgets, connected });
const registry = (providers) => ({ version: 1, providers });
const content = (id = 'default') => ({
  version: 2,
  widgetId: id,
  title: 'Tasks',
  emptyText: 'Caught up',
  expiresAt: 2000,
  entries: [{ id: 'one', title: 'Task', detail: 'Ready', expiresAt: 1500 }],
});
test('an arbitrary SDK app registers multiple widgets without any built-in app catalog', () => {
  const { api } = harness();
  assert.equal(api.appGlanceSources().length, 0);
  api.applyAppGlanceRegistry(registry([provider('org.example/Service', [widget(), widget('tall', 2)])]));
  assert.equal(api.appGlanceSources().length, 2);
  assert.equal(api.appGlanceSources()[1].rows, 2);
  const requests = [];
  api.configureAppGlanceRequest((source) => requests.push(source.id));
  api.requestAppGlance('org.example/Service#tall');
  assert.deepEqual(requests, ['tall']);
});
test('widget frames replace, expire, and clear without affecting another widget or owner', () => {
  const { api, timers, advance } = harness();
  api.applyAppGlanceRegistry(registry([provider('a/Service', [widget(), widget('tall', 2)]), provider('b/Service')]));
  api.acceptAppGlance('a/Service', content());
  api.acceptAppGlance('a/Service', content('tall'));
  api.acceptAppGlance('b/Service', content());
  advance(500);
  assert.equal(api.appGlanceContent('a/Service').entries.length, 0);
  api.clearAppGlance('a/Service');
  assert.equal(api.appGlanceContent('a/Service#tall'), null);
  assert.ok(api.appGlanceContent('b/Service'));
  assert.equal(timers.size, 1);
  [...timers.values()][0]();
  assert.equal(api.appGlanceContent('b/Service'), null);
});
test('withdrawal, revocation and disconnect remove content; reconnect requests use registered identity', () => {
  const { api, timers } = harness();
  api.applyAppGlanceRegistry(registry([provider('a/Service')]));
  api.acceptAppGlance('a/Service', content());
  api.applyAppGlanceRegistry(registry([provider('a/Service', [widget()], false)]));
  assert.equal(api.appGlanceContent('a/Service'), null);
  assert.equal(timers.size, 0);
  let requests = 0;
  api.configureAppGlanceRequest(() => requests++);
  api.requestAppGlance('a/Service');
  assert.equal(requests, 0);
  api.acceptAppGlance('a/Service', content());
  assert.equal(api.appGlanceContent('a/Service'), null);
  api.applyAppGlanceRegistry(registry([]));
  assert.equal(api.appGlanceSources().length, 0);
  api.acceptAppGlance('a/Service', content());
  assert.equal(api.appGlanceContent('a/Service'), null);
});
test('an undeclared widget cannot impersonate another service or create a catalog entry', () => {
  const { api } = harness();
  api.applyAppGlanceRegistry(registry([provider('victim/Service')]));
  api.acceptAppGlance('attacker/Service', content());
  assert.equal(api.appGlanceContent('victim/Service'), null);
  assert.equal(api.appGlanceSources().length, 1);
  api.acceptAppGlance('victim/Service', content('undeclared'));
  assert.equal(api.appGlanceContent('victim/Service#undeclared'), null);
  api.acceptAppGlance('victim/Service', { ...content(), expiresAt: 1000 });
  assert.equal(api.appGlanceContent('victim/Service'), null);
});
