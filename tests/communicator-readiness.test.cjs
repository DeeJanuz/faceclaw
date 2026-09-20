const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');

function harness() {
  const source = ts.createSourceFile('communicator.ts', fs.readFileSync('app/native/faceclaw-communicator.ts', 'utf8'), ts.ScriptTarget.Latest, true);
  let method;
  function visit(node) {
    if (ts.isMethodDeclaration(node) && node.name?.getText(source) === 'pollEvenHubReadiness') method = node.getText(source);
    ts.forEachChild(node, visit);
  }
  visit(source);
  const context = { Date, Promise, setTimeout, Math, nonNegativeNumber: value => Math.max(0, Number(value) || 0) };
  vm.createContext(context);
  vm.runInContext(ts.transpileModule(`class Harness {
    closed = false;
    enqueueJavaCall(operation) { return Promise.resolve(operation()); }
    ${method}
  }; globalThis.Harness = Harness;`, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, context);
  return new context.Harness();
}

test('readiness polling yields between native state queries', async () => {
  const bridge = harness();
  let calls = 0;
  let timerRan = false;
  setTimeout(() => { timerRan = true; }, 0);
  const ready = await bridge.pollEvenHubReadiness(() => ++calls === 3, 250);
  assert.equal(ready, true);
  assert.equal(calls, 3);
  assert.equal(timerRan, true);
});

test('readiness polling is bounded and stops when the bridge closes', async () => {
  const bridge = harness();
  setTimeout(() => { bridge.closed = true; }, 5);
  assert.equal(await bridge.pollEvenHubReadiness(() => false, 250), false);
});

test('readiness polling does not enter native code after close', async () => {
  const bridge = harness();
  bridge.closed = true;
  let calls = 0;
  assert.equal(await bridge.pollEvenHubReadiness(() => { calls++; return true; }, 250), false);
  assert.equal(calls, 0);
});
