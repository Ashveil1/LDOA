import pathlib,json,unittest,subprocess,re
import prepare_incoming as p
P=pathlib.Path(__file__).parent
class Names(unittest.TestCase):
 def test_source_resource_repro(self):
  paths=['payload/'+n for n in json.loads((P/'previous-source-resource-manifest.json').read_text())]
  old=lambda n:bool(re.fullmatch('[A-Za-z0-9_./+-]+',n)) and not n.startswith('/') and all(v not in ('','..','.') for v in n.split('/'))
  self.assertEqual([n for n in paths if not old(n)],['payload/share/locale/sr@latin/LC_MESSAGES/pcmanfm.mo'])
  self.assertTrue(all(p.safe(n) for n in paths))
 def test_exact_exception_and_refusals(self):
  for n in ['payload/share/locale/sr@latin','payload/share/locale/sr@latin/LC_MESSAGES','payload/share/locale/sr@latin/LC_MESSAGES/pcmanfm.mo']:self.assertTrue(p.safe(n))
  for n in ['payload/share/locale/sr@latin/extra','source/sr@latin','payload/share/locale/evil@latin/LC_MESSAGES/pcmanfm.mo','../evil','payload/../evil','/absolute','payload//x','payload/./x','payload/x\ny','payload/a;id','payload/$(id)']:self.assertFalse(p.safe(n),n)
if __name__=='__main__':unittest.main()
