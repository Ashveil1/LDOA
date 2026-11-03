import io,os,pathlib,subprocess,tempfile,time,unittest,threading
from unittest.mock import patch
import geometry as g

class KeptBytes(io.BytesIO):
 def close(self):pass

class Logging(unittest.TestCase):
 def persistent(self,code,limit=4096):
  reg=[];known={};sink=KeptBytes()
  p,s=g.launch(['python3','-c',code],reg,known,persistent=True,limit=limit,stdout=sink,stderr=subprocess.STDOUT)
  p.wait(timeout=3);r=s['log_drain'].finish();return p,sink.getvalue(),r
 def test_internal_ftruncate_unaffected(self):
  p,b,r=self.persistent('import tempfile,os; f=tempfile.TemporaryFile(); f.truncate(32*1024*1024); print("internal32MiB OK")')
  self.assertEqual(p.returncode,0);self.assertEqual(b,b'internal32MiB OK\n');self.assertTrue(g.log_receipt_ok(r))
 def test_overflow_drained_and_joined(self):
  p,b,r=self.persistent('import os; os.write(1,b"x"*300000)')
  self.assertEqual(p.returncode,0);self.assertEqual(len(b),4096);self.assertEqual(r['seen_bytes'],300000)
  self.assertTrue(r['overflow']);self.assertTrue(r['eof']);self.assertFalse(r['worker_alive']);self.assertFalse(g.log_receipt_ok(r))
 def test_quiet_live_deadline(self):
  r,w=os.pipe();sink=KeptBytes();drain=g.BoundedLog(os.fdopen(r,'rb'),sink,4096,time.monotonic()+.08)
  receipt=drain.finish();os.close(w)
  self.assertTrue(receipt['deadline']);self.assertFalse(receipt['eof']);self.assertFalse(receipt['worker_alive'])
 def test_inherited_writer_missing_eof(self):
  r,w=os.pipe();drain=g.BoundedLog(os.fdopen(r,'rb'),io.BytesIO(),4096,time.monotonic()+10)
  receipt=drain.finish();os.close(w)
  self.assertFalse(receipt['eof']);self.assertFalse(receipt['worker_alive']);self.assertFalse(g.log_receipt_ok(receipt))
 def test_sink_error(self):
  class Bad:
   def write(self,b):raise OSError('injected disk full')
   def flush(self):pass
  r,w=os.pipe();os.write(w,b'hello');os.close(w)
  drain=g.BoundedLog(os.fdopen(r,'rb'),Bad(),4096,time.monotonic()+1);receipt=drain.finish()
  self.assertIn('disk full',receipt['error']);self.assertFalse(g.log_receipt_ok(receipt))
 def test_registration_failure_drain_finishes(self):
  reg=[];known={};sink=KeptBytes()
  with patch.object(g,'stat',side_effect=OSError('lookup')):
   with self.assertRaises(OSError):g.launch(['sleep','2'],reg,known,persistent=True,stdout=sink,stderr=subprocess.STDOUT)
  p,s=reg[0];self.assertIsNotNone(p.poll());r=s['log_drain'].finish();self.assertFalse(r['worker_alive']);self.assertTrue(r['eof'])
 def test_primary_survives_logger_receipt_write_failure(self):
  primary=AssertionError('root failed')
  g.preserve_primary(primary,lambda:(_ for _ in ()).throw(OSError('logging receipt full')))
  self.assertEqual(str(primary),'root failed');self.assertIn('receipt full',str(primary.__notes__))
 def test_unjoined_worker_stays_unknown(self):
  # Do not create an actual wedged worker. Inject worker state to verify receipt.
  from unittest.mock import Mock
  drain=g.BoundedLog.__new__(g.BoundedLog)
  drain.worker=Mock();drain.worker.is_alive.return_value=True
  drain.stop=Mock();drain.eof=False;drain.overflow=False;drain.timed_out=False
  drain.error=None;drain.seen=0;drain.kept=0;drain.limit=4096
  r=drain.finish();self.assertTrue(r['worker_alive']);self.assertFalse(g.log_receipt_ok(r));self.assertEqual(drain.worker.join.call_count,2)
 def test_worker_start_failure_owned_child_cleanup(self):
  reg=[];known={};sink=KeptBytes()
  with patch.object(g.threading.Thread,'start',side_effect=RuntimeError('start failed')):
   with self.assertRaises(RuntimeError):g.launch(['sleep','3'],reg,known,persistent=True,stdout=sink)
  self.assertIsNotNone(reg[0][0].poll());self.assertTrue(reg[0][1]['unregistered_pipe'].closed)
 def test_flush_close_errors(self):
  for phase in ['flush','close']:
   class Bad(KeptBytes):
    def flush(self):
     if phase=='flush':raise OSError('flush failed')
    def close(self):
     if phase=='close':raise OSError('close failed')
   r,w=os.pipe();os.close(w);d=g.BoundedLog(os.fdopen(r,'rb'),Bad(),20,time.monotonic()+3);receipt=d.finish()
   self.assertIn(phase,receipt['error']);self.assertFalse(g.log_receipt_ok(receipt))
 def test_blocked_flush_close_unknown_then_release(self):
  for phase in ['flush','close']:
   gate=threading.Event()
   class Block(KeptBytes):
    def flush(self,phase=phase,gate=gate):
     if phase=='flush':gate.wait(5)
    def close(self,phase=phase,gate=gate):
     if phase=='close':gate.wait(5)
   r,w=os.pipe();os.close(w);d=g.BoundedLog(os.fdopen(r,'rb'),Block(),20,time.monotonic()+5)
   receipt=d.finish();self.assertTrue(receipt['worker_alive']);self.assertFalse(receipt['counts_final']);self.assertFalse(g.log_receipt_ok(receipt))
   gate.set();d.worker.join(1);self.assertFalse(d.worker.is_alive())
 def test_threaded_subsequent_launch_no_preexec(self):
  r,w=os.pipe();d=g.BoundedLog(os.fdopen(r,'rb'),KeptBytes(),20,time.monotonic()+10)
  reg=[];known={}
  try:
   for _ in range(10):
    p,s=g.launch(['true'],reg,known);p.wait(timeout=1);self.assertEqual(p.returncode,0)
  finally:os.close(w);self.assertTrue(d.finish()['eof'])
if __name__=='__main__':unittest.main()


