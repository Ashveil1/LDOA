import unittest,tempfile,pathlib,os,time,json
from unittest.mock import patch
import intercase_absence as a
class Absence(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();self.addCleanup(self.t.cleanup);self.p=pathlib.Path(self.t.name);(self.p/'net').mkdir();self.uid=os.getuid();self.sdk=self.p/'sdk';self.old={'pid':121,'start':99,'uid':self.uid,'exe':str(self.sdk/'emulator/qemu-system-x86_64'),'state':'S'}
  self.net();self.adb=self.p/'adb';self.adbwrite('printf "List of devices attached\\n\\n"')
 def adbwrite(self,body):self.adb.write_text('#!/bin/sh\n'+body+'\n');self.adb.chmod(0o700)
 def proc(self,pid=121,start=99,name='qemu-system-x86_64',avd='managed-original',port='5554'):
  p=self.p/str(pid);p.mkdir(exist_ok=True);(p/'stat').write_text(f'{pid} ({name}) S '+' '.join(['0']*18)+f' {start} '+' '.join(['0']*5));(p/'comm').write_text(name[:15]);(p/'exe').symlink_to(self.sdk/'emulator'/name);(p/'cmdline').write_bytes(('emulator\0-avd\0'+avd+'\0-port\0'+port+'\0').encode());return p
 def net(self,port=None,table='tcp',state='0A'):
  for n in ['tcp','tcp6']:(self.p/'net'/n).write_text('sl local_address rem_address st tx tr retr uid timeout inode\n')
  if port:
   with (self.p/'net'/table).open('a') as f:f.write(f'0: 00000000:{port:04X} 00000000:0000 {state} 0 0 0 0 0 123\n')
 def obs(self):return a.observation(self.p,self.uid,str(self.adb),self.old,time.monotonic()+3)
 def test_absent_and_two_pass(self):
  self.assertTrue(self.obs()['absent']);seen=[];a.wait(self.p,self.uid,str(self.adb),self.old,time.monotonic()+3,seen.append,interval=.01);self.assertEqual(len(seen),2)
 def test_original_anchor_identity(self):
  self.proc();obj=a.anchor(self.p,self.uid,self.sdk,time.monotonic()+2);self.assertEqual(obj['process']['start'],99);self.assertFalse(self.obs()['absent'])
 def test_pid_reuse_not_original(self):
  self.proc(start=100,name='unrelated');self.assertTrue(self.obs()['absent'])
 def test_old_exe_changed_still_present(self):
  self.proc(name='unrelated');o=self.obs();self.assertTrue(o['anchor_present']);self.assertFalse(o['absent'])
 def test_replacement_emulator(self):
  self.proc(pid=122,start=100);self.assertFalse(self.obs()['absent'])
 def test_stale_adb_refused(self):
  self.adbwrite('printf "List of devices attached\\nemulator-5554\\toffline\\n"');o=self.obs();self.assertFalse(o['absent']);self.assertFalse(o['before']);self.assertFalse(o['ports_before'])
 def test_adb_timeout_nonzero_malformed_overflow(self):
  for b in ['exit 7','printf bad','exec sleep 3',"python3 -c 'print(\"x\"*17000)'",'printf "List of devices attached\\nemulator-5554 garbled\\n"']:
   self.adbwrite(b)
   with self.subTest(b=b),self.assertRaises(Exception):self.obs()
 def test_port_families_and_timewait(self):
  for n in ['tcp','tcp6']:
   for port in [5554,5555]:self.net(port,n);self.assertFalse(self.obs()['absent'])
  self.net(5554,state='06');self.assertTrue(self.obs()['absent'])
 def test_unknown_port_table(self):
  (self.p/'net/tcp6').write_text('bad');
  with self.assertRaises(a.Refused):self.obs()
 def test_wrong_avd_port_or_multiple_anchor(self):
  p=self.proc(avd='managed-patched')
  with self.assertRaises(a.Refused):a.anchor(self.p,self.uid,self.sdk,time.monotonic()+2)
 def test_unreadable_process(self):
  p=self.proc();(p/'stat').write_text('bad')
  with self.assertRaises(Exception):self.obs()
 def test_persistent_and_total_timeout(self):
  self.proc();seen=[]
  with self.assertRaises(a.Refused):a.wait(self.p,self.uid,str(self.adb),self.old,time.monotonic()+.1,seen.append,interval=.02)
 def test_transient_reset_consecutive(self):
  observations=iter([{'absent':True},{'absent':False},{'absent':True},{'absent':True}]);seen=[]
  with patch.object(a,'observation',side_effect=lambda *args:next(observations)):
   a.wait(self.p,self.uid,str(self.adb),self.old,time.monotonic()+2,seen.append,interval=.001)
  self.assertEqual(len(seen),4)
 def test_scan_identity_change_refused(self):
  self.proc();original=a.stat;calls=0
  def race(p):
   nonlocal calls
   result=original(p);calls+=1
   if calls==2:result['start']+=1
   return result
  with patch.object(a,'stat',side_effect=race),self.assertRaises(a.Refused):a.scan(self.p,self.uid,time.monotonic()+2)
 def test_prepost_new_listener_refuses(self):
  def adb(*args):self.net(5555,'tcp6');return []
  with patch.object(a,'adb_snapshot',side_effect=adb):self.assertFalse(self.obs()['absent'])
 def test_scan_cap_and_state_only(self):
  self.proc()
  with patch.object(a,'MAX_PROCS',0),self.assertRaises(a.Refused):a.scan(self.p,self.uid,time.monotonic()+2)
  original=a.stat;calls=0
  def change(p):
   nonlocal calls
   result=original(p);calls+=1
   if calls==2:result['state']='R'
   return result
  with patch.object(a,'stat',side_effect=change):self.assertEqual(len(a.scan(self.p,self.uid,time.monotonic()+2)),1)
if __name__=='__main__':unittest.main()
