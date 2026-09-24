const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const { transpile } = require('./helpers/transpile.cjs');
function load(file, imports) {
  const module = { exports: {} };
  vm.runInNewContext(transpile(file), {
    module,
    exports: module.exports,
    require: (name) => {
      assert.ok(name in imports, name);
      return imports[name];
    },
    setTimeout,
    clearTimeout,
    console,
  });
  return module.exports;
}
function importMap(file) {
  const imports = {};
  for (const s of ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest).statements)
    if (ts.isImportDeclaration(s)) imports[s.moduleSpecifier.text] = {};
  return imports;
}
function harness(options = {}) {
  let enabled = options.enabled ?? true;
  const policy = () =>
    enabled
      ? {
          doubleTap: options.doubleTap ?? 'sleep',
          tapHold: options.tapHold ?? 'switcher',
          hold: options.hold ?? 'app-menu',
          wakeFocus: options.wakeFocus ?? 'window',
          rootBack: options.rootBack ?? 'sleep',
        }
      : { doubleTap: 'back', tapHold: 'app-menu', hold: 'system-menu', wakeFocus: 'window', rootBack: 'switcher' };
  const imports = importMap('app/ui/shell/shell.ts');
  class Stack {
    constructor() {
      this.layers = [];
    }
    push(layer) {
      this.layers.push(layer);
    }
    clearToBase() {
      this.layers = [];
    }
    isAtBase() {
      return this.layers.length === 0;
    }
    topMatches(predicate) {
      return this.layers.length > 0 && predicate(this.layers.at(-1));
    }
    popIfTop(predicate) {
      if (!this.layers.length || !predicate(this.layers.at(-1))) return false;
      this.layers.pop();
      return true;
    }
    async handleInput(event) {
      this.layers.at(-1)?.handleInput(event);
    }
  }
  imports['../layers'] = { LayerStack: Stack };
  imports['./chrome-layer'] = { ShellChromeLayer: class {} };
  imports['../menu'] = {
    MenuLayer: class {
      constructor(title, items) {
        this.items = items;
      }
      selectItem() {
        return this;
      }
    },
  };
  imports['../gestures'] = load('app/ui/gestures.ts', {});
  imports['../input-monitor'] = { acceptInput: () => true };
  imports['../extension-settings'] = {
    navigationPolicy: policy,
    appMenuPolicy: () => ({}),
    windowLayoutPolicy: () => ({ switcherHeight: enabled ? 'display' : 'minimum' }),
  };
  imports['../dashboard-settings'] = {
    wakeWordActionSetting: { get: () => options.wakeWordAction ?? 'voice-input' },
    brightnessSetting: { get: () => 'auto' },
  };
  imports['../../apps/external/extension-platform'] = { extensionPlatform: () => options.extensionPlatform ?? null };
  imports['../../graphics/image'] = { G2_LENS_WIDTH: 640, G2_LENS_HEIGHT: 480 };
  imports['./geometry'] = { appViewportRect: () => ({ x: 0, width: 640 }), minWindowTop: () => 0, TOP_BAR_HEIGHT: 0 };
  const { shell } = load('app/ui/shell/shell.ts', imports);
  const delivered = [],
    power = [],
    visibility = [];
  const window = {
    windowId: 'own',
    appId: options.appId ?? 'apk:fixture/Service',
    title: 'Test',
    closeable: true,
    hasAppMenu: () => true,
    claimsLongPress: () => options.claimsLongPress ?? true,
    handleInput: async (event) => delivered.push(event),
    requestRender() {},
    setScreenOn: (value) => visibility.push(value),
    onFocus() {},
  };
  shell.windows = [window];
  shell.focus = 'window';
  shell.config = { requestShellRender() {}, onScreenStateChanged: (value) => power.push(value) };
  const send = (type, source = 'ring') => shell.receiveInput({ type, source, timestampMs: Date.now() });
  return {
    shell,
    window,
    send,
    delivered,
    power,
    visibility,
    policy,
    disable() {
      enabled = false;
    },
  };
}
function controllerFor(h, options = {}) {
  const file = 'app/g2/dashboard-controller.ts';
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true);
  let method;
  function visit(node) {
    if (ts.isMethodDeclaration(node) && node.name.getText(source) === 'handleInputEvent') method = node.getText(source);
    ts.forEachChild(node, visit);
  }
  visit(source);
  assert.ok(method);
  let barriers = 0;
  const context = {
    shell: h.shell,
    navigationPolicy: h.policy,
    rawInputEventToInputEvent: (event) => ({
      type: event.kind === 'display-wake' ? 'display-wake' : event.kind === 'even-ai' ? 'wakeword' : 'double-click',
      source: 'ring',
    }),
    EvenAIStatus: { EVEN_AI_WAKE_UP: 1 },
    wakeWordActionSetting: { get: () => 'turn-screen-on' },
    frameTimings: {
      logFrame() {},
      annotateFrame() {},
      spanAsync: (_id, _name, fn) => fn(),
      spanStart() {},
      spanEnd() {},
      finishFrame() {},
    },
    eventLabel: () => '',
    eventName: () => '',
    sourceName: () => '',
    EventSourceType: { TOUCH_EVENT_FROM_RING: 2, TOUCH_EVENT_FROM_WATCH: 3 },
    OsEventTypeList: {},
    acceptInput: () => true,
  };
  vm.createContext(context);
  vm.runInContext(
    ts.transpileModule(`class Controller { ${method} }; globalThis.Controller = Controller;`, {
      compilerOptions: { target: ts.ScriptTarget.ES2020 },
    }).outputText,
    context,
  );
  const controller = new context.Controller();
  let operation = null;
  const ensure = async () => {
    if (operation) return operation;
    barriers++;
    operation = (async () => {
      if (options.deferred) await options.deferred;
      return true;
    })();
    return operation;
  };
  Object.assign(controller, {
    glassesLocked: false,
    evenHubSessionSuspended: options.suspended ?? true,
    ensureEvenHubSessionActive: ensure,
    glanceEventFor: () => null,
    requestShellRender() {},
    appendLog() {},
  });
  return {
    controller,
    barriers: () => barriers,
    send: (kind = 'display-wake', eventType = kind === 'even-ai' ? 1 : 3) =>
      controller.handleInputEvent({ kind, eventType, eventSource: 0, frameId: 1 }),
  };
}
test('suspended display wake honors app focus through the controller and shell', async () => {
  const h = harness();
  h.shell.sleep();
  h.shell.focus = 'sidebar';
  const c = controllerFor(h);
  await c.send();
  assert.equal(h.shell.isScreenOn(), true);
  assert.equal(h.shell.getFocus(), 'window');
  assert.equal(h.delivered.length, 0);
  assert.equal(c.barriers(), 1);
  await c.send();
  assert.equal(h.shell.isScreenOn(), true);
  assert.equal(h.shell.getFocus(), 'window');
});
test('normal and suspended directional wake honor either configured wake focus', async () => {
  for (const wakeFocus of ['window', 'sidebar'])
    for (const suspended of [false, true]) {
      const h = harness({ wakeFocus });
      h.shell.sleep();
      h.shell.focus = 'window';
      const c = controllerFor(h, { suspended });
      await c.send();
      assert.equal(h.shell.isScreenOn(), true);
      assert.equal(h.shell.getFocus(), wakeFocus);
      assert.equal(h.delivered.length, 0);
    }
});
test('overlapping suspended wake events share the resume barrier and remain wake-only', async () => {
  let release;
  const deferred = new Promise((resolve) => {
    release = resolve;
  });
  const h = harness();
  h.shell.sleep();
  h.shell.focus = 'sidebar';
  const c = controllerFor(h, { deferred });
  const first = c.send();
  await Promise.resolve();
  const second = c.send();
  release();
  await Promise.all([first, second]);
  assert.equal(c.barriers(), 1);
  assert.equal(h.shell.getFocus(), 'window');
  assert.equal(h.delivered.length, 0);
});
test('enabled wakeword pre-wake turns on the display without invoking assistant authority', async () => {
  const h = harness({ wakeWordAction: 'turn-screen-on' });
  h.shell.sleep();
  h.shell.focus = 'sidebar';
  const c = controllerFor(h, { suspended: false });
  await c.send('even-ai');
  assert.equal(h.shell.isScreenOn(), true);
  assert.equal(h.shell.getFocus(), 'window');
  assert.equal(h.delivered.length, 0);
  assert.equal(c.barriers(), 1);
});
test('wake with no foreground window stays safe and does not invent a launch', async () => {
  const h = harness();
  h.shell.sleep();
  h.shell.windows = [];
  h.shell.focus = 'sidebar';
  await controllerFor(h, { suspended: false }).send();
  assert.equal(h.shell.isScreenOn(), true);
  assert.equal(h.shell.getFocus(), 'window');
  assert.equal(h.delivered.length, 0);
});
test('duplicate display wake preserves an awake switcher; locked wake keeps lock focus', async () => {
  const h = harness();
  h.shell.focus = 'sidebar';
  await controllerFor(h).send();
  assert.equal(h.shell.getFocus(), 'sidebar');
  h.shell.sleep();
  const c = controllerFor(h);
  c.controller.glassesLocked = true;
  await c.send();
  assert.equal(h.shell.isScreenOn(), true);
  assert.equal(h.shell.getFocus(), 'sidebar');
  assert.equal(h.delivered.length, 0);
});
test('APK double tap powers off before app back or switcher routing, then wakes the app', async () => {
  for (const source of ['ring', 'watch', 'left-arm', 'right-arm']) {
    const h = harness();
    await h.send('double-click', source);
    assert.equal(h.shell.isScreenOn(), false);
    assert.equal(h.shell.getFocus(), 'window');
    assert.equal(h.delivered.length, 0);
    assert.deepEqual(h.power, [false]);
    await h.send('double-click', source);
    assert.equal(h.shell.isScreenOn(), true);
    assert.equal(h.shell.getFocus(), 'window');
    assert.equal(h.delivered.length, 0);
    assert.deepEqual(h.power, [false, true]);
  }
});
test('tap-and-hold selects the switcher, including from a sleeping display', async () => {
  for (const awake of [true, false]) {
    const h = harness();
    if (!awake) h.shell.sleep();
    await h.send('short-then-long-press');
    assert.equal(h.shell.isScreenOn(), true);
    assert.equal(h.shell.getFocus(), 'sidebar');
    assert.ok(h.delivered.every((event) => event.type === 'system-menu-opened'));
    assert.deepEqual({ ...h.shell.screenshotCropRect() }, { x: 0, y: 0, width: 640, height: 480 });
  }
});
test('single hold requests App actions even when an app claims hold; text capture stays protected', async () => {
  const h = harness();
  await h.send('long-press');
  assert.equal(h.delivered.length, 1);
  assert.equal(h.delivered[0].type, 'short-then-long-press');
  assert.notEqual(h.shell.escapeMenuTimer, null);
  assert.equal(h.shell.getFocus(), 'window');
  await h.send('long-press-release');
  assert.equal(h.shell.escapeMenuTimer, null);
  assert.equal(h.delivered.length, 1, 'the opening release is consumed by the host');
  for (const field of ['activeVoiceLayer', 'activeKeyboardLayer']) {
    const protectedInput = harness();
    protectedInput.shell[field] = {};
    await protectedInput.send('long-press');
    assert.equal(protectedInput.delivered.length, 0);
  }
});

test('a notification preview owns plain hold without opening the app menu', async () => {
  const h = harness();
  let held = 0;
  const layer = {
    claimsNotificationDismissGesture: () => true,
    handleInput: (event) => {
      if (event.type === 'long-press') held++;
    },
  };
  h.shell.stack.push(layer);
  await h.send('long-press');
  assert.equal(held, 1);
  assert.equal(h.delivered.length, 0);
  h.shell.stack.layers.pop();
});

test('a notification preview turns a lifecycle wake into its first tap', async () => {
  const h = harness();
  const received = [];
  const layer = { claimsNotificationDismissGesture: () => true, handleInput: (event) => received.push(event) };
  h.shell.stack.push(layer);
  await h.shell.receiveInput({ type: 'display-wake', timestampMs: Date.now() });
  assert.deepEqual(
    received.map((event) => event.type),
    ['click'],
  );
  assert.equal(h.shell.isScreenOn(), true);
  assert.deepEqual(h.delivered, []);
  h.shell.stack.layers.pop();
});

test('a notification preview keeps a tap when screen-off state races its wake', async () => {
  const h = harness();
  const received = [];
  const layer = { claimsNotificationDismissGesture: () => true, handleInput: (event) => received.push(event) };
  h.shell.stack.push(layer);
  h.shell.sleep();
  h.shell.stack.push(layer);
  await h.shell.receiveInput({ type: 'click', source: 'ring', timestampMs: Date.now() });
  assert.deepEqual(
    received.map((event) => event.type),
    ['click'],
  );
  assert.equal(h.shell.isScreenOn(), true);
  h.shell.stack.layers.pop();
});

test('T3 hold reaches only allowlisted games while their gameplay claim is active', async () => {
  for (const appId of ['blocks', 'minesweeper', 'pinball']) {
    for (const source of ['ring', 'watch', 'left-arm', 'right-arm']) {
      const h = harness({ appId, doubleTap: 'back' });
      try {
        await h.send('long-press', source);
        assert.deepEqual(
          h.delivered.map((event) => event.type),
          ['long-press'],
          `${appId}: ${source}`,
        );
        assert.equal(h.delivered[0].source, source);
        assert.notEqual(h.shell.escapeMenuTimer, null);
        await h.send('long-press-release', source);
        assert.equal(h.shell.escapeMenuTimer, null);
        assert.equal(h.delivered.length, 1, 'release must not select a menu action');
        await h.send('short-then-long-press', source);
        assert.equal(h.shell.getFocus(), 'sidebar', 'T3 tap-then-hold still opens the switcher');
      } finally {
        h.shell.cancelEscapeMenuTimer();
      }
    }
    const inactive = harness({ appId, claimsLongPress: false });
    try {
      await inactive.send('long-press');
      assert.deepEqual(
        inactive.delivered.map((event) => event.type),
        ['short-then-long-press'],
        `${appId}: inactive`,
      );
      await inactive.send('click');
      await inactive.send('long-press-release');
      assert.equal(inactive.delivered.length, 1, 'menu opening click and release stay consumed');
    } finally {
      inactive.shell.cancelEscapeMenuTimer();
    }
  }
  for (const appId of ['freecell', 'compass', 'apk:blocks', 'apk:minesweeper', 'apk:pinball']) {
    const h = harness({ appId });
    try {
      await h.send('long-press');
      assert.deepEqual(
        h.delivered.map((event) => event.type),
        ['short-then-long-press'],
        appId,
      );
      await h.send('long-press-release');
    } finally {
      h.shell.cancelEscapeMenuTimer();
    }
  }
});

test('allowlisted game holds cannot bypass text capture or a shell overlay', async () => {
  for (const appId of ['blocks', 'minesweeper', 'pinball']) {
    for (const protection of ['activeVoiceLayer', 'activeKeyboardLayer', 'overlay']) {
      const h = harness({ appId });
      if (protection === 'overlay') h.shell.stack.push({ handleInput() {} });
      else h.shell[protection] = {};
      try {
        await h.send('long-press');
        assert.equal(h.delivered.length, 0, `${appId}: ${protection}`);
        assert.equal(h.shell.escapeMenuTimer, null);
      } finally {
        h.shell.cancelEscapeMenuTimer();
      }
    }
  }
});

test('allowlisted gameplay hold keeps the four-second system escape and release cancels it', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const appId of ['blocks', 'minesweeper', 'pinball']) {
    const released = harness({ appId });
    await released.send('long-press');
    await released.send('long-press-release');
    t.mock.timers.tick(4000);
    assert.equal(released.shell.stack.isAtBase(), true);

    const held = harness({ appId });
    await held.send('long-press');
    t.mock.timers.tick(3999);
    assert.equal(held.shell.stack.isAtBase(), true);
    t.mock.timers.tick(1);
    assert.equal(held.shell.stack.isAtBase(), false, appId);
    assert.ok(held.shell.stack.layers[0].items.some((item) => item.label === 'Close app'));
    assert.deepEqual(
      held.delivered.map((event) => event.type),
      ['long-press', 'system-menu-opened'],
    );
    await held.send('long-press-release');
    assert.equal(held.delivered.length, 2);
  }
});

test('Compass-style hold opens actions without arming Display off', async () => {
  const h = harness();
  await h.send('long-press');
  assert.equal(h.delivered[0].type, 'short-then-long-press');
  // A firmware-generated click before the release must not reach the Compass
  // layer and select the leading power row.
  await h.send('click');
  await h.send('long-press-release');
  assert.equal(h.delivered.length, 1);
  assert.equal(h.shell.isScreenOn(), true);
});

test('tap-then-hold app-menu consumes its opening click before later selection', async () => {
  const h = harness({ tapHold: 'app-menu' });
  await h.send('short-then-long-press');
  assert.equal(h.delivered.length, 1);
  await h.send('click');
  assert.equal(h.delivered.length, 1, 'synthetic opening click is consumed');
  await h.send('click');
  assert.equal(h.delivered.length, 2, 'a later click remains a deliberate selection');
  assert.equal(h.shell.isScreenOn(), true);
});
test('removing navigation override restores app back; watch swipe-left still means back', async () => {
  const h = harness();
  h.disable();
  await h.send('double-click');
  assert.equal(h.shell.isScreenOn(), true);
  assert.equal(h.delivered[0].type, 'double-click');
  const watch = harness();
  await watch.send('swipe-left', 'watch');
  assert.equal(watch.shell.isScreenOn(), true);
  assert.equal(watch.delivered[0].type, 'double-click');
});
test('semantic root-back uses the selected policy for every window', () => {
  for (const rootBack of ['sleep', 'switcher']) {
    const h = harness({ rootBack });
    h.shell.returnFromAppRoot();
    assert.equal(h.shell.isScreenOn(), rootBack !== 'sleep');
    assert.equal(h.shell.getFocus(), rootBack === 'sleep' ? 'window' : 'sidebar');
  }
});
test('host fallback wakes the retained window when no navigation provider is effective', async () => {
  const h = harness();
  h.disable();
  h.shell.sleep();
  h.shell.focus = 'sidebar';
  await h.send('double-click');
  assert.equal(h.shell.isScreenOn(), true);
  assert.equal(h.shell.getFocus(), 'window');
  assert.equal(h.delivered.length, 0);
});
test('T3 double-tap back remains app back while a sleeping display only wakes', async () => {
  const h = harness({ doubleTap: 'back' });
  await h.send('double-click');
  assert.equal(h.shell.isScreenOn(), true);
  assert.equal(h.delivered.length, 1);
  assert.equal(h.delivered[0].type, 'double-click');
  h.shell.sleep();
  h.delivered.length = 0;
  await h.send('double-click');
  assert.equal(h.shell.isScreenOn(), true);
  assert.equal(h.shell.getFocus(), 'window');
  assert.equal(h.delivered.length, 0);
});
test('full-height switcher draws and hit-tests the panel, then restores compact geometry', () => {
  let full = true;
  const imports = importMap('app/ui/shell/chrome-layer.ts');
  imports['../../graphics/image'] = { G2_LENS_WIDTH: 640, G2_LENS_HEIGHT: 480 };
  imports['../extension-settings'] = {
    windowLayoutPolicy: () => ({ switcherHeight: full ? 'display' : 'minimum', dividerWidth: 2 }),
  };
  imports['./geometry'] = {
    MIN_WINDOW_HEIGHT: 288,
    minWindowTop: () => 96,
    TOP_BAR_HEIGHT: 28,
    SIDEBAR_WIDTH: 64,
    SHELL_OPAQUE_BLACK: 1,
  };
  imports['../menu'] = {
    scrollToKeepSelectionVisible: (_scroll, selected, visible, count) =>
      Math.min(selected, Math.max(0, count - visible)),
  };
  const icons = [],
    fills = [];
  const state = {
    selectedIndex: 0,
    focus: 'sidebar',
    windows: Array.from({ length: 10 }, (_, index) => ({
      drawIcon: (_image, x, y, size) => icons.push({ index, x, y, size }),
    })),
  };
  const { ShellChromeLayer } = load('app/ui/shell/chrome-layer.ts', imports),
    chrome = new ShellChromeLayer(() => state);
  const image = new Proxy(
    { fillRect: (...args) => fills.push(args) },
    { get: (target, prop) => target[prop] ?? (() => {}) },
  );
  chrome.drawSidebar(image, state);
  assert.deepEqual(fills[0].slice(0, 4), [0, 0, 64, 480]);
  assert.equal(icons[0].y, 38);
  assert.ok(icons.at(-1).y > 384);
  for (const icon of icons) assert.equal(chrome.windowIndexAt(icon.x + 4, icon.y + 4, 10), icon.index);
  assert.equal(chrome.windowIndexAt(65, 42, 10), null);
  full = false;
  fills.length = 0;
  icons.length = 0;
  chrome.drawSidebar(image, state);
  assert.deepEqual(fills[0].slice(0, 4), [0, 96, 64, 288]);
  assert.equal(chrome.windowIndexAt(40, 42, 10), null);
  for (const icon of icons) assert.equal(chrome.windowIndexAt(icon.x + 4, icon.y + 4, 10), icon.index);
});

test('T3 back policy wakes only the retained app; tap then hold opens the switcher', async () => {
  for (const suspended of [false, true]) {
    const h = harness({ doubleTap: 'back', rootBack: 'sleep', tapHold: 'switcher', wakeFocus: 'window' });
    h.shell.sleep();
    h.shell.focus = 'sidebar';
    if (suspended) await controllerFor(h).send();
    else await h.send('double-click');
    assert.equal(h.shell.isScreenOn(), true);
    assert.equal(h.shell.getFocus(), 'window');
    assert.equal(h.shell.foregroundWindow(), h.window);
    assert.equal(h.delivered.length, 0, 'wake must not also dispatch back to the app');
    await h.send('short-then-long-press');
    assert.equal(h.shell.getFocus(), 'sidebar');
  }
});

test('Glanceboard tap-hold policy leaves asleep gestures to the board and keeps the awake switcher', async () => {
  const h = harness({ tapHold: 'glanceboard', doubleTap: 'back', rootBack: 'sleep' });
  h.shell.sleep();
  await h.send('short-then-long-press');
  assert.equal(h.shell.isScreenOn(), false, 'shell must not wake the app switcher ahead of Glanceboard');
  await h.send('double-click');
  await h.send('short-then-long-press');
  assert.equal(h.shell.getFocus(), 'sidebar');
});
