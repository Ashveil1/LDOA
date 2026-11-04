import unittest,tarfile,io,pathlib,tempfile
from guest_data_only import safe_name,selected,link_target,inventory_tar,link_errors,Stop
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
