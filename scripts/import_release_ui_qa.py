"""Final signed app's SAF and preview checks. Never confirm an import into personal data."""
from pathlib import Path
import subprocess,xml.etree.ElementTree as ET,re,time,json,struct,os
from device_qa import ADB,SERIAL
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'qa'/os.environ.get('RECALL_QA_RUN_NAME','import-v130')/SERIAL;OUT.mkdir(parents=True,exist_ok=True)
def adb(*args,timeout=90):return subprocess.check_output([ADB,'-s',SERIAL,*args],stderr=subprocess.STDOUT,timeout=timeout)
def nodes():
    adb('shell','uiautomator','dump','/data/local/tmp/recall-import-ui.xml')
    return list(ET.fromstring(adb('exec-out','cat','/data/local/tmp/recall-import-ui.xml')).iter('node'))
def tap(text=None,desc=None):
    candidates=[n for n in nodes() if (text is None or n.get('text')==text) and (desc is None or desc in n.get('content-desc',''))]
    if not candidates:raise RuntimeError('Missing UI target: '+str((text,desc)))
    a,b,c,d=map(int,re.findall(r'\d+',candidates[0].get('bounds')))
    adb('shell','input','tap',str((a+c)//2),str((b+d)//2));time.sleep(.3)
def wait(text,seconds=80):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        data=nodes()
        if any(text in n.get('text','') for n in data):return data
        time.sleep(.3)
    raise RuntimeError('UI timeout: '+text)
def shot(name):
    p=OUT/('release-'+name+'.png');p.write_bytes(adb('exec-out','screencap','-p'));return p
def scroll():
    w,h=struct.unpack('>II',adb('exec-out','screencap','-p')[16:24])
    adb('shell','input','swipe',str(w//2),str(int(h*.8)),str(w//2),str(int(h*.4)),'450')
def choose(name):
    for attempt in range(6):
        data=nodes()
        if any(n.get('text')==name for n in data):
            tap(name)
            if any(n.get('text')=='确定' for n in nodes()):tap('确定')
            return
        for folder in ['RecallImportLab','Download','下载']:
            if any(n.get('text')==folder for n in data):tap(folder);break
        else:raise RuntimeError('Open picker manually to Download/RecallImportLab; expected '+name)
    raise RuntimeError('Fixture missing '+name)
def picker(label):tap(desc='导入文件');tap(label)
def discard():tap(desc='返回');tap('放弃导入');wait('把知识')
def run():
    rotation={k:adb('shell','settings','get','system',k).decode().strip() for k in ['accelerometer_rotation','user_rotation']}
    (OUT/'release-rotation-before.json').write_text(json.dumps(rotation),'utf-8');checks=[]
    try:
        adb('install','-r',str(ROOT/'app/build/outputs/apk/release/app-release.apk'))
        adb('shell','am','force-stop','com.hh.recall');adb('shell','settings','put','system','accelerometer_rotation','0');adb('shell','settings','put','system','user_rotation','0')
        directory='/sdcard/Download/RecallImportLab';adb('shell','mkdir','-p',directory)
        for src,name in [('scan.png','v130-qa.png'),('scan.pdf','v130-qa-scan.pdf'),('notes.pdf','v130-qa-native.pdf')]:
            adb('push',str(ROOT/'app/src/androidTest/assets/import'/src),directory+'/'+name)
        fixture=OUT/'v130-qa.md';fixture.write_text('# 主动回忆\n\n学习之后合上材料。\nActive recall.\n','utf-8');adb('push',str(fixture),directory+'/v130-qa.md')
        adb('shell','am','start','-W','-n','com.hh.recall/.MainActivity');wait('把知识')
        tap(desc='导入文件');wait('PNG图片');shot('menu');checks.append('home_menu_png_image_with_icon')
        tap('PNG图片');wait('安全访问');tap(desc='取消');wait('把知识');checks.append('picker_cancel_no_preview')
        for label,file,kind in [('PNG图片','v130-qa.png','png'),('PDF 文件','v130-qa-scan.pdf','scan'),('PDF 文件','v130-qa-native.pdf','native'),('Markdown 文件','v130-qa.md','markdown')]:
            picker(label);choose(file);wait('检查并修改标题与正文');shot('preview-'+kind);checks.append('saf_'+kind+'_preview')
            if kind=='png':
                assert any(n.get('class')=='android.widget.EditText' and n.get('text')=='v130-qa' for n in nodes())
                assert not any(n.get('text') in ['逐页对照','校对文字'] for n in nodes())
                scroll();shot('png-comparison');checks.append('png_source_inline_preview')
                tap(desc='PNG原图');wait('双指缩放');shot('png-fullscreen');tap('关闭');checks.append('png_fullscreen_preview')
                adb('shell','input','keyevent','3');adb('shell','am','start','-n','com.hh.recall/.MainActivity');wait('导入预览');checks.append('background_resume_retains_preview')
                if SERIAL=='b840bb56':
                    for angle,name in [('1','landscape'),('0','portrait')]:
                        adb('shell','settings','put','system','user_rotation',angle);time.sleep(1);wait('导入预览');shot('png-'+name);checks.append('tablet_'+name+'_retains_preview')
            discard()
        # A real scan is cancelled before any confirmation can save an article.
        picker('PDF 文件');choose('v130-qa-scan.pdf');wait('取消读取');tap('取消读取');wait('把知识',60);checks.append('cancel_scan_no_partial_preview')
    finally:
        for k,v in rotation.items():adb('shell','settings','put','system',k,v)
    assert rotation=={k:adb('shell','settings','get','system',k).decode().strip() for k in rotation}
    checks.append('rotation_settings_restored')
    (OUT/'release-ui-checks.json').write_text(json.dumps(dict(checks=checks,personal_import_confirmed=False),ensure_ascii=False,indent=2),'utf-8')
    print(SERIAL,'Release UI checks passed',checks,flush=True)
if __name__=='__main__':run()
