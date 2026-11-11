#!/usr/bin/env bash
# Toca un elemento de la UI por texto, descripción o testTag (resource-id) usando uiautomator.
# Uso: scripts/tap.sh <serial> "<texto|tag>"
set -euo pipefail
SERIAL="$1"
QUERY="$2"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"

"$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/vl_ui.xml >/dev/null 2>&1
XML="$("$ADB" -s "$SERIAL" shell cat /sdcard/vl_ui.xml)"

BOUNDS="$(printf '%s' "$XML" | python3 -c '
import re, sys
q = sys.argv[1]
xml = sys.stdin.read()
nodes = re.findall(r"<node [^>]*>", xml)
def attr(n, a):
    m = re.search(a + r"=\"([^\"]*)\"", n)
    return m.group(1) if m else ""
for exact in (True, False):
    for n in nodes:
        vals = [attr(n, "text"), attr(n, "content-desc"), attr(n, "resource-id")]
        ok = any(v == q for v in vals) if exact else any(q.lower() in v.lower() for v in vals if v)
        if ok:
            b = re.findall(r"\d+", attr(n, "bounds"))
            if len(b) == 4:
                x1, y1, x2, y2 = map(int, b)
                print((x1 + x2) // 2, (y1 + y2) // 2)
                sys.exit(0)
sys.exit(1)
' "$QUERY")" || { echo "No encontrado: $QUERY" >&2; exit 1; }

"$ADB" -s "$SERIAL" shell input tap $BOUNDS
echo "Tap '$QUERY' en $BOUNDS"
