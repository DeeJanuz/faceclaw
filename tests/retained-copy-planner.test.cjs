const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

test('retained-copy planner matches overlap semantics and beats a raster slide', () => {
  const classes = fs.mkdtempSync(path.join(os.tmpdir(), 'faceclaw-retained-copy-'));
  try {
    execFileSync('javac', ['-d', classes,
      'tests/fixtures/android/util/Log.java',
      'tests/fixtures/android/os/SystemClock.java',
      'tests/fixtures/PlannerBleProtocol.java',
      'App_Resources/Android/src/main/java/com/faceclaw/app/util/BmpUtil.java',
      'App_Resources/Android/src/main/java/com/faceclaw/app/SurfaceCompositor.java',
      'App_Resources/Android/src/main/java/com/faceclaw/app/GlyphAtlas.java',
      'App_Resources/Android/src/main/java/com/faceclaw/app/ImageAtlas.java',
      'App_Resources/Android/src/main/java/com/faceclaw/app/FwGlyphAtlas.java',
      'App_Resources/Android/src/main/java/com/faceclaw/app/g2protocol/TextureCacheState.java',
      'App_Resources/Android/src/main/java/com/faceclaw/app/g2protocol/BleImageOptimizer.java',
      'App_Resources/Android/src/main/java/com/faceclaw/app/g2protocol/TexturePlanner.java',
      'tests/fixtures/RetainedCopyPlannerCheck.java'], { encoding: 'utf8', stdio: 'pipe' });
    assert.equal(execFileSync('java', ['-cp', classes, 'com.faceclaw.app.RetainedCopyPlannerCheck'], { encoding: 'utf8', stdio: 'pipe' }), '');
  } finally { fs.rmSync(classes, { recursive: true, force: true }); }
});
