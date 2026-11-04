from pathlib import Path
import json,hashlib,subprocess,tarfile,io,re,argparse
ap=argparse.ArgumentParser();ap.add_argument('--package-root',required=True);ap.add_argument('--output-dir',required=True);args=ap.parse_args();root=Path(args.package_root);base=Path(args.output_dir);dis=json.loads((base/'deps.discovery.json').read_text());allrecords={}
for ix in dis['indexes']:
 for f in (root/'var/lib/apt/lists').glob('*'+ix['index'].replace('/','_')):
  if hashlib.sha256(f.read_bytes()).hexdigest()!=ix['sha256']:continue
  for block in f.read_text().split('\n\n'):
   d={l.split(': ',1)[0]:l.split(': ',1)[1] for l in block.splitlines() if ': ' in l and not l.startswith(' ')}
   if all(k in d for k in ['Package','Version','Filename','SHA256','Size']):allrecords[(d['Package'],d['SHA256'])]=d
wanted={'libglib2.0-0t64','libc6','libffi8','libpcre2-8-0','libmount1','libselinux1','zlib1g','libblkid1','libglib2.0-dev','libc6-dev','libgcc-13-dev','linux-libc-dev'}
packages=[];manifest=[];missing=[]
for name in sorted(wanted):
 matches={}
 for f in (root/'var/cache/apt/archives').glob(name+'_*.deb'):
  sha=hashlib.sha256(f.read_bytes()).hexdigest();d=allrecords.get((name,sha))
  if d and len(f.read_bytes())==int(d['Size']):matches[sha]=(d,f)
 # Select exact existing discovery pin when available; otherwise require unique cache pin.
 old=next((p for p in dis['packages'] if p['package']==name),None)
 if old:matches={h:v for h,v in matches.items() if h==old['sha256']}
 if len(matches)!=1:missing.append({'name':name,'matches':len(matches)});continue
 d,f=next(iter(matches.values()));packages.append({'package':name,'version':d['Version'],'url':'https://archive.ubuntu.com/ubuntu/'+d['Filename'],'sha256':d['SHA256'],'bytes':int(d['Size']),'cached_filename':f.name})
 data=subprocess.check_output(['dpkg-deb','--fsys-tarfile',str(f)])
 with tarfile.open(fileobj=io.BytesIO(data),mode='r:') as tf:
  for m in tf.getmembers():
   n=m.name.removeprefix('./')
   # Select headers + pkg metadata + runtime/linker libs/crt only. No compiler, Python, GIR or maintainer scripts.
   select=n.startswith('usr/include/') or n.startswith('usr/lib/x86_64-linux-gnu/') or n.startswith('lib/x86_64-linux-gnu/') or n.startswith('usr/lib64/') or n.startswith('lib64/') or (n.startswith('usr/lib/gcc/x86_64-linux-gnu/13/') and (n.endswith('.o') or n.endswith('.a') or n.endswith('.so')))
   if not select or m.isdir():continue
   if '/gio/modules/' in n or '/girepository-' in n or '/glib-2.0/codegen/' in n:continue
   if m.isfile():manifest.append({'path':n,'package':name,'sha256':hashlib.sha256(tf.extractfile(m).read()).hexdigest(),'bytes':m.size,'type':'file'})
   elif m.issym():manifest.append({'path':n,'package':name,'target':m.linkname,'type':'symlink'})
   else:missing.append({'path':n,'unsupported_type':str(m.type)})
(base/'sysroot-file-selection.json').write_text(json.dumps({'schema':1,'complete':False,'scope':'AUTHENTICATED_SELECTED_SYSROOT_FILES_NOT_RUNTIME_ELF_GRAPH','packages':packages,'files':manifest,'missing':missing,'toolchain':'NDK29 host clang --target=x86_64-linux-gnu + ld.lld, NOT gcc package executable','blockers':['Selected archives need isolated staging and compile/link validation','ELF runtime graph and GIO builtin backend source proof pending','Toolchain host resource-file closure hashes not frozen']},indent=2)+'\n');print('packages',len(packages),'files',len(manifest),'missing',missing)
