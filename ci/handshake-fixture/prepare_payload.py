#!/usr/bin/env python3
"""Author isolated generated payload and pins. Does not invoke Gradle, build or run APK."""
import argparse,pathlib,json,hashlib,subprocess,shutil
from source_inventory import inventory, source_pin
ap=argparse.ArgumentParser();ap.add_argument('--repository',required=True);ap.add_argument('--runtime-zip',required=True);ap.add_argument('--out',required=True);a=ap.parse_args();b=pathlib.Path(__file__).parent;repo=pathlib.Path(a.repository);out=pathlib.Path(a.out)
h=lambda p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
if out.exists():raise ValueError('OUTPUT_EXISTS')
native=json.loads((b/'android-native-inputs.json').read_text());fresh=json.loads((b/'compile-freshness.json').read_text());runtime=json.loads((b/'runtime-asset-plan.json').read_text());zipfile=pathlib.Path(a.runtime_zip)
# Native provenance is original commit, not a requirement that future candidate HEAD stay old.
# Validate original committed bytes AND current input bytes against each fixed native pin.
for r in native['files']:
 original=subprocess.check_output(['git','-C',str(repo),'show',native['repository_commit']+':'+r['repository_path']])
 if hashlib.sha256(original).hexdigest()!=r['sha256']:raise ValueError('NATIVE_PROVENANCE_COMMIT')
for path,pin in {**fresh['source_files'],**fresh['bound_evidence']}.items():
 if h(b/path)!=pin:raise ValueError('STALE_COMPILE_EVIDENCE')
if h(zipfile)!=runtime['zip_sha256'] or zipfile.stat().st_size!=runtime['zip_bytes']:raise ValueError('RUNTIME_ZIP_PIN')
# Source pin contains isolated authored inputs, not generated outputs or private parent checkout.
source_rows=inventory(b)
source={r['path']:r['sha256'] for r in source_rows}
sourcepin=source_pin(source_rows)
nativepin=h(b/'android-native-inputs.json');guestpin=h(b/'runtime-asset-plan.json')
for r in native['files']:
 p=repo/r['repository_path']
 if h(p)!=r['sha256'] or p.stat().st_size!=r['bytes']:raise ValueError('NATIVE_PIN')
out.mkdir(mode=0o700);assets=out/'assets';assets.mkdir();libs=out/'jniLibs/x86_64';libs.mkdir(parents=True)
for r in native['files']:shutil.copyfile(repo/r['repository_path'],libs/r['name'])
shutil.copyfile(zipfile,assets/'runtime.zip');shutil.copyfile(b/'runtime-asset-plan.json',assets/'runtime.manifest.json')
java=out/'java/dev/phonelinux/handshakefixture';java.mkdir(parents=True)
(java/'FixedInputs.java').write_text('package dev.phonelinux.handshakefixture;\nfinal class FixedInputs {\n'+''.join(' static final String '+k+'="'+v+'";\n' for k,v in {'SOURCE':sourcepin,'NATIVE':nativepin,'GUEST':guestpin,'RUNTIME_ZIP':runtime['zip_sha256']}.items())+'}\n')
(out/'payload-binding.json').write_text(json.dumps({'scope':'LOCAL_PREPARED_PAYLOAD_NO_APK_BUILD','source_pin':sourcepin,'source_files':source,'native_pin':nativepin,'guest_pin':guestpin,'runtime_zip_sha256':runtime['zip_sha256'],'files':{str(p.relative_to(out)):h(p) for p in sorted(out.rglob('*')) if p.is_file()},'apk_pin':'UNAVAILABLE_NOT_BUILT','test_apk_pin':'UNAVAILABLE_NOT_BUILT'},indent=2)+'\n')
print('LOCAL_PAYLOAD_PREPARED_NOT_APK')
