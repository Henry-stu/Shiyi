"""Compare saved pre-test rows and restore pre-test preferences without re-running tests."""
import json,subprocess,os
from pathlib import Path
from device_qa import ADB,SERIAL
from database_snapshot import snapshot
out=Path(__file__).resolve().parents[1]/'qa'/os.environ.get('RECALL_QA_RUN_NAME','import-v130')/SERIAL
subprocess.run([ADB,'-s',SERIAL,'shell','am','force-stop','com.hh.recall'],check=True)
before=json.loads((out/'database-before.json').read_text('utf-8'));after=snapshot()
(out/'database-after.json').write_text(json.dumps(after,ensure_ascii=False,indent=2),'utf-8')
pref=out/'settings-before.xml'
if pref.exists():
    subprocess.run([ADB,'-s',SERIAL,'shell','run-as','com.hh.recall','tee','shared_prefs/settings.xml'],input=pref.read_bytes(),stdout=subprocess.DEVNULL,check=True)
    actual=subprocess.check_output([ADB,'-s',SERIAL,'exec-out','run-as','com.hh.recall','cat','shared_prefs/settings.xml'])
    restored=actual==pref.read_bytes()
else:
    subprocess.run([ADB,'-s',SERIAL,'shell','run-as','com.hh.recall','rm','-f','shared_prefs/settings.xml'],check=True);restored=True
result=dict(database_rows_unchanged=before==after,preferences_restored=restored,counts={k:len(before[k]) for k in ['articles','folders','drafts']})
(out/'preservation.json').write_text(json.dumps(result,indent=2),'utf-8')
assert result['database_rows_unchanged'] and result['preferences_restored']
print(SERIAL,result)
