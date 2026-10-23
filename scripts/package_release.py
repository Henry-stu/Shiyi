"""Package a built release and source without private signing material or caches."""
from pathlib import Path
import hashlib
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
dist.mkdir(exist_ok=True)
apk = dist / "拾忆-v1.2.1.apk"
shutil.copy2(root / "app/build/outputs/apk/release/app-release.apk", apk)
source = dist / "Recall-source-v1.2.1.zip"
excluded = {"signing", "dist", "build", ".gradle", ".kotlin", ".idea", ".git", "__pycache__"}
with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if not path.is_file() or any(part in excluded for part in relative.parts):
            continue
        if relative.name == "local.properties" or path.suffix in {".jks", ".keystore", ".pyc"}:
            continue
        if relative.parts[0] == "qa" and relative.name != "device-tests.txt":
            continue
        archive.write(path, "Recall/" + relative.as_posix())
with zipfile.ZipFile(source) as archive:
    assert archive.testzip() is None
    assert not any("/signing/" in name or name.endswith("local.properties") for name in archive.namelist())
(dist / "SHA256SUMS.txt").write_text("".join(
    f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in (apk, source)
), encoding="utf-8")
for path in (apk, source):
    print(f"{path}: {path.stat().st_size:,} bytes")
