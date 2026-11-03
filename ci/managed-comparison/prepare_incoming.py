#!/usr/bin/env python3
"""UNEXECUTED package preparer, finite exact paths. Does not build or publish."""
import argparse,hashlib,json,pathlib,shutil,re
PREFIX='opt/ldoa/pcmanfm/1.3.2-4build2-floor1'
CAP=128*1024*1024
SOURCE_NAMES=['orig.tar.xz','debian.tar.xz','default-size.patch','build-recipe.sh','SOURCE-LABEL.txt']
def safe(name):
 return (bool(re.fullmatch(r'[A-Za-z0-9_./+-]+',name)) and not name.startswith('/') and all(x not in ('','..','.') for x in name.split('/'))) or name in {'payload/share/locale/sr@latin','payload/share/locale/sr@latin/LC_MESSAGES','payload/share/locale/sr@latin/LC_MESSAGES/pcmanfm.mo'}
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(65536),b''):h.update(b)
 return h.hexdigest()
def inventory(root):
 files={};dirs=[];total=0
 for p in root.rglob('*'):
  if p.is_symlink():raise ValueError('symlink')
  n=p.relative_to(root).as_posix()
  if not n or any(x in ('','..','.') for x in n.split('/')):raise ValueError('noncanonical')
  if p.is_dir():dirs.append(n)
  elif p.is_file():
   total+=p.stat().st_size
   if p.stat().st_size>32*1024*1024 or total>CAP:raise ValueError('byte budget')
   files[n]=digest(p)
  else:raise ValueError('special file')
  if len(files)+len(dirs)>1024:raise ValueError('entry cap')
 return files,sorted(dirs),total
def prepare(local,case,out):
 if case not in ('original','patched'):raise ValueError('case')
 if out.exists():raise ValueError('output exists')
 stage=local/case/'stage'/PREFIX
 files,dirs,_=inventory(stage)
 if not {'bin/pcmanfm','COPYING','DEBIAN-COPYRIGHT','SOURCE.txt','inventory.sha256'}<=files.keys() or 'lib/pcmanfm' not in dirs:raise ValueError('missing stage resources')
 for name in SOURCE_NAMES:
  p=local/name
  if not p.is_file() or p.is_symlink():raise ValueError('source missing '+name)
 if digest(local/'orig.tar.xz')!='14cb7b247493c4cce65fbb5902611e3ad00a7a870fbc1e50adc50428c5140cf7':raise ValueError('orig source pin')
 if digest(local/'debian.tar.xz')!='ac2538fe14f44bd56ea77f1bba8c4f16349898ae259e2b602465f4d2faec0723':raise ValueError('Debian source pin')
 if digest(local/'default-size.patch')!='ae69d35d1215efc6e521c7fbe81ceacb261fd0c4917c2828ffb385bded7d6829':raise ValueError('patch pin')
 # Originals + exact one applied patch + exact controlling recipe are preferred
 # corresponding-source inputs; no unnecessary configured object/binary tree.
 out.mkdir(mode=0o700)
 shutil.copytree(stage,out/'payload')
 src=out/'source';src.mkdir()
 for name in SOURCE_NAMES:shutil.copyfile(local/name,src/name)
 for name in ['COPYING','DEBIAN-COPYRIGHT']:shutil.copyfile(stage/name,src/name)
 # Recipe configure commands + flags retained alongside exact source.
 for name in ['commands-'+case+'.txt','explicit-build-environment.txt']:
  p=local.parent/name
  if not p.is_file() or p.is_symlink():raise ValueError('build receipt '+name)
  shutil.copyfile(p,src/name)
 f,d,total=inventory(out)
 if len(f)>256 or len(d)>256:raise ValueError('Android manifest cardinality cap')
 if not all(safe(n) for n in list(f)+d):raise ValueError('Android manifest name policy')
 m={'schema':1,'case':case,'prefix':'/'+PREFIX,'files':f,'directories':d,'bytes':total,'label':'upstream1.3.2; Debian metadata1.3.2-4build2 extracted, quilt not applied'}
 b=json.dumps(m,sort_keys=True,separators=(',',':')).encode()
 if len(b)>65536:raise ValueError('manifest cap')
 (out/'manifest.json').write_bytes(b)
 return hashlib.sha256(b).hexdigest()
if __name__=='__main__':
 a=argparse.ArgumentParser();a.add_argument('--local',type=pathlib.Path,required=True);a.add_argument('--case',choices=['original','patched'],required=True);a.add_argument('--out',type=pathlib.Path,required=True);x=a.parse_args();print(prepare(x.local.resolve(),x.case,x.out))
