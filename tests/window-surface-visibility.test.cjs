const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');

function harness() {
  const source = ts.createSourceFile(
    'controller.ts',
    fs.readFileSync('app/g2/dashboard-controller.ts', 'utf8'),
    ts.ScriptTarget.Latest,
    true,
  );
  const methods = [];
  function visit(node) {
    if (ts.isMethodDeclaration(node) && ['configureWindowSurface', 'isForegroundSurface'].includes(node.name?.getText(source)))
      methods.push(node.getText(source));
    ts.forEachChild(node, visit);
  }
  visit(source);
  assert.equal(methods.length, 2);
  const windows = [
    { windowId: 'launcher', appId: 'launcher', surfaceId: 'window:launcher' },
    { windowId: 'calendar', appId: 'calendar', surfaceId: 'window:calendar' },
  ];
  let foreground = windows[0];
  const configured = [],
    visibility = [],
    pending = [];
  const display = {
    configureSurface(id) {
      configured.push(id);
      return new Promise((resolve) => pending.push(resolve));
    },
    async setSurfaceVisible(id, visible) {
      visibility.push([id, visible]);
    },
  };
  const context = {
    shell: { getWindows: () => windows, foregroundWindow: () => foreground },
    appViewportRect: () => ({ x: 0, y: 0, width: 576, height: 260 }),
  };
  vm.createContext(context);
  vm.runInContext(
    ts.transpileModule(`class Harness { ${methods.join('\n')} }; globalThis.Harness = Harness;`, {
      compilerOptions: { target: ts.ScriptTarget.ES2020 },
    }).outputText,
    context,
  );
  const controller = new context.Harness();
  controller.display = display;
  return {
    controller,
    visibility,
    focus(index) {
      foreground = windows[index];
    },
    finish() {
      pending.shift()();
    },
  };
}

test('delayed geometry setup cannot make the old launcher cover a newly focused window', async () => {
  const h = harness();
  const setup = h.controller.configureWindowSurface('window:launcher', h.controller.isForegroundSurface('window:launcher'), 'min');
  h.focus(1);
  h.finish();
  await setup;
  assert.deepEqual(h.visibility, [['window:launcher', false]]);
});

test('a window focused during geometry setup remains visible after setup completes', async () => {
  const h = harness();
  const setup = h.controller.configureWindowSurface('window:calendar', h.controller.isForegroundSurface('window:calendar'), 'min');
  h.focus(1);
  h.finish();
  await setup;
  assert.deepEqual(h.visibility, [['window:calendar', true]]);
});
