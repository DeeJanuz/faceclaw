const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs'), vm = require('node:vm'), ts = require('typescript');
function load(file, imports) {
 const module = { exports: {} };
 vm.runInNewContext(ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText,
  { module, exports: module.exports, require: name => { assert.ok(name in imports, name); return imports[name]; }, setTimeout, clearTimeout, console });
 return module.exports;
}
function importMap(file) {
 const imports = {};
 for (const s of ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest).statements)
  if (ts.isImportDeclaration(s)) imports[s.moduleSpecifier.text] = {};
 return imports;
}
function harness() {
 let enabled = true;
 const policy = () => enabled ? { doubleTap: 'sleep', tapHold: 'switcher', hold: 'app-menu', wakeFocus: 'window' }
  : { doubleTap: 'back', tapHold: 'app-menu', hold: 'system-menu', wakeFocus: 'sidebar' };
 const imports = importMap('app/ui/shell/shell.ts');
 class Stack {
  constructor() { this.layers = []; }
  clearToBase() { this.layers = []; }
  isAtBase() { return this.layers.length === 0; }
  topMatches(predicate) { return this.layers.length > 0 && predicate(this.layers.at(-1)); }
  async handleInput(event) { this.layers.at(-1)?.handleInput(event); }
 }
 imports['../layers'] = { LayerStack: Stack };
 imports['./chrome-layer'] = { ShellChromeLayer: class {} };
 imports['../menu'] = { MenuLayer: class {} };
 imports['../gestures'] = load('app/ui/gestures.ts', {});
 imports['../extension-settings'] = { navigationPolicy: policy, windowLayoutPolicy: () => ({ switcherHeight: enabled ? 'display' : 'minimum' }) };
 imports['../../graphics/image'] = { G2_LENS_WIDTH: 640, G2_LENS_HEIGHT: 480 };
 const { shell } = load('app/ui/shell/shell.ts', imports);
 const delivered = [], power = [], visibility = [];
 const window = { windowId: 'own', appId: 'apk:fixture/Service', title: 'Test', hasAppMenu: () => true, claimsLongPress: () => true,
  handleInput: async event => delivered.push(event), requestRender() {}, setScreenOn: value => visibility.push(value), onFocus() {} };
 shell.windows = [window]; shell.focus = 'window';
 shell.config = { requestShellRender() {}, onScreenStateChanged: value => power.push(value) };
 const send = (type, source = 'ring') => shell.receiveInput({ type, source, timestampMs: Date.now() });
 return { shell, window, send, delivered, power, visibility, policy, disable() { enabled = false; } };
}
test('APK double tap powers off before app back or switcher routing, then wakes the app', async () => {
 for (const source of ['ring', 'watch', 'left-arm', 'right-arm']) {
  const h = harness(); await h.send('double-click', source);
  assert.equal(h.shell.isScreenOn(), false); assert.equal(h.shell.getFocus(), 'window');
  assert.equal(h.delivered.length, 0); assert.deepEqual(h.power, [false]);
  await h.send('double-click', source);
  assert.equal(h.shell.isScreenOn(), true); assert.equal(h.shell.getFocus(), 'window');
  assert.equal(h.delivered.length, 0); assert.deepEqual(h.power, [false, true]);
 }
});
test('tap-and-hold selects the switcher, including from a sleeping display', async () => {
 for (const awake of [true, false]) {
  const h = harness(); if (!awake) h.shell.sleep(); await h.send('short-then-long-press');
  assert.equal(h.shell.isScreenOn(), true); assert.equal(h.shell.getFocus(), 'sidebar');
  assert.ok(h.delivered.every(event => event.type === 'system-menu-opened'));
  assert.deepEqual({ ...h.shell.screenshotCropRect() }, { x: 0, y: 0, width: 640, height: 480 });
 }
});
test('single hold requests App actions even when an app claims hold; text capture stays protected', async () => {
 const h = harness(); await h.send('long-press');
 assert.equal(h.delivered.length, 1); assert.equal(h.delivered[0].type, 'short-then-long-press');
 assert.notEqual(h.shell.escapeMenuTimer, null); assert.equal(h.shell.getFocus(), 'window');
 await h.send('long-press-release'); assert.equal(h.shell.escapeMenuTimer, null);
 for (const field of ['activeVoiceLayer', 'activeKeyboardLayer']) {
  const protectedInput = harness(); protectedInput.shell[field] = {};
  await protectedInput.send('long-press'); assert.equal(protectedInput.delivered.length, 0);
 }
});
test('removing navigation override restores app back; watch swipe-left still means back', async () => {
 const h = harness(); h.disable(); await h.send('double-click');
 assert.equal(h.shell.isScreenOn(), true); assert.equal(h.delivered[0].type, 'double-click');
 const watch = harness(); await watch.send('swipe-left', 'watch');
 assert.equal(watch.shell.isScreenOn(), true); assert.equal(watch.delivered[0].type, 'double-click');
});
test('full-height switcher draws and hit-tests the panel, then restores compact geometry', () => {
 let full = true;
 const imports = importMap('app/ui/shell/chrome-layer.ts');
 imports['../../graphics/image'] = { G2_LENS_WIDTH: 640, G2_LENS_HEIGHT: 480 };
 imports['../extension-settings'] = { windowLayoutPolicy: () => ({ switcherHeight: full ? 'display' : 'minimum', dividerWidth: 2 }) };
 imports['./geometry'] = { MIN_WINDOW_HEIGHT: 288, minWindowTop: () => 96, TOP_BAR_HEIGHT: 28, SIDEBAR_WIDTH: 64, SHELL_OPAQUE_BLACK: 1 };
 imports['../menu'] = { scrollToKeepSelectionVisible: (_scroll, selected, visible, count) => Math.min(selected, Math.max(0, count - visible)) };
 const icons = [], fills = [];
 const state = { selectedIndex: 0, focus: 'sidebar', windows: Array.from({ length: 10 }, (_, index) => ({ drawIcon: (_image, x, y, size) => icons.push({ index, x, y, size }) })) };
 const { ShellChromeLayer } = load('app/ui/shell/chrome-layer.ts', imports), chrome = new ShellChromeLayer(() => state);
 const image = new Proxy({ fillRect: (...args) => fills.push(args) }, { get: (target, prop) => target[prop] ?? (() => {}) });
 chrome.drawSidebar(image, state);
 assert.deepEqual(fills[0].slice(0, 4), [0, 0, 64, 480]);
 assert.equal(icons[0].y, 38); assert.ok(icons.at(-1).y > 384);
 for (const icon of icons) assert.equal(chrome.windowIndexAt(icon.x + 4, icon.y + 4, 10), icon.index);
 assert.equal(chrome.windowIndexAt(65, 42, 10), null);
 full = false; fills.length = 0; icons.length = 0; chrome.drawSidebar(image, state);
 assert.deepEqual(fills[0].slice(0, 4), [0, 96, 64, 288]);
 assert.equal(chrome.windowIndexAt(40, 42, 10), null);
 for (const icon of icons) assert.equal(chrome.windowIndexAt(icon.x + 4, icon.y + 4, 10), icon.index);
});

test('AI Chat hold-to-talk yields to selected APK navigation and restores host gestures when disabled', async () => {
 const h = harness(); h.window.holdToTalk = true;
 await h.send('short-then-long-press');
 assert.equal(h.shell.getFocus(), 'sidebar'); assert.equal(h.delivered[0].type, 'system-menu-opened');
 await h.send('long-press'); assert.equal(h.delivered.at(-1).type, 'short-then-long-press');
 await h.send('long-press-release'); assert.equal(h.shell.escapeMenuTimer, null);
 h.disable(); h.delivered.length = 0;
 await h.send('long-press'); assert.equal(h.delivered[0].type, 'long-press'); assert.equal(h.shell.escapeMenuTimer, null);
 let escapes = 0; h.shell.openEscapeMenu = () => escapes++;
 await h.send('short-then-long-press'); assert.equal(escapes, 1); assert.equal(h.delivered.length, 1);
});

// The controller pre-wakes the real shell before dispatching firmware wake.
function wakeController(h) {
 const source = ts.createSourceFile('controller.ts', fs.readFileSync('app/g2/dashboard-controller.ts', 'utf8'), ts.ScriptTarget.Latest, true);
 const controller = source.statements.find(n => ts.isClassDeclaration(n) && n.name.text === 'DashboardController');
 const method = controller.members.find(n => n.name?.getText(source) === 'handleInputEvent');
 assert.ok(method);
 const context = {
  shell: h.shell, navigationPolicy: h.policy,
  rawInputEventToInputEvent: event => ({ type: event.kind, source: 'ring' }),
  eventLabel: () => '', wakeWordActionSetting: { get: () => 'off' },
  EventSourceType: { TOUCH_EVENT_FROM_RING: 1, TOUCH_EVENT_FROM_WATCH: 2 },
  frameTimings: { startFrame: () => 1, logFrame() {}, annotateFrame() {}, spanStart() {}, spanEnd() {}, finishFrame() {}, spanAsync: (_id, _name, fn) => fn() },
 };
 vm.createContext(context);
 vm.runInContext(ts.transpileModule(`class Harness { ${method.getText(source)} }; globalThis.Harness = Harness;`, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, context);
 h.shell.describeInputTarget = () => '';
 const instance = new context.Harness();
 Object.assign(instance, { glassesLocked: false, ensureEvenHubSessionActive: async () => true, requestShellRender() {}, appendLog() {} });
 return instance;
}
test('firmware wake restores APK focus and next gesture without phone activity', async () => {
 const h = harness(), controller = wakeController(h);
 for (let cycle = 0; cycle < 2; cycle++) {
  h.shell.sleep();
  await controller.handleInputEvent({ kind: 'display-wake', frameId: 1 });
  assert.equal(h.shell.isScreenOn(), true);
  assert.equal(h.shell.getFocus(), 'window');
  await h.send('click');
  assert.equal(h.delivered.at(-1).type, 'click');
 }
 assert.deepEqual(h.visibility, [false, true, false, true]);
});
test('firmware wake retains default focus and duplicate wake does not steal focus', async () => {
 const h = harness(), controller = wakeController(h);
 h.disable(); h.shell.sleep();
 await controller.handleInputEvent({ kind: 'display-wake', frameId: 1 });
 assert.equal(h.shell.getFocus(), 'sidebar');
 h.shell.focus = 'window';
 await controller.handleInputEvent({ kind: 'display-wake', frameId: 1 });
 assert.equal(h.shell.getFocus(), 'window');
 assert.deepEqual(h.visibility, [false, true]);
});
test('locked firmware wake keeps host focus and blocks APK input', async () => {
 const h = harness(), controller = wakeController(h);
 controller.glassesLocked = true; h.shell.sleep();
 await controller.handleInputEvent({ kind: 'display-wake', frameId: 1 });
 assert.equal(h.shell.getFocus(), 'sidebar');
 await controller.handleInputEvent({ kind: 'click', frameId: 1 });
 assert.equal(h.delivered.length, 0);
});
