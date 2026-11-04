import unittest,pathlib,tempfile,io,tarfile,time
from guest_data_only import inventory_tar,Stop
from qualify_static import canonical,resolve
from entry import cleanup
class Integration(unittest.TestCase):
 def test_usrmerge_resolver_and_no_host_fallback(self):
  r={'usr/lib/x86_64-linux-gnu/a.so.1':{'type':'file'}}
  self.assertEqual(resolve('lib/x86_64-linux-gnu/a.so.1',r),'usr/lib/x86_64-linux-gnu/a.so.1')
  with self.assertRaises(Stop):resolve('/usr/lib/host-only.so',r)
 def test_cleanup_owned_root_only(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d)/'owned';root.mkdir();(root/'x').write_text('x');cleanup(root);self.assertFalse(root.exists());self.assertTrue(pathlib.Path(d).exists())
 def test_selected_hardlink_rejected(self):
  b=io.BytesIO()
  with tarfile.open(fileobj=b,mode='w') as t:
   x=tarfile.TarInfo('usr/include/link.h');x.type=tarfile.LNKTYPE;x.linkname='usr/include/x.h';t.addfile(x)
  with tempfile.TemporaryDirectory() as d,tarfile.open(fileobj=io.BytesIO(b.getvalue())) as t,self.assertRaises(Stop):inventory_tar(t,'p',{},pathlib.Path(d),0)
 def test_deadline_and_no_compiler_entry_contract(self):
  s=pathlib.Path(__file__).with_name('guest_data_only.py').read_text();e=pathlib.Path(__file__).with_name('entry.py').read_text()
  self.assertIn("time.monotonic()+600",s);self.assertIn('timeout=600',e);self.assertIn('os.killpg(child.pid,signal.SIGKILL)',e);self.assertIn('start_new_session=True',e);self.assertIn('finally:',e);self.assertIn('cleanup(root)',e)
  for forbidden in ['build_selected','clang','ld.lld','ldd','apt-get']:self.assertNotIn(forbidden,e)
if __name__=='__main__':unittest.main()

class DeadlineAndStatic(unittest.TestCase):
 def test_readelf_deadline_prevents_launch(self):
  from qualify_static import qualify
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);(p/'elf').write_bytes(b'\x7fELF')
   with self.assertRaises(Stop):qualify({'elf':{'path':'elf','type':'file','sha256':'synthetic','package':'p'}},p,time.monotonic()-1)
 def test_malformed_archive_stops(self):
  with self.assertRaises(tarfile.ReadError):tarfile.open(fileobj=io.BytesIO(b'not-a-tar'))
 def test_static_closure_reports_missing_without_execution(self):
  import unittest.mock as m
  from qualify_static import qualify
  def fake(command,**kw):
   kw['stdout'].write(b'Machine: Advanced Micro Devices X86-64\n (NEEDED) Shared library: [missing.so]\n')
   return type('R',(),{'returncode':0})()
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);(p/'elf').write_bytes(b'\x7fELF')
   with m.patch('qualify_static.subprocess.run',fake):result=qualify({'elf':{'path':'elf','type':'file','sha256':'synthetic','package':'p'}},p,time.monotonic()+5)
   self.assertEqual(result['missing'][0]['needed'],'missing.so');self.assertFalse(result['runtime_asset_ready'])
