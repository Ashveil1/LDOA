#!/usr/bin/env python3
"""Single-attempt lifecycle logic, dependency injected for local mocks. No CLI entrypoint."""
import subprocess
from target_once import classified_run
def run_once(steps,owner,publish):
 result={'scope':'HANDSHAKE_FIXTURE_ONLY','stage':'UNKNOWN','cleanup':'UNKNOWN'};code=2
 try:
  for name in ['bootstrap','guest','build','boot']:
   steps[name]();result['stage']=name.upper()+'_COMPLETE'
  code=steps['target']()
  if code not in [0,1,2]:raise ValueError('EXIT_TAXONOMY')
  result['stage']='MATCH' if code==0 else 'MISMATCH' if code==1 else 'UNKNOWN'
 except (KeyboardInterrupt,SystemExit):result['stage']='SUPERVISOR_INTERRUPTED';code=2;raise
 except (subprocess.SubprocessError,OSError,ValueError,UnicodeError,KeyError,TypeError):result['stage']='TRANSPORT_OR_EXECUTION_UNAVAILABLE';code=2
 except Exception:result['stage']='HARNESS_INTERNAL_ERROR';code=2
 finally:
  try:result['cleanup']=owner.close()
  except Exception:result['cleanup']='UNKNOWN';code=2
  if result['cleanup']=='UNKNOWN':code=2;result['stage']='CLEANUP_UNKNOWN'
  # Called only after cleanup. No healthy final verdict before ownership closeout.
  try:publish(result,code)
  except Exception:
   code=2
   # Do not recursively call the failed publisher or emit a raw traceback.
   print('{"scope":"HANDSHAKE_FIXTURE_ONLY","stage":"OUTPUT_TRANSPORT_UNAVAILABLE","assessment":"UNKNOWN"}')
 return code
