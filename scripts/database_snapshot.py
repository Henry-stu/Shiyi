"""Read a private device database copy for local update QA; never changes device data."""
import io,sys,json,tarfile,tempfile,sqlite3,pathlib
from contextlib import closing
from device_qa import adb,OUT

def snapshot():
    data=adb('exec-out','run-as','com.hh.recall','tar','-cf','-','databases')
    with tempfile.TemporaryDirectory(prefix='recall-snapshot-') as directory:
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for member in archive.getmembers():
                path=pathlib.PurePosixPath(member.name)
                if member.isfile() and path.parent==pathlib.PurePosixPath('databases'):
                    (pathlib.Path(directory)/path.name).write_bytes(archive.extractfile(member).read())
        with closing(sqlite3.connect(str(pathlib.Path(directory)/'recall.db'))) as db:
            db.row_factory=sqlite3.Row
            return {'version':db.execute('PRAGMA user_version').fetchone()[0], **{table:[dict(row) for row in db.execute('SELECT * FROM '+table+' ORDER BY '+('articleId' if table=='drafts' else 'id'))] for table in ['articles','folders','drafts']}}
if __name__=='__main__':
    value=snapshot()
    (OUT/sys.argv[1]).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    print({'version':value['version'],**{key:len(value[key]) for key in ['articles','folders','drafts']}})
