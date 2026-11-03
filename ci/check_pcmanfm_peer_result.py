#!/usr/bin/env python3
"""Exact selected Android test + zero-payload evidence gate, not UI/ABI proof."""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET
METHOD='mobileEntryPcmanfmPeerNoPayload'
CLASS='dev.phonelinux.proto.MobileDesktopEntryTest'
PASS='PASS transport peer/path only; zero bytes; EOF may open cwd window; no Files/parser/FPS/no-side-effect claim'
def gate(results,evidence):
    xmls=list(Path(results).rglob('*.xml'))
    if not xmls or len(xmls)>64:raise ValueError('missing/too many XML results')
    cases=[]
    for f in xmls:
        if f.stat().st_size>2*1024*1024:raise ValueError('XML byte cap')
        root=ET.parse(f).getroot()
        for e in root.iter():
            if e.tag in ('failure','error'):raise ValueError('failed test in selected run')
            if e.tag=='testcase':cases.append(e)
    if len(cases)!=1:raise ValueError('expected exactly one executed testcase')
    c=cases[0]
    if c.get('name')!=METHOD or c.get('classname')!=CLASS:raise ValueError('wrong selected method/class')
    if list(c):raise ValueError('skipped/failure/error/unknown testcase child')
    files=list(Path(evidence).rglob('pcmanfm-peer-no-payload.txt'))
    if len(files)!=1 or files[0].stat().st_size>128*1024:raise ValueError('missing/duplicate/oversized evidence')
    text=files[0].read_text()
    if PASS not in text or 'bytes_sent=0' not in text or 'client_list_changed=' not in text:raise ValueError('no complete zero-payload peer result')
    if 'worker_alive=false cleanup_alive=false helper_alive=null failure=null' not in text:raise ValueError('deadline/cleanup/failure unresolved')
    if 'UNKNOWN' in text:raise ValueError('UNKNOWN evidence')
    return 'PASS selected exact non-skipped no-payload method + complete peer evidence; NOT Files/parser/Android-device qualification'
if __name__=='__main__':
    try:print(gate(*sys.argv[1:]))
    except Exception as e:print('REFUSED:',type(e).__name__,str(e));sys.exit(1)
