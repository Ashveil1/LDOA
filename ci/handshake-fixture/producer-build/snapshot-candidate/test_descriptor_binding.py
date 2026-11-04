import unittest,tempfile,pathlib,os,time,struct
from unittest.mock import patch
from descriptor_binding import authenticated_row,capture,FLAGS
from host_contract import Stop,parse_elf,closure
from test_host_contract import elf,P
import test_host_contract as fixtures
from resolution_policy import POLICY
class Binding(unittest.TestCase):
 def test_relative_and_absolute_alias_components(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);(r/'real').mkdir();(r/'real/x').write_bytes(b'x');(r/'a').symlink_to('real');(r/'b').symlink_to('/real/x');fd=os.open(d,FLAGS)
   try:
    row,receipt=authenticated_row(fd,'/a/x',time.monotonic()+5);self.assertEqual(row['canonical'],'/real/x');self.assertEqual(len(row['aliases']),1);self.assertTrue(receipt['components'])
    row,_=authenticated_row(fd,'/b',time.monotonic()+5);self.assertEqual(row['data'],b'x')
   finally:os.close(fd)
 def test_escape_cycle_missing(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);(r/'escape').symlink_to('../outside');(r/'cycle').symlink_to('cycle');fd=os.open(d,FLAGS)
   try:
    for path in ['/escape','/cycle']:
     with self.assertRaises(Stop):authenticated_row(fd,path,time.monotonic()+5)
    with self.assertRaises(FileNotFoundError):authenticated_row(fd,'/missing',time.monotonic()+5)
   finally:os.close(fd)
 def test_cap_deadline_setid(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);(r/'x').write_bytes(b'xx');fd=os.open(d,FLAGS)
   try:
    with self.assertRaisesRegex(Stop,'HOST_BINDING_BYTE_CAP'):capture(fd,'/x',time.monotonic()+5,1)
    with self.assertRaisesRegex(Stop,'DEADLINE'):capture(fd,'/x',0)
    (r/'x').chmod(0o4755)
    with self.assertRaisesRegex(Stop,'HOST_TERMINAL_TYPE'):capture(fd,'/x',time.monotonic()+5)
   finally:os.close(fd)
 def test_interpass_mutation_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);(r/'x').write_bytes(b'x');fd=os.open(d,FLAGS)
   import descriptor_binding as b
   original=b.capture;calls=[]
   def changed(*a,**kw):
    if calls:(r/'x').write_bytes(b'y')
    calls.append(True);return original(*a,**kw)
   try:
    with patch.object(b,'capture',changed):
     with self.assertRaisesRegex(Stop,'HOST_BINDING_CHANGED'):authenticated_row(fd,'/x',time.monotonic()+5)
   finally:os.close(fd)
 def test_edgecap_before_append(self):
  import host_contract as h
  with patch.object(h,'MAX_EDGES',0):
   with self.assertRaisesRegex(Stop,'HOST_EDGE_CAP'):closure({'clang21':P+'clang-21','lld':P+'lld'},fixtures.Host().mapping().get)
 def test_exact_string4096_and_4097(self):
  # Construct valid dynamic stringtable at known offset; large enough for exact length.
  def fixture(n):
   data=bytearray(elf(['x']));data.extend(b'\0'*5000);data[512:512+n+2]=b'\0'+b'a'*n+b'\0';struct.pack_into('<Q',data,256+32+8,n+2);struct.pack_into('<Q',data,64+32,len(data));struct.pack_into('<Q',data,64+40,len(data));return bytes(data)
  self.assertEqual(len(parse_elf(fixture(4096))['needed'][0]),4096)
  with self.assertRaisesRegex(Stop,'ELF_STRING'):parse_elf(fixture(4097))
 def test_one_mib_strtable_cap(self):
  data=bytearray(elf(['x']));struct.pack_into('<Q',data,256+32+8,1024*1024+1)
  with self.assertRaisesRegex(Stop,'STRTAB'):parse_elf(bytes(data))
 def test_policy_not_loader_proof(self):self.assertFalse(POLICY['execution_authority']);self.assertIn('POSSIBLE',POLICY['failure'])
if __name__=='__main__':unittest.main()
