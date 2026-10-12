#!/bin/sh
# Builds extension/content.js and video.js: the page-lookup userscript wrapped for use as a browser extension (see extension/shim.js).
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
shim=open(here+'/extension/shim.js').read()
out=shim.replace('__NAME__','web')+"\n"+src.rstrip()+"\n})();\n"
open(here+'/extension/content.js','w').write(out)

# The video subtitle helper (YouTube, Netflix, Viki, GagaOOLala, Bilibili) as a second content script.
v=open(here+'/web/kotoba-video.user.js').read()
v=v[v.index("/*\n * Draws the site's current caption"):]
v=v.replace("/*__KOTOBA_DARK__*/",open(here+'/../android/assets/dark.js').read())
assert old in v
v=v.replace(old,"const KEY = __cfg['kotoba.key'] || '', PORT = __cfg['kotoba.port'] || '47823';")
gate="if (document.getElementById('kotoba-video-ui')) return;"
assert gate in v
v=v.replace(gate,"if (document.getElementById('kotoba-video-ui') || GM_getValue('kotoba.video.enabled', true) === false) return;")
v=v.replace("Reinstall it from Kotoba › Video.","Connect it again: Kotoba › Video › Browser extension code.")
open(here+'/extension/video.js','w').write(shim.replace('__NAME__','video')+"\n// YouTube changes pages without reloading: wait here until a watch page is open.\nwhile (location.hostname === 'www.youtube.com' && location.pathname !== '/watch') await new Promise(r => setTimeout(r, 700));\n"+v.rstrip()+"\n})();\n")
PY
node --check "$HERE/extension/content.js"
node --check "$HERE/extension/video.js"
echo "$HERE/extension"
