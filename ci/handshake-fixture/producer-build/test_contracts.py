import unittest,tempfile,pathlib,json
from contracts import Stop,depfile,trace_inputs,digest,command_plan
from entry import tool_gate
from rooted import rooted_closure
class Contracts(unittest.TestCase):
 def test_depfile_escape(self):
  self.assertEqual(depfile('producer.o: /root/a\\ b.h /root/c\\#d.h \\\n /root/d$$e.h'),['/root/a b.h','/root/c#d.h','/root/d$e.h'])
  for bad in ['broken','a:','a: $UNTRUSTED','a: /x #unescaped']:
   with self.assertRaises(Stop):depfile(bad)
 def test_trace_unknown_rejected_and_archive_bound(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'lib.a';p.write_bytes(b'archive');allowed={str(p):{'sha256':digest(p),'scope':'SELECTED_SYSROOT'}}
   self.assertEqual(trace_inputs(str(p)+'(x.o)',allowed,'/object')[0]['archive_member'],'x.o')
   with self.assertRaises(Stop):trace_inputs('/usr/lib/host.a',allowed,'/object')
 def test_tool_policy_closed(self):
  with self.assertRaises(Stop):tool_gate({'status':'UNRESOLVED_NO_COMPILER_AUTHORITY'})
 def test_explicit_no_driver_link_discovery(self):
  pre,comp,link=command_plan('/ndk/clang','/ndk/ld.lld','/ndk/resource','/private/sysroot','/source/producer.c','/out')
  self.assertIn('-nostdinc',pre);self.assertEqual(link[0],'/ndk/ld.lld');self.assertIn('/private/sysroot/usr/lib/x86_64-linux-gnu/crt1.o',link)
  for command in [pre,comp,link]:
   for arg in command:self.assertNotIn('/usr/lib/gcc',arg.removeprefix('/private/sysroot')) if arg.startswith('/usr/lib') else None
 def test_rooted_loader_required(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'producer';p.write_bytes(b'elf')
   with self.assertRaises(Stop):rooted_closure(p,d,{'files':[]},'readelf',10,lambda *a:'ELF64 Advanced Micro Devices X86-64')
 def test_source_include_scope(self):
  source=(pathlib.Path(__file__).resolve().parents[1]/'native/producer.c').read_text()
  self.assertIn('fflush(stdout)',source);self.assertIn('#include <stdio.h>',source);self.assertIn('#include "request_protocol.h"',source)
if __name__=='__main__':unittest.main()

class NegativeGates(unittest.TestCase):
 def test_unrecorded_sysroot_file(self):
  from contracts import bind_manifest
  with tempfile.TemporaryDirectory() as d:
   (pathlib.Path(d)/'extra').write_bytes(b'extra')
   with self.assertRaisesRegex(Stop,'UNRECORDED_SYSROOT_FILE'):bind_manifest(d,{'files':[]})
 def test_staged_link_escape(self):
  from contracts import bind_manifest
  with tempfile.TemporaryDirectory() as d:
   (pathlib.Path(d)/'escape').symlink_to('/etc/passwd')
   with self.assertRaisesRegex(Stop,'STAGED_LINK_ESCAPE'):bind_manifest(d,{'files':[]})
 def test_rooted_member_pin_mismatch(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);(p/'producer').write_bytes(b'producer');lib=p/'usr/lib/x86_64-linux-gnu/libx.so';lib.parent.mkdir(parents=True);lib.write_bytes(b'changed')
   text='ELF64 Advanced Micro Devices X86-64\nRequesting program interpreter: /lib64/ld-linux-x86-64.so.2]\n(NEEDED) [libx.so]'
   m={'files':[{'path':'usr/lib/x86_64-linux-gnu/libx.so','type':'file','sha256':'wrong'}]}
   with self.assertRaisesRegex(Stop,'ROOTED_MEMBER_PIN'):rooted_closure(p/'producer',p,m,'readelf',10,lambda *a:text)
 def test_rooted_loader_pin_mismatch(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);(p/'producer').write_bytes(b'producer');loader=p/'usr/lib64/ld-linux-x86-64.so.2';loader.parent.mkdir(parents=True);loader.write_bytes(b'changed')
   text='ELF64 Advanced Micro Devices X86-64\nRequesting program interpreter: /lib64/ld-linux-x86-64.so.2]'
   m={'files':[{'path':'usr/lib64/ld-linux-x86-64.so.2','type':'file','sha256':'wrong'}]}
   with self.assertRaisesRegex(Stop,'ROOTED_LOADER_PIN'):rooted_closure(p/'producer',p,m,'readelf',10,lambda *a:text)
 def test_missing_readelf_clean_stop_and_durable_receipt(self):
  from unittest.mock import patch
  import entry
  # Reach readelf check without touching or running any host compiler.
  policy={'status':'REVIEWED_EXACT_RUNNER_TOOL_AND_RESOURCE_POLICY','ndk_version':'29.0.14206865','files':[{'path':'/usr/local/lib/android/sdk/ndk/29.0.14206865/clang','sha256':'h','scope':'tool'}],'clang':'/usr/local/lib/android/sdk/ndk/29.0.14206865/clang','linker':'/usr/local/lib/android/sdk/ndk/29.0.14206865/clang','resource_dir':'/usr/local/lib/android/sdk/ndk/29.0.14206865/resource','host_dependencies':'EXPLICIT_TRUSTED_RUNNER_OS_DYNAMIC_LOADER_AND_LIBRARIES','readelf':'/absent/readelf'}
  original=pathlib.Path.resolve
  def resolve(p,*a,**k):
   if str(p)=='/absent/readelf':raise FileNotFoundError()
   return p
  with patch('pathlib.Path.resolve',resolve),patch('entry.digest',return_value='h'),patch('pathlib.Path.rglob',return_value=iter([])):
   with self.assertRaisesRegex(Stop,'READELF_MISSING'):tool_gate(policy)
  with tempfile.TemporaryDirectory() as d:
   out=pathlib.Path(d)/'fresh'
   with patch('entry.main',side_effect=Stop('READELF_MISSING')),patch('sys.argv',['entry.py','--out',str(out)]):self.assertEqual(entry.cli(),1)
   receipt=json.loads((out/'producer-build-receipt.json').read_text());self.assertEqual(receipt['reason'],'READELF_MISSING');self.assertFalse(receipt['compiler_executed'])
