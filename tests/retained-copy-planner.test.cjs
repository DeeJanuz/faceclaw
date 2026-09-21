const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');

test('retained-copy planner is owned and tested by the shared Kotlin module', () => {
  const migrations = JSON.parse(fs.readFileSync('native/kotlin/migrated-java-sources.json', 'utf8'));
  for (const name of ['SurfaceCompositor', 'BleImageOptimizer', 'TexturePlanner']) {
    const java = `com/faceclaw/app/${name === 'SurfaceCompositor' ? '' : 'g2protocol/'}${name}.java`;
    const migration = migrations.find(entry => entry.java === java);
    assert.ok(migration, `${java} must remain an explicit Kotlin migration`);
    assert.ok(fs.existsSync(`native/kotlin/${migration.kotlin}`), migration.kotlin);
    assert.equal(fs.existsSync(`App_Resources/Android/src/main/java/${java}`), false, `${java} shadow returned`);
  }
  const coverage = fs.readFileSync('tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/RetainedCopyTest.kt', 'utf8');
  assert.match(coverage, /fun overlappingCopiesMatchReference/);
  assert.match(coverage, /fun copyPlanBeatsRasterAndComposesWithTextureDraws/);
  assert.match(coverage, /fun surfaceCopyHintsTranslateToScreenCoordinates/);
});
