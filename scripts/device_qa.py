"""Local QA helper: ADB operations on this project's authorized test device."""
import json
import os
import pathlib
import subprocess
import xml.etree.ElementTree as ET

ADB = r"C:\Users\hh\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = os.environ.get("RECALL_DEVICE_SERIAL", "b840bb56")
OUT = pathlib.Path(__file__).resolve().parents[1] / "qa"
OUT.mkdir(exist_ok=True)

def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, *map(str, args)])

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/recall-ui.xml")
    return list(ET.fromstring(adb("shell", "cat", "/sdcard/recall-ui.xml")).iter("node"))

def describe():
    print(json.dumps([
        {"text": n.get("text") or n.get("content-desc"), "bounds": n.get("bounds")}
        for n in nodes() if n.get("text") or n.get("content-desc")
    ], ensure_ascii=False, indent=2))

def tap(text):
    import re
    for n in nodes():
        if n.get("text") == text or n.get("content-desc") == text:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            adb("shell", "input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)
            return
    raise RuntimeError("UI item not found: " + text)

def screenshot(name):
    path = OUT / (name + ".png")
    path.write_bytes(adb("exec-out", "screencap", "-p"))
    print(str(path))
