"""Audit final non-debuggable APK in place; do not emit any personal document text."""
from pathlib import Path
import subprocess,json,re,hashlib,struct,os
from device_qa import ADB,SERIAL
root=Path(__file__).resolve().parents[1];run=root/'qa'/os.environ.get('RECALL_QA_RUN_NAME','import-v130');out=run/SERIAL;out.mkdir(parents=True,exist_ok=True)
version=re.search(r'versionName\s*=\s*"([^"]+)"',(root/'app/build.gradle.kts').read_text('utf-8')).group(1)
def adb(*args):return subprocess.check_output([ADB,'-s',SERIAL,*args],stderr=subprocess.STDOUT)
def digest_rows(rows):
    digest=hashlib.sha256()
    for row in rows:
        for column in sorted(row):
            value=row[column];value='N' if value is None else ('I'+str(value) if isinstance(value,int) else 'S'+str(value))
            for data in [column.encode('utf-8'),value.encode('utf-8')]:digest.update(struct.pack('>I',len(data)));digest.update(data)
    return dict(count=len(rows),sha256=digest.hexdigest())
adb('shell','am','force-stop','com.hh.recall')
adb('install','-r',str(run/'release-audit-test.apk'))
text=adb('shell','am','instrument','-w','-r','com.hh.recall.test/com.hh.recall.ReleaseAuditInstrumentation').decode('utf-8')
(out/'release-audit.txt').write_text(text,'utf-8')
match=re.search(r'RECALL_RELEASE_AUDIT (\{[^\n]+\})',text);assert match and 'INSTRUMENTATION_CODE: -1' in text,text
result=json.loads(match.group(1));before=json.loads((out/'database-before.json').read_text('utf-8'))
assert result['database_version']==before['version']==2
for table in ['articles','folders','drafts']:assert digest_rows(before[table])==result['tables'][table],table
assert result['version']==version and result['debuggable'] is False
for model,hashes in result['models'].items():
    expected=hashlib.sha256((root/'app/src/main/assets/models'/model).read_bytes()).hexdigest()
    assert expected==hashes['asset_sha256']==hashes['cache_sha256']
apk=root/'app/build/outputs/apk/release/app-release.apk';installed=adb('shell','pm','path','com.hh.recall').decode().strip().removeprefix('package:')
assert len(installed.splitlines())==1
actual=adb('shell','sha256sum',installed).decode().split()[0];assert actual==hashlib.sha256(apk.read_bytes()).hexdigest()
result.update(database_rows_unchanged=True,installed_release_sha256=actual)
(out/'release-audit.json').write_text(json.dumps(result,indent=2),'utf-8')
adb('uninstall','com.hh.recall.test')
print(SERIAL,'Release '+version+' verified; existing rows and model hashes match; test package removed',flush=True)
