import sys,time,subprocess
from device_qa import *
root=OUT.parent
for kind,apk in [('app','app/build/outputs/apk/debug/app-debug.apk'),('tests','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
    log_path=OUT/(kind+'-install.txt')
    with log_path.open('w',encoding='utf-8') as log:
        p=subprocess.Popen([ADB,'-s',SERIAL,'install','-r',str(root/apk)],stdout=log,stderr=subprocess.STDOUT)
        time.sleep(2)
        if p.poll() is None:
            try: tap('安装')
            except RuntimeError: pass
        p.wait(timeout=30)
    print(log_path.read_text(encoding='utf-8'),flush=True)
    if p.returncode:sys.exit(p.returncode)
def blocked_launch():
    logs=adb('logcat','-d','-s','ActivityTaskManager:I').decode(errors='replace')
    lines=[s for s in logs.splitlines() if 'cmp=com.hh.recall/androidx.activity.ComponentActivity' in s and 'result code=102' in s]
    return lines[-1] if lines else ''
last_block=blocked_launch()
log_path=OUT/('device-tests-'+SERIAL+'.txt')
with log_path.open('w',encoding='utf-8') as log:
    args=[ADB,'-s',SERIAL,'shell','am','instrument','-w','-r']
    if len(sys.argv)>1: args += ['-e','class',sys.argv[1]]
    p=subprocess.Popen(args+['com.hh.recall.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
    deadline=time.monotonic()+360
    while p.poll() is None and time.monotonic()<deadline:
        time.sleep(1.5)
        blocked=blocked_launch()
        if blocked and blocked != last_block:
            last_block=blocked
            adb('shell','am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-f','0x10008000','-n','com.hh.recall/androidx.activity.ComponentActivity')
    if p.poll() is None:
        adb('shell','am','force-stop','com.hh.recall');p.wait(timeout=10)
print(log_path.read_text(encoding='utf-8'),flush=True)

if 'OK (' not in log_path.read_text(encoding='utf-8'):
    sys.exit(1)
