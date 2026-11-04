#!/usr/bin/env python3
"""Future fixed bootstrap source. No top-level effects; workflow/job gates stay closed."""
import pathlib,zipfile,stat,json,os,subprocess,hashlib,time
from fetch_locked import fetch
from maven_seed import seed
from avd_prepare import prepare
class Steps:
 def __init__(self,base,repo,owner):
  self.base=base;self.repo=repo;self.work=base/'private-run';self.owner=owner;self.receipts=[];self.sdk=pathlib.Path(os.environ['ANDROID_HOME']);self.key=None
 def call(self,args,cap,env=None):
  return subprocess.run([str(a) for a in args],cwd=self.base,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=cap,check=True)
 def unpack(self,archive,dest):
  with zipfile.ZipFile(archive) as z:
   count=0;total=0
   for row in z.infolist():
    p=pathlib.PurePosixPath(row.filename);mode=row.external_attr>>16
    if p.is_absolute() or '..' in p.parts or stat.S_ISLNK(mode):raise ValueError('ZIP_PATH_OR_LINK')
    count+=1;total+=row.file_size
    if count>65536 or total>4*1024**3:raise ValueError('ZIP_EXPANSION_CAP')
    target=dest/p
    if row.is_dir():target.mkdir(parents=True,exist_ok=True);continue
    target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('xb') as out:out.write(z.read(row))
    target.chmod(0o700 if mode&0o111 else 0o600)
 def bootstrap(self):
  self.work.mkdir(mode=0o700,exist_ok=False)
  lock=json.loads((self.base/'ci-validation/google-metadata-proposal.json').read_text())
  # Host build SDK is inspected, not downloaded/updated; only emulator+image are new SDK archives.
  for row in lock['archives']:
   if row['package'] not in ['emulator','system-images;android-34;default;x86_64']:continue
   dest=self.work/('emulator.zip' if row['package']=='emulator' else 'image.zip');self.receipts.append(fetch(row,dest));self.unpack(dest,self.work/'sdk' if row['package']=='emulator' else self.work/'image')
  self.receipts+=seed(json.loads((self.base/'ci-validation/maven-download.lock.json').read_text()),self.work/'maven')
  # Image archive actual standard layout must match, never select first arbitrary directory.
  image=self.work/'image/x86_64';avd=prepare(self.work,image);self.owner.avd_home=avd;self.owner.executable=self.work/'sdk/emulator/emulator'
 def guest(self):
  cache=self.work/'debs';cache.mkdir()
  for row in json.loads((self.base/'sysroot-packages.lock.json').read_text())['packages']:
   self.receipts.append(fetch(row,cache/row['cached_filename']))
  self.call(['python3',self.base/'build_selected.py','--manifest',self.base/'sysroot-file-selection.json','--cache',cache,'--ndk',self.sdk/'ndk/29.0.14206865','--out',self.work/'compiled'],120)
  self.call(['python3',self.base/'stage_runtime.py','--compiled',self.work/'compiled','--out',self.work/'runtime.zip'],30)
  # Preserve reviewed producer/runtime pins: do not silently repin output on host difference.
  expected=json.loads((self.base/'runtime-asset-plan.json').read_text())
  if hashlib.sha256((self.work/'runtime.zip').read_bytes()).hexdigest()!=expected['zip_sha256']:raise ValueError('GUEST_OUTPUT_PIN')
 def build(self):
  # Repository and generated payload source pins must be approved together before dispatch.
  self.call(['python3',self.base/'prepare_payload.py','--repository',self.repo,'--runtime-zip',self.work/'runtime.zip','--out',self.base/'generated'],30)
  private=self.work/'hs-fixture-private';private.mkdir(mode=0o700);self.key=private/'debug.keystore'
  self.call([pathlib.Path(os.environ['JAVA_HOME'])/'bin/keytool','-genkeypair','-keystore',self.key,'-storepass','android','-alias','androiddebugkey','-keypass','android','-dname','CN=Local Fixture Debug Only','-keyalg','RSA','-keysize','2048','-validity','1'],15);self.key.chmod(0o600)
  env={**os.environ,'HS_FIXTURE_DEBUG_KEYSTORE':str(self.key)}
  gradle=pathlib.Path(os.environ['HS_REVIEWED_GRADLE_89'])
  init=self.base/'ci-validation/local_maven.init.gradle'
  self.call([gradle,'--offline','--no-daemon','--max-workers=1','-I',init,':fixture:assembleDebug',':fixture:assembleDebugAndroidTest'],600,env)
  import shutil
  shutil.copyfile(self.base/'fixture/build/outputs/apk/debug/fixture-debug.apk',self.work/'fixture-debug.apk');shutil.copyfile(self.base/'fixture/build/outputs/apk/androidTest/debug/fixture-debug-androidTest.apk',self.work/'fixture-debug-androidTest.apk')
  from artifact_check import inspect
  report=inspect(self.base,self.sdk,self.work/'fixture-debug.apk',self.work/'fixture-debug-androidTest.apk');(self.work/'artifact-binding.json').write_text(json.dumps(report,indent=2)+'\n')
 def boot(self):
  self.owner.start();adb=self.sdk/'platform-tools/adb';end=time.monotonic()+300
  while time.monotonic()<end:
   p=subprocess.run([str(adb),'-s','emulator-5554','shell','getprop','sys.boot_completed'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10)
   if p.returncode==0 and p.stdout.strip()==b'1':return
   time.sleep(2)
  raise subprocess.TimeoutExpired('EMULATOR_BOOT',300)
 def erase_key(self):
  if self.key is not None:self.key.unlink(missing_ok=True)
