import unittest
from binding_contract import bind,Stop,REPO,PATH
class Binding(unittest.TestCase):
 def pair(self):
  e={'head_sha':'a'*40,'ref':'refs/tags/example-review-only'}
  return {**e,'workflow_sha':'b'*40,'repository':REPO,'workflow_ref':REPO+'/'+PATH+'@'+e['ref']},e
 def test_non_circular_external_values(self):
  a,e=self.pair();self.assertIn('EXTERNAL_CONTROLLER',bind(a,e)['status'])
 def test_moved_head(self):
  a,e=self.pair();a['head_sha']='b'*40
  with self.assertRaisesRegex(Stop,'BINDING_SHA'):bind(a,e)
 def test_workflow_context_opaque_distinct_recorded(self):
  a,e=self.pair();self.assertEqual(bind(a,e)['actual']['workflow_sha'],'b'*40)
 def test_wrong_workflow_path(self):
  a,e=self.pair();a['workflow_ref']='wrong'
  with self.assertRaisesRegex(Stop,'BINDING_CONTEXT'):bind(a,e)
 def test_defaults_symbolic_or_rawsha_ref_rejected(self):
  for bad in ['latest','UNRESOLVED','']:
   a,e=self.pair();e['head_sha']=bad
   with self.assertRaises(Stop):bind(a,e)
  a,e=self.pair();e['ref']='a'*40
  with self.assertRaises(Stop):bind(a,e)
 def test_workflow_context_shape_only(self):
  a,e=self.pair();a['workflow_sha']='UNRESOLVED'
  with self.assertRaisesRegex(Stop,'WORKFLOW_CONTEXT_SHAPE'):bind(a,e)
 def test_git_ref_malformed_components(self):
  for ref in ['refs/heads/a//b','refs/tags/.hidden','refs/heads/a/.b','refs/tags/a.lock','refs/heads/a/','refs/heads/a.','refs/heads/a..b','refs/heads/a@{b','refs/heads/a b','refs/heads/a~b','refs/heads/a^b','refs/heads/a:b','refs/heads/a?b','refs/heads/a*b','refs/heads/a[b','refs/heads/a\\b','refs/heads/-a','refs/heads/@']:
   a,e=self.pair();a['ref']=e['ref']=ref;a['workflow_ref']=REPO+'/'+PATH+'@'+ref
   with self.subTest(ref=ref):
    with self.assertRaisesRegex(Stop,'BINDING_REF'):bind(a,e)
 def test_valid_git_ref_components(self):
  for ref in ['refs/heads/topic/a-b_c.1','refs/tags/v1.0','refs/tags/-allowedtag']:
   a,e=self.pair();a['ref']=e['ref']=ref;a['workflow_ref']=REPO+'/'+PATH+'@'+ref;self.assertTrue(bind(a,e))
if __name__=='__main__':unittest.main()
