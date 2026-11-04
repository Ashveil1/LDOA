import unittest,tarfile,io,pathlib,tempfile
from guest_data_only import safe_name,selected,link_target,inventory_tar,link_errors,Stop,OPTIONAL_GCC_ALIASES,GCC_PREFIX,PRODUCER_RECIPE_SHA256
class Data(unittest.TestCase):
 def test_paths_and_links(self):
  for n in ['/etc/a','../x','./a/../../b']:
   with self.assertRaises(Stop):safe_name(n)
  self.assertEqual(link_target('usr/lib/a.so','a.so.1'),'usr/lib/a.so.1')
  self.assertEqual(link_target('usr/lib/a.so','/lib/a.so.1'),'lib/a.so.1')
  with self.assertRaises(Stop):link_target('a','../../escape')
 def test_link_cycle_and_missing(self):
  self.assertEqual(link_errors({'a':{'type':'symlink','normalized_target':'b'},'b':{'type':'symlink','normalized_target':'a'}})[0]['reason'],'LINK_CYCLE')
  self.assertEqual(link_errors({'a':{'type':'symlink','normalized_target':'missing'}})[0]['reason'],'LINK_TARGET_MISSING')
 def test_nine_exclusions_only_core_preserved(self):
  self.assertEqual(len(OPTIONAL_GCC_ALIASES),9)
  for name in OPTIONAL_GCC_ALIASES:self.assertFalse(selected(GCC_PREFIX+name))
  for name in ['crtbegin.o','crtbeginS.o','crtend.o','crtendS.o','libgcc.a','libgcc_eh.a','libgcc_s.so','libasan.a']:
   self.assertTrue(selected(GCC_PREFIX+name))
  self.assertTrue(selected('usr/lib/x86_64-linux-gnu/libasan.so.8'))
 def test_exclusion_is_type_target_package_checked(self):
  for target,package,okay in [('wrong','libgcc-13-dev',False),(OPTIONAL_GCC_ALIASES['libasan.so'],'other',False),(OPTIONAL_GCC_ALIASES['libasan.so'],'libgcc-13-dev',True)]:
   b=io.BytesIO()
   with tarfile.open(fileobj=b,mode='w') as t:
    m=tarfile.TarInfo(GCC_PREFIX+'libasan.so');m.type=tarfile.SYMTYPE;m.linkname=target;t.addfile(m)
   with tempfile.TemporaryDirectory() as d,tarfile.open(fileobj=io.BytesIO(b.getvalue())) as t:
    if okay:
     ex=[];inventory_tar(t,package,{},pathlib.Path(d),0,ex);self.assertEqual(len(ex),1)
    else:
     with self.assertRaises(Stop):inventory_tar(t,package,{},pathlib.Path(d),0,[])
 def test_recipe_hash_and_flag_assumptions(self):
  import hashlib,ast
  p=pathlib.Path(__file__).resolve().parents[1]/'build_selected.py'
  self.assertEqual(hashlib.sha256(p.read_bytes()).hexdigest(),PRODUCER_RECIPE_SHA256)
  tree=ast.parse(p.read_text());cmd=next(n.value for n in ast.walk(tree) if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='cmd' for t in n.targets))
  flags=[n.value for n in cmd.elts if isinstance(n,ast.Constant) and isinstance(n.value,str)]
  for flag in ['--target=x86_64-linux-gnu','-fuse-ld=lld','-O2','-g0','-pthread','-lgio-2.0','-lgobject-2.0','-lglib-2.0','-Wl,-t']:self.assertIn(flag,flags)
  for flag in flags:
   self.assertFalse(any(x in flag for x in ['sanitize','openmp','gnu-tm','-latomic','-lquadmath','-fprofile','coverage']))
 def test_selection(self):
  self.assertTrue(selected('usr/include/glib-2.0/gio/gio.h'));self.assertTrue(selected('usr/lib/gcc/x86_64-linux-gnu/13/crtbegin.o'))
  for n in ['usr/bin/tool','DEBIAN/postinst','usr/lib/x86_64-linux-gnu/gio/modules/plugin.so']:self.assertFalse(selected(n))
 def test_no_symlink_materialization_and_collision_fail(self):
  b=io.BytesIO()
  with tarfile.open(fileobj=b,mode='w') as t:
   m=tarfile.TarInfo('usr/include/x.h');m.size=1;t.addfile(m,io.BytesIO(b'x'))
   m=tarfile.TarInfo('usr/lib/x86_64-linux-gnu/x.so');m.type=tarfile.SYMTYPE;m.linkname='x.so.1';t.addfile(m)
  with tempfile.TemporaryDirectory() as d:
   records={}
   with tarfile.open(fileobj=io.BytesIO(b.getvalue()),mode='r:') as t:inventory_tar(t,'p',records,pathlib.Path(d),0)
   self.assertEqual(len(records),2);self.assertFalse((pathlib.Path(d)/'usr/lib/x86_64-linux-gnu/x.so').exists())
   with tarfile.open(fileobj=io.BytesIO(b.getvalue()),mode='r:') as t,self.assertRaises(Stop):inventory_tar(t,'p',records,pathlib.Path(d),0)
if __name__=='__main__':unittest.main()
