import copy,json,pathlib,unittest
from test_evidence import Evidence
import check_case as c
class Schema3(Evidence):
 def setgeom(self,case,g):
  d=self.e/case
  # Independent fixture arithmetic, not imported from checker.
  rw,rh,dw,dh,x,y=g;roi=[rw//5,rh*35//100,rw*3//5,rh*3//10]
  def intersect(a):
   l=max(a[0],x);t=max(a[1],y);r=min(a[0]+a[2],x+dw);b=min(a[1]+a[3],y+dh)
   return [l,t,r-l,b-t] if r>l and b>t else None
  ri=intersect(roi);fit=g[:2]==g[2:4] and g[4:]==[0,0]
  for i in range(5):
   f=d/f'managed-{i}-capture-bracket.json';v=json.loads(f.read_text());v.update(before=g,after=g,roi_root=roi,root_desktop_intersection=intersect([0,0,rw,rh]),roi_desktop_intersection=ri,root_roi_contained=ri==roi,roi_coverage='FULL' if ri==roi else 'NONE' if ri is None else 'PARTIAL');f.write_text(json.dumps(v))
  p=d/'managed-comparison.txt';s=p.read_text();import re;s=re.sub(r'fit=(true|false)','fit='+str(fit).lower(),s);p.write_text(s)
  return d
 def test_original_limits_rotation_disjoint(self):
  for g in [[1080,1949,1280,1949,0,0],[896,1570,1280,1570,0,0],[800,1382,1280,1382,0,0],[1800,414,1280,1382,0,0],[800,1800,800,2000,0,0],[800,1800,1280,2000,0,0],[800,1800,800,1800,0,48],[800,1800,802,1802,-1,-1],[800,1800,800,1800,800,0],[800,1800,800,1800,1000,0],[803,1387,1280,1387,0,0]]:
   with self.subTest(g=g):self.assertIn('MANUAL_REVIEW_REQUIRED',c.check(self.setgeom('original',g),'original','a'*64))
 def test_shift48_full_not_fit(self):
  d=self.setgeom('patched',[896,1570,896,1570,0,48]);b=json.loads((d/'managed-0-capture-bracket.json').read_text());self.assertEqual(b['roi_coverage'],'FULL')
  p=d/'managed-comparison.txt';p.write_text(p.read_text().replace('fit=false','fit=true'))
  with self.assertRaises(ValueError):c.check(d,'patched','a'*64)
 def test_t3_values_and_tamper(self):
  d=self.setgeom('original',[1800,414,1280,1382,0,0]);f=d/'managed-0-capture-bracket.json';base=json.loads(f.read_text());self.assertEqual(base['roi_root'],[360,144,1080,124]);self.assertEqual(base['roi_desktop_intersection'],[360,144,920,124])
  for k,v in [('roi_root',[360,144,920,124]),('roi_coverage','FULL'),('root_roi_contained',True),('roi_desktop_intersection',None),('root_desktop_intersection',[0,0,1800,414]),('schema',2),('after',[1800,414,1281,1382,0,0]),('occluders',['2'])]:
   b=copy.deepcopy(base);b[k]=v;f.write_text(json.dumps(b))
   with self.subTest(k=k),self.assertRaises(ValueError):c.check(d,'original','a'*64)
  f.write_text(json.dumps(base));(d/'managed-input.png').unlink()
  with self.assertRaises(ValueError):c.check(d,'original','a'*64)
 def test_null_not_zeroarea_and_missing_fields(self):
  d=self.setgeom('original',[800,1800,800,1800,800,0]);f=d/'managed-0-capture-bracket.json';b=json.loads(f.read_text());self.assertIsNone(b['root_desktop_intersection']);b['root_desktop_intersection']=[800,0,0,1800];f.write_text(json.dumps(b))
  with self.assertRaises(ValueError):c.check(d,'original','a'*64)
 def test_aggregate_rejects_schema2(self):
  from unittest.mock import patch
  import split_join,transport,shutil
  receipts=[];dirs=[]
  for case,role in [('original','first'),('patched','second')]:
   d=self.p/role;d.mkdir();shutil.copytree(self.e/case,d/case)
   binding={'role':role};receipt={'binding':binding,'manifest_pins':{case:'a'*64}};receipts.append(receipt);dirs.append(d)
   (d/'coverage.json').write_text(json.dumps({'complete':True,'expected_case':case,'binding':binding,'issues':[],'selected':['host-phase.txt','runtime-sdk.json']+[case+'/'+n for n in transport.CASE_FILES],'private_excluded':True}))
   (d/'runtime-sdk.json').write_text(json.dumps({'schema':1,'case':case,'binding':binding,'properties':{'Pkg.Revision':'37.2.13','Pkg.BuildId':'16740729'}}))
  with patch.object(split_join.repro,'join',return_value={}):self.assertEqual(split_join.join(*receipts,*dirs)['presentation_binding'],'UNVERIFIED')
  f=dirs[0]/'original/managed-0-capture-bracket.json';b=json.loads(f.read_text());b['schema']=2;f.write_text(json.dumps(b))
  with patch.object(split_join.repro,'join',return_value={}),self.assertRaises(ValueError):split_join.join(*receipts,*dirs)
 def test_source_scope_math_and_occluders(self):
  r=pathlib.Path(__file__).parents[2];s=(r/'app/src/androidTest/java/dev/phonelinux/proto/MobileDesktopEntryTest.kt').read_text();self.assertIn('ManagedComparisonGeometry.occluders(roi,candidates.single(),windows)',s);self.assertIn('if(case=="patched")check(fit&&geometry.fit&&geometry.contained&&geometry.coverage=="FULL")',s)
if __name__=='__main__':unittest.main()
