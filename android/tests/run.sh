#!/bin/sh
# Desktop tests for the pure-Java core. Optional arguments: .mdx/.mdd files to read.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../src/app/kotoba/reader"
OUT="$(mktemp -d)"
JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$OUT" "$SRC/MdictFile.java" "$SRC/HtmlText.java" "$SRC/Lzo.java" "$SRC/Ripemd128.java" "$SRC/Fsrs.java" "$SRC/Deinflect.java" "$SRC/ZipSource.java" "$SRC/BookParser.java" "$SRC/MarkupFix.java" "$HERE/CoreTests.java"
"$JAVA_HOME/bin/java" -cp "$OUT" app.kotoba.reader.CoreTests "$@"
rm -rf "$OUT"
