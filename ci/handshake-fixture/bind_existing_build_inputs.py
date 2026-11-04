#!/usr/bin/env python3
"""Inventory existing host build bytes. Not a resolved Maven closure, no downloads/Gradle/APK."""
import argparse,pathlib,hashlib,json,subprocess
ap=argparse.ArgumentParser();ap.add_argument('--sdk',required=True);ap.add_argument('--jdk',required=True);ap.add_argument('--gradle',required=True);ap.add_argument('--out',required=True);a=ap.parse_args();sdk=pathlib.Path(a.sdk);jdk=pathlib.Path(a.jdk);gradle=pathlib.Path(a.gradle)
h=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
files=[]
for root in [jdk,gradle,sdk/'build-tools/35.0.0',sdk/'platforms/android-35']:
 for p in sorted(root.rglob('*')):
  if p.is_file():files.append({'path':str(p),'bytes':p.stat().st_size,'sha256':h(p),'symlink':str(p.readlink()) if p.is_symlink() else None})
cmake=pathlib.Path('/usr/bin/cmake');files.append({'path':str(cmake),'bytes':cmake.stat().st_size,'sha256':h(cmake)})
cache=pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1';cached=[]
for p in sorted(cache.rglob('*')):
 if p.is_file():cached.append({'path':str(p.relative_to(cache)),'bytes':p.stat().st_size,'sha256':h(p)})
result={'schema':1,'scope':'EXISTING_LOCAL_BUILD_INPUT_INVENTORY_NOT_RESOLVED_DEPENDENCY_CLOSURE','complete':False,'versions':{'cmake':subprocess.check_output([str(cmake),'--version'],text=True).splitlines()[0]},'files':files,'maven_cached_candidates':cached,'blockers':['No isolated Gradle configuration/dependency-resolution run yet','Cached candidate inventory is not proof of selected dependency graph','SDK CMake registration/discovery not tested','No APK artifact or testAPK manifest verification','Installed Android runtime system image not in scope']}
pathlib.Path(a.out).write_text(json.dumps(result,indent=2)+'\n');print('EXISTING_BYTES',len(files),'MAVEN_CANDIDATES',len(cached),'NOT_RESOLVED_CLOSURE')
