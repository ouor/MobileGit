"""QA helper: tap/dump Android UI by text via uiautomator. Usage:
  ui.py dump                -> list visible text/desc with centers
  ui.py tap <substring|=exact> [n] -> tap n-th (0-based) node whose text/desc contains substring (or equals, with = prefix)
  ui.py shot <file.png>
"""
import re, subprocess, sys, xml.etree.ElementTree as ET
DEV = ["adb", "-s", "d418f2d1"]

def dump():
    subprocess.run(DEV + ["shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    xml = subprocess.run(DEV + ["exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout.decode("utf-8")
    out = []
    for n in ET.fromstring(xml).iter("node"):
        t = n.get("text") or n.get("content-desc") or ""
        if not t:
            continue
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        out.append((t, (x1 + x2) // 2, (y1 + y2) // 2, n.get("checked")))
    return out

if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "dump":
        for t, x, y, c in dump():
            print(f"{x:5d},{y:5d} {'[x]' if c == 'true' else '   '} {t!r}")
    elif cmd == "tap":
        q = sys.argv[2]
        hits = [h for h in dump() if (h[0] == q[1:] if q.startswith("=") else q in h[0])]
        i = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        if not hits:
            sys.exit(f"not found: {sys.argv[2]}")
        _, x, y, _ = hits[i]
        subprocess.run(DEV + ["shell", "input", "tap", str(x), str(y)])
        print("tapped", hits[i][0][:40], x, y)
    elif cmd == "shot":
        with open(sys.argv[2], "wb") as f:
            f.write(subprocess.run(DEV + ["exec-out", "screencap", "-p"], capture_output=True).stdout)
