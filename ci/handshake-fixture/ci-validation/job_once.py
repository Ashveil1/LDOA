#!/usr/bin/env python3
"""Proposed complete orchestration function, CLI deliberately locked pending review."""
import pathlib,json,sys,os,signal
BASE=pathlib.Path(__file__).resolve().parents[1]
def verify_external_pin(repo,expected):
 # Supplied by separately approved dispatch/review, never read from candidate tree.
 import subprocess,re
 if not isinstance(expected,str) or not re.fullmatch('[0-9a-f]{40}',expected):raise ValueError('EXTERNAL_COMMIT_PIN_REQUIRED')
 actual=subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD'],text=True).strip()
 if actual!=expected:raise ValueError('CANDIDATE_COMMIT_MISMATCH')
 return actual
def run_candidate(base,repo,publish,approved_commit):
 try:verify_external_pin(repo,approved_commit)
 except Exception:
  publish({'scope':'HANDSHAKE_FIXTURE_ONLY','stage':'CANDIDATE_IDENTITY_UNAVAILABLE','assessment':'UNKNOWN','cleanup':'NOT_LAUNCHED'},2)
  return 2
 def interrupted(signum,frame):raise SystemExit(2)
 signal.signal(signal.SIGTERM,interrupted)
 from owned_emulator import OwnedEmulator
 from bootstrap_steps import Steps
 from lifecycle import run_once
 import target_once
 owner=OwnedEmulator(base/'private-run/sdk/emulator/emulator',base/'private-run/avd')
 steps=Steps(base,repo,owner)
 class Closeout:
  def close(self):
   try:return owner.close()
   finally:steps.erase_key()
 def target():return target_once.execute('emulator-5554')
 return run_once({n:getattr(steps,n) for n in ['bootstrap','guest','build','boot']}|{'target':target},Closeout(),publish)
def publish_after_cleanup(base,result,code):
 # Exactly one final payload. No frame on UNKNOWN/transport/internal/cleanup failure.
 if code in [0,1] and result['cleanup']!='UNKNOWN':
  raw=(base/'private-run/validated-result.hsf1').read_bytes()
  if len(raw)>87500:raise ValueError('FRAME_CAP')
  sys.path.insert(0,str(base));from check_result import unframe,assess
  verdict=assess(unframe(raw.decode('ascii')))
  if (verdict['assessment']=='MATCH')!=(code==0):raise ValueError('VERDICT_CODE_BINDING')
  sys.stdout.buffer.write(raw);sys.stdout.buffer.flush()
 else:print(json.dumps(result,separators=(',',':')))
def main():
 # Removing this gate requires separate source/execution approval, never workflow input.
 print('{"scope":"HANDSHAKE_FIXTURE_ONLY","stage":"BOOTSTRAP_EXECUTION_NOT_APPROVED"}')
 return 78
if __name__=='__main__':raise SystemExit(main())
