#!/usr/bin/env python3
"""Drive Kotoba for Mac during development (needs: defaults write app.kotoba.desktop DevHooks -bool true).
  python3 tools/mac.py 'return await api("dicts")'   evaluates async JS in the page and prints the result
  python3 tools/mac.py --shot out.png                 saves a snapshot of the window
  python3 tools/mac.py --player 'return st.cues.length' evaluates in the newest video window's layer
  python3 tools/mac.py --player 'mpv:time-pos'        reads an mpv property of the newest video
"""
import json,os,subprocess,sys,tempfile,time
def post(name,obj):
    literal=json.dumps(obj,ensure_ascii=False)  # JSON string escapes are valid Swift string escapes
    swift=f'import Foundation\nDistributedNotificationCenter.default().postNotificationName(NSNotification.Name("{name}"),object:{literal},userInfo:nil,deliverImmediately:true)\n'
    f=tempfile.NamedTemporaryFile('w',suffix='.swift',delete=False);f.write(swift);f.close()
    subprocess.run(['swift',f.name],check=True);os.unlink(f.name)
def wait(path,timeout=60):
    for _ in range(timeout*10):
        if os.path.exists(path)and os.path.getsize(path)>0:time.sleep(.1);return
        time.sleep(.1)
    sys.exit('no answer from Kotoba (is it running with DevHooks on?)')
if sys.argv[1]=='--shot':
    out=os.path.abspath(sys.argv[2])
    if os.path.exists(out):os.unlink(out)
    post('app.kotoba.desktop.snapshot',out);wait(out);print('saved')
else:
    player=sys.argv[1]=='--player'
    script=sys.argv[2] if player else sys.argv[1]
    out=tempfile.mktemp(suffix='.txt')
    post('app.kotoba.desktop.eval',('player:' if player else '')+out+'\n'+script);wait(out,300);print(open(out).read());os.unlink(out)
