"""Actual incoming-transfer shell with finite fake ADB, synthetic bytes only."""
import unittest,pathlib,tempfile,subprocess,os,json,hashlib,shutil
from unittest.mock import patch
import directory_plan as d
P=pathlib.Path(__file__).parent
class Pipeline(unittest.TestCase):
 def execute(self,mode):
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);r=pathlib.Path(t.name);host=r/'host';host.mkdir();app=r/'app';app.mkdir();tmp=r/'tmpincoming';b=r/'bin';b.mkdir();out=r/'comparison-evidence/original';out.mkdir(parents=True)
  for n in ['payload/bin/fixture','source/orig.tar.xz','source/debian.tar.xz','source/default-size.patch']:
   f=host/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_text('SYNTHETIC NONEXECUTABLE')
  (host/'payload/lib/pcmanfm').mkdir(parents=True);files={str(f.relative_to(host)):hashlib.sha256(f.read_bytes()).hexdigest() for f in host.rglob('*') if f.is_file()};dirs=[str(f.relative_to(host)) for f in host.rglob('*') if f.is_dir()];raw=json.dumps({'schema':1,'case':'original','prefix':'/opt/ldoa/pcmanfm/1.3.2-4build2-floor1','files':files,'directories':dirs,'bytes':sum(f.stat().st_size for f in host.rglob('*') if f.is_file())}).encode();(host/'manifest.json').write_bytes(raw);pin=hashlib.sha256(raw).hexdigest();plans=r/'plans'
  with patch.multiple(d,ORIG=files['source/orig.tar.xz'],DEB=files['source/debian.tar.xz'],PATCH=files['source/default-size.patch']):d.plan(host,pin,plans)
  # Planner already tested actual. Stub merely installs the prevalidated plans.
  f=b/'python3';f.write_text('#!/bin/sh\nmkdir "$4" && /bin/cp "$PLANS/"* "$4/"\n');f.chmod(0o700)
  for n,script in [('id','printf 10123'),('stat','v=$(/usr/bin/stat "$@") || exit $?; printf "%s\\n" "${v%:*:*}:10123:10123"')]:
   f=b/n;f.write_text('#!/bin/sh\n'+script+'\n');f.chmod(0o700)
  f=b/'cp';f.write_text('#!/bin/sh\nif [ \"$MODE\" = copyfail ]; then exit 37; fi\n/bin/cp \"$@\" || exit $?\nif [ \"$MODE\" = privatecorrupt ]; then printf bad > files/managed-incoming/payload/bin/fixture; fi\n');f.chmod(0o700)
  f=b/'adb';f.write_text('''#!/usr/bin/python3
import pathlib,sys,os,subprocess,shutil
args=sys.argv[1:];tmp=pathlib.Path(os.environ['TMPIN']);app=pathlib.Path(os.environ['APP']);mode=os.environ['MODE']
if 'push' in args:
 if mode=='pushfail':sys.exit(31)
 for src in args[args.index('push')+1:-1]:
  p=pathlib.Path(src)
  if p.is_file():shutil.copyfile(p,tmp/p.name)
  else:
   for f in p.rglob('*'):
    if f.is_file():target=tmp/p.name/f.relative_to(p);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,target)
 if mode=='missingempty':(tmp/'payload/lib/pcmanfm').rmdir()
 if mode=='mutated':(tmp/'payload/bin/fixture').write_text('bad')
 sys.exit(0)
if '-T' in args:
 s=sys.stdin.read().replace('/data/local/tmp/ldoa-managed-incoming',str(tmp)).replace('/data/data/dev.phonelinux.proto',str(app)).replace('/data/user/0/dev.phonelinux.proto',str(app))
 tail=args[args.index('-T')+1:]
 if tail==['sh']:command=['sh','-c',s];cwd=app
 elif tail[:2]==['sh','-s']:command=['sh','-c',s,'sh',str(tmp)];cwd=app
 else:command=['sh','-c',s];cwd=app
 if mode=='identitylate' and s.startswith('set -e') and 'cp -R' in s:cwd=app.parent
 sys.exit(subprocess.run(command,cwd=cwd).returncode)
# Original read-only parent/incoming/tmp guards, executed after path remapping.
s=args[-1].replace('/data/local/tmp/ldoa-managed-incoming',str(tmp))
if s.startswith('run-as dev.phonelinux.proto sh -c '):
 import shlex
 s=shlex.split(s)[-1]
sys.exit(subprocess.run(['sh','-c',s],cwd=app).returncode)
''');f.chmod(0o700)
  if mode=='existing':tmp.mkdir()
  env={**os.environ,'PATH':str(b)+':'+os.environ['PATH'],'GITHUB_WORKSPACE':str(r),'MANAGED_TRANSFER_CASE':'original','DISPOSABLE_TRANSFER_APPROVED':'yes','PLANS':str(plans),'APP':str(app),'HOME':str(app),'TMPIN':str(tmp),'MODE':mode}
  x=subprocess.run(['bash',str(P/'incoming-transfer.sh'),str(host),pin,'emulator-5554'],env=env,capture_output=True,text=True);return x,app,out
 def test_actual_pipeline(self):
  x,app,out=self.execute('ok');self.assertEqual(x.returncode,0,x.stderr);self.assertTrue((app/'files/managed-incoming/payload/lib/pcmanfm').is_dir());self.assertIn('guard=private-copy rc=0',(out/'transfer-guards.txt').read_text())
 def test_private_copy_faults(self):
  for mode in ['copyfail','privatecorrupt','identitylate']:
   with self.subTest(mode=mode):
    x,app,out=self.execute(mode);self.assertNotEqual(x.returncode,0);receipt=(out/'transfer-guards.txt').read_text();self.assertRegex(receipt,r'guard=private-copy rc=[1-9]');self.assertNotIn('guard=private-copy rc=0',receipt)
    if mode=='identitylate':self.assertFalse((app/'files').exists())
 def test_actual_pipeline_faults(self):
  for mode,label in [('existing','tmp-absent'),('pushfail','push'),('missingempty','tmp-inventory'),('mutated','tmp-inventory')]:
   with self.subTest(mode=mode):
    x,app,out=self.execute(mode);self.assertNotEqual(x.returncode,0);self.assertFalse((app/'files').exists());receipt=(out/'transfer-guards.txt').read_text();self.assertRegex(receipt,'guard='+label+r' rc=[1-9]');self.assertNotIn('guard=private-copy',receipt)
if __name__=='__main__':unittest.main()
