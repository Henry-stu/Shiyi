"""Run device regression with a private before/after data comparison and preference restore."""
from pathlib import Path
import os,sys,json,subprocess,hashlib
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from device_qa import ADB,SERIAL
from database_snapshot import snapshot
OUT=ROOT/'qa'/os.environ.get('RECALL_QA_RUN_NAME','import-v130')/SERIAL;OUT.mkdir(parents=True,exist_ok=True)
def call(*args,check=True):
    return subprocess.run([ADB,'-s',SERIAL,*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=check)
def state():
    if call('shell','run-as','com.hh.recall','ls','databases/recall.db',check=False).returncode:
        return dict(version=2,articles=[],folders=[],drafts=[])
    return snapshot()
for name,path in [('app','app/build/outputs/apk/debug/app-debug.apk'),('test','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
    result=call('install','-r',str(ROOT/path));(OUT/(name+'-install.txt')).write_bytes(result.stdout+result.stderr)
call('shell','am','force-stop','com.hh.recall')
before=state();(OUT/'database-before.json').write_text(json.dumps(before,ensure_ascii=False,indent=2),'utf-8')
prefs=call('exec-out','run-as','com.hh.recall','cat','shared_prefs/settings.xml',check=False)
has_prefs=prefs.returncode==0
if has_prefs:(OUT/'settings-before.xml').write_bytes(prefs.stdout)
try:
    result=subprocess.run([sys.executable,str(ROOT/'scripts/run_device_tests.py'),*sys.argv[1:]],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    (OUT/'regression-console.txt').write_bytes(result.stdout);print(result.stdout.decode('utf-8',errors='replace'),flush=True)
finally:
    call('shell','am','force-stop','com.hh.recall')
    after=state();(OUT/'database-after.json').write_text(json.dumps(after,ensure_ascii=False,indent=2),'utf-8')
    if has_prefs:
        subprocess.run([ADB,'-s',SERIAL,'shell','run-as','com.hh.recall','tee','shared_prefs/settings.xml'],input=prefs.stdout,stdout=subprocess.DEVNULL,check=True)
    else:call('shell','run-as','com.hh.recall','rm','-f','shared_prefs/settings.xml')
    restored=call('exec-out','run-as','com.hh.recall','cat','shared_prefs/settings.xml',check=False)
    preserved=before==after;preferences_preserved=(restored.returncode==0 and restored.stdout==prefs.stdout) if has_prefs else restored.returncode!=0
    (OUT/'preservation.json').write_text(json.dumps(dict(database_rows_unchanged=preserved,preferences_restored=preferences_preserved,
        counts={k:len(before[k]) for k in ['articles','folders','drafts']}),indent=2),'utf-8')
    assert preserved,'Personal database rows changed; inspect private QA snapshots'
    assert preferences_preserved,'Preferences were not restored'
assert result.returncode==0,'Device regression failed'
print(SERIAL,'REGRESSION PASSED; DATA AND PREFERENCES PRESERVED',flush=True)
