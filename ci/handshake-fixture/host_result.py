#!/usr/bin/env python3
"""Mandatory decode -> schema -> assess -> verdict. No live transport implemented."""
import sys,json,pathlib
from check_result import unframe,assess
if __name__=='__main__':
 try:
  raw=pathlib.Path(sys.argv[1]).read_bytes()
  if len(raw)>87500:raise ValueError('frame cap')
  result=unframe(raw.decode('ascii'));verdict=assess(result)
  print(json.dumps({'fixture_only':True,'transport':'VALID','verdict':verdict},separators=(',',':')))
  raise SystemExit(0 if verdict['assessment']=='MATCH' else 1)
 except (ValueError,UnicodeError,IndexError):
  print('{"fixture_only":true,"failure_stage":"TRANSPORT"}');raise SystemExit(2)
