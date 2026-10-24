"""Rebuild the user-designed leaf icon as native Android vectors and export art.

Optional PNG preview needs Pillow and NumPy; the Android build needs neither.
Geometry is traced from the supplied 360 x 342 reference, not upscaled pixels.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ART = ROOT / "design/app-icon"
MAIN = "M240,77 C244,76 245,78 245,82 L245,125 C245,179 233,208 209,227 C190,241 167,244 143,247 C129,250 122,257 114,264 C110,268 105,266 104,261 C100,237 99,215 101,194 C104,158 115,131 145,110 C174,91 215,83 240,77 Z"
BACK = "M231,181 C241,180 249,177 256,174 C259,173 261,175 260,179 C259,207 250,231 230,247 C211,261 187,267 159,265 C156,265 157,264 162,262 C176,251 183,240 187,233 C207,223 225,204 231,181 Z"
SLIT = "M184,138 C188,135 191,137 187,142 C156,171 132,206 120,236 C119,239 116,240 116,235 C119,203 145,159 184,138 Z"
BG = "M72,0 L232,0 C272,0 304,32 304,72 L304,232 C304,272 272,304 232,304 L72,304 C32,304 0,272 0,232 L0,72 C0,32 32,0 72,0 Z"
LAYERS = [
    (BACK, "#A4B99B", "#D3D9C5", "#C7D2BB", .65),
    (MAIN, "#FAF8F0", "#E8E8DC", "#FFFFF7", .85),
    (SLIT, "#385343", "#294435", None, 0),
]


def write(relative, text):
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text + "\n", encoding="utf-8")


def gradient_coordinates(path):
    return (256, 176, 158, 266) if path == BACK else (90, 70, 250, 280)


def vector_path(path, start, end=None, stroke=None, width=0):
    outline = f' android:strokeColor="{stroke}" android:strokeWidth="{width}"' if stroke else ""
    if end is None:
        return f'<path android:pathData="{path}" android:fillColor="{start}"{outline}/>'
    x1, y1, x2, y2 = gradient_coordinates(path)
    return f'''<path android:pathData="{path}"{outline}>
        <aapt:attr name="android:fillColor"><gradient android:type="linear"
            android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}"
            android:startColor="{start}" android:endColor="{end}"/></aapt:attr>
    </path>'''


def leaves():
    result = []
    for path, start, end, stroke, width in LAYERS:
        if path != SLIT:
            result.append('<group android:translateX="1.1" android:translateY="1.4">' +
                          vector_path(path, "#40203125", stroke="#18203125", width=2) + '</group>')
        result.append(vector_path(path, start, end, stroke, width))
    return "\n".join(result)


def vector(content, size):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp"
    android:viewportWidth="{size}" android:viewportHeight="{size}">
{content}
</vector>'''


def generate():
    background = '''<path android:pathData="M0,0h108v108H0z">
    <aapt:attr name="android:fillColor"><gradient android:type="linear"
        android:startX="0" android:startY="0" android:endX="108" android:endY="108"
        android:startColor="#435F4E" android:endColor="#253B2E"/></aapt:attr></path>'''
    write("app/src/main/res/drawable/ic_recall_background.xml", vector(background, 108))
    write("app/src/main/res/drawable/ic_recall_foreground.xml",
          vector('<group android:translateX="49" android:translateY="59">' + leaves() + '</group>', 456))
    mono = vector_path(BACK, "#FFFFFF") + f'<path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="{MAIN} {SLIT}"/>'
    write("app/src/main/res/drawable/ic_recall_monochrome.xml",
          vector('<group android:translateX="49" android:translateY="59">' + mono + '</group>', 456))
    # minSdk 26: every supported Android version understands adaptive icons.
    # Older versions ignore the optional Android 13 monochrome element.
    write("app/src/main/res/mipmap-anydpi/ic_launcher.xml", '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_recall_background"/>
    <foreground android:drawable="@drawable/ic_recall_foreground"/>
    <monochrome android:drawable="@drawable/ic_recall_monochrome"/>
</adaptive-icon>''')
    svg_layers = []
    defs = ['<linearGradient id="bg" x2="1" y2="1"><stop stop-color="#435F4E"/><stop offset="1" stop-color="#253B2E"/></linearGradient>']
    for i, (path, start, end, stroke, width) in enumerate(LAYERS):
        x1, y1, x2, y2 = gradient_coordinates(path)
        defs.append(f'<linearGradient id="g{i}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}"><stop stop-color="{start}"/><stop offset="1" stop-color="{end}"/></linearGradient>')
        if path != SLIT:
            svg_layers.append(f'<path d="{path}" transform="translate(1.1 1.4)" fill="#203125" fill-opacity=".25" stroke="#203125" stroke-opacity=".094" stroke-width="2"/>')
        outline = f' stroke="{stroke}" stroke-width="{width}"' if stroke else ""
        svg_layers.append(f'<path d="{path}" fill="url(#g{i})"{outline}/>')
    write("design/app-icon/recall-icon.svg", '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 304 304"><defs>' + ''.join(defs) + '</defs>' + f'<path d="{BG}" fill="url(#bg)"/>' + '<g transform="translate(-27 -17)">' + ''.join(svg_layers) + '</g></svg>')


def preview():
    import numpy as np
    from PIL import Image, ImageDraw
    size = 2048
    scale = size / 304

    def mask(path, dx=0, dy=0):
        tokens = re.findall(r'[MLCZ]|-?\d+(?:\.\d+)?', path)
        points = []; index = 0; last = (0, 0)
        while index < len(tokens):
            command = tokens[index]; index += 1
            if command == 'Z':
                continue
            count = 6 if command == 'C' else 2
            values = list(map(float, tokens[index:index+count])); index += count
            if command == 'C':
                p0 = last; p1 = values[:2]; p2 = values[2:4]; p3 = values[4:]
                for t in np.linspace(0, 1, 65)[1:]:
                    points.append(tuple((1-t)**3*p0[k] + 3*(1-t)**2*t*p1[k] + 3*(1-t)*t*t*p2[k] + t**3*p3[k] for k in range(2)))
                last = tuple(p3)
            else:
                last = tuple(values); points.append(last)
        result = Image.new('L', (size, size)); ImageDraw.Draw(result).polygon([((x+dx)*scale, (y+dy)*scale) for x,y in points], fill=255)
        return result

    def gradient(start, end, x1, y1, x2, y2):
        yy, xx = np.ogrid[:size, :size]
        t = np.clip(((xx/scale-x1)*(x2-x1) + (yy/scale-y1)*(y2-y1))/((x2-x1)**2+(y2-y1)**2), 0, 1)
        a = np.array([int(start[i:i+2],16) for i in (1,3,5)])
        b = np.array([int(end[i:i+2],16) for i in (1,3,5)])
        return Image.fromarray(np.uint8(np.round(a[None,None,:] + t[:,:,None]*(b-a)[None,None,:])), 'RGB').convert('RGBA')

    canvas = Image.new('RGBA',(size,size))
    canvas.paste(gradient('#435F4E','#253B2E',0,0,304,304),(0,0),mask(BG))
    for path,start,end,_,_ in LAYERS:
        if path != SLIT:
            shadow=Image.new('RGBA',(size,size),'#203125')
            canvas.paste(shadow,(0,0),mask(path,-25.9,-15.6).point(lambda value: round(value*.25)))
        x1,y1,x2,y2 = gradient_coordinates(path)
        canvas.paste(gradient(start,end,x1-27,y1-17,x2-27,y2-17),(0,0),mask(path,-27,-17))
    canvas.resize((1024,1024),Image.Resampling.LANCZOS).save(ART/'recall-icon.png')


if __name__ == '__main__':
    generate()
    import sys
    if '--preview' in sys.argv:
        preview()
    print(ART)
