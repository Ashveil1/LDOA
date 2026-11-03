import unittest,json,copy
from test_evidence import Evidence
import check_case as c
class Brackets(Evidence):
 def test_each_bracket_refusal(self):
  f=self.e/'original/managed-2-capture-bracket.json';base=json.loads(f.read_text())
  for field,value in [('case','patched'),('manifest','b'*64),('transition',1),('before',None),('after',[]),('roi_root',[0,0,0,1]),('roi_root',[900,0,1,1]),('roi_bitmap',[0,0,0,2]),('desktop_pid',124),('desktop_start','457'),('desktop_xid',0),('occluders',['123']),('xterm_unmapped',False),('manual_pixels',False)]:
   b=copy.deepcopy(base);b[field]=value;f.write_text(json.dumps(b))
   with self.subTest(field=field,value=value),self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
  f.write_text(json.dumps(base));self.assertIn('PASS',c.check(self.e/'original','original','a'*64))
 def test_missing_bracket(self):
  (self.e/'original/managed-0-capture-bracket.json').unlink()
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
if __name__=='__main__':unittest.main()
