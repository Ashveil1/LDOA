"""Private finite shell plan, pinned manifest plus all bytes checked before planning."""
import pathlib,json,hashlib,shlex,re,argparse
CAP=128*1024*1024
ORIG='14cb7b247493c4cce65fbb5902611e3ad00a7a870fbc1e50adc50428c5140cf7'
DEB='ac2538fe14f44bd56ea77f1bba8c4f16349898ae259e2b602465f4d2faec0723'
PATCH='056bd20e1914f35cfe8924ab0a6044dd2c5e9f5366eef9104276ed1ccfe8ae63'
def safe(n):return (bool(re.fullmatch('[A-Za-z0-9_./+-]+',n)) and not n.startswith('/') and all(x not in ('','..','.') for x in n.split('/'))) or n in {'payload/share/locale/sr@latin','payload/share/locale/sr@latin/LC_MESSAGES','payload/share/locale/sr@latin/LC_MESSAGES/pcmanfm.mo'}
def plan(root,pin,out):
 assert root.is_dir() and not root.is_symlink();root=root.resolve()
 with (root/'manifest.json').open('rb') as f:raw=f.read(65537)
 assert len(raw)<=65536 and hashlib.sha256(raw).hexdigest()==pin
 m=json.loads(raw);assert m['schema']==1 and m['case'] in ('original','patched') and m['prefix']=='/opt/ldoa/pcmanfm/1.3.2-4build2-floor1'
 files=m['files'];dirs=m['directories'];assert 1<=len(files)<=256 and len(dirs)<=256 and len(dirs)==len(set(dirs)) and all(safe(n) for n in list(files)+dirs)
 assert set(n.split('/')[0] for n in list(files)+dirs)=={'payload','source'}
 actualfiles={};actualdirs=set();total=0
 entries=list(root.rglob('*'));assert len(entries)<=1024
 for p in entries:
  assert not p.is_symlink();n=p.relative_to(root).as_posix()
  if p.is_dir():actualdirs.add(n)
  else:
   assert p.is_file() and p.stat().st_size<=32*1024*1024
   total+=p.stat().st_size;assert total<=CAP+65536
   if n!='manifest.json':actualfiles[n]=hashlib.sha256(p.read_bytes()).hexdigest()
 assert actualfiles==files and actualdirs==set(dirs) and total-len(raw)==m['bytes']
 assert files['source/orig.tar.xz']==ORIG and files['source/debian.tar.xz']==DEB and files['source/default-size.patch']==PATCH
 assert 'payload/lib/pcmanfm' in dirs
 expectedfiles=sorted(list(files)+['manifest.json']);hashes=dict(files,**{'manifest.json':pin})
 q=shlex.quote
 # List equality includes exact directory set; symlinks and special objects refused.
 verify='''set -eu
cd "$1"
test -z "$(find . -type l -print)"
test -z "$(find . ! -type d ! -type f -print)"
'''
 verify+='test "$(find . -type f | sed \'s|^./||\' | LC_ALL=C sort)" = '+q('\n'.join(expectedfiles))+'\n'
 verify+='test "$(find . -mindepth 1 -type d | sed \'s|^./||\' | LC_ALL=C sort)" = '+q('\n'.join(sorted(dirs)))+'\n'
 for n in expectedfiles:verify+='test "$(sha256sum '+q(n)+" | cut -d' ' -f1)\" = "+q(hashes[n])+'\n'
 scaffold='set -eu\nT=/data/local/tmp/ldoa-managed-incoming\ntest ! -e "$T" && test ! -L "$T"\nmkdir -m755 "$T"\ncd "$T"\n'
 for n in sorted(dirs,key=lambda n:(n.count('/'),n)):scaffold+='mkdir -m755 '+q(n)+'\n'
 out.mkdir(mode=0o700)
 assert len(scaffold.encode())<=65536 and len(verify.encode())<=131072
 (out/'scaffold.sh').write_text(scaffold);(out/'verify.sh').write_text(verify)
if __name__=='__main__':
 a=argparse.ArgumentParser();a.add_argument('root',type=pathlib.Path);a.add_argument('pin');a.add_argument('out',type=pathlib.Path);x=a.parse_args();plan(x.root,x.pin,x.out)
