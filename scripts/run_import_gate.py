"""Verify relocated extraction code using the independent lab's ten fixed outputs."""
from pathlib import Path
import subprocess,os
from device_qa import ADB,SERIAL
root=Path(__file__).resolve().parents[1];out=root/'qa/import-v130'/SERIAL;out.mkdir(parents=True,exist_ok=True)
for apk in ['app/build/outputs/apk/debug/app-debug.apk','qa/import-v130/gate-test.apk']:
    subprocess.run([ADB,'-s',SERIAL,'install','-r',str(root/apk)],check=True,stdout=subprocess.DEVNULL)
result=subprocess.run([ADB,'-s',SERIAL,'shell','am','instrument','-w','-r','-e','class','com.hh.recall.ImportExtractionDeviceTest',
    'com.hh.recall.test/androidx.test.runner.AndroidJUnitRunner'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=240)
(out/'extraction-gate.txt').write_bytes(result.stdout)
assert result.returncode==0 and b'OK (1 test)' in result.stdout,result.stdout.decode(errors='replace')
print(SERIAL,'10 extraction outputs match lab; one test passed',flush=True)
