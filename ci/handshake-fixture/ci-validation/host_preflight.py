#!/usr/bin/env python3
"""Standard host inspection only; no install/update/network. Future CI source, not executed here."""
import os,pathlib,subprocess,shutil,json,hashlib
sdk=pathlib.Path(os.environ.get('ANDROID_HOME','/nonexistent'));checks={};fail=[]
def tool(name,path,args,expect):
 p=pathlib.Path(path);r={'path':str(p)}
 if not p.is_file():fail.append(name+':MISSING')
 else:
  r['sha256']=hashlib.sha256(p.read_bytes()).hexdigest();v=subprocess.run([str(p)]+args,capture_output=True,text=True,timeout=10);r.update(exit=v.returncode,version=(v.stdout+v.stderr).strip()[:1000]);
  if v.returncode or expect not in r['version']:fail.append(name+':VERSION')
 checks[name]=r
tool('java',str(pathlib.Path(os.environ.get('JAVA_HOME','/nonexistent'))/'bin/java'),['-version'],'17.')
tool('gradle',os.environ.get('HS_REVIEWED_GRADLE_89','/nonexistent'),['--version'],'Gradle 8.9')
tool('cmake',shutil.which('cmake') or '/nonexistent',['--version'],'3.22.1')
tool('ninja',shutil.which('ninja') or '/nonexistent',['--version'],'1.')
import re
v=checks['ninja'].get('version','');m=re.fullmatch(r'(\d+)\.(\d+)(?:\.(\d+))?',v)
if not m or tuple(map(int,m.groups(default='0'))) < (1,10,0):fail.append('ninja:MIN_1_10')
for name,path in [('platform35','platforms/android-35/android.jar'),('buildtools35','build-tools/35.0.0/aapt2'),('ndkr29','ndk/29.0.14206865/source.properties'),('adb','platform-tools/adb')]:
 p=sdk/path;checks[name]={'path':str(p),'exists':p.is_file()}
 if not p.is_file():fail.append(name+':MISSING')
if not os.path.exists('/dev/kvm') or not os.access('/dev/kvm',os.R_OK|os.W_OK):fail.append('KVM_UNAVAILABLE_NO_SOFTWARE_FALLBACK')
print(json.dumps({'scope':'HOST_PREFLIGHT','checks':checks,'failures':fail},separators=(',',':')))
raise SystemExit(1 if fail else 0)
