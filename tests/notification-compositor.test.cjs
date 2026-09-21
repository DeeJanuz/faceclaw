const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');

test('notification compositor regressions run against the shared Kotlin implementation', () => {
  const migrations = JSON.parse(fs.readFileSync('native/kotlin/migrated-java-sources.json', 'utf8'));
  const migration = migrations.find(entry => entry.java === 'com/faceclaw/app/SurfaceCompositor.java');
  assert.equal(migration?.kotlin, 'shared/src/commonMain/kotlin/com/faceclaw/app/graphics/SurfaceCompositor.kt');
  assert.equal(fs.existsSync('App_Resources/Android/src/main/java/com/faceclaw/app/SurfaceCompositor.java'), false);
  const coverage = fs.readFileSync('tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/SurfaceCompositorRegressionTest.kt', 'utf8');
  assert.match(coverage, /fun zeroUnderlayConcealsRasterIdentitiesAndFingerprint/);
  assert.match(coverage, /fun packedDamageMatchesFullPackingAndFailedTargetsDoNotMutateState/);
  assert.match(coverage, /fun sparseDamageNeverDivergesFromFullPacking/);
});
