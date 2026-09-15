#!/usr/bin/env python3
"""Package built SDK bytes into an immutable, task-local Maven repository."""
import hashlib, json, os, pathlib, shutil, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[1]
output=root.parents[1]/'output'/'sdk-independence'
aar=root/'sdk/build/outputs/aar/sdk-release.aar'
sha=lambda data: hashlib.sha256(data).hexdigest()
content=aar.read_bytes()
js=root/'javascript'
files=sorted(p for p in js.iterdir() if p.suffix in ('.js','.ts','.json') and '.test.' not in p.name and p.name not in ('test.cjs',))
digest=sha(content+b''.join(p.name.encode()+p.read_bytes() for p in files))
version='1.1.0-rc.1.'+digest[:12]
def immutable(path,data):
 path.parent.mkdir(parents=True,exist_ok=True)
 try:
  with path.open('xb') as stream: stream.write(data)
 except FileExistsError:
  if path.read_bytes()!=data: raise RuntimeError('Refusing to overwrite immutable artifact: '+str(path))
coordinate=output/'maven/com/faceclaw/sdk'/version
immutable(coordinate/f'sdk-{version}.aar',content)
pom=f'<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>com.faceclaw</groupId><artifactId>sdk</artifactId><version>{version}</version><packaging>aar</packaging></project>\n'
immutable(coordinate/f'sdk-{version}.pom',pom.encode())
for artifact in (coordinate/f'sdk-{version}.aar',coordinate/f'sdk-{version}.pom'):
 immutable(artifact.with_suffix(artifact.suffix+'.sha256'),(sha(artifact.read_bytes())+'\n').encode())
with tempfile.TemporaryDirectory(prefix='faceclaw-sdk-package-') as tmp:
 directory=pathlib.Path(tmp)
 for p in files: shutil.copyfile(p,directory/p.name)
 package=json.loads((directory/'package.json').read_text());package['version']=version;(directory/'package.json').write_text(json.dumps(package,indent=2)+'\n')
 result=json.loads(subprocess.check_output(['npm','pack','--json','--ignore-scripts'],cwd=directory,text=True))
 tarball=directory/result[0]['filename'];target=output/'npm'/tarball.name;immutable(target,tarball.read_bytes())
manifest={'version':version,'coordinate':f'com.faceclaw:sdk:{version}','contractVersion':1,'aar':str(coordinate/f'sdk-{version}.aar'),'aarSha256':sha(content),'npm':str(target),'npmSha256':sha(target.read_bytes()),'sourceMode':'explicit opt-in only','deviceAcceptance':'pending by user direction'}
immutable(output/'candidates'/version/'sdk-manifest.json',(json.dumps(manifest,indent=2)+'\n').encode())
(output/'current-sdk.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest,indent=2))
