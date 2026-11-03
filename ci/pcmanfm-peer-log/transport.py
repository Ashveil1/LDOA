#!/usr/bin/env python3
"""Bounded evidence in ordinary job logs. Not artifact/cache upload.
Decoder accepts timestamp/prefix before PEER1 frame, not after its payload.
No executable/archive source binaries, raw environment, credentials or HOME.
"""
import argparse,base64,hashlib,json,pathlib,re,sys
MAX_RAW=512*1024
MAX_ENCODED=800*1024
CHUNK=3072
MAX_CHUNKS=180
MAX_LINE=4608
PREFIX='PEER1 '
ROOT_FILES={'raw.txt','selection.txt','phase.txt','receipt.json'}
def allowed(name):
    return name in ROOT_FILES or bool(re.fullmatch(r'xml-[0-9]{3}\.xml',name))

def selected(root):
    # Direct named root files and flat case entries only. Never walk HOME,
    # config/runtime, symlinked directories or unrelated workspace subtrees.
    result=[]
    for name in sorted(ROOT_FILES):
        p=root/name
        if p.is_file() and not p.is_symlink():result.append(p)
    for p in sorted(root.glob('xml-*.xml')):
        if allowed(p.name) and p.is_file() and not p.is_symlink():result.append(p)
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

def collect(workspace,root):
    # No broad workspace/HOME traversal; fixed CI outputs only. Full raw/XML
    # bytes or explicit missing/overflow, never truncate and claim complete.
    root.mkdir(parents=True,exist_ok=False)
    items=[('selection.txt',workspace/'peer-selection-result.txt'),('phase.txt',workspace/'phase.txt')]
    rawdir=workspace/'app/build/outputs/connected_android_test_additional_output'
    xmldir=workspace/'app/build/outputs/androidTest-results'
    raw=list(rawdir.rglob('pcmanfm-peer-no-payload.txt')) if rawdir.is_dir() else []
    xml=list(xmldir.rglob('*.xml')) if xmldir.is_dir() else []
    issues=[];mapping={};total=0
    if len(raw)!=1:issues.append('raw count '+str(len(raw)))
    else:items.append(('raw.txt',raw[0]))
    if not 1<=len(xml)<=32:issues.append('XML count '+str(len(xml)))
    else:items += [('xml-%03d.xml'%i,p) for i,p in enumerate(sorted(xml))]
    for name,p in items:
        try:
            # Reject symlinks at ANY level below repository workspace.
            rel=p.relative_to(workspace)
            assert all(not(workspace.joinpath(*rel.parts[:i]).is_symlink()) for i in range(1,len(rel.parts)+1)),'symlink'
            assert p.is_file(),'missing/not regular'
            assert p.stat().st_size<=128*1024,'file byte cap'
            with p.open('rb') as f:b=f.read(128*1024+1)
            assert len(b)<=128*1024 and total+len(b)<=MAX_RAW-16384,'aggregate byte cap'
            total+=len(b);(root/name).write_bytes(b);mapping[name]=str(rel)
        except Exception as e:issues.append(name+': '+str(e))
    (root/'receipt.json').write_text(json.dumps({'complete':not issues,'issues':issues,'paths':mapping},sort_keys=True))
    return not issues

def main():
    import tempfile
    ap=argparse.ArgumentParser();sub=ap.add_subparsers(dest='mode',required=True)
    p=sub.add_parser('emit');p.add_argument('--workspace',type=pathlib.Path,required=True)
    p=sub.add_parser('decode');p.add_argument('--log',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True)
    a=ap.parse_args()
    if a.mode=='emit':
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)/'selected';complete=collect(a.workspace.resolve(),root)
            for line in frame(root):print(line,flush=True)
        return 0 if complete else 1
    decode(a.log.read_text().splitlines(),a.out)
    return 0
if __name__=='__main__':sys.exit(main())
