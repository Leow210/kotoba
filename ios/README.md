# Kotoba for iPad (native): the core

The Java core (dictionary import and search, deinflection, EPUB and comic parsing, cards, FSRS) is translated to
Objective-C with [J2ObjC](https://github.com/google/j2objc) and compiled for iOS, so the iPad app runs the same code as the
phone and the Mac. `App/` is the iPad app around it: a Swift shell with the interface in a WKWebView (served from the
bundle and the core through a `kotoba://` scheme), the system Files pickers (folders are opened in place and remembered),
and Apple Vision for comic text. See `docs/ipad.md` for the plan.

## Build

1. Build J2ObjC (Google no longer ships binaries): clone it, apply `ios/j2objc.patch` (JDK 21 can't compile one
   Java 22 line, newer clang rejects some of its warnings, and `File.toPath` used a reflective lookup that fails on iOS), then
   `JAVA_HOME=<jdk 21> make -j6 dist`. The `JRE.xcframework` step fails until `sudo xcodebuild -runFirstLaunch`; the static
   libraries in `dist/lib/{iphone,macosx}` are all that's needed.
2. `J2OBJC=<j2objc>/dist ios/build-core.sh` translates the classes listed in it plus `ios/shim`, and compiles them for
   iphoneos (arm64) and macosx.

## What's here

| Path | What |
|---|---|
| `shim/android/…` | iOS versions of the few Android classes the core uses (Context, ContentValues, Cursor, SQLiteDatabase, Bitmap…) |
| `shim/app/kotoba/ios/Sqlite3.java` | The system SQLite, called through J2ObjC native methods |
| `shim/app/kotoba/reader/Core.java` | The entry point: `call(route, json)` for the interface's requests, `file(kind, path)` for pages and images |
| `shim/ai/onnxruntime/…` | Stubs so the shared OCR classes translate; comic text will use Apple Vision |
| `build-core.sh`, `j2objc.patch` | Build and the patch to J2ObjC |

Link with `-ljre_emul -ljson -lsqlite3 -lz -liconv -licucore -ObjC` (`libjre_emul.a` already contains the split libraries).

## Tested

On a Mac (the same arm64 code): a 79,007-entry Yomitan dictionary imported in 44 s; `食べさせられなかった` → 食べる ·
causative-passive · negative · past in 2 ms; an EPUB imported and opened. Not yet tested on an iPad or in the Simulator.

## The app

```sh
ios/build-app.sh simulator     # translate, generate ios/App/Kotoba.xcodeproj (XcodeGen), build for the iPad simulator
SIGN_ARGS="-allowProvisioningUpdates DEVELOPMENT_TEAM=<team id>" ios/build-app.sh device
```

Needs Xcode, XcodeGen (`brew install xcodegen`) and the J2ObjC build above. The Xcode project reads J2ObjC's headers and static libraries from the J2ObjC `dist` folder (`J2OBJC` in `App/project.yml`, `/Volumes/XcodeDisk/work/j2objc/dist` on the Extreme SSD). For a real iPad, open `ios/App/Kotoba.xcodeproj` in
Xcode once, choose your team under Signing, and run. With a free Apple ID the app has to be reinstalled every 7 days.

The interface is `android/assets` plus the Mac's `desktop/web/{desktop.css,hover-modifier.js,desktop-after.js}` and the iPad bridge
`App/web/ipad.js` (copied into the app by a build phase; the OCR models are left out).

Tested in the iPad simulator (iPadOS 17.2): importing a Yomitan dictionary from a Files folder, browsing it, opening entries,
adding an EPUB from Files, reading it, and tapping a word (食べさせられなかった → させる · passive or potential · negative · past,
with the 三省堂 entry). Not yet tried: a real iPad, comics and OCR, Listening audio, sync, exports.
