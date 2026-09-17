const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');

function load() {
  const module = { exports: {} };
  const source = fs.readFileSync('app/g2/ring-scroll-filter.ts', 'utf8');
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
  }).outputText, { module, exports: module.exports });
  return module.exports;
}

test('ring scroll rate limiter softens a short same-direction burst', () => {
  const { RingScrollRateLimiter } = load();
  const limiter = new RingScrollRateLimiter();
  assert.equal(limiter.shouldDispatch('down', 1_000), true);
  assert.equal(limiter.shouldDispatch('down', 1_080), false);
  assert.equal(limiter.shouldDispatch('down', 1_180), false);
});

test('ring scroll rate limiter preserves sustained travel and immediate reversal', () => {
  const { RingScrollRateLimiter, RING_SCROLL_REPEAT_INTERVAL_MS } = load();
  const limiter = new RingScrollRateLimiter();
  assert.equal(limiter.shouldDispatch('down', 1_000), true);
  assert.equal(limiter.shouldDispatch('down', 1_000 + RING_SCROLL_REPEAT_INTERVAL_MS), true);
  assert.equal(limiter.shouldDispatch('up', 1_001 + RING_SCROLL_REPEAT_INTERVAL_MS), true);
  assert.equal(limiter.shouldDispatch('up', 1_100 + RING_SCROLL_REPEAT_INTERVAL_MS), false);
});

test('reset rearms the next scroll immediately', () => {
  const { RingScrollRateLimiter } = load();
  const limiter = new RingScrollRateLimiter();
  assert.equal(limiter.shouldDispatch('up', 5_000), true);
  assert.equal(limiter.shouldDispatch('up', 5_010), false);
  limiter.reset();
  assert.equal(limiter.shouldDispatch('up', 5_010), true);
});
