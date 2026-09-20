const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

test('firmware exit recovery is generation-bound and limited to one relaunch', () => {
  const classes = fs.mkdtempSync(path.join(os.tmpdir(), 'faceclaw-session-recovery-'));
  try {
    execFileSync('javac', ['-d', classes,
      'App_Resources/Android/src/main/java/com/faceclaw/app/g2protocol/SessionRecoveryPolicy.java',
      'tests/fixtures/SessionRecoveryPolicyCheck.java'], { encoding: 'utf8', stdio: 'pipe' });
    assert.equal(execFileSync('java', ['-cp', classes, 'com.faceclaw.app.SessionRecoveryPolicyCheck'],
      { encoding: 'utf8', stdio: 'pipe' }), '');
  } finally {
    fs.rmSync(classes, { recursive: true, force: true });
  }
});
