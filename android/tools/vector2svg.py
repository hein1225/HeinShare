#!/usr/bin/env python3
"""把 Android VectorDrawable XML 转成 SVG，供 HTML 预览使用。

只处理本项目实际用到的子集：path 的 fillColor/strokeColor/strokeWidth/
strokeLineCap/strokeLineJoin/pathData，以及 group 的 rotation/pivot/scale。
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

NS = "{http://schemas.android.com/apk/res/android}"


def color(v, default=None):
    if v is None:
        return default
    v = v.strip()
    if not v or v == "#00000000" or v == "@android:color/transparent":
        return "none"
    if v.startswith("#"):
        h = v[1:]
        if len(h) == 3:
            h = "".join(c * 2 for c in h)
        if len(h) == 6:
            return "#" + h
        if len(h) == 8:  # AARRGGBB
            a = int(h[0:2], 16) / 255.0
            r, g, b = h[2:4], h[4:6], h[6:8]
            return f"rgba({int(r,16)},{int(g,16)},{int(b,16)},{a:.3f})"
    return default


def vec_to_svg(src, dst):
    tree = ET.parse(src)
    root = tree.getroot()
    vb_w = root.get(NS + "viewportWidth", "24")
    vb_h = root.get(NS + "viewportHeight", "24")
    body = []

    def walk(node, depth=0):
        for child in node:
            tag = child.tag
            if tag == "group":
                tr = []
                rot = child.get(NS + "rotation")
                px = child.get(NS + "pivotX")
                py = child.get(NS + "pivotY")
                sx = child.get(NS + "scaleX")
                sy = child.get(NS + "scaleY")
                tx = child.get(NS + "translateX")
                ty = child.get(NS + "translateY")
                if tx or ty:
                    tr.append(f"translate({float(tx or 0)},{float(ty or 0)})")
                if rot:
                    tr.append(f"rotate({float(rot)},{float(px or 0)},{float(py or 0)})")
                if sx or sy:
                    tr.append(f"scale({float(sx or 1)},{float(sy or 1)})")
                g = f'<g transform="{" ".join(tr)}">' if tr else "<g>"
                body.append("  " * depth + g)
                walk(child, depth + 1)
                body.append("  " * depth + "</g>")
            elif tag == "path":
                d = child.get(NS + "pathData")
                if not d:
                    continue
                fill = color(child.get(NS + "fillColor"), "none")
                stroke = color(child.get(NS + "strokeColor"))
                sw = child.get(NS + "strokeWidth")
                cap = child.get(NS + "strokeLineCap", "butt")
                join = child.get(NS + "strokeLineJoin", "miter")
                attrs = [f'd="{d}"', f'fill="{fill}"']
                if stroke:
                    attrs.append(f'stroke="{stroke}"')
                    attrs.append(f'stroke-width="{sw or 1}"')
                    attrs.append(f'stroke-linecap="{cap}"')
                    attrs.append(f'stroke-linejoin="{join}"')
                body.append("  " * depth + f"<path {' '.join(attrs)}/>")

    walk(root)
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vb_w} {vb_h}" '
        f'width="24" height="24">{chr(10)}{chr(10).join(body)}{chr(10)}</svg>'
    )
    with open(dst, "w", encoding="utf-8") as f:
        f.write(svg)
    return svg


def main():
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    n = 0
    for name in sorted(os.listdir(src_dir)):
        if not name.startswith("ic_") or not name.endswith(".xml"):
            continue
        src = os.path.join(src_dir, name)
        dst = os.path.join(out_dir, name[:-4] + ".svg")
        try:
            vec_to_svg(src, dst)
            n += 1
        except Exception as e:  # noqa: BLE001
            print(f"[skip] {name}: {e}", file=sys.stderr)
    print(f"converted {n} icons -> {out_dir}")


if __name__ == "__main__":
    main()
