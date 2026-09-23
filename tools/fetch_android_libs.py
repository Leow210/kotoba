#!/usr/bin/env python3
"""Downloads Google's LiteRT-LM runtime (for PaddleOCR-VL, the on-device text recognizer for manga and manhwa) and what
it depends on into android/libs/aar/, for android/build.py. Run once (needs network); the build itself stays offline.
  python3 tools/fetch_android_libs.py
Without these files the app builds as before and uses PaddleOCR's mobile models for every language."""
import urllib.request,re,sys,json,os,ssl,certifi
CTX=ssl.create_default_context(cafile=certifi.where())
import xml.etree.ElementTree as ET
REPOS=['https://dl.google.com/dl/android/maven2','https://repo1.maven.org/maven2']
NS='{http://maven.apache.org/POM/4.0.0}'
cache={}
def get(url):
    try:
        with urllib.request.urlopen(url,timeout=30,context=CTX) as r:return r.read()
    except Exception:return None
def latest(g,a):
    for R in REPOS:
        d=get(f"{R}/{g.replace('.','/')}/{a}/maven-metadata.xml")
        if d:
            vs=re.findall(r'<version>([^<]+)</version>',d.decode())
            vs=[v for v in vs if not re.search(r'alpha|beta|rc',v,re.I)] or vs
            return vs[-1]
def pom(g,a,v):
    k=(g,a,v)
    if k in cache:return cache[k]
    for R in REPOS:
        d=get(f"{R}/{g.replace('.','/')}/{a}/{v}/{a}-{v}.pom")
        if d:cache[k]=(R,ET.fromstring(d));return cache[k]
    cache[k]=(None,None);return cache[k]
def clean(v):
    m=re.match(r'^\[([^,\]]+)\]$',v or '');return m.group(1) if m else v
resolved={}
def walk(g,a,v,depth=0):
    v=clean(v)
    key=(g,a)
    if key in resolved:
        # keep the highest version
        if tuple(int(x) if x.isdigit() else 0 for x in re.split(r'[.-]',v))<=tuple(int(x) if x.isdigit() else 0 for x in re.split(r'[.-]',resolved[key][0])):return
    R,root=pom(g,a,v)
    if root is None:print('MISSING',g,a,v,file=sys.stderr);return
    pk=root.findtext(NS+'packaging') or 'jar'
    resolved[key]=(v,pk,R)
    deps=root.find(NS+'dependencies')
    if deps is None:return
    for d in deps.findall(NS+'dependency'):
        sc=d.findtext(NS+'scope') or 'compile'
        if sc in('test','provided','system') or d.findtext(NS+'optional')=='true':continue
        dg,da,dv=d.findtext(NS+'groupId'),d.findtext(NS+'artifactId'),d.findtext(NS+'version')
        if not dv or '$' in dv:continue
        walk(dg,da,dv,depth+1)
roots=[('com.google.ai.edge.litertlm','litertlm-android')]
for g,a in roots:
    v=latest(g,a);print('root',a,v,file=sys.stderr);walk(g,a,v)
OUT=os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','android','libs','aar')
os.makedirs(OUT,exist_ok=True)
for (g,a),(v,pk,R) in sorted(resolved.items()):
    ext='aar' if pk=='aar' else 'jar'
    name=f'{g}__{a}-{v}.{ext}'
    dest=os.path.join(OUT,name)
    if os.path.exists(dest):continue
    data=get(f"{R}/{g.replace('.','/')}/{a}/{v}/{a}-{v}.{ext}")
    if data is None:print('could not download',name,file=sys.stderr);continue
    open(dest,'wb').write(data);print(name,len(data)//1024,'KB')
