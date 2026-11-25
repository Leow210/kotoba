#!/usr/bin/env python3
"""Applies the iOS-only edits to a COPY of the shared core sources (usage: patch-sources.py <dir with the .java files>).

The shared classes stay untouched for the phone and the Mac; the copy that J2ObjC translates gets autorelease pools around the
per-entry work of dictionary imports (see shim/app/kotoba/ios/Pool.java).
"""
import sys, os

root = sys.argv[1]

def patch(name, old, new):
    p = os.path.join(root, name)
    s = open(p, encoding='utf-8').read()
    if s.count(old) != 1:
        sys.exit(f"{name}: expected exactly one of: {old!r} (found {s.count(old)})")
    open(p, 'w', encoding='utf-8').write(s.replace(old, new))

POOL_ON = "long pool=app.kotoba.ios.Pool.push();try{"
POOL_OFF = "}finally{app.kotoba.ios.Pool.pop(pool);}"

patch('Yomitan.java', "                each.accept(value());",
      f"                {POOL_ON}each.accept(value());{POOL_OFF}")
patch('MdictFile.java', "                visitor.key(new String(block,pos,end-pos,charset),offset);",
      f"                {POOL_ON}visitor.key(new String(block,pos,end-pos,charset),offset);{POOL_OFF}")

# J2ObjC's Files.write (Files.newByteChannel underneath) throws a NullPointerException on iOS: write with a stream instead
# (Pool.writeFile in shim/app/kotoba/ios/Pool.java). Covers comic thumbnails/covers and the accent trainer's files.
for name in sorted(os.listdir(root)):
    if not name.endswith('.java'):
        continue
    p = os.path.join(root, name)
    s = open(p, encoding='utf-8').read()
    t = s.replace('java.nio.file.Files.write(', 'app.kotoba.ios.Pool.writeFile(')
    t = __import__('re').sub(r'(?<![\w.])Files\.write\(', 'app.kotoba.ios.Pool.writeFile(', t)
    if t != s:
        open(p, 'w', encoding='utf-8').write(t)
