#!/usr/bin/env python3
"""Rebuild isolated selected sysroot and GNU producer. Never executes resulting ELF."""
import argparse,json,pathlib,hashlib,subprocess,tarfile,io,os,shutil,re
ap=argparse.ArgumentParser();ap.add_argument('--manifest',required=True);ap.add_argument('--cache',required=True);ap.add_argument('--ndk',required=True);ap.add_argument('--out',required=True);a=ap.parse_args()
b=pathlib.Path(__file__).parent;m=json.loads(pathlib.Path(a.manifest).read_text());out=pathlib.Path(a.out)
if out.exists():raise ValueError('OUTPUT_EXISTS')
out.mkdir(mode=0o700);sysroot=out/'sysroot';sysroot.mkdir(mode=0o700);chosen={r['path']:r for r in m['files']}
for pkg in m['packages']:
 p=pathlib.Path(a.cache)/pkg['cached_filename'];data=p.read_bytes()
 if len(data)!=pkg['bytes'] or hashlib.sha256(data).hexdigest()!=pkg['sha256']:raise ValueError('ARCHIVE_PIN')
 # Data stream only. No maintainer scripts executed.
 raw=subprocess.check_output(['dpkg-deb','--fsys-tarfile',str(p)])
 with tarfile.open(fileobj=io.BytesIO(raw),mode='r:') as tf:
  for item in tf.getmembers():
   name=item.name.removeprefix('./');r=chosen.get(name)
   if not r or r['package']!=pkg['package']:continue
   if r['type']=='symlink':
    if not item.issym() or item.linkname!=r['target']:raise ValueError('ARCHIVE_LINK_PIN')
    continue
   if not item.isfile():raise ValueError('ARCHIVE_FILE_TYPE')
   if pathlib.PurePosixPath(name).is_absolute() or '..' in pathlib.PurePosixPath(name).parts:raise ValueError('PATH')
   data=tf.extractfile(item).read()
   if len(data)!=r['bytes'] or hashlib.sha256(data).hexdigest()!=r['sha256']:raise ValueError('MEMBER_PIN')
   dest=sysroot/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data);dest.chmod(item.mode&0o777)
for r in m['files']:
 if r['type']!='symlink':continue
 dest=sysroot/r['path'];target=r['target']
 # Rewrite only reviewed absolute in-sysroot links into relative equivalents.
 if target.startswith('/'):target=os.path.relpath(sysroot/target.lstrip('/'),dest.parent)
 resolved=(dest.parent/target).resolve()
 if not resolved.is_relative_to(sysroot.resolve()):raise ValueError('LINK_ESCAPE')
 dest.parent.mkdir(parents=True,exist_ok=True);dest.symlink_to(target)
# Explicit distro usrmerge aliases, not inferred archive content.
(sysroot/'lib').symlink_to('usr/lib');(sysroot/'lib64').symlink_to('usr/lib64')
ndk=pathlib.Path(a.ndk);bin=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin';compiler=bin/'clang';linker=bin/'ld.lld'
expected={'clang':'d326ed4fc4f71e5315b9ce388fc23ec792f81652ca7b29e66294de6ed2520844','ld.lld':'9f7df260a1a6cd02e3274c1d57ff22b2745791b85fb38a958051ebcd31e42900'}
for tool in [compiler,linker]:
 if hashlib.sha256(tool.read_bytes()).hexdigest()!=expected[tool.name]:raise ValueError('TOOL_PIN')
cmd=[str(compiler),'--target=x86_64-linux-gnu','--sysroot='+str(sysroot),'-fuse-ld=lld','-O2','-g0','-pthread','-I'+str(sysroot/'usr/include/glib-2.0'),'-I'+str(sysroot/'usr/lib/x86_64-linux-gnu/glib-2.0/include'),str(b/'native/producer.c'),'-L'+str(sysroot/'usr/lib/x86_64-linux-gnu'),'-lgio-2.0','-lgobject-2.0','-lglib-2.0','-Wl,-t','-o',str(out/'producer')]
p=subprocess.run(cmd,capture_output=True,text=True);(out/'link.stdout').write_text(p.stdout);(out/'link.stderr').write_text(p.stderr);(out/'build.json').write_text(json.dumps({'argv':cmd,'exit_code':p.returncode,'compiler_sha256':hashlib.sha256(compiler.read_bytes()).hexdigest(),'linker_sha256':hashlib.sha256(linker.read_bytes()).hexdigest(),'producer_source_sha256':hashlib.sha256((b/'native/producer.c').read_bytes()).hexdigest(),'target_executed':False},indent=2)+'\n')
if p.returncode:raise SystemExit(p.returncode)
# Resolve ELF graph strictly within staged sysroot. No host ldd.
queue=[out/'producer'];seen={};edges=[]
while queue:
 elf=queue.pop().resolve()
 if str(elf) in seen:continue
 data=elf.read_bytes();seen[str(elf)]={'path':'/producer' if elf==(out/'producer').resolve() else '/'+str(elf.relative_to(sysroot)),'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)}
 dyn=subprocess.check_output(['readelf','-d',str(elf)],text=True)
 for soname in re.findall(r'\(NEEDED\).*?\[(.*?)\]',dyn):
  dep=sysroot/'usr/lib/x86_64-linux-gnu'/soname
  if not dep.exists():raise ValueError('MISSING_ELF:'+soname)
  edges.append({'from':seen[str(elf)]['path'],'needed':soname,'resolved':'/'+str(dep.resolve().relative_to(sysroot))});queue.append(dep)
 program=subprocess.check_output(['readelf','-l',str(elf)],text=True);match=re.search(r'Requesting program interpreter: (.*?)\]',program)
 if match:
  loader=sysroot/match[1].lstrip('/')
  if not loader.exists():raise ValueError('LOADER_MISSING')
  queue.append(loader)
(out/'runtime-graph.json').write_text(json.dumps({'scope':'STATIC_ELF_GRAPH_NO_RUNTIME_PROOF','files':list(seen.values()),'edges':edges,'target_executed':False},indent=2)+'\n')
