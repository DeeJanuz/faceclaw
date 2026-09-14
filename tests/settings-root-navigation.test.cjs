const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');

function loadPanel() {
  const module = { exports: {} };
  const calls = [];
  const shell = {
    returnFromAppRoot: () => calls.push('root'),
    yieldFocusToSidebar: () => calls.push('sidebar'),
  };
  const imports = {
    '../../graphics/ui-fonts': { getDefaultSmallFont: () => ({}) },
    '../../graphics/image': { GrayImage: class {} },
    '../../graphics/textwrap': { wrapText: () => [] },
    '../../util/numeric-util': { clamp: (value, min, max) => Math.max(min, Math.min(max, value)) },
    '../gestures': {},
    '../layers': { MenuLayer: class {}, PaintBelow: {}, Layer: {} },
    '../menu': { drawSelectionHighlight() {}, isMenuItemDisabled: () => false, MenuLayer: class {}, openModalMenu() {} },
    '../metrics': { LIST_ROW_TEXT_INSET: 0, lineStep: () => 10, listRowHeight: () => 20 },
    '../shell/shell': { shell },
  };
  const source = fs.readFileSync('app/ui/dashboard/settings-panel.ts', 'utf8');
  const output = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText;
  vm.runInNewContext(output, { module, exports: module.exports, require: name => imports[name] || {} });
  return { Panel: module.exports.SettingsPanelLayer, calls };
}

function panelWith(items = [{ label: 'General', onSelect() {} }]) {
  const { Panel, calls } = loadPanel();
  return { panel: new Panel([{ label: 'System', items }]), calls };
}

test('root double-click uses semantic root-back', async () => {
  const h = panelWith();
  await h.panel.handleInput({ type: 'double-click' }, {});
  assert.deepEqual(h.calls, ['root']);
});

test('root directional back uses semantic root-back', async () => {
  const h = panelWith();
  await h.panel.handleInput({ type: 'swipe-left' }, {});
  assert.deepEqual(h.calls, ['root']);
});

test('inner-pane back returns to the left pane without root-back', async () => {
  const h = panelWith();
  await h.panel.handleInput({ type: 'click' }, {});
  await h.panel.handleInput({ type: 'double-click' }, {});
  assert.deepEqual(h.calls, []);
  await h.panel.handleInput({ type: 'double-click' }, {});
  assert.deepEqual(h.calls, ['root']);
});

test('ordinary selection remains in the current pane', async () => {
  const selected = [];
  const h = panelWith([{ label: 'General', onSelect: () => selected.push(true) }]);
  await h.panel.handleInput({ type: 'click' }, {});
  await h.panel.handleInput({ type: 'click' }, {});
  assert.deepEqual(selected, [true]);
  assert.deepEqual(h.calls, []);
});
