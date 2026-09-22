#!/usr/bin/env python3
"""Scripted demos for the README and the GitHub page: real taps (adb input) located through the WebView (DevTools).

    python3 tools/demo.py shots OUTDIR          # screenshots
    python3 tools/demo.py video NAME OUTDIR     # one recording of the app's WebView (scan, lists, kanji, entry)

Needs the app open on a phone over adb, `pip install websocket-client`, and ffmpeg for the videos.
Leave the phone alone while it runs: every touch is recorded.
"""
import base64, json, shutil, subprocess, sys, tempfile, threading, time, urllib.request
import websocket



class App:
    def __init__(self):
        pid = subprocess.check_output(['adb', 'shell', 'pidof', 'app.kotoba.reader']).decode().strip()
        subprocess.run(['adb', 'forward', 'tcp:9333', 'localabstract:webview_devtools_remote_' + pid], check=True, capture_output=True)
        page = [p for p in json.load(urllib.request.urlopen('http://localhost:9333/json')) if p['type'] == 'page'][0]
        self.url = page['webSocketDebuggerUrl']
        self.ws = websocket.create_connection(self.url, timeout=120, suppress_origin=True)
        self.n = 0
        self.call('Page.stopScreencast')  # a recording that died mid-way leaves one running, and captures then hang
        m = self.js('return [devicePixelRatio, innerHeight]')
        self.dpr = m[0]
        # The WebView sits under the status bar.
        self.top = 0
        insets = subprocess.check_output(['adb', 'shell', 'dumpsys', 'window']).decode()
        for line in insets.splitlines():
            if 'type=statusBars frame=' in line:
                self.top = int(line.split('frame=[0,0][')[1].split(',')[1].split(']')[0])
                break

    def call(self, method, **params):
        self.n += 1
        self.ws.send(json.dumps({'id': self.n, 'method': method, 'params': params}))
        while True:
            m = json.loads(self.ws.recv())
            if m.get('id') == self.n:
                return m

    def js(self, body):
        r = self.call('Runtime.evaluate', expression='(async()=>{' + body + '})()', awaitPromise=True, returnByValue=True)
        res = r.get('result', {})
        if 'exceptionDetails' in res:
            raise RuntimeError(json.dumps(res['exceptionDetails'])[:500])
        return res.get('result', {}).get('value')

    def rect(self, selector, text=None):
        """Centre of the last visible element matching selector (and containing text) in the top page or tab."""
        q = json.dumps(selector)
        t = json.dumps(text)
        return self.js(f"""
            const els=[...document.querySelectorAll({q})].filter(e=>{{const r=e.getBoundingClientRect();return r.width&&r.height&&r.bottom>0&&r.top<innerHeight&&({t}===null||e.textContent.includes({t}));}});
            const e=els.pop();if(!e)return null;const r=e.getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2];""")

    def tap(self, selector, text=None, wait=1.0):
        for _ in range(20):
            p = self.rect(selector, text)
            if p:
                break
            time.sleep(.25)
        else:
            raise RuntimeError(f'Not found: {selector} {text}')
        subprocess.run(['adb', 'shell', 'input', 'tap', str(round(p[0] * self.dpr)), str(round(self.top + p[1] * self.dpr))], check=True)
        time.sleep(wait)

    def swipe(self, y1, y2, ms=450, wait=.8, x=180):
        s = lambda v: str(round(v * self.dpr))
        subprocess.run(['adb', 'shell', 'input', 'swipe', s(x), str(round(self.top + y1 * self.dpr)), s(x), str(round(self.top + y2 * self.dpr)), str(ms)], check=True)
        time.sleep(wait)

    def scroll_to(self, selector, text=None):
        self.js(f"const e=[...document.querySelectorAll({json.dumps(selector)})].filter(e=>{json.dumps(text)}===null||e.textContent.includes({json.dumps(text)})).pop();e&&e.scrollIntoView({{block:'center',inline:'center',behavior:'smooth'}});")
        time.sleep(.9)

    def shot(self, path):
        # A still page produces no new frame and the capture waits for one: nudge a repaint first.
        self.js("const d=document.createElement('div');d.style.cssText='position:fixed;left:0;top:0;width:1px;height:1px;opacity:.01';document.body.appendChild(d);requestAnimationFrame(()=>requestAnimationFrame(()=>d.remove()));")
        r = self.call('Page.captureScreenshot', format='png')
        open(path, 'wb').write(base64.b64decode(r['result']['data']))
        print('saved', path)

    def home(self, tab='search'):
        self.js(f"while(pageStack.length)popPage(true);document.querySelectorAll('.scrim').forEach(s=>s.click());showTab('{tab}');window.scrollTo(0,0);")
        time.sleep(.8)


class Screencast(threading.Thread):
    """Frames of the WebView through a second DevTools connection (shell screenrecord is blocked on some phones)."""
    def __init__(self, url, folder):
        super().__init__(daemon=True)
        self.ws = websocket.create_connection(url, timeout=5, suppress_origin=True)
        self.folder, self.frames, self.running = folder, [], True

    def run(self):
        self.ws.send(json.dumps({'id': 1, 'method': 'Page.startScreencast', 'params': {'format': 'jpeg', 'quality': 85, 'everyNthFrame': 1}}))
        while self.running:
            try:
                m = json.loads(self.ws.recv())
            except websocket.WebSocketTimeoutException:
                continue
            if m.get('method') == 'Page.screencastFrame':
                p = m['params']
                path = f'{self.folder}/{len(self.frames):05d}.jpg'
                open(path, 'wb').write(base64.b64decode(p['data']))
                self.frames.append((path, p['metadata']['timestamp']))
                self.ws.send(json.dumps({'id': 2, 'method': 'Page.screencastFrameAck', 'params': {'sessionId': p['sessionId']}}))

    def stop(self):
        self.running = False
        self.join(6)
        try:
            self.ws.send(json.dumps({'id': 3, 'method': 'Page.stopScreencast'}))
        finally:
            self.ws.close()


def record(name, out, scene, url, prep=None):
    if prep:
        prep()
    tmp = tempfile.mkdtemp()
    cast = Screencast(url, tmp)
    cast.start()
    time.sleep(1.2)
    try:
        scene()
        time.sleep(1.5)
    finally:
        cast.stop()
    frames = cast.frames
    if not frames:
        raise RuntimeError('No frames captured')
    # Frames only come when something changes: hold each one until the next (concat demuxer with durations).
    lst = f'{tmp}/frames.txt'
    with open(lst, 'w') as fl:
        for (path, t), nxt in zip(frames, frames[1:] + [(None, frames[-1][1] + 1.5)]):
            fl.write(f"file '{path}'\nduration {max(0.001, nxt[1] - t):.3f}\n")
        fl.write(f"file '{frames[-1][0]}'\n")
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', lst, '-vf', 'scale=540:-2:flags=lanczos,fps=30',
                    '-c:v', 'libx264', '-crf', '26', '-preset', 'slow', '-pix_fmt', 'yuv420p', '-an', '-movflags', '+faststart', f'{out}/{name}.mp4'], check=True)
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-sseof', '-1', '-i', f'{out}/{name}.mp4', '-frames:v', '1', '-q:v', '3', f'{out}/{name}-poster.jpg'], check=True)
    shutil.rmtree(tmp)
    print('saved', f'{out}/{name}.mp4', len(frames), 'frames')


def scene_scan(a, scan):
    a.js(f"openScan({json.dumps(scan)})")
    time.sleep(2.5)
    a.tap('.scan-page .ocr-box', wait=1.6)
    a.tap('.sheet .ocr-w', '遺', wait=2.2)  # 遺跡
    a.swipe(600, 300, wait=1.5)
    a.js("document.querySelectorAll('.scrim').forEach(s=>s.click())")
    time.sleep(.8)
    a.tap('.scan-foot [data-a="select"]', wait=.6)
    # drag over the dialogue box
    p1, p2 = a.js("const r=document.querySelector('.scan-page [data-f=img]').getBoundingClientRect();return [[r.left+r.width*.08,r.top+r.height*.66],[r.left+r.width*.96,r.top+r.height*.97]]")
    s = lambda v: str(round(v * a.dpr))
    subprocess.run(['adb', 'shell', 'input', 'swipe', s(p1[0]), str(round(a.top + p1[1] * a.dpr)), s(p2[0]), str(round(a.top + p2[1] * a.dpr)), '700'])
    time.sleep(2.2)
    a.tap(".sheet .ocr-w", "夕", wait=2.4)


def scene_lists(a):
    a.scroll_to('.dl-dict', '朝鮮語')
    a.tap('[data-dl]', '専門用語', wait=1.4)
    a.tap('.cat-cell', '医学', wait=1.6)
    a.swipe(600, 250, wait=1)
    a.tap('.wl-page [data-a="view"]', wait=1.2)
    a.tap('.wl-page [data-a="view"]', wait=1)
    a.tap('.wl-cell', None, wait=2.2)


def scene_kanji(a):
    a.js("openKanjiGrid(5)")
    time.sleep(1.8)
    # Browse the whole grid first (the filter rows stay put above it).
    a.swipe(680, 300, ms=500, wait=.9)
    a.swipe(680, 300, ms=500, wait=.9)
    a.swipe(300, 680, ms=500, wait=.9)
    a.tap('[data-rad]', wait=1.4)
    # The radical picker: scroll through the stroke groups, then jump with the stroke row.
    a.swipe(650, 250, ms=500, wait=.9)
    a.swipe(650, 250, ms=500, wait=.9)
    a.tap('.rad-strokes [data-st]', '3画', wait=.8)
    a.scroll_to('.rad-cell', '⺡')
    a.tap('.rad-cell', '⺡', wait=1.6)
    a.swipe(680, 320, ms=500, wait=1)
    a.scroll_to('[data-k="strokes"][data-v="7"]')
    a.tap('[data-k="strokes"][data-v="7"]', wait=1.6)
    a.tap('[data-dict]', '漢辞海', wait=1.6)
    a.swipe(680, 380, ms=500, wait=1)
    a.tap('.kcell', None, wait=2.2)


def scene_entry(a):
    a.tap('#q', wait=.6)
    subprocess.run(['adb', 'shell', 'input', 'text', 'tabesaserarenakatta'])  # romaji is shown as typed; the query is set below
    a.js("const q=$('q');q.value='食べさせられなかった';q.dispatchEvent(new Event('input',{bubbles:true}));")
    time.sleep(2)
    a.tap('#results .row', None, wait=2.4)
    a.swipe(600, 300, wait=1.2)


def scene_cards(a, deck):
    """Look up 言葉, keep only meaning ② with NHK audio, save it to a deck and review it."""
    a.tap('#q', wait=.5)
    a.js("const q=$('q');q.value='言葉';q.dispatchEvent(new Event('input',{bubbles:true}));"); time.sleep(1.5)
    a.js("document.activeElement&&document.activeElement.blur()"); time.sleep(.8)  # keyboard away, or the next tap only closes it
    a.tap('#results .row[data-g="0"]', wait=2.2)
    # Saving a word that is already saved edits that card (and would move it into the demo deck): stop instead.
    if a.js("return !!document.querySelector('.entry-page [data-act=\"bookmark\"].on')"):
        raise RuntimeError('言葉 is already saved on this phone: pick another word for the demo')
    a.tap('.entry-page [data-act="bookmark"]', wait=1.6)
    for n in [1, 3, 4, 5, 6, 7, 8, 9, 10]:  # untick every meaning except ②
        a.scroll_to(f'#sv-parts [data-p="{n}"]')
        a.tap(f'#sv-parts .part:nth-child({n + 1})', wait=.25)
    a.scroll_to('#sv-back'); time.sleep(.8)
    a.scroll_to('#sv-audio')
    a.tap('#sv-audio [data-play="0"]', wait=1.2)  # the first NHK clip is attached already: just play it
    a.scroll_to('#sv-folders')
    a.tap('#sv-folders .chip', '日本語', wait=.6)
    a.tap('#sv-save', wait=1.4)
    a.js(f"startReview({deck['id']})"); time.sleep(1.8)
    a.tap('#reveal', wait=2)
    if a.rect('.review-page .audio-pill'):
        a.tap('.review-page .audio-pill', wait=1.2)
    a.tap('[data-g="3"]', wait=2)


def shots(a, out):
    """Stills of the newer screens for the README and the page."""
    a.home()
    a.js("openScan('scan-1000000000004.jpg')"); time.sleep(2.5)
    a.shot(f'{out}/scan.png')
    a.tap('.scan-page .ocr-box', wait=1.4); a.tap('.sheet .ocr-w', '遺', wait=2)
    a.shot(f'{out}/scan-lookup.png')
    a.home('folders'); time.sleep(1.5)
    a.js("document.querySelector('.dl-dict').previousElementSibling.scrollIntoView({block:'start'})"); time.sleep(.6)
    a.shot(f'{out}/dict-lists.png')
    a.js("const l=await api('dictlists');await openDictList(8,l.find(x=>x.dict===8&&x.title==='専門用語').index)"); time.sleep(1.2)
    a.shot(f'{out}/categories.png')
    a.tap('.cat-cell', '医学', wait=1.4)
    a.js("try{localStorage.setItem('wlView','grid')}catch(e){}")
    a.shot(f'{out}/hanja-grid.png')
    a.home(); a.js("openKanjiGrid(5)"); time.sleep(1.5); a.tap('[data-rad]', wait=1.2)
    a.shot(f'{out}/radicals.png')
    a.home(); a.js("openAppendix(4)"); time.sleep(1.2)
    a.shot(f'{out}/furoku.png')
    a.home(); a.js("openAppendixPage(5,'index/付録_おもな対義語_あ行.html','あ行')"); time.sleep(1.8)
    a.shot(f'{out}/furoku-vertical.png')
    a.home()


if __name__ == '__main__':
    cmd, *rest = sys.argv[1:]
    a = App()
    if cmd == 'video':
        name, out = rest[0], rest[1]
        deck = {}
        if name == 'cards':
            deck['since'] = a.js("return Math.floor(Date.now()/1000)")
            deck['id'] = a.js("const f=await api('folder.save',{name:'日本語'});localStorage.setItem('lastFolderBefore',localStorage.getItem('lastFolder')||'');localStorage.setItem('lastFolder',String(f.id));return f.id")
        scene = {'cards': lambda: scene_cards(a, deck),
                 'scan': lambda: scene_scan(a, rest[2] if len(rest) > 2 else 'scan-1000000000004.jpg'),
                 'lists': lambda: scene_lists(a), 'kanji': lambda: scene_kanji(a), 'entry': lambda: scene_entry(a)}[name]
        try:
            record(name, out, scene, a.url, prep=lambda: (a.home('folders' if name == 'lists' else 'search'), time.sleep(1.2)))
        finally:
            if deck and '--keep' not in rest:
                # Only cards made during the demo are deleted; anything older that ended up in the deck goes back to Inbox.
                a.js(f"""const d={deck['id']},since={deck['since']};
                    const items=await api('items',{{folder:d,q:'',filter:'',sort:'updated'}});
                    const mine=items.filter(i=>i.created>=since).map(i=>i.id),theirs=items.filter(i=>i.created<since).map(i=>i.id);
                    if(theirs.length)await api('item.move',{{ids:theirs,folder:1}});
                    if(mine.length)await api('item.delete',{{ids:mine}});
                    await api('folder.delete',{{id:d,items:false}});
                    const b=localStorage.getItem('lastFolderBefore');if(b)localStorage.setItem('lastFolder',b);else localStorage.removeItem('lastFolder');""")
    elif cmd == 'shots':
        shots(a, rest[0])
    elif cmd == 'js':
        print(a.js(rest[0]))
