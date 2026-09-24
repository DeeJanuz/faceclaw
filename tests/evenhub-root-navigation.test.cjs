const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const ts = require("typescript");

const sessionSource = fs.readFileSync("app/apps/evenhub/session.ts", "utf8");
const windowSource = fs.readFileSync("app/apps/evenhub/evenhub-window.ts", "utf8");

function methodSource(fileSource, name) {
  const sourceFile = ts.createSourceFile("fixture.ts", fileSource, ts.ScriptTarget.Latest, true);
  let found;
  function visit(node) {
    if (ts.isMethodDeclaration(node) && node.name?.getText(sourceFile) === name) found = node.getText(sourceFile);
    ts.forEachChild(node, visit);
  }
  visit(sourceFile);
  assert.ok(found, `method ${name} not found`);
  return found;
}

function loadMethods(...names) {
  const methods = names.map(name => methodSource(sessionSource, name)).join("\n");
  const module = { exports: {} };
  const context = { module, exports: module.exports, setTimeout, clearTimeout, console };
  vm.runInNewContext(
    ts.transpileModule(`class Session { ${methods} }; module.exports.Session = Session;`, {
      compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
    }).outputText,
    context,
  );
  return module.exports.Session;
}

function sessionWithHooks(Session, hooks) {
  const session = new Session();
  let closed = 0;
  Object.assign(session, { windowHooks: hooks, close: () => { closed += 1; } });
  return { session, closed: () => closed };
}

test("legacy exitMode 1 follows rootBack and retains the EvenHub session", () => {
  const Session = loadMethods("shutDown");
  for (const rootBack of ["sleep", "switcher"]) {
    const calls = [];
    const h = sessionWithHooks(Session, {
      returnFromAppRoot: () => calls.push(rootBack),
      focusSwitcher: () => calls.push("switcher-explicit"),
    });
    assert.equal(h.session.shutDown(1), true);
    assert.deepEqual(calls, [rootBack]);
    assert.equal(h.closed(), 0, "root-back must retain the running session");
  }
});

test("explicit returnToAppSwitcher stays an unconditional switcher action", async () => {
  const Session = loadMethods("dispatchExtension");
  const calls = [];
  const h = sessionWithHooks(Session, {
    returnFromAppRoot: () => calls.push("root-policy"),
    focusSwitcher: () => calls.push("switcher"),
  });
  assert.equal(await h.session.dispatchExtension("returnToAppSwitcher", []), null);
  assert.deepEqual(calls, ["switcher"]);
  assert.equal(h.closed(), 0);
});

test("quit and other shutdown modes retain their existing close semantics", async () => {
  const pending = [];
  const originalSetTimeout = global.setTimeout;
  global.setTimeout = (callback, delay) => { pending.push({ callback, delay }); return pending.length; };
  try {
    const Session = loadMethods("shutDown", "dispatchExtension");
    const h = sessionWithHooks(Session, {
      returnFromAppRoot: () => {},
      focusSwitcher: () => {},
    });
    assert.equal(h.session.shutDown(0), true);
    assert.equal(await h.session.dispatchExtension("quit", []), null);
    assert.deepEqual(pending.map(item => item.delay), [200, 0]);
    assert.equal(h.closed(), 0);
    pending[0].callback();
    assert.equal(h.closed(), 1, "exitMode 0 still closes after its existing delay");
    pending[1].callback();
    assert.equal(h.closed(), 2, "explicit quit still closes immediately");
  } finally {
    global.setTimeout = originalSetTimeout;
  }
});

test("the window adapter routes root-back and switcher hooks to their own shell actions", () => {
  const calls = [];
  let hooks;
  const module = { exports: {} };
  const imports = {
    "../../ui/shell/in-process-window": {
      createInProcessWindow: () => ({ requestRender() {}, stack: { push() {} }, setHeightMode() {}, window: {} }),
    },
    "../../ui/shell/shell": {
      shell: {
        yieldFocusToSidebar: () => calls.push("switcher"),
        returnFromAppRoot: () => calls.push("root-back"),
        closeWindow: () => calls.push("close"),
      },
    },
    "../../ui/shell/chrome-layer": { makeImageWindowIcon: () => () => {}, windowIcon: () => ({}) },
  };
  vm.runInNewContext(
    ts.transpileModule(windowSource, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText,
    { module, exports: module.exports, require: name => imports[name] || {} },
  );
  const session = { manifest: { name: "Demo", packageId: "demo" }, attachWindow: value => { hooks = value; } };
  module.exports.createEvenHubWindow("window", "app", session, { actions: {}, onClosed() {} }, () => {});
  hooks.returnFromAppRoot();
  hooks.focusSwitcher();
  assert.deepEqual(calls, ["root-back", "switcher"]);
});
