const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');
const { validateWidgetRegistry, validateWidgetContent, GlanceCanvas } = require('./glanceboard');
const fixture = require('../test-vectors/glanceboard.json');
for (const item of fixture.cases)
  test('shared contract: ' + item.name, () => {
    const action = () =>
      item.kind === 'registry'
        ? validateWidgetRegistry(item.value)
        : validateWidgetContent(item.value, fixture.registry, fixture.now);
    if (item.valid) assert.doesNotThrow(action);
    else assert.throws(action);
  });
test('builder packs pixels and produces independent frames', () => {
  const canvas = new GlanceCanvas('home', 61000).bitmap(0, 0, 2, 1, [255, 0]);
  const value = canvas.build();
  assert.equal(value.commands[0].bits, 'gA==');
  canvas.rect(0, 1, 10, 10);
  assert.equal(value.commands.length, 1);
  assert.doesNotThrow(() => validateWidgetContent(value, fixture.registry, 1000));
});
test('CLI scaffold validates and previews without host sources, and rejects invalid content', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'faceclaw-widget-'));
  try {
    const cli = path.join(__dirname, 'glanceboard-cli.js');
    execFileSync(process.execPath, [cli, 'init', dir]);
    const registry = path.join(dir, 'widgets.json'),
      file = path.join(dir, 'content.json'),
      out = path.join(dir, 'preview.html');
    const content = JSON.parse(fs.readFileSync(file));
    const now = content.expiresAt - 60000;
    execFileSync(process.execPath, [cli, 'validate', registry, file, '--now', String(now)]);
    content.entries[0].title = '<script>alert(1)</script>';
    fs.writeFileSync(file, JSON.stringify(content));
    execFileSync(process.execPath, [cli, 'preview', registry, file, '--out', out, '--now', String(now)]);
    assert.ok(fs.readFileSync(out, 'utf8').includes('&lt;script&gt;'));
    assert.ok(!fs.readFileSync(out, 'utf8').includes('<script>'));
    content.widgetId = 'foreign';
    fs.writeFileSync(file, JSON.stringify(content));
    assert.notEqual(spawnSync(process.execPath, [cli, 'validate', registry, file, '--now', String(now)]).status, 0);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
