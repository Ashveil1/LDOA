import unittest,json,pathlib
from build_guest import safe_path,verify
class Contract(unittest.TestCase):
 def test_discovery_not_frozen(self):
  with self.assertRaises(ValueError):verify(json.loads(pathlib.Path(__file__).with_name('deps.discovery.json').read_text()))
 def test_paths(self):
  for p in ['/etc/passwd','../x','a/../../x','x\x00y']:
   self.assertFalse(safe_path(p))
  self.assertTrue(safe_path('usr/lib/x86_64-linux-gnu/libgio.so'))
 def test_case_inventory(self):
  c=json.loads(pathlib.Path(__file__).with_name('cases.json').read_text());self.assertEqual(len(c),17);self.assertEqual(len({r['case'] for r in c}),17);self.assertTrue(all(r['deadline_ms']==5000 for r in c))
 def test_failclosed_stubs(self):
  base=pathlib.Path(__file__).parent
  for n in ['build_fixture.sh','run_fixture.sh']:self.assertIn('exit 78',(base/n).read_text())
 def test_schema_inventory_parity(self):
  base=pathlib.Path(__file__).parent
  schema=json.loads((base/'result_schema.json').read_text())
  names=schema['properties']['cases']['items']['properties']['case']['enum']
  self.assertEqual(names,[r['case'] for r in json.loads((base/'cases.json').read_text())])
 def test_jni_noreplace(self):
  s=(pathlib.Path(__file__).parent/'fixture/src/main/cpp/safe_files.c').read_text();self.assertIn('SYS_renameat2',s);self.assertIn('RENAME_NOREPLACE',s);self.assertNotIn('linkat(fd,a',s);self.assertIn('O_NOFOLLOW',s)
if __name__=='__main__':unittest.main()
