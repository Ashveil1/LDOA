"""One monotonic process-family budget. No fetch, compiler or guest effects."""
import time,math
from contracts import Stop
def start(clock=time.monotonic):return clock()+1200
def check(deadline,clock=time.monotonic):
 now=clock()
 if not math.isfinite(deadline) or not math.isfinite(now) or deadline-now>1200:raise Stop('TOTAL_DEADLINE_INVALID')
 if now>=deadline:raise Stop('TOTAL_DEADLINE')
 return deadline

def qualify_pipeline(reacquire,extract,stage,build,cleanup,clock=time.monotonic):
 """Source-only orchestration contract; callbacks supplied only by future reviewed wrapper.
 All receive the SAME absolute deadline; no callback obtains a fresh budget.
 Cleanup runs even on deadline or callback failure. No callback implementation here.
 """
 deadline=start(clock)
 result={'deadline_monotonic':deadline,'stages':[],'complete':False,'runtime_asset_ready':False}
 try:
  for name,callback in [('reacquire',reacquire),('extract',extract),('stage',stage),('build',build)]:
   check(deadline,clock);callback(deadline);check(deadline,clock)
   result['stages'].append(name)
  return result
 finally:cleanup()
