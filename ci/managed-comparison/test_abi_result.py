import unittest
import abi_result as a
class ABI(unittest.TestCase):
 def setUp(self):
  self.h="ELF64 2's complement, little endian Advanced Micro Devices X86-64\n[Requesting program interpreter: /lib64/ld-linux-x86-64.so.2]\n"+'\n'.join('(NEEDED) Shared library: ['+n+']' for n in sorted(a.REQUIRED))
  self.l='\n'.join(n+' => /lib/x86_64-linux-gnu/'+n+' (0x123)' for n in sorted(a.REQUIRED))
 def call(self,h=None,l=None,**kw):return a.validate(h or self.h,l or self.l,**dict(dict(rc=0,eof=True,overflow=False,guest_arch='amd64',manifest_verified=True),**kw))
 def test_pass(self):self.assertEqual(len(self.call()),14)
 def test_capture(self):
  for kw in [dict(rc=1),dict(eof=False),dict(overflow=True),dict(guest_arch='arm64'),dict(manifest_verified=False)]:
   with self.assertRaises(ValueError):self.call(**kw)
 def test_error_rczero(self):
  for suffix in ['not found','undefined symbol: x','wrong ELF','version GLIBC_999 not found','cannot open file']:
   with self.assertRaises(ValueError):self.call(l=self.l+'\n'+suffix)
 def test_missing(self):
  with self.assertRaises(ValueError):self.call(l=self.l.split('\n',1)[1])
 def test_path(self):
  with self.assertRaises(ValueError):self.call(l=self.l.replace('/lib/','/tmp/'))
 def test_interp(self):
  with self.assertRaises(ValueError):self.call(h=self.h.replace('/lib64/ld-linux-x86-64.so.2','/host/loader'))
 def test_needed(self):
  with self.assertRaises(ValueError):self.call(h=self.h+'\n(NEEDED) [extra.so]')
if __name__=='__main__':unittest.main()
