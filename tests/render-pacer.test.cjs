const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
test('transport pacing overlaps rendering and bounds recovery outliers', () => {
  const classes=fs.mkdtempSync(path.join(os.tmpdir(),'faceclaw-pacer-'));
  try {
    execFileSync('javac',['-d',classes,'App_Resources/Android/src/main/java/com/faceclaw/app/RenderPacer.java','tests/fixtures/RenderPacerCheck.java']);
    execFileSync('java',['-cp',classes,'com.faceclaw.app.RenderPacerCheck']);
  } finally { fs.rmSync(classes,{recursive:true,force:true}); }
});
