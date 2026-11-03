import pathlib,tempfile,unittest,json,sys
from unittest.mock import patch
import os
import transport as t
import split_join as s
ENV={'GITHUB_REPOSITORY':'Ashveil1/LDOA','GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1','GITHUB_SHA':'a'*40,'GITHUB_JOB':'repro_first'}
@patch.dict(os.environ,ENV)
class Scope(unittest.TestCase):
 def test_explicit_case_collection(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=pathlib.Path(tmp);e=root/'e';e.mkdir();p=root/'private';p.mkdir();(e/'host-phase.txt').write_text('schema=1\nphase=complete case=both rc=0\n');c=e/'original';c.mkdir();(e/'runtime-sdk.json').write_text('{}')
   for n in t.CASE_FILES:(c/n).write_bytes(b'fixture')
   (c/'transfer-guards.txt').write_text('schema=1\nguard=context-identity rc=0\n')
   self.assertTrue(t.collect(e,p,root/'one','original'))
   coverage=json.loads((root/'one/coverage.json').read_text());self.assertEqual(coverage['expected_case'],'original');self.assertTrue(coverage['complete'])
   self.assertFalse(t.collect(e,p,root/'both'))
   (c/'managed-2-capture-bracket.json').unlink();self.assertFalse(t.collect(e,p,root/'missing','original'))
 def test_aggregate_binds_local_device_manifest(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=pathlib.Path(tmp);a=root/'a';b=root/'b';a.mkdir();b.mkdir()
   for d,c in [(a,'original'),(b,'patched')]:
    (d/c).mkdir();(d/'coverage.json').write_text(json.dumps({'complete':True,'expected_case':c}))
   first={'binding':{'role':'first'},'manifest_pins':{'original':'a'*64}};second={'binding':{'role':'second'},'manifest_pins':{'patched':'b'*64}}
   with patch.object(s.repro,'join',return_value={}),patch.object(s.check_case,'check',return_value=True) as cc:
    
    for d,r in [(a,first),(b,second)]:
     v=json.loads((d/'coverage.json').read_text());v['binding']=r['binding'];v['issues']=[];v['private_excluded']=True;v['selected']=['host-phase.txt','runtime-sdk.json']+[v['expected_case']+'/'+n for n in t.CASE_FILES];(d/'coverage.json').write_text(json.dumps(v));(d/'runtime-sdk.json').write_text(json.dumps({'schema':1,'case':v['expected_case'],'binding':r['binding'],'properties':{'Pkg.Revision':'37.2.13','Pkg.BuildId':'16740729'}}))
    s.join(first,second,a,b);self.assertEqual([v.args[1:] for v in cc.call_args_list],[('original','a'*64),('patched','b'*64)])
   (b/'coverage.json').write_text('{"complete":true,"expected_case":"original"}')
   with patch.object(s.repro,'join',return_value={}),self.assertRaises(ValueError):s.join(first,second,a,b)
 def test_bracket_schema_false_not_implicit_pixels(self):
  source=(pathlib.Path(__file__).parents[2]/'app/src/androidTest/java/dev/phonelinux/proto/MobileDesktopEntryTest.kt').read_text()
  self.assertIn('"manual_pixels",true',source);self.assertIn('check(after==before&&clearROI().isEmpty())',source)
  self.assertLess(source.index('guest("xdotool","windowminimize",xtId.toString())',source.index('private fun managedComparison')),source.index('for((i,wh) in sizes.withIndex())',source.index('private fun managedComparison')))
if __name__=='__main__':unittest.main()
