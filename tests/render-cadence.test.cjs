const test=require('node:test'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {execFileSync}=require('node:child_process');
test('credit cadence includes elapsed render work',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'faceclaw-cadence-'));
 try {
  execFileSync('javac',['-d',dir,'App_Resources/Android/src/main/java/com/faceclaw/app/RenderCadence.java','tests/fixtures/RenderCadenceCheck.java']);
  execFileSync('java',['-cp',dir,'com.faceclaw.app.RenderCadenceCheck']);
 } finally {fs.rmSync(dir,{recursive:true,force:true});}
});
