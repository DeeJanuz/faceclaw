const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');

const { transpile } = require('./helpers/transpile.cjs');
function fixture() {
  let now = 1000,
    sent = 0,
    cancelled = 0,
    current = true;
  const draws = [],
    module = { exports: {} };
  class Image {
    drawText(_font, x, y, text) {
      draws.push({ x, y, text });
    }
  }
  const font = { fingerprintId: 1, lineHeight: 20 };
  const imports = {
    '../../graphics/image': { GrayImage: Image },
    '../../graphics/textwrap': { wrapText: (_f, text) => text.split('\n'), truncateText: (_f, text) => text },
    '../../graphics/ui-fonts': { getDefaultSmallFont: () => font },
  };
  vm.runInNewContext(transpile('app/ui/shell/messaging-review.ts'), {
    module,
    exports: module.exports,
    require: (n) => imports[n],
    Date: { now: () => now },
  });
  const review = {
    title: 'Review message',
    text: Array.from({ length: 50 }, (_, i) => `Line ${i}`).join('\n'),
    acceptLabel: 'Send',
    accept: () => sent++,
    cancel: () => cancelled++,
    current: () => current,
  };
  const layer = new module.exports.MessagingReviewLayer(review, () => layer.onRemoved());
  const ctx = { actions: { requestRender() {} } };
  return {
    layer,
    draws,
    font,
    ctx,
    paint: () => layer.paint(ctx, () => {}),
    click: (type = 'click') => layer.handleInput({ type, source: 'ring', timestampMs: now }, ctx),
    wait: () => (now += 400),
    revoke: () => (current = false),
    sent: () => sent,
    cancelled: () => cancelled,
  };
}
test('all message pages are available and a fresh final click alone confirms once', () => {
  const f = fixture();
  f.click();
  assert.equal(f.sent(), 0);
  for (let i = 0; i < 4; i++) {
    f.paint();
    f.click();
    assert.equal(f.sent(), 0);
    f.wait();
    f.click();
  }
  assert.equal(f.sent(), 1);
  f.click();
  assert.equal(f.sent(), 1);
  const lines = f.draws.map((d) => d.text).filter((t) => /^Line /.test(t));
  assert.deepEqual(
    lines,
    Array.from({ length: 50 }, (_, i) => `Line ${i}`),
  );
});
test('revocation and cancellation cannot become a send; scrolling final page does not send', () => {
  const f = fixture();
  for (let i = 0; i < 5; i++) {
    f.paint();
    f.wait();
    f.click('scroll-down');
  }
  assert.equal(f.sent(), 0);
  f.revoke();
  f.click();
  assert.equal(f.sent(), 0);
  assert.equal(f.cancelled(), 1);
});
test('typography changes restart review at the first page', () => {
  const f = fixture();
  f.paint();
  f.wait();
  f.click();
  f.font.fingerprintId = 2;
  f.paint();
  assert.ok(f.draws.some((d) => d.text === 'Page 1 of 4'));
  assert.equal(f.sent(), 0);
});
