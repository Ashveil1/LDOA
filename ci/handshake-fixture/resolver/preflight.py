"""Read-only future runner preflight. No installer, no target execution."""
import os,pathlib,shutil,json,subprocess,hashlib,platform
SDK=pathlib.Path('/usr/local/lib/android/sdk');errors=[];tools={}
if platform.machine()!='x86_64':errors.append('ARCH_X86_64_REQUIRED')
if os.cpu_count()<4:errors.append('CPU_MIN4')
mem={k:int(v.split()[0])*1024 for k,v in (x.split(':',1) for x in pathlib.Path('/proc/meminfo').read_text().splitlines())}
if mem.get('MemAvailable',0)<12*1024**3:errors.append('RAM_MIN12GIB')
free=shutil.disk_usage(os.environ.get('RUNNER_TEMP','/tmp')).free
if free<13*1024**3:errors.append('DISK_MIN12GIB_PLUS1GIB_EMERGENCY')
for name,p,args,expect in [('java17',pathlib.Path(os.environ.get('JAVA_HOME_17_X64','/missing'))/'bin/java',['-version'],'17.'),('cmake',SDK/'cmake/3.22.1/bin/cmake',['--version'],'3.22.1'),('ninja',pathlib.Path(shutil.which('ninja') or '/missing'),['--version'],'1.')]:
 if not p.is_file():errors.append(name+'_MISSING');continue
 r=subprocess.run([str(p)]+args,capture_output=True,text=True,timeout=10);text=r.stdout+r.stderr
 tools[name]={'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'version':text[:300]}
 if r.returncode or expect not in text:errors.append(name+'_VERSION')
for name,p in [('ndk29',SDK/'ndk/29.0.14206865/source.properties'),('platform35',SDK/'platforms/android-35/android.jar'),('buildtools35',SDK/'build-tools/35.0.0/aapt2')]:
 if not p.is_file():errors.append(name+'_MISSING')
 else:tools[name]={'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
print(json.dumps({'scope':'BUILD_INPUT_PREFLIGHT_ONLY','errors':errors,'tools':tools,'cpus':os.cpu_count(),'available_ram':mem.get('MemAvailable'),'free_disk':free,'kvm_access':os.path.exists('/dev/kvm') and os.access('/dev/kvm',os.R_OK|os.W_OK),'kvm_not_required_for_build_only':True},sort_keys=True))
raise SystemExit(1 if errors else 0)
