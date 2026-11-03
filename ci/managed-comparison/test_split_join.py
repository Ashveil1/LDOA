import json,copy,unittest,pathlib,subprocess,os,tempfile
from test_evidence import Evidence
from test_paired_repro import Repro
import transport as t, split_join as s
class Aggregate(Evidence):
 def test_real_both_case_join_and_replay_rejections(self):
  a=Repro().base('first');b=Repro().base('second')
  a['manifest_pins']['original']='a'*64;b['manifest_pins']['patched']='a'*64
  dirs=[]
  for case,receipt in [('original',a),('patched',b)]:
   out=self.p/case;out.mkdir();(out/case).mkdir()
   import shutil
   for f in (self.e/case).iterdir():shutil.copyfile(f,out/case/f.name)
   coverage={'complete':True,'expected_case':case,'binding':receipt['binding'],'issues':[],'private_excluded':True,'selected':['host-phase.txt','runtime-sdk.json']+[case+'/'+n for n in t.CASE_FILES]}
   (out/'coverage.json').write_text(json.dumps(coverage));(out/'runtime-sdk.json').write_text(json.dumps({'schema':1,'case':case,'binding':receipt['binding'],'properties':{'Pkg.Revision':'37.2.13','Pkg.BuildId':'16740729'}}));dirs.append(out)
  self.assertIn('MANUAL_REVIEW_REQUIRED',s.join(a,b,*dirs)['result'])
  for key,value in [('run_id','2'),('run_attempt','2'),('sha','b'*40),('job','repro_first')]:
   altered=copy.deepcopy(b);altered['binding'][key]=value
   with self.subTest(key=key),self.assertRaises(ValueError):s.join(a,altered,*dirs)
  coverage=json.loads((dirs[1]/'coverage.json').read_text());coverage['binding']['run_id']='2';(dirs[1]/'coverage.json').write_text(json.dumps(coverage))
  with self.assertRaises(ValueError):s.join(a,b,*dirs)
 def test_runtime_sdk_exact_and_duplicate_refusal(self):
  for props,ok in [('Pkg.Revision = 37.2.13\nPkg.BuildId = 16740729\n',True),('Pkg.Revision=37.2.14\nPkg.BuildId=16740729\n',False),('Pkg.Revision=37.2.13\nPkg.BuildId=16740729\nPkg.BuildId=16740729\n',False)]:
   with tempfile.TemporaryDirectory() as tmp:
    p=pathlib.Path(tmp);(p/'emulator').mkdir();(p/'emulator/source.properties').write_text(props);(p/'comparison-evidence').mkdir()
    env={**os.environ,'ANDROID_HOME':tmp,'GITHUB_WORKSPACE':tmp,'GITHUB_REPOSITORY':'Ashveil1/LDOA','GITHUB_RUN_ID':'1','GITHUB_RUN_ATTEMPT':'1','GITHUB_SHA':'a'*40,'GITHUB_JOB':'repro_first'}
    rc=subprocess.run([os.sys.executable,str(pathlib.Path(__file__).with_name('runtime_sdk.py')),'original'],env=env,capture_output=True).returncode
    self.assertEqual(rc==0,ok)
if __name__=='__main__':unittest.main()
