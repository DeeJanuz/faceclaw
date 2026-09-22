const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');

function harness() {
  const source = ts.createSourceFile('controller.ts', fs.readFileSync('app/g2/dashboard-controller.ts', 'utf8'), ts.ScriptTarget.Latest, true);
  const wanted = new Set(['renderShell', 'ensureEvenHubSessionActive']);
  let methods;
  function visit(node) { if (ts.isClassDeclaration(node)) { const found = node.members.filter(m => wanted.has(m.name?.getText(source))); if (found.length === 2) methods = found.map(m => m.getText(source)); } ts.forEachChild(node, visit); }
  visit(source);
  const events = []; const timers = []; const deadlines = []; let submit;
  const context = {
    shell: { isScreenOn: () => true, describeInputTarget() {}, paintSurface: () => [], underlayDim: () => 256 },
    frameTimings: { startFrame: () => 1, annotateFrame() {}, span: (_id, _label, fn) => fn(), runWithFrame: (_id, fn) => fn(), spanAsync: (_id, _label, fn) => fn(), finishFrame() {}, logFrame() {} },
    beginRenderPass() {}, endRenderPass: () => false, planesFingerprint: () => 'frame',
    flattenPlanesWithDraws: () => ({ image: { width: 640, height: 350, to8bppBuffer: () => [] }, draws: [] }),
    prepareFrameDraws: () => [], SHELL_SURFACE_ID: 'shell', SHELL_SURFACE_Z_ORDER: 100,
    FRAME_TRANSMIT_BACKPRESSURE_TIMEOUT_MS: 1, EVENHUB_WAKE_READY_TIMEOUT_MS: 1, NOTIFICATION_INPUT_GRACE_MS: 150,
    setTimeout: fn => { timers.push(fn); return timers.length; }, clearTimeout() {},
  };
  vm.createContext(context);
  vm.runInContext(ts.transpileModule(`class Harness { ${methods.join('\n')} }; globalThis.Harness = Harness;`, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, context);
  const controller = new context.Harness();
  Object.assign(controller, { pendingNotificationWake: { readyForDisplay: false }, notificationSessionReady: false, displayWakeGeneration: 1, extensionSurfaces: new Map(), phase: 'connected', appliedUnderlayDim: 256,
    externalApps: { extensions: { notificationPreviewRevealed: (presentationId, expiresAtMs) => deadlines.push({ presentationId, expiresAtMs }) } },
    schedulePreviewUpdate() {}, display: { submitSurfaceFrame: async () => { events.push('submit'); if (submit) await submit(); }, waitForFrameFinished: async () => { events.push('wait'); return true; } },
    communicator: { setG2ScreenOn: async () => events.push('screen-on'), resumeEvenHubSession: async () => { events.push('resume'); return true; }, setScreenBlanked: async () => events.push('unblank'), awaitEvenHubSessionPrepared: async () => { events.push('session-ready'); return true; }, awaitEvenHubSessionReady: async () => true },
  });
  return { controller, events, timers, deadlines, shell: context.shell, onSubmit: fn => submit = fn };
}
test('notification wake stays blank until content is submitted, without waiting for a blank frame ACK', async () => {
  const h = harness();
  assert.equal(await h.controller.ensureEvenHubSessionActive(), true);
  await h.controller.renderShell(); assert.deepEqual(h.events, ['screen-on', 'resume', 'session-ready', 'submit']);
  h.controller.pendingNotificationWake.readyForDisplay = true;
  await h.controller.renderShell();
  assert.deepEqual(h.events, ['screen-on', 'resume', 'session-ready', 'submit', 'submit', 'unblank', 'wait']);
  assert.equal(h.controller.pendingNotificationWake, null);
});
test('a replaced notification cannot unblank from the previous in-flight submission', async () => {
  const h = harness(); h.controller.pendingNotificationWake.readyForDisplay = true;
  const replacement = { readyForDisplay: false };
  h.onSubmit(() => { h.controller.pendingNotificationWake = replacement; });
  await h.controller.renderShell();
  assert.deepEqual(h.events, ['submit']); assert.equal(h.controller.pendingNotificationWake, replacement);
});

test('a replaced notification cannot complete the previous preparation barrier', async () => {
  const h = harness();
  let release;
  h.controller.communicator.awaitEvenHubSessionPrepared = () => new Promise(resolve => { release = resolve; });
  const preparation = h.controller.ensureEvenHubSessionActive();
  await new Promise(resolve => setImmediate(resolve));
  h.controller.pendingNotificationWake = { readyForDisplay: false };
  release(true);
  assert.equal(await preparation, false);
  assert.equal(h.controller.notificationSessionReady, false);
});

test('a newer notification starts its own preparation instead of inheriting stale work', async () => {
  const h = harness();
  const releases = [];
  h.controller.communicator.awaitEvenHubSessionPrepared = () => new Promise(resolve => releases.push(resolve));
  const stale = h.controller.ensureEvenHubSessionActive();
  await new Promise(resolve => setImmediate(resolve));
  h.controller.pendingNotificationWake = { readyForDisplay: false };
  const current = h.controller.ensureEvenHubSessionActive();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(releases.length, 2);
  releases[0](true);
  releases[1](true);
  assert.equal(await stale, false);
  assert.equal(await current, true);
  assert.equal(h.controller.notificationSessionReady, true);
});

test('sleep and re-wake retires preparation from the previous wake generation', async () => {
  const h = harness();
  let release;
  h.controller.communicator.awaitEvenHubSessionPrepared = () => new Promise(resolve => { release = resolve; });
  const stale = h.controller.ensureEvenHubSessionActive();
  await new Promise(resolve => setImmediate(resolve));
  h.controller.displayWakeGeneration++;
  release(true);
  assert.equal(await stale, false);
  assert.equal(h.controller.notificationSessionReady, false);
});

test('the configured preview timer starts only after the first valid frame is revealed', async () => {
  const h = harness();
  const before = Date.now();
  const layer = { readyForDisplay: true };
  h.controller.extensionSurfaces.set('ui.notifications', {
    layer, component: 'com.faceclaw.t3/Service', wokeScreen: false, interacted: false,
    revealed: false, presentationId: 'preview', durationMs: 3000,
  });
  await h.controller.renderShell();
  const state = h.controller.extensionSurfaces.get('ui.notifications');
  assert.equal(state.revealed, true);
  assert.equal(typeof state.timer, 'number');
  assert.equal(h.timers.length, 1);
  assert.equal(h.deadlines.length, 1);
  assert.equal(h.deadlines[0].presentationId, 'preview');
  assert.ok(h.deadlines[0].expiresAtMs >= before + 3000);
  assert.equal(state.deadlineMs, h.deadlines[0].expiresAtMs);
});

test('Glanceboard can resume and unblank while the regular shell remains asleep', async () => {
 const h = harness(); h.controller.pendingNotificationWake = null;
 h.shell.isScreenOn = () => false; h.controller.glance = { isVisible: () => true };
 assert.equal(await h.controller.ensureEvenHubSessionActive(), true);
 assert.deepEqual(h.events, ['screen-on', 'resume', 'unblank']);
 assert.equal(h.shell.isScreenOn(), false);
});
test('release during Glanceboard wake prevents a late resume or unblank', async () => {
 const h = harness(); h.controller.pendingNotificationWake = null;
 h.shell.isScreenOn = () => false; let held = true;
 h.controller.glance = { isVisible: () => held };
 h.controller.communicator.setG2ScreenOn = async () => { held = false; };
 assert.equal(await h.controller.ensureEvenHubSessionActive(), false);
 assert.deepEqual(h.events, []);
});
