#!/usr/bin/env python3
"""Evaluate JS in the Kotoba WebView: cdp.py 'expression' (awaits promises, prints JSON)."""
import json, sys, subprocess, urllib.request, websocket, base64
pid=subprocess.check_output(['adb','shell','pidof','app.kotoba.reader']).decode().strip()
subprocess.run(['adb','forward','tcp:9333','localabstract:webview_devtools_remote_'+pid],check=True,capture_output=True)
pages=json.load(urllib.request.urlopen('http://localhost:9333/json'))
page=[p for p in pages if p['type']=='page'][0]
ws=websocket.create_connection(page['webSocketDebuggerUrl'],timeout=600,suppress_origin=True)
n=0
def call(method,**params):
    global n;n+=1;ws.send(json.dumps({'id':n,'method':method,'params':params}))
    while True:
        m=json.loads(ws.recv())
        if m.get('id')==n:return m
if sys.argv[1]=='--shot':
    r=call('Page.captureScreenshot',format='png')
    open(sys.argv[2],'wb').write(base64.b64decode(r['result']['data']));print('saved');sys.exit()
expr=sys.argv[1] if sys.argv[1]!='-' else sys.stdin.read()
r=call('Runtime.evaluate',expression='(async()=>{'+expr+'})()',awaitPromise=True,returnByValue=True)
res=r.get('result',{})
if 'exceptionDetails' in res: print('EXCEPTION',json.dumps(res['exceptionDetails'],ensure_ascii=False)[:2000])
else: print(json.dumps(res.get('result',{}).get('value'),ensure_ascii=False,indent=1)[:int(sys.argv[2]) if len(sys.argv)>2 else 6000])
