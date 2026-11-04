#!/usr/bin/env python3
"""Fixed private AVD filesystem authoring, no sdkmanager install/update. Not invoked."""
import pathlib
# Image is verified/extracted from reviewed android34-default-x86_64r04 ZIP first.
def prepare(work,image):
 avdhome=work/'avd';avd=avdhome/'hs-fixture.avd';avd.mkdir(parents=True,exist_ok=False)
 if not all((image/n).is_file() for n in ['system.img','vendor.img','ramdisk.img','kernel-ranchu']):raise ValueError('IMAGE_LAYOUT')
 (avdhome/'hs-fixture.ini').write_text('avd.ini.encoding=UTF-8\npath='+str(avd)+'\ntarget=android-34\n')
 (avd/'config.ini').write_text('AvdId=hs-fixture\navd.ini.displayname=hs-fixture\nabi.type=x86_64\nhw.cpu.arch=x86_64\nhw.cpu.ncore=2\nhw.ramSize=2048\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\nhw.lcd.width=720\nhw.lcd.height=1280\nhw.lcd.density=320\nimage.sysdir.1='+str(image)+'\ntag.id=default\ntarget=android-34\ndisk.dataPartition.size=2G\nshowDeviceFrame=no\n')
 return avdhome
