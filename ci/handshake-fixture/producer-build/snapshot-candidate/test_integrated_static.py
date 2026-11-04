import unittest,tempfile,os,time,pathlib,ast
from unittest.mock import patch
import integrated_static as I
from host_contract import Stop
class Integrated(unittest.TestCase):
 def test_reserve_within_shared_deadline(self):
  with tempfile.TemporaryDirectory() as d:
   fd=os.open(d,os.O_RDONLY|os.O_DIRECTORY)
   try:
    for remaining in [30,0,1201,float('nan')]:
     with self.assertRaisesRegex(Stop,'STATIC_DEADLINE_RESERVE'):I.Session(fd,remaining,clock=lambda:0).check('X')
    s=I.Session(fd,31,clock=lambda:0);s.check('X');self.assertEqual(s.phases[0]['remaining'],31)
   finally:os.close(fd)
 def test_config_preload_and_absence(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);(p/'etc/ld.so.conf.d').mkdir(parents=True);(p/'etc/ld.so.conf').write_bytes(b'config');(p/'etc/ld.so.preload').write_bytes(b'evil.so')
   fd=os.open(d,os.O_RDONLY|os.O_DIRECTORY)
   try:
    with self.assertRaisesRegex(Stop,'HOST_PRELOAD'):I.Session(fd,time.monotonic()+300).config()
    (p/'etc/ld.so.preload').write_bytes(b'');s=I.Session(fd,time.monotonic()+300);r=s.config();self.assertIsNone(r['files']['/etc/ld.so.cache']);s.finish()
   finally:os.close(fd)
 def test_missing_post_change_and_limit(self):
  with tempfile.TemporaryDirectory() as d:
   fd=os.open(d,os.O_RDONLY|os.O_DIRECTORY)
   try:
    s=I.Session(fd,time.monotonic()+300);self.assertIsNone(s.lookup('/absent'));pathlib.Path(d,'absent').write_bytes(b'x')
    with self.assertRaisesRegex(Stop,'HOST_CHANGED'):s.finish()
    s=I.Session(fd,time.monotonic()+300);s.attempts=256
    with self.assertRaisesRegex(Stop,'HOST_LOOKUP_CAP'):s.lookup('/x')
   finally:os.close(fd)
 def test_config_cap_before_read(self):
  with tempfile.TemporaryDirectory() as d:
   fd=os.open(d,os.O_RDONLY|os.O_DIRECTORY)
   try:
    p=pathlib.Path(d,'big');p.touch();p.write_bytes(b'x'*17)
    with self.assertRaisesRegex(Stop,'HOST_BINDING_BYTE_CAP'):I.Session(fd,time.monotonic()+300).lookup('/big',16)
   finally:os.close(fd)
 def test_standalone_namespace_caps_and_no_subprocess(self):
  p=pathlib.Path(__file__).parent/'standalone_helper.py';data=p.read_text();ns={'__name__':'synthetic'};exec(compile(data,'source','exec'),ns)
  self.assertEqual(ns['MAX_TOTAL'],600*1024**2);self.assertEqual(ns['HOST_MAX_TOTAL'],512*1024**2)
  self.assertIn('static_phase=integrated_static_phase',data)
  self.assertNotIn('import subprocess',data)
  self.assertIn("result['status']='STOP'",data)

 def test_snapshot_callback_failure_always_cleanup(self):
  from test_fd_lifecycle import ns,FD
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);src=p/'src';src.mkdir();fixture=FD();fixture.fixture(src);parent=p/'private';parent.mkdir();fd=os.open(parent,ns['FLAGS']);seen=[]
   def fail(snapshotfd,rows,image,deadline):
    seen.append(os.fstat(snapshotfd).st_mode&0o777)
    raise Stop('STATIC_SYNTHETIC_FAIL')
   try:out=ns['snapshot_only'](src,fd,fixture.image(),time.monotonic()+300,os.getuid(),static_phase=fail)
   finally:os.close(fd)
   self.assertEqual(seen,[0o555]);self.assertEqual(out['status'],'STOP');self.assertEqual(out['cleanup'],'ABSENT');self.assertEqual(list(parent.iterdir()),[])
 def test_snapshot_callback_success_reverify_and_cleanup(self):
  from test_fd_lifecycle import ns,FD
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);src=p/'src';src.mkdir();fixture=FD();fixture.fixture(src);parent=p/'private';parent.mkdir();fd=os.open(parent,ns['FLAGS'])
   try:out=ns['snapshot_only'](src,fd,fixture.image(),time.monotonic()+300,os.getuid(),static_phase=lambda *a:{'status':'SYNTHETIC_ONLY'})
   finally:os.close(fd)
   self.assertEqual(out['static_consistency']['status'],'SYNTHETIC_ONLY');self.assertEqual(out['cleanup'],'ABSENT')
