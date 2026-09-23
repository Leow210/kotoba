#!/bin/sh
# Builds desktop/build/kotoba-core.jar: the Android app's shared classes + desktop stand-ins for the Android APIs they use + the local server.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../android/src/app/kotoba/reader"
OUT="$HERE/build/core-classes"
JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}"
CP="$HERE/libs/json-20250517.jar:$HERE/libs/sqlite-jdbc-3.50.3.0.jar:$HERE/libs/slf4j-api-2.0.17.jar"
rm -rf "$OUT" && mkdir -p "$OUT"
SHARED="Library Store WordLists Extras Routes Yomitan HtmlText MarkupFix Deinflect Fsrs MdictFile ZipSource Lzo Ripemd128 BookParser Sync"
# Paths contain spaces ("New Chinese typing"), so sources go through a quoted argument file.
ARGS="$HERE/build/core-sources.txt"
: > "$ARGS"
for c in $SHARED; do printf '"%s"\n' "$SRC/$c.java" >> "$ARGS"; done
find "$HERE/core/shim" "$HERE/core/src" -name '*.java' | while IFS= read -r f; do printf '"%s"\n' "$f" >> "$ARGS"; done
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 21 -nowarn -cp "$CP" -d "$OUT" @"$ARGS"
"$JAVA_HOME/bin/jar" --create --file "$HERE/build/kotoba-core.jar" -C "$OUT" .
echo "$HERE/build/kotoba-core.jar"
