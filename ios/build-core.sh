#!/bin/bash
# Translates the shared Kotoba core to Objective-C with J2ObjC and compiles it for iOS (and, for testing, macOS).
#   ios/build-core.sh [iphoneos|macosx ...]      (default: both)
# Needs a J2ObjC build (set J2OBJC to its dist folder; see docs/ipad.md), Xcode, and JDK 21.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
J="${J2OBJC:-/Volumes/XcodeDisk/work/j2objc/dist}"   # a J2ObjC dist (j2objc, lib, include); see README
OUT="${OUT:-$HERE/build}"
SRC="$OUT/src/app/kotoba/reader"   # a patched copy of the shared sources (patch-sources.py)
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}"
rm -rf "$OUT/objc" "$OUT/src" && mkdir -p "$OUT/objc" "$OUT/src/app/kotoba/reader"
cp "$HERE"/../android/src/app/kotoba/reader/*.java "$OUT/src/app/kotoba/reader/"
python3 "$HERE/patch-sources.py" "$OUT/src/app/kotoba/reader"
FILES=()
# The classes both apps share (the same list as desktop/build-core.sh), then the iOS stand-ins and entry point.
for c in Library Store WordLists Extras Routes Yomitan HtmlText MarkupFix Deinflect German Fsrs MdictFile ZipSource Lzo Ripemd128 BookParser Sync Books Comics MihonBackup Ocr Spacing Lyrics Listening Accent; do FILES+=("$SRC/$c.java"); done
while IFS= read -r f; do FILES+=("$f"); done < <(find "$HERE/shim" -name '*.java')
"$J/j2objc" -d "$OUT/objc" -use-arc -encoding UTF-8 -sourcepath "$SRC:$HERE/shim" -classpath "$J/lib/jre_emul.jar:$J/lib/json.jar" "${FILES[@]}"
[ -n "$TRANSLATE_ONLY" ] && exit 0
for sdk in ${@:-iphoneos macosx}; do
  case $sdk in iphoneos) arch=arm64; min=-miphoneos-version-min=16.0;; macosx) arch=arm64; min=-mmacosx-version-min=13.0;; *) echo "unknown sdk $sdk"; exit 1;; esac
  mkdir -p "$OUT/obj-$sdk"
  find "$OUT/objc" -name '*.m' | while IFS= read -r f; do
    o="$OUT/obj-$sdk/$(echo "${f#$OUT/objc/}" | tr '/' '_' | sed 's/\.m$/.o/')"
    xcrun --sdk $sdk clang -arch $arch $min -fobjc-arc -O1 -w -I "$J/include" -I "$OUT/objc" -c "$f" -o "$o"
  done
  echo "$sdk: $(ls "$OUT/obj-$sdk" | wc -l | tr -d ' ') objects"
done
