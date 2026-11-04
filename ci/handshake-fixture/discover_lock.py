#!/usr/bin/env python3
"""Read-only authenticated cached Noble index discovery. Never installs packages."""
import hashlib,json,pathlib,subprocess,re,sys
root=pathlib.Path(sys.argv[1]);lists=root/'var/lib/apt/lists';records=[];indexes=[]
for release in sorted(lists.glob('*InRelease')):
 subprocess.run(['gpgv','--keyring',str(root/'usr/share/keyrings/ubuntu-archive-keyring.gpg'),str(release)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 text=release.read_text();prefix=release.name[:-len('InRelease')]
 for idx in lists.glob(prefix+'*binary-amd64_Packages'):
  rel=idx.name[len(prefix):].replace('_','/');b=idx.read_bytes();sha=hashlib.sha256(b).hexdigest()
  if not re.search(r'^ '+sha+r'\s+'+str(len(b))+r'\s+'+re.escape(rel)+r'$',text,re.M):continue
  indexes.append({'release':release.name,'release_sha256':hashlib.sha256(release.read_bytes()).hexdigest(),'index':rel,'sha256':sha,'bytes':len(b)})
  for block in b.decode().split('\n\n'):
   d={l.split(': ',1)[0]:l.split(': ',1)[1] for l in block.splitlines() if ': ' in l and not l.startswith(' ')}
   if all(k in d for k in ['Package','Version','Filename','SHA256','Size']):records.append(d)
wanted=['libglib2.0-0t64','libc6','libffi8','libpcre2-8-0','libmount1','libselinux1','zlib1g','libblkid1','libglib2.0-dev','libglib2.0-dev-bin','libc6-dev','gcc-13','gcc-13-base','gcc-13-x86-64-linux-gnu','pkgconf','pkgconf-bin','binutils','binutils-x86-64-linux-gnu','libgcc-13-dev']
packages=[];missing=[]
for name in wanted:
 candidates=[d for d in records if d['Package']==name];cached=[]
 for d in candidates:
  for f in (root/'var/cache/apt/archives').glob(name+'_*.deb'):
   if f.stat().st_size==int(d['Size']) and hashlib.sha256(f.read_bytes()).hexdigest()==d['SHA256']:cached.append((d,f.name))
 cached=list({(d['Package'],d['Version'],d['SHA256']):(d,n) for d,n in cached}.values())
 if len(cached)!=1:missing.append({'package':name,'authenticated_cached_matches':len(cached)});continue
 d,n=cached[0];packages.append({'package':name,'version':d['Version'],'url':'https://archive.ubuntu.com/ubuntu/'+d['Filename'],'sha256':d['SHA256'],'bytes':int(d['Size']),'cached_filename':n})
print(json.dumps({'schema':1,'scope':'AUTHENTICATED_CACHED_PACKAGE_DISCOVERY_NOT_COMPLETE_BUILD_CLOSURE','complete':False,'indexes':indexes,'packages':packages,'missing_or_ambiguous':missing,'blockers':['Compiler transitive closure not frozen','GIO backend runtime dependency proof pending','Authenticated indexes may be older than dispatch; availability must be rechecked without silently repinning']},indent=2))
