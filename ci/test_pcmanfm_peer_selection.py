#!/usr/bin/env python3
import tempfile
from pathlib import Path
import check_pcmanfm_peer_result as g
checks=0
def test(xml,evidence,ok):
 global checks
 with tempfile.TemporaryDirectory() as td:
  p=Path(td);r=p/'results';r.mkdir();e=p/'output';e.mkdir()
  if xml is not None:(r/'TEST.xml').write_text(xml)
  if evidence is not None:(e/'pcmanfm-peer-no-payload.txt').write_text(evidence)
  try:g.gate(r,e)
  except Exception:
   assert not ok
  else:assert ok
 checks+=1
case=f'<testcase classname="{g.CLASS}" name="{g.METHOD}"/>'
xml='<testsuite>'+case+'</testsuite>'
evidence=g.PASS+'\nclient_list_changed=true; zero-byte EOF may open cwd window\npeer pid=123 uid=123 gid=123 bytes_sent=0\nworker_alive=false cleanup_alive=false helper_alive=null failure=null\n'
test(xml,evidence,True)
for x in [None,'<bad', '<testsuite/>',xml.replace(g.METHOD,'mobileEntryLifecycle'),xml.replace(g.CLASS,'wrong'),'<testsuite>'+case+case+'</testsuite>',xml.replace('/>','><skipped/></testcase>'),xml.replace('/>','><failure/></testcase>'),xml.replace('/>','><error/></testcase>')]:test(x,evidence,False)
for x in [None,'',evidence.replace('bytes_sent=0','bytes_sent=1'),evidence.replace('worker_alive=false','worker_alive=true'),evidence.replace('failure=null','failure=error'),evidence+'UNKNOWN',evidence.replace(g.PASS,'')]:test(xml,x,False)
w=(Path(__file__).resolve().parents[1]/'.github/workflows/mobile-desktop.yml').read_text()
assert 'PEER_ONLY: ${{ inputs.pcmanfm_peer_no_payload }}' in w
assert '"$PEER_ONLY" = "true" ] && [ "$XDIAG_ONLY" = "true"' in w
assert w.index('conflicting diagnostic selection')<w.index('uses: actions/checkout')
line=next(l for l in w.splitlines() if 'connectedDebugAndroidTest' in l and '#mobileEntryPcmanfmPeerNoPayload' in l)
assert '-Pandroid.testInstrumentationRunnerArguments.ldoa_pcmanfm_peer_nopayload=true' in line
assert '-Pandroid.testInstrumentationRunnerArguments.ldoa_ci_emulator=true' in line
assert "inputs.pcmanfm_peer_no_payload && '-Pandroid.testInstrumentationRunnerArguments.ldoa_pcmanfm_peer_nopayload=true'" in line
assert 'timeout -k 30 1500' in line and 'pipefail' in line
assert 'timeout -k 2 15 python3 ci/check_pcmanfm_peer_result.py' in w
assert 'peer-selection-result.txt' in w
print('PASS',checks,'result fault fixtures + workflow selector/output static contracts; no Android/remote run')
# Execute actual workflow instrumentation+result lines through local stubs.
import os,re,subprocess
lines=w.splitlines();instr=next(l.strip() for l in lines if './gradlew connectedDebugAndroidTest' in l)
gate=next(l.strip() for l in lines if 'python3 ci/check_pcmanfm_peer_result.py' in l)
def render(line,peer,xdiag):
 def expression(m):return m.group(2) if {'pcmanfm_peer_no_payload':peer,'xdiag_capture':xdiag}[m.group(1)] else ''
 line=re.sub(r"\$\{\{ inputs\.(pcmanfm_peer_no_payload|xdiag_capture) && '([^']*)' \|\| '' \}\}",expression,line)
 return line.replace('${{ inputs.pcmanfm_peer_no_payload }}','true' if peer else 'false')
for mode,expected in [('pass',True),('skip',False),('wrong',False),('missing',False),('failrc',False)]:
 with tempfile.TemporaryDirectory() as td:
  p=Path(td);(p/'ci').mkdir();(p/'ci/check_pcmanfm_peer_result.py').write_text(Path(g.__file__).read_text())
  r=p/'app/build/outputs/androidTest-results';r.mkdir(parents=True)
  e=p/'app/build/outputs/connected_android_test_additional_output';e.mkdir(parents=True)
  if mode!='missing':
   x=xml
   if mode=='skip':x=x.replace('/>','><skipped/></testcase>')
   if mode=='wrong':x=x.replace(g.METHOD,'mobileEntryLifecycle')
   (r/'TEST.xml').write_text(x);(e/'pcmanfm-peer-no-payload.txt').write_text(evidence)
  stub=p/'gradlew';stub.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > argv.txt\necho BUILD SUCCESSFUL\nexit '+('7' if mode=='failrc' else '0')+'\n');stub.chmod(0o755)
  script=render(instr,True,False)+'\n'+render(gate,True,False)
  script=script.replace('/tmp/instr.log',str(p/'instr.log')).replace('/tmp/PHONELINUX_TESTS_FAILED',str(p/'FAILED'))
  subprocess.run(['bash','-c',script],cwd=p,timeout=20,check=True,stdout=subprocess.DEVNULL)
  argv=(p/'argv.txt').read_text().splitlines()
  assert '-Pandroid.testInstrumentationRunnerArguments.class='+g.CLASS+'#'+g.METHOD in argv
  assert '-Pandroid.testInstrumentationRunnerArguments.ldoa_pcmanfm_peer_nopayload=true' in argv
  assert not any('ldoa_xdiag=' in a for a in argv)
  assert (not (p/'FAILED').exists())==expected,mode
  assert (p/'instr.log').is_file() and (p/'peer-selection-result.txt').is_file()
print('PASS 5 executed workflow peer-selector/failure/evidence fixtures, local stub only')

import hashlib
base=Path(__file__).resolve().parents[1]
xserver=base/'app/src/main/java/dev/phonelinux/proto/XServer.kt'
expected='015ece2cb998ef876b7947005b4c5bed3f36f5e19e1e403a5fc1443d499c69d2'
assert hashlib.sha256(xserver.read_bytes()).hexdigest()==expected, 're-review reflection on XServer source change'
xs=xserver.read_text()
for token in ['private val ownershipLock = Any()', 'private var ownershipGeneration = 0L', '@Volatile private var stopInFlight = false']:assert token in xs
print('PASS actual XServer source SHA256 + three reflected field source contracts; runtime field types checked by diagnostic')

assert 'cache-disabled: ${{ inputs.pcmanfm_peer_no_payload }}' in w
assert 'if: ${{ always() && !inputs.pcmanfm_peer_no_payload }}' in w
assert 'if: ${{ always() && inputs.pcmanfm_peer_no_payload }}' in w
assert 'timeout -k 2 90 python3 ci/pcmanfm-peer-log/transport.py emit --workspace "$GITHUB_WORKSPACE"' in w
assert w.count('uses: actions/upload-artifact@')==1
print('PASS peer-only cache/artifact disable + always bounded ordinary-log evidence static route; default false preserved')
