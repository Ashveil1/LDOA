import unittest,tempfile,pathlib,subprocess,os
P=pathlib.Path(__file__).parent
class Identity(unittest.TestCase):
 def runbody(self,variant):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);home=p/'pkg';home.mkdir();other=p/'other';other.mkdir();bin=p/'bin';bin.mkdir()
   f=bin/'id';f.write_text('#!/bin/sh\nprintf "%s\\n" '+('0' if variant=='root' else '10123')+'\n');f.chmod(0o700)
   f=bin/'stat';f.write_text('''#!/bin/sh
'''+('exit 8\n' if variant=='stat-fail' else 'v=$(/usr/bin/stat "$@") || exit $?\nprintf "%s\\n" "${v%:*:*}:'+('99999:99999' if variant=='owner' else '10123:10123')+'"\n'));f.chmod(0o700)
   body=(P/'identity-body.sh').read_text().replace('/data/data/dev.phonelinux.proto',str(home)).replace('/data/user/0/dev.phonelinux.proto',str(p/'alias'))
   path=home
   if variant=='symlink':path=p/'alias';path.symlink_to(home)
   if variant=='unknown':path=other
   if variant=='file':home.rmdir();home.write_text('no')
   cwd=other if variant in ('mismatch','file') else home
   x=subprocess.run(['sh','-c',body],cwd=cwd,env={**os.environ,'HOME':str(path),'PATH':str(bin)+':'+os.environ['PATH']},capture_output=True,text=True);return x
 def test_accept_identity(self):self.assertEqual(self.runbody('ok').returncode,0)
 def test_refuse_variants(self):
  for name,rc in [('unknown',73),('symlink',74),('file',74),('root',75),('stat-fail',71),('mismatch',76),('owner',77)]:
   with self.subTest(name=name):x=self.runbody(name);self.assertEqual(x.returncode,rc,x.stderr);self.assertEqual(x.stdout,'')
if __name__=='__main__':unittest.main()
