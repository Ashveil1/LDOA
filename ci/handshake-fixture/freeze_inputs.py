#!/usr/bin/env python3
"""Freeze observed compile inputs; refuses to call runtime evidence complete."""
import argparse,json,pathlib,hashlib,subprocess,re
ap=argparse.ArgumentParser();ap.add_argument('--candidate',required=True);ap.add_argument('--producer',required=True);ap.add_argument('--ndk',required=True);a=ap.parse_args();b=pathlib.Path(a.candidate)
selection=json.loads((b/'sysroot-file-selection.json').read_text());headers=json.loads((b/'preprocessor-inputs.json').read_text());graph=json.loads((b/'runtime-elf-graph.json').read_text());ndk=pathlib.Path(a.ndk)
compiler=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/clang';linker=compiler.with_name('ld.lld')
for r in headers['files']:
 p=pathlib.Path(r['path'])
 if hashlib.sha256(p.read_bytes()).hexdigest()!=r['sha256']:raise ValueError('HEADER_CHANGED')
for r in selection['packages']:
 if not re.fullmatch('[0-9a-f]{64}',r['sha256']):raise ValueError('PIN')
producer=pathlib.Path(a.producer);elf={'sha256':hashlib.sha256(producer.read_bytes()).hexdigest(),'bytes':producer.stat().st_size,'version_requirements':subprocess.check_output(['readelf','--version-info',str(producer)],text=True)}
lock={'schema':1,'scope':'FROZEN_OBSERVED_COMPILE_INPUTS_NOT_COMPLETE_FIXTURE_LOCK','complete':False,'packages':selection['packages'],'selected_files_sha256':hashlib.sha256((b/'sysroot-file-selection.json').read_bytes()).hexdigest(),'headers_sha256':hashlib.sha256((b/'preprocessor-inputs.json').read_bytes()).hexdigest(),'runtime_graph_sha256':hashlib.sha256((b/'runtime-elf-graph.json').read_bytes()).hexdigest(),'tools':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [compiler,linker,ndk/'source.properties']},'elf':elf,'blockers':['Android executor and negative behavior incomplete','No runtime GIO/backend/rename/proot verification','Runtime graph was derived before further source edits; must regenerate on final producer','Toolchain host DT_NEEDED graph not frozen','No CI frame readback proof']}
(b/'compile-inputs.lock.json').write_text(json.dumps(lock,indent=2)+'\n')
