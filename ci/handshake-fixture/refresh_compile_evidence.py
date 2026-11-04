#!/usr/bin/env python3
"""Refresh all source-dependent local compile evidence together. Never execute target ELF."""
import pathlib,json,subprocess,hashlib,argparse,shutil
ap=argparse.ArgumentParser();ap.add_argument('--compiled',required=True);ap.add_argument('--runtime-manifest',required=True);ap.add_argument('--ndk',required=True);a=ap.parse_args();b=pathlib.Path(__file__).parent;c=pathlib.Path(a.compiled)
h=lambda p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
meta=json.loads((c/'build.json').read_text());assert meta['exit_code']==0 and meta['producer_source_sha256']==h(b/'native/producer.c')
cmd=meta['argv'];stop=next(i for i,x in enumerate(cmd) if x.startswith('-L'));args=cmd[:stop]+['-M'];raw=subprocess.check_output(args,text=True);deps=raw.replace('\\\n',' ').split(':',1)[1].split();files=[]
for name in deps:
 p=pathlib.Path(name);files.append({'scope':'producer_source' if p.is_relative_to(b/'native') else 'selected_sysroot' if p.is_relative_to(c/'sysroot') else 'compiler_resource','path':str(p),'sha256':h(p),'bytes':p.stat().st_size})
(b/'preprocessor-inputs.json').write_text(json.dumps({'schema':1,'method':'clang -M actual source/header input list, no target execution','argv':args,'files':files},indent=2)+'\n')
for src,dst in [('build.json','compile-evidence.json'),('runtime-graph.json','runtime-elf-graph.json')]:shutil.copyfile(c/src,b/dst)
shutil.copyfile(a.runtime_manifest,b/'runtime-asset-plan.json')
subprocess.run(['python3',str(b/'freeze_inputs.py'),'--candidate',str(b),'--producer',str(c/'producer'),'--ndk',a.ndk],check=True)
p=b/'compile-inputs.lock.json';d=json.loads(p.read_text());d['blockers']=[x for x in d['blockers'] if x not in ['Runtime graph was derived before further source edits; must regenerate on final producer','Toolchain host DT_NEEDED graph not frozen']];d['blockers']+=['Gradle/Maven exact resolved closure not frozen','Target runtime and packaging parity unverified'];d['compiler_host_inputs_sha256']=h(b/'compiler-host-inputs.json');p.write_text(json.dumps(d,indent=2)+'\n')
bound=['compile-evidence.json','preprocessor-inputs.json','runtime-elf-graph.json','runtime-asset-plan.json','compile-inputs.lock.json','compiler-host-inputs.json']
(b/'compile-freshness.json').write_text(json.dumps({'schema':1,'scope':'CURRENT_LOCAL_COMPILE_NOT_RUNTIME','source_files':{str(p.relative_to(b)):h(p) for p in (b/'native').glob('*') if p.is_file()},'bound_evidence':{n:h(b/n) for n in bound}},indent=2)+'\n')
