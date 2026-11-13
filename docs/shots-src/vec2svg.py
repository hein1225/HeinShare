#!/usr/bin/env python3
"""把 Android VectorDrawable (XML) 转成 SVG，用于离线生成界面截图。
只处理本项目实际用到的特性：path / group、fillColor、strokeColor、strokeWidth、
strokeLineCap、fillType、trimPath、viewportWidth/Height、width/height。
"""
import re, sys, os, xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"

def color(v):
    if not v or v in ("#00000000", "@android:color/transparent"):
        return None
    if v.startswith("@"):
        return None
    if v.startswith("#"):
        h = v[1:]
        if len(h) == 3:
            h = "".join(c*2 for c in h)
        if len(h) == 6:
            return "#" + h
        if len(h) == 8:  # AARRGGBB
            a = int(h[0:2], 16)/255.0
            r, g, b = h[2:4], h[4:6], h[6:8]
            return f"rgba({int(r,16)},{int(g,16)},{int(b,16)},{a:.3f})"
    return None

def esc(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
             .replace('"', "&quot;"))

def path_attrs(p):
    fill = color(p.get(ANDROID + "fillColor"))
    stroke = color(p.get(ANDROID + "strokeColor"))
    sw = p.get(ANDROID + "strokeWidth")
    out = []
    out.append(f'fill="{fill}"' if fill else 'fill="none"')
    if stroke:
        out.append(f'stroke="{stroke}"')
        out.append(f'stroke-width="{sw}"' if sw else 'stroke-width="1"')
        cap = p.get(ANDROID + "strokeLineCap")
        if cap:
            out.append(f'stroke-linecap="{cap}"')
        join = p.get(ANDROID + "strokeLineJoin")
        if join:
            out.append(f'stroke-linejoin="{join}"')
        ft = p.get(ANDROID + "fillType")
        if ft == "evenOdd":
            out.append('fill-rule="evenodd"')
    d = p.get(ANDROID + "pathData")
    if not d:
        return None
    return f'<path {" ".join(out)} d="{esc(d)}"/>'

def walk(node, tf, out):
    for ch in node:
        tag = ch.tag
        if tag == "group":
            t = ch.get(ANDROID + "translateX")
            walk(ch, tf, out)
        elif tag == "path":
            s = path_attrs(ch)
            if s:
                out.append(s)
        elif tag == "clip-path":
            pass

def convert(xml_path, svg_path):
    root = ET.parse(xml_path).getroot()
    vw = root.get(ANDROID + "viewportWidth", "24")
    vh = root.get(ANDROID + "viewportHeight", "24")
    w = root.get(ANDROID + "width", vw)
    h = root.get(ANDROID + "height", vh)
    w = re.sub(r"[a-zA-Z]+$", "", w)
    h = re.sub(r"[a-zA-Z]+$", "", h)
    out = []
    walk(root, None, out)
    body = "\n  ".join(out)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}" '
           f'width="{w}" height="{h}">\n  {body}\n</svg>\n')
    with open(svg_path, "w") as f:
        f.write(svg)
    return svg_path

if __name__ == "__main__":
    src_dir = sys.argv[1]
    out_dir = sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    for fn in sorted(os.listdir(src_dir)):
        if not fn.endswith(".xml"):
            continue
        convert(os.path.join(src_dir, fn), os.path.join(out_dir, fn[:-4] + ".svg"))
        print("ok", fn)
