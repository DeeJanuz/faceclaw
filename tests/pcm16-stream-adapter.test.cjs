const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

test('PCM stream adapter preserves variable-length processor output', () => {
  const classes = fs.mkdtempSync(path.join(os.tmpdir(), 'faceclaw-pcm-adapter-'));
  try {
    execFileSync('javac', [
      '-d', classes,
      'App_Resources/Android/src/main/java/com/faceclaw/app/Pcm16StreamAdapter.java',
      'tests/fixtures/Pcm16StreamAdapterCheck.java',
    ], { encoding: 'utf8', stdio: 'pipe' });
    assert.equal(execFileSync(
      'java',
      ['-cp', classes, 'com.faceclaw.app.Pcm16StreamAdapterCheck'],
      { encoding: 'utf8', stdio: 'pipe' },
    ), '');
  } finally {
    fs.rmSync(classes, { recursive: true, force: true });
  }
});
