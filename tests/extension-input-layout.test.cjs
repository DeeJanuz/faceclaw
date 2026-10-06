const test = require('node:test');
const assert = require('node:assert/strict');

const vm = require('node:vm');
const { transpile } = require('./helpers/transpile.cjs');
function load(file, modules = {}) {
  const context = {
    exports: {},
    require: (name) => {
      assert.ok(name in modules, name);
      return modules[name];
    },
  };
  vm.runInNewContext(transpile(file), context);
  return context.exports;
}
test('viewport text entry uses its full height, renderer widths and reverts to the base layout', () => {
  let expanded = true;
  const draws = [],
    boxes = [],
    hints = [];
  const font = { lineHeight: 17, measureText: (text) => Array.from(text).length * 5.5 };
  const { paintInputDialog } = load('app/ui/shell/input-dialog.ts', {
    '../extension-settings': {
      windowLayoutPolicy: () => ({ inputDialogs: expanded ? 'viewport' : 'compact' }),
      typographyPolicy: () => ({}),
    },
    '../../graphics/image': { G2_LENS_WIDTH: 640, GrayImage: class { drawText() {} } },
    '../../graphics/textwrap': load('app/graphics/textwrap.ts'),
    '../../graphics/ui-fonts': { getDefaultSmallFont: () => font },
    '../menu-core': { Menu: class {} },
    '../metrics': { listRowHeight: () => 25, textInkBounds: () => ({ top: 0, bottom: 17 }) },
    './geometry': {
      MIN_WINDOW_HEIGHT: 288,
      minWindowTop: () => 96,
      appViewportRect: () => ({ x: 0, y: 28, width: 640, height: 452 }),
    },
  });
  const image = {
    bakeDeferredDrawsInPlace() {},
    fillRoundedRect: (...args) => boxes.push(args),
    drawRoundedRect() {},
    drawText: (_font, x, y, text) => draws.push({ x, y, text }),
    // The hint floats at stereo depth, drawn into an image of its own.
    drawDepthImage: (_source, x, y) => hints.push({ x, y }),
  };
  const content = {
    title: 'DICTATING',
    status: 'Nothing sent',
    text: 'Wide transcript words '.repeat(200),
    menu: null,
    hint: 'Tap to review',
  };
  paintInputDialog(image, content);
  assert.deepEqual(boxes[0].slice(0, 4), [8, 36, 624, 436]);
  const lines = draws.slice(2);
  assert.ok(lines.length > 12);
  assert.ok(lines.some((line) => line.y > 350));
  assert.ok(lines.every((line) => line.x + font.measureText(line.text) <= 616));
  assert.ok(lines.every((line, index) => !index || line.y - lines[index - 1].y === 22));
  assert.ok(draws.every((line) => line.y + font.lineHeight < 480));
  assert.equal(hints.length, 1);
  assert.ok(hints[0].y > lines.at(-1).y && hints[0].y + font.lineHeight < 480);
  expanded = false;
  paintInputDialog(image, content);
  assert.deepEqual(boxes[1].slice(0, 4), [40, 124, 560, 232]);
});
