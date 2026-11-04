"""Source contract plus synthetic poison-delegate model, NOT a Groovy interpreter."""
import unittest,pathlib,re,types
SOURCE=pathlib.Path(__file__).with_name('local-repositories.init.gradle')
class Binding(unittest.TestCase):
 def test_exact_explicit_source_binding(self):
  text=SOURCE.read_text();tail=text.split('gradle.projectsEvaluated {',1)[1]
  for required in ['evaluatedGradle.getRootProject()','capturedRoot.getAllprojects()','capturedRoot.getRootDir()','capturedRoot.getTasks().register','resolverTask.doLast','capturedProjects.each','selectedProject.getConfigurations()','configuration.isCanBeResolved()','configuration.resolve()','configuration.getResolvedConfiguration().getResolvedArtifacts()','artifact.getFile().length()','capturedOutput.text=']:
   self.assertIn(required,tail)
  for bare in [r'(?<![.\w])allprojects\b',r'(?<![.\w])rootProject\b',r'(?<![.\w])rootDir\b',r'(?<![.\w])configurations\b',r'(?<![.\w])doLast\b',r'\bit\b']:
   self.assertIsNone(re.search(bare,tail),bare)
  self.assertNotIn('url=uri(',text)
 def test_poison_task_delegate_synthetic_model(self):
  # Independent model illustrates the captured-object invariant; does not validate Groovy syntax/runtime.
  class Poison:
   def __getattr__(self,name):raise AssertionError('TASK_DELEGATE_ACCESSED:'+name)
  seen=[]
  class Configuration:
   def __init__(self,name,resolvable):self.name=name;self.resolvable=resolvable
   def resolve(self):seen.append(self.name)
  projects=[types.SimpleNamespace(path=':fixture',configurations=[Configuration('z',True),Configuration('notResolvable',False)]),types.SimpleNamespace(path=':',configurations=[Configuration('a',True)])]
  root=types.SimpleNamespace(allprojects=projects,rootDir='/synthetic-root')
  capturedProjects=sorted(root.allprojects,key=lambda p:p.path);capturedOutput=root.rootDir+'/selected-inputs.json'
  def action(ignoredTask):
   rows=[]
   for selectedProject in capturedProjects:
    for configuration in sorted((c for c in selectedProject.configurations if c.resolvable),key=lambda c:c.name):
     configuration.resolve();rows.append((selectedProject.path,configuration.name))
   return rows,capturedOutput
  self.assertEqual(action(Poison()),([(':','a'),(':fixture','z')],'/synthetic-root/selected-inputs.json'));self.assertEqual(seen,['a','z'])
if __name__=='__main__':unittest.main()
