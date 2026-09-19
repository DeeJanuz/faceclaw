const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

test('glasses activity snapshot keeps bounded traffic, event, and state totals', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'faceclaw-battery-stats-'));
  try {
    execFileSync('javac', ['-d', dir,
      'App_Resources/Android/src/main/java/com/faceclaw/app/GlassesActivityStats.java',
      'tests/fixtures/GlassesActivityStatsCheck.java',
    ]);
    execFileSync('java', ['-cp', dir, 'com.faceclaw.app.GlassesActivityStatsCheck']);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
