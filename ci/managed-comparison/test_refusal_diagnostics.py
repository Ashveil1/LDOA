import errno,json,pathlib,time,unittest
from unittest.mock import patch
import intercase_absence as a
import test_intercase_absence as fixtures
class Diagnostics(fixtures.Absence):
 def diagnostic(self,fn,stage,op,pid,kind,err):
  with self.assertRaises(a.Diagnostic) as x:fn()
  d=x.exception.fields
  self.assertEqual((d['stage'],d['operation'],d['pid'],d['kind'],d['errno']),(stage,op,pid,kind,err))
  out=json.dumps(d);self.assertNotIn('/private',out);self.assertNotIn('secret',out)
 def test_each_proc_read_permission(self):
  for field,stage,op in [('stat','process','stat-before'),('comm','process','comm'),('cmdline','family','cmdline')]:
   self.proc();real=a.read
   def faulty(p,cap):
    if p.name==field:raise PermissionError(errno.EACCES,'secret','/private/secret')
    return real(p,cap)
   with patch.object(a,'read',side_effect=faulty):self.diagnostic(lambda:a.scan(self.p,self.uid,time.monotonic()+2),stage,op,121,'permission',13)
   import shutil;shutil.rmtree(self.p/'121')
 def test_exe_owner_permission(self):
  self.proc()
  with patch.object(a.os,'readlink',side_effect=PermissionError(errno.EPERM,'secret','/private/secret')):
   self.diagnostic(lambda:a.scan(self.p,self.uid,time.monotonic()+2),'process','exe',121,'permission',1)
 def test_parse_stat_comm_cmdline(self):
  self.proc();real=a.read
  for field,stage,op in [('stat','process','stat-before'),('comm','process','comm'),('cmdline','family','cmdline')]:
   def faulty(p,cap):return b'\xff' if p.name==field else real(p,cap)
   with patch.object(a,'read',side_effect=faulty):self.diagnostic(lambda:a.scan(self.p,self.uid,time.monotonic()+2),stage,op,121,'parse',0)
 def test_poststat_and_race(self):
  self.proc();real=a.stat;calls=0
  def faulty(p):
   nonlocal calls
   calls+=1
   if calls==2:raise PermissionError(errno.EACCES,'secret','/private/secret')
   return real(p)
  with patch.object(a,'stat',side_effect=faulty):self.diagnostic(lambda:a.scan(self.p,self.uid,time.monotonic()+2),'family','stat-after',121,'permission',13)
 def test_proc_enumeration_and_owner(self):
  with patch.object(pathlib.Path,'iterdir',side_effect=PermissionError(errno.EACCES,'secret','/private/secret')):
   self.diagnostic(lambda:a.scan(self.p,self.uid,time.monotonic()+2),'process','enumerate',0,'permission',13)
  self.proc();real=pathlib.Path.stat
  def faulty(p,*args,**kw):
   if p.name=='121':raise PermissionError(errno.EACCES,'secret','/private/secret')
   return real(p,*args,**kw)
  with patch.object(pathlib.Path,'stat',faulty):self.diagnostic(lambda:a.scan(self.p,self.uid,time.monotonic()+2),'process','owner',121,'permission',13)
 def test_disappearance_distinct_no_permission_skip(self):
  self.proc();real=a.read
  def gone(p,cap):
   if p.name=='comm':raise FileNotFoundError(errno.ENOENT,'secret','/private/secret')
   return real(p,cap)
  with patch.object(a,'read',side_effect=gone):self.assertEqual(a.scan(self.p,self.uid,time.monotonic()+2),[])
  with self.assertRaises(a.Diagnostic) as x:a.call('process','comm',121,lambda:(_ for _ in ()).throw(FileNotFoundError(errno.ENOENT,'secret','/private/secret')))
  self.assertEqual(x.exception.fields['kind'],'disappearance')
 def test_network_sdk_adb_stages(self):
  (self.p/'net/tcp').write_bytes(b'\xff');self.diagnostic(lambda:a.ports(self.p),'ports','tcp-read',0,'parse',0)
  self.net();(self.p/'net/tcp6').write_text('bad');self.diagnostic(lambda:a.ports(self.p),'ports','tcp6-parse',0,'refusal',0)
  self.diagnostic(lambda:a.sdk_assert(self.sdk),'sdk','properties-read',0,'disappearance',2)
  self.diagnostic(lambda:a.adb_snapshot('/private/secret/nonexistent',time.monotonic()+1),'adb','spawn',0,'disappearance',2)
 def test_old_anchor_and_net_permission(self):
  self.proc(name='unrelated');real=a.stat
  def denied(p):
   if p.name=='121':raise PermissionError(errno.EACCES,'secret','/private/secret')
   return real(p)
  # Bypass scan only in fault fixture to isolate independent anchor-stat stage.
  with patch.object(a,'scan',return_value=[]),patch.object(a,'stat',side_effect=denied):self.diagnostic(self.obs,'anchor','old-stat',121,'permission',13)
  with patch.object(a,'read',side_effect=PermissionError(errno.EACCES,'secret','/private/secret')):self.diagnostic(lambda:a.ports(self.p),'ports','tcp-read',0,'permission',13)
 def test_identity_race_stage(self):
  self.proc();real=a.stat;calls=0
  def race(p):
   nonlocal calls
   d=real(p);calls+=1
   if calls==2:d['start']+=1
   return d
  with patch.object(a,'stat',side_effect=race):self.diagnostic(lambda:a.scan(self.p,self.uid,time.monotonic()+2),'family','identity',121,'race',0)
if __name__=='__main__':unittest.main()
