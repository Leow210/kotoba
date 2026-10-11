#!/bin/sh
# Builds extension/content.js: the page-lookup userscript wrapped for use as a browser extension (see extension/shim.js).
# Load the extension/ folder in Chrome, Helium or any Chromium browser (chrome://extensions › Developer mode › Load unpacked).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
python3 - "$HERE" <<'PY'
import re,sys
here=sys.argv[1]
src=open(here+'/web/kotoba-web.user.js').read()
src=src[src.index('/*\n * Page lookup'):]
src=src.replace("/*__KOTOBA_DARK__*/",open(here+'/../android/assets/dark.js').read())
old="const KEY = '__KOTOBA_KEY__', PORT = '__KOTOBA_PORT__';"
assert old in src
src=src.replace(old,"const KEY = __cfg['kotoba.key'] || '', PORT = __cfg['kotoba.port'] || '47823';")
src=src.replace("Reinstall it from Kotoba › Video.","Connect it again: Kotoba › Video › Browser extension code.")
out=open(here+'/extension/shim.js').read()+"\n"+src.rstrip()+"\n})();\n"
open(here+'/extension/content.js','w').write(out)
PY
node --check "$HERE/extension/content.js"
echo "$HERE/extension"
