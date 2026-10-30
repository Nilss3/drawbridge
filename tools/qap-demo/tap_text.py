import re, subprocess, sys, xml.etree.ElementTree as ET
ADB = sys.argv[1]; want = sys.argv[2]
subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
               check=True, capture_output=True)
xml = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                     check=True, capture_output=True).stdout.decode("utf-8", "replace")
root = ET.fromstring(xml[xml.index("<hierarchy"):])
for n in root.iter("node"):
    if n.get("text", "").strip().lower() == want.lower():
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        subprocess.run([ADB, "shell", "input", "tap", str(cx), str(cy)], check=True)
        print(f"tapped {want!r} at {cx},{cy}")
        sys.exit(0)
print(f"NOT FOUND: {want!r}", file=sys.stderr)
sys.exit(1)
