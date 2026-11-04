import unittest,ast,pathlib
from host_contract import Stop
from static_diagnostic import StaticPhaseStop,HOST_REASONS
class Diagnostic(unittest.TestCase):
 def test_known_stop_no_path(self):
  e=StaticPhaseStop(Stop('STRTAB'),'CLOSURE');self.assertEqual(e.args,('STRTAB',));self.assertEqual(e.checkpoint['phase'],'CLOSURE')
 def test_unknown_error_text_never_escapes(self):
  for e in [ValueError('/private/secret'),Stop('/private/secret'),OSError('private')]:
   out=StaticPhaseStop(e,'unknown');self.assertEqual(out.args,('STATIC_UNCLASSIFIED_STOP',));self.assertNotIn('private',str(out.checkpoint));self.assertEqual(out.checkpoint['phase'],'ENTER')
 def test_all_literal_host_stop_codes_enumerated(self):
  r=pathlib.Path(__file__).parent;found=set()
  for n in ['host_contract.py','descriptor_binding.py','integrated_static.py']:
   for node in ast.walk(ast.parse((r/n).read_text())):
    if isinstance(node,ast.Call) and isinstance(node.func,ast.Name) and node.func.id=='Stop' and node.args and isinstance(node.args[0],ast.Constant):found.add(node.args[0].value)
  self.assertEqual(found|{'HOST_PATH'},HOST_REASONS)
 def test_snapshot_cleanup_keeps_sanitized_checkpoint(self):
  import tempfile,time,os
  from test_fd_lifecycle import ns,FD
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);src=p/'src';src.mkdir();f=FD();f.fixture(src);parent=p/'private';parent.mkdir();fd=os.open(parent,ns['FLAGS'])
   # ns Stop belongs to snapshot module, match bundled inheritance in synthetic wrapper.
   Wrapper=type('StaticPhaseStop',(ns['Stop'],),{})
   error=Wrapper('STRTAB');error.checkpoint={'phase':'CLOSURE','reason':'STRTAB','status':'STATIC_STOP_PARTIAL_NOT_QUALIFIED'}
   def fail(*a):raise error
   try:out=ns['snapshot_only'](src,fd,f.image(),time.monotonic()+300,os.getuid(),static_phase=fail)
   finally:os.close(fd)
   self.assertEqual(out['status'],'STOP');self.assertEqual(out['cleanup'],'ABSENT');self.assertEqual(out['static_checkpoint']['reason'],'STRTAB')
