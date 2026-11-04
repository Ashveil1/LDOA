#!/usr/bin/env python3
"""Owned emulator lifecycle source only. No broad pkill/device discovery or software fallback."""
import subprocess,os,signal,time,pathlib
class OwnedEmulator:
 def __init__(self,executable,avd_home):
  self.child=None;self.executable=executable;self.avd_home=avd_home
 def start(self):
  # AVD must be private fixed-name image, prepared by reviewed bootstrap; no user AVD.
  if not os.access('/dev/kvm',os.R_OK|os.W_OK):raise ValueError('KVM_REQUIRED')
  env={**os.environ,'ANDROID_AVD_HOME':str(self.avd_home)}
  self.child=subprocess.Popen([str(self.executable),'-avd','hs-fixture','-port','5554','-accel','on','-no-window','-no-audio','-no-snapshot','-wipe-data','-gpu','swiftshader_indirect'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
 def close(self):
  if self.child is None:return 'NOT_LAUNCHED'
  # Only this launched process group. Group membership/guest residual absence not claimed.
  try:os.killpg(self.child.pid,signal.SIGTERM)
  except ProcessLookupError:pass
  try:self.child.wait(timeout=20)
  except subprocess.TimeoutExpired:
   try:os.killpg(self.child.pid,signal.SIGKILL)
   except ProcessLookupError:pass
   try:self.child.wait(timeout=10)
   except subprocess.TimeoutExpired:return 'UNKNOWN'
  return 'OWNED_EMULATOR_EXITED_NOT_GUEST_ABSENCE_PROOF'
