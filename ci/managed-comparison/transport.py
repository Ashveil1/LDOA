#!/usr/bin/env python3
"""Bounded evidence in ordinary job logs. Not artifact/cache upload.
Decoder accepts timestamp/prefix before MCMP1 frame, not after its payload.
No executable/archive source binaries, raw environment, credentials or HOME.
"""
import argparse,base64,hashlib,json,pathlib,re,sys
MAX_RAW=24*1024*1024
MAX_ENCODED=34*1024*1024
CHUNK=3072
MAX_CHUNKS=8500
MAX_LINE=4608
PREFIX='MCMP1 '
ROOT_FILES={'coverage.json','host-phase.txt'}
CASE_FILES={'transfer-guards.txt','managed-setup-raw.txt','managed-setup-capture.txt','managed-abi-raw.txt','managed-abi-capture.txt','managed-closure-raw.txt','managed-closure-capture.txt','managed-comparison.txt','managed-abi.txt','managed-provenance.txt','managed-teardown.txt','managed-junit.xml','managed-input.png','managed-0.png','managed-1.png','managed-2.png','managed-3.png','managed-4.png','instrumentation.txt','phase.txt','caller.txt'}
def allowed(name):
    p=pathlib.PurePosixPath(name)
    return name in ROOT_FILES or (len(p.parts)==2 and p.parts[0] in ('original','patched') and p.parts[1] in CASE_FILES and str(p)==name)

def selected(root):
    # Direct named root files and flat case entries only. Never walk HOME,
    # config/runtime, symlinked directories or unrelated workspace subtrees.
    result=[]
    for name in sorted(ROOT_FILES):
        p=root/name
        if p.is_file() and not p.is_symlink():result.append(p)
    for case in ['original','patched']:
        d=root/case
        if not d.is_dir() or d.is_symlink():continue
        for p in sorted(d.iterdir()):
            if allowed(case+'/'+p.name) and p.is_file() and not p.is_symlink():result.append(p)
    assert result,'no selected evidence'
    return result

def frame(root):
    files=selected(root);records=[];data=[];raw=0;chunks=0
    for p in files:
        assert p.stat().st_size<=MAX_RAW-raw,'raw cap before read'
        with p.open('rb') as f:b=f.read(MAX_RAW-raw+1)
        raw+=len(b);assert raw<=MAX_RAW,'raw cap bounded read overflow'
        n=(len(b)+CHUNK-1)//CHUNK;chunks+=n;assert chunks<=MAX_CHUNKS,'chunk cap'
        name=p.relative_to(root).as_posix()
        record={'name':name,'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest(),'chunks':n}
        if p.suffix=='.png':
            tag=re.sub(r'^\d\d-','',p.stem)
            bracket=p.parent/(tag+'-capture-bracket.json')
            record['geometry_bracket']=bracket.relative_to(root).as_posix() if bracket.exists() else 'UNPROVEN'
        records.append(record)
        for i in range(n):data.append({'name':name,'index':i,'data':base64.b64encode(b[i*CHUNK:(i+1)*CHUNK]).decode()})
    manifest={'files':records,'raw_bytes':raw,'chunks':chunks}
    lines=[PREFIX+'BEGIN '+json.dumps(manifest,separators=(',',':'))]
    # Manifest may be long: encode it into the same bounded line protocol.
    m=json.dumps(manifest,separators=(',',':')).encode()
    lines=[PREFIX+'START '+json.dumps({'manifest_bytes':len(m),'manifest_sha256':hashlib.sha256(m).hexdigest(),'manifest_chunks':(len(m)+CHUNK-1)//CHUNK},separators=(',',':'))]
    for i in range(0,len(m),CHUNK):lines.append(PREFIX+'MANIFEST '+str(i//CHUNK)+' '+base64.b64encode(m[i:i+CHUNK]).decode())
    lines += [PREFIX+'DATA '+json.dumps(d,separators=(',',':')) for d in data]
    lines.append(PREFIX+'END '+hashlib.sha256(m).hexdigest())
    assert all(len(l.encode())<=MAX_LINE for l in lines),'line cap'
    assert sum(len(l.encode())+1 for l in lines)<=MAX_ENCODED,'encoded cap'
    assert len(lines)<=MAX_CHUNKS+256,'frame count cap'
    return lines

def decode(lines,out):
    frames=[];encoded=0
    for line in lines:
        if PREFIX not in line:continue
        line=line[line.index(PREFIX):].rstrip('\r\n');encoded+=len(line.encode())+1
        assert len(line.encode())<=MAX_LINE and encoded<=MAX_ENCODED,'encoded cap'
        frames.append(line[len(PREFIX):]);assert len(frames)<=MAX_CHUNKS+256,'frame count cap'
    assert frames and frames[0].startswith('START '),'missing start'
    start=json.loads(frames[0][6:]);count=start['manifest_chunks'];assert 0<count<=128,'manifest chunk cap'
    m=b''
    for i in range(count):
        kind,index,b64=frames[i+1].split(' ',2);assert kind=='MANIFEST' and index==str(i),'manifest order/duplicate'
        m+=base64.b64decode(b64,validate=True)
    assert len(m)==start['manifest_bytes'] and hashlib.sha256(m).hexdigest()==start['manifest_sha256'],'manifest hash/length'
    manifest=json.loads(m);assert manifest['raw_bytes']<=MAX_RAW and manifest['chunks']<=MAX_CHUNKS,'declared cap'
    pos=1+count;names=set();blobs=[];total=0;nchunks=0
    for record in manifest['files']:
        name=record['name'];path=pathlib.PurePosixPath(name)
        assert allowed(name) and name!='transport-manifest.json' and name not in names,'unsafe/noncanonical/unlisted/duplicate name'
        assert all(not(name.startswith(n+'/') or n.startswith(name+'/')) for n in names),'ancestor collision'
        names.add(name);b=b'';assert 0<=record['bytes']<=MAX_RAW and 0<=record['chunks']<=MAX_CHUNKS
        assert record['chunks']==(record['bytes']+CHUNK-1)//CHUNK,'wrong declared chunks'
        for i in range(record['chunks']):
            assert frames[pos].startswith('DATA '),'missing data';d=json.loads(frames[pos][5:]);pos+=1
            assert d['name']==name and d['index']==i,'out of order/duplicate'
            part=base64.b64decode(d['data'],validate=True);assert 0<len(part)<=CHUNK,'chunk byte cap'
            b+=part;nchunks+=1
        total+=len(b);assert total<=MAX_RAW,'actual raw cap'
        assert len(b)==record['bytes'] and hashlib.sha256(b).hexdigest()==record['sha256'],'file hash/length'
        blobs.append((name,b))
    assert total==manifest['raw_bytes'] and nchunks==manifest['chunks'],'totals mismatch'
    assert pos==len(frames)-1 and frames[pos]=='END '+start['manifest_sha256'],'missing end/trailing frames'
    out.mkdir(parents=True,exist_ok=False)
    for name,b in blobs:
        target=out/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(b)
    (out/'transport-manifest.json').write_text(json.dumps(manifest,indent=2))
    return manifest

def collect(evidence,private,out):
    # Hard separation: runner-local E(source/stages) never an export subtree.
    evidence=evidence.resolve();private=private.resolve()
    assert evidence!=private and private not in evidence.parents and evidence not in private.parents,'private/output overlap'
    out.mkdir();issues=[];seen=set();total=0
    host=evidence/'host-phase.txt'
    if host.exists():
        assert not host.is_symlink() and host.is_file() and host.stat().st_size<=1024,'host receipt cap/type'
        raw=host.read_bytes()
        assert re.fullmatch(rb'schema=1\nphase=(host-check|source-fetch|extract|configure|build|stage|elf-inspect|resource-equality|source-inventory|recipe-copy|complete) case=(none|original|patched|both) rc=(0|[1-9][0-9]{0,2})\n',raw),'host receipt syntax'
        (out/'host-phase.txt').write_bytes(raw);seen.add('host-phase.txt');total+=len(raw)
    else:issues.append('host-phase.txt: count 0')
    for case in ['original','patched']:
        source=evidence/case
        for name in sorted(CASE_FILES):
            matches=list(source.rglob(name)) if source.is_dir() else []
            if len(matches)!=1:issues.append(case+'/'+name+': count '+str(len(matches)));continue
            p=matches[0];rel=p.relative_to(evidence)
            assert all(not evidence.joinpath(*rel.parts[:i]).is_symlink() for i in range(1,len(rel.parts)+1)),'symlink export'
            assert p.is_file() and p.stat().st_size<=4*1024*1024,'file cap'
            if name=='transfer-guards.txt':
                assert p.stat().st_size<=1024,'guard receipt cap'
                receipt=p.read_bytes()
                assert re.fullmatch(rb'schema=1\n(?:guard=(?:context-identity|parent-nonlink|parent-type|incoming-absent|incoming-nonlink|tmp-absent|tmp-nonlink|tmp-scaffold|push|tmp-inventory|private-copy) rc=(?:0|[1-9][0-9]{0,2})\n){1,11}',receipt),'guard receipt syntax'
            total+=p.stat().st_size;assert total<=MAX_RAW-16384,'raw cap'
            target=out/case/name;target.parent.mkdir(exist_ok=True)
            with p.open('rb') as f:b=f.read(4*1024*1024+1)
            assert len(b)<=4*1024*1024;target.write_bytes(b);seen.add(case+'/'+name)
    (out/'coverage.json').write_text(json.dumps({'complete':not issues,'issues':issues,'selected':sorted(seen),'private_excluded':True}))
    return not issues

def main():
    import tempfile
    ap=argparse.ArgumentParser();sub=ap.add_subparsers(dest='mode',required=True)
    p=sub.add_parser('emit');p.add_argument('--evidence',type=pathlib.Path,required=True);p.add_argument('--private',type=pathlib.Path,required=True)
    p=sub.add_parser('decode');p.add_argument('--log',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True)
    a=ap.parse_args()
    if a.mode=='emit':
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)/'out';complete=collect(a.evidence,a.private,root)
            for line in frame(root):print(line,flush=True)
        return 0 if complete else 1
    decode(a.log.read_text().splitlines(),a.out);return 0
if __name__=='__main__':sys.exit(main())
