import unittest,pathlib,tempfile
from unittest.mock import patch
from tool_gate import qualify_tools,unchanged
from guest_data_only import Stop
class ToolGate(unittest.TestCase):
 def test_policy_is_compatible_family_not_local_sha(self):
  import tool_gate
  self.assertEqual(tool_gate.POLICY[0][2],'1.21.1ubuntu');self.assertEqual(tool_gate.POLICY[1][2],'2.38-4ubuntu')
  source=pathlib.Path(tool_gate.__file__).read_text();self.assertNotIn('04db0000',source);self.assertIn('package_file_md5_matched',source)
 def test_runtime_binding_unchanged_and_tamper_stop(self):
  import hashlib
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'tool';p.write_bytes(b'a');rows=[{'path':str(p),'realpath':str(p.resolve()),'sha256':hashlib.sha256(b'a').hexdigest()}]
   unchanged(rows);p.write_bytes(b'b')
   with self.assertRaises(Stop):unchanged(rows)
if __name__=='__main__':unittest.main()
