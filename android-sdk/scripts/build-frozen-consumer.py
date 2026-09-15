#!/usr/bin/env python3
"""Build two standalone app variants from frozen SDK bytes; never invokes a host build."""
import hashlib,json,os,pathlib,shutil,subprocess
sdk=pathlib.Path(__file__).resolve().parents[1]
workspace=sdk.parents[1]
output=workspace/'output/sdk-independence'
manifest=json.loads((output/'current-sdk.json').read_text())
host=workspace/'faceclaw-app-platform/platforms/android/app/build/outputs/apk/debug/app-debug.apk'
hashfile=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
before=hashfile(host)
consumer=output/'frozen-consumer';(consumer/'app').mkdir(parents=True,exist_ok=True)
(consumer/'settings.gradle.kts').write_text('pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { maven { url = uri("'+str(output/'maven')+'") }; google(); mavenCentral() } }\nrootProject.name="frozen-sdk-consumer"\ninclude(":app")\n')
(consumer/'build.gradle.kts').write_text('plugins { id("com.android.application") version "8.9.2" apply false }\n')
source=sdk/'standalone-example/src';shutil.copytree(source,consumer/'app/src',dirs_exist_ok=True)
p=consumer/'app/src/main/java/com/faceclaw/example/CounterPresentation.java';p.write_text(p.read_text().replace('state.getInt("count",0)+1','state.getInt("count",0)+BuildConfig.STEP'))
(consumer/'app/build.gradle.kts').write_text('''plugins { id("com.android.application") }
android {
 namespace="com.faceclaw.example"
 compileSdk=35
 defaultConfig { applicationId="com.faceclaw.sdkexample"; minSdk=27; targetSdk=35; versionCode=1; versionName="1.0"; buildConfigField("int","STEP",providers.gradleProperty("counterStep").orElse("1").get()) }
 buildFeatures { buildConfig=true }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_11; targetCompatibility=JavaVersion.VERSION_11 }
}
dependencies { implementation("'''+manifest['coordinate']+'''") }
''')
env=dict(os.environ,JAVA_HOME='/home/deej/.local/jdk-21',ANDROID_HOME='/home/deej/.local/android-sdk')
artifacts={}
for step in (1,2):
 with (output/f'frozen-consumer-A{step}.log').open('w') as log:
  subprocess.run([str(sdk/'gradlew'),'-p',str(consumer),':app:assembleDebug',f'-PcounterStep={step}','--console=plain'],env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
 target=output/f'counter-A{step}.apk';shutil.copyfile(consumer/'app/build/outputs/apk/debug/app-debug.apk',target);artifacts[f'A{step}']={'path':str(target),'sha256':hashfile(target),'counterStep':step}
after=hashfile(host)
if before!=after:raise RuntimeError('Host hash changed during independent app builds')
record={'host':str(host),'hostSha256Before':before,'hostSha256After':after,'sdk':manifest['coordinate'],'artifacts':artifacts,'evidence':'compile-only; device behavior pending by user direction'}
(output/'fixed-host-build-evidence.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps(record,indent=2))
