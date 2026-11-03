import pathlib,tempfile,subprocess,os,shutil,unittest
P=pathlib.Path(__file__).parent
class Recipe(unittest.TestCase):
 def run_recipe(self,fail=False):
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);r=pathlib.Path(t.name)/'repo';d=r/'ci/managed-comparison';d.mkdir(parents=True);shutil.copy(P/'build-and-test.sh',d/'build-and-test.sh');(d/'default-size.patch').write_text('SYNTHETIC');e=pathlib.Path(t.name)/'private';e.mkdir();stub=pathlib.Path(t.name)/'stubs';stub.mkdir()
  envfile=stub/'env';envfile.write_text('''function .() { if [[ $1 == /etc/os-release ]]; then ID=ubuntu;VERSION_ID=24.04;else builtin . "$@";fi; }
export -f .
''')
  code='''#!/usr/bin/env python3
import os,sys,pathlib
name=pathlib.Path(sys.argv[0]).name;a=sys.argv[1:];cwd=pathlib.Path.cwd()
if name=='curl':pathlib.Path(a[a.index('-o')+1]).write_text('SYNTHETIC')
elif name=='tar':
 if 'orig.tar.xz' in a:
  q=pathlib.Path(a[a.index('-C')+1])/'pcmanfm-1.3.2';(q/'src').mkdir(parents=True);(q/'COPYING').write_text('fixture');(q/'src/desktop.c').write_text('fixture');(q/'configure').write_text('#!/bin/sh\\nexit 0\\n');(q/'configure').chmod(0o700)
 else:(cwd/'debian').mkdir();(cwd/'debian/copyright').write_text('fixture')
elif name=='make':
 if os.environ.get('STUB_FAIL')=='yes' and '-j2' in a:sys.exit(23)
 for v in a:
  if v.startswith('DESTDIR='):
   q=pathlib.Path(v.split('=',1)[1])/'opt/ldoa/pcmanfm/1.3.2-4build2-floor1';(q/'bin').mkdir(parents=True);(q/'lib/pcmanfm').mkdir(parents=True);(q/'share/pcmanfm/ui').mkdir(parents=True);(q/'bin/pcmanfm').write_text('NONEXECUTABLE FIXTURE');(q/'share/pcmanfm/ui/desktop-pref.ui').write_text('fixture')
elif name=='python3':
 if len(a)==2:(pathlib.Path(a[1])/'source-record.txt').write_text('fixture')
 else:(pathlib.Path(a[1])/'resource-manifest.json').write_text('{}')
else:print('synthetic stub')
'''
  for n in ['curl','gpgv','tar','make','sha256sum','python3','gcc','ld','pkg-config','dpkg-query','patch','file','readelf']:
   f=stub/n;f.write_text(code.replace('#!/usr/bin/env python3','#!'+os.sys.executable));f.chmod(0o700)
  # Invoke EXACT actual script through relative ci/... path from repo root.
  res=subprocess.run(['bash','ci/managed-comparison/build-and-test.sh'],cwd=r,env={**os.environ,'PATH':str(stub)+':'+os.environ['PATH'],'BASH_ENV':str(envfile),'FLOOR_EVIDENCE':str(e),'FIXED_PREFIX_REVIEWED_BUILD':'yes','STUB_FAIL':'yes' if fail else 'no'},capture_output=True,text=True)
  return res,r,e
 def test_relative_invocation_late_copy(self):
  x,r,e=self.run_recipe();self.assertEqual(x.returncode,0,x.stderr+x.stdout);local=pathlib.Path((e/'runner-local-location.txt').read_text().strip());self.assertEqual((local/'build-recipe.sh').read_bytes(),(r/'ci/managed-comparison/build-and-test.sh').read_bytes());self.assertEqual((e/'build-recipe.sh').read_bytes(),(local/'build-recipe.sh').read_bytes());self.assertEqual((r/'comparison-evidence/host-phase.txt').read_text(),'schema=1\nphase=complete case=both rc=0\n')
 def test_failed_build_receipt(self):
  x,r,e=self.run_recipe(True);self.assertEqual(x.returncode,23);self.assertEqual((r/'comparison-evidence/host-phase.txt').read_text(),'schema=1\nphase=build case=original rc=23\n')
if __name__=='__main__':unittest.main()
