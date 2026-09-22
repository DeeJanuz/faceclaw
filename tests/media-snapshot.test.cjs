const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
test('passive media snapshot is bounded, cached, refreshes late art, and clears missing sessions', () => {
  let now = 10000, reads = 0;
  const state = { available: true, accessEnabled: true, packageName: 'example', title: 'Track', artist: 'Artist', album: 'Album' };
  const bridge = { snapshot: () => state, getAlbumArt: size => { assert.equal(size, 64); reads++; return { width: 2, height: 1, pixels: Uint8Array.from([0, 255]) }; } };
  const context = { exports: {}, Date: { now: () => now }, require: () => ({ mediaControllerBridge: bridge }) };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync('app/apps/external/media-snapshot.ts', 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, context);
  const snapshot = context.exports.currentMediaSnapshot;
  assert.equal(snapshot().art.gray4, '0f'); snapshot(); assert.equal(reads, 1);
  now += 5000; snapshot(); assert.equal(reads, 2);
  state.title = 'Next'; snapshot(); assert.equal(reads, 3);
  state.accessEnabled = false; assert.equal(snapshot(), null); assert.equal(reads, 3);
  state.accessEnabled = true; state.available = false; assert.equal(snapshot(), null);
});
test('artwork is shared only with a visible, ready window with preview permission and an awake display', () => {
  let awake = true, previews = true, reads = 0;
  const context = { exports: {}, global: { isAndroid: false }, require: name => {
    if (name === './media-snapshot') return { currentMediaSnapshot: () => { reads++; return { title: 'Track', artist: '', art: null }; } };
    if (name === '../../ui/shell/shell') return { shell: { isScreenOn: () => awake } };
    return {};
  } };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync('app/apps/external/platform.ts', 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, context);
  const platform = Object.create(context.exports.ExternalAppPlatform.prototype);
  const window = { ready: true, visible: true };
  platform.windows = new Map([['app', window]]); platform.hostStateSnapshots = new Map();
  platform.extensions = { ownHostState: () => ({}) }; platform.granted = () => previews;
  let payload; platform.send = (_component, _type, value) => { payload = value; };
  platform.publishHostState('app'); assert.equal(payload.media.title, 'Track');
  for (const revoke of [() => { awake = false; }, () => { previews = false; }, () => { window.visible = false; }, () => { window.ready = false; }]) {
    awake = previews = window.visible = window.ready = true;
    revoke(); platform.publishHostState('app'); assert.equal(payload.media, null);
  }
  assert.equal(reads, 1, 'unauthorized or hidden windows do not decode artwork');
});
