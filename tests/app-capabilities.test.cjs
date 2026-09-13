const test = require('node:test'), assert = require('node:assert/strict');
const { AppCapabilityRegistry } = require('../.test-build/app/apps/external/app-capabilities.js');
const capability = (id, visibility = 'agent') => ({ id, version: 1, kind: 'operation', title: 'Do thing', description: 'Performs a provider-owned workflow.',
  inputSchema: { type: 'object', properties: { query: { type: 'string' } }, required: ['query'], additionalProperties: false },
  resultVisibility: visibility, durability: 'durable', operationClass: 'side-effect', profiles: [{ id: 'org.faceclaw.profile.action', version: 1 }] });
function fixture() {
  let count = 0; const sent = [], changes = [];
  const registry = new AppCapabilityRegistry((component, type, data) => { sent.push({ component, type, data }); return true; }, () => changes.push(true), () => `request-${++count}`, () => 1000);
  return { registry, sent, changes };
}
test('published capabilities become namespaced tools and collisions fail closed', () => {
  const f = fixture();
  assert.equal(f.registry.update('app-a', { version: 1, generation: 1, capabilities: [capability('com.example.player.play')] }), true);
  assert.equal(f.registry.tools()[0].name, 'com.example.player.play');
  assert.match(f.registry.tools()[0].description, /Provider: app-a/);
  assert.deepEqual(f.registry.tools()[0]._meta['org.faceclaw/capability'].profiles, [{ id: 'org.faceclaw.profile.action', version: 1 }]);
  assert.equal(f.registry.tools(new Set(['com.example.player.play'])).length, 0);
  assert.equal(f.registry.update('app-b', { version: 1, generation: 1, capabilities: [capability('com.example.player.play')] }), true);
  assert.equal(f.registry.tools().length, 0);
});
test('a capability call is bound to provider, version, generation, caller, and one result', async () => {
  const f = fixture(); f.registry.update('app-a', { version: 1, generation: 4, capabilities: [capability('com.example.player.play')] });
  const pending = f.registry.call({ participant: 'com.faceclaw.t3/.T3Service', origin: 'bridge', project: 'project-1', session: 'session-1' }, 'com.example.player.play', { query: 'Blue Train' });
  assert.deepEqual(f.sent[0], { component: 'app-a', type: 'capability-request', data: {
    requestId: 'request-1', capabilityId: 'com.example.player.play', capabilityVersion: 1, catalogGeneration: 4,
    arguments: { query: 'Blue Train' }, caller: { participant: 'com.faceclaw.t3/.T3Service', origin: 'bridge', project: 'project-1', session: 'session-1' }, issuedAt: 1000, expiresAt: 26000,
  } });
  assert.equal(f.registry.event('other-app', 'capability-result', { requestId: 'request-1', result: { state: 'completed' } }), true);
  f.registry.event('app-a', 'capability-result', { requestId: 'request-1', result: { state: 'completed', operationId: 'op-1', content: { selected: 'track' } } });
  assert.deepEqual(await pending, { ok: true, content: JSON.stringify({ state: 'completed', operationId: 'op-1', content: { selected: 'track' } }) });
});
test('local results do not cross into agent output and catalog changes invalidate pending calls', async () => {
  const f = fixture(); f.registry.update('app-a', { version: 1, generation: 1, capabilities: [capability('com.example.picker.open', 'local')] });
  const first = f.registry.call('bridge.owner', 'com.example.picker.open', { query: 'Miles' });
  f.registry.event('app-a', 'capability-result', { requestId: 'request-1', result: { state: 'waiting_for_user', operationId: 'pick-1', message: 'Choose on glasses', content: { private: 'library row' } } });
  assert.deepEqual(await first, { ok: true, content: JSON.stringify({ state: 'waiting_for_user', operationId: 'pick-1', message: 'Choose on glasses' }) });
  const second = f.registry.call('bridge.owner', 'com.example.picker.open', { query: 'Coltrane' });
  f.registry.update('app-a', { version: 1, generation: 2, capabilities: [capability('com.example.picker.open', 'local')] });
  assert.deepEqual(await second, { ok: false, error: 'Provider catalog changed; operation outcome may be unknown' });
});
test('arguments must match the provider schema before dispatch', async () => {
  const f = fixture(); f.registry.update('app-a', { version: 1, generation: 1, capabilities: [capability('com.example.player.play')] });
  assert.deepEqual(await f.registry.call('bridge.owner', 'com.example.player.play', {}), { ok: false, error: 'Invalid capability call' });
  assert.deepEqual(await f.registry.call('bridge.owner', 'com.example.player.play', { query: 'ok', extra: true }), { ok: false, error: 'Invalid capability call' });
  assert.equal(f.sent.length, 0);
});
