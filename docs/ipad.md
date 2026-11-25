# Kotoba for iPad: scope

Status (2026-11-23): the native iPad app exists and runs on an iPad (see `ios/README.md`): the shared Java core is translated with
J2ObjC, a Swift shell hosts the interface in a WKWebView, and Apple Vision reads comic text. Dictionaries import from the app's
own storage or from Files folders, books open, and a tapped word opens its dictionary popup. Still to do: comics and Vision OCR on
the iPad, Listening audio, a Home Screen icon, opening books from Files. Below is the original plan, kept for the options not taken.

Goal: the **reader** (books, comics, tap-to-look-up, saving cards) with the same dictionaries, on iPad.

## The one real problem: the core is Java

Kotoba's brain (dictionary import and search, deinflection, EPUB/CBZ parsing, cards, FSRS, sync) is the phone app's
own Java in `android/src/app/kotoba/reader`. The Mac app runs it as a **Java subprocess** behind a local server.
iPadOS has **no JVM and does not let an app launch a subprocess**, so that trick can't be copied. The interface itself
(`android/assets/*.js/css/html`, ~50 MB with the OCR models) is plain web and runs in a WKWebView as it does on Mac.

| Option | Reuse | Offline | Risk |
|---|---|---|---|
| **A. Translate the Java to Objective-C with J2ObjC** and link it into the app. **Recommended, after a spike.** | Everything: search quirks, importers and sync behave as on phone/Mac | Yes | J2ObjC no longer ships binary releases (build from source); its Java library is a subset of the JDK (`java.nio.file` is used in a few places and may need patching); new SQLite and Bitmap shims needed |
| B. Rewrite the needed core in Swift | Only the UI | Yes | Largest. The needed Java is ~500 KB (Library alone is 187 KB) and README describes hundreds of search behaviours that would each need re-checking |
| C. iPad as a client of the Mac's server | All | **No**: needs the Mac on and reachable | Smallest, but not an iPad app you can read on a train |

Why A looks feasible: the shared classes use only a small slice of Android (SQLite, ContentValues, Cursor, Context,
Bitmap/Matrix, Uri; the Mac already stands in for them in `desktop/core/shim`), and I checked that they use **no
records, pattern-matching instanceof, text blocks, arrow-switch or `var`**, so J2ObjC's front end shouldn't trip on
the language level. Not yet proven: that it compiles and that import speed is acceptable. That is **Phase 0**.

## What will be added (v1: the reader)

Most of this arrives with the core rather than being rebuilt, since the UI calls the same `api(route)` bridge.

| Area | v1 |
|---|---|
| Dictionaries | Import `.mdx/.mdd` and Yomitan `.zip` from Files; search, one row per word, deinflection (JA/KO/RU/DE), kanji grid, frequency, NHK audio, dictionary groups |
| Books | EPUB and TXT; horizontal/vertical, pages or scroll, RTL; tap to look up; highlights, bookmarks, contents, in-book search; text size per book |
| Comics | CBZ/ZIP and image folders, webtoon and paged, Mihon `.tachibk` import (progress, categories, covers) |
| Comic text | 文 layer using **Apple Vision** through a Swift bridge (same recognizer the Mac uses; languages ja, ko, zh, th, ru confirmed in the Vision list on this Mac, to re-check on the iPad's OS) |
| Cards | ＋ card and sentence cards from books and comics, folders, FSRS-5 review, stats, known words (no highlighting), Anki/CSV export, backup/restore |
| Sync | Same `kotoba-<device>.json` merge, through a Files folder (see below) |
| iPad touches | Sidebar layout in landscape (reuse the Mac's `desktop.css` idea), split view / Stage Manager, hardware-keyboard page turns, pointer hover lookup (Shift-hover like Mac), "Open in Kotoba" for EPUB/CBZ/MDX from Files |

## Possible but later

- **Listening** sets and audiobooks (the core already has them; needs the folder picker and audio session).
- **Scan text** with the camera, and screenshots shared into Kotoba (Vision again).
- **PP-OCRv5 on ONNX** as a second OCR (onnxruntime exists for iOS; 48 MB of models). Only if Vision reads manga badly.
- **Pleco handoff** probably works through Pleco's iOS URL scheme; unverified.
- **Translate** button into Apple's Translate or Google Translate (the Mac's local Gemma won't fit).
- **Import a ready-made index from the Mac** to skip the ~3 minute dictionary import (paths in the index would need remapping).
- **USB-C drive**: the T7's Mihon folder could be read in place from Files; untested.

## Not planned (possible, but not the reader)

- Video player with hover subtitles (libmpv exists for iOS, but it is the Mac's feature).
- Safari extension / page lookup, the Write editor, live captions, Gemma grammar check.

## Not possible on iPadOS

- **Lyrics that follow what's playing.** iOS gives no access to other apps' now-playing (Android uses media sessions, the Mac uses AppleScript and the YouTube Music API). Manual lyric search or `.lrc` import could still work.
- **Screen overlay over games and other apps** (the Mac's ⌃` layer). iOS apps can't draw over, or capture, other apps except through a ReplayKit broadcast, which can't give interactive lookup.
- **HunyuanOCR, PaddleOCR-VL, local Gemma 26B**: Python or Android-GPU runtimes, multi-GB, with no practical iPad port.
- **Running Java, mpv, ffmpeg, Python as helpers**: no subprocesses on iPadOS (the reason for the architecture above).
- **Syncthing as on the phone**: no official iOS client. Möbius Sync is a third-party Syncthing client that iOS suspends in the background, so don't rely on it.

## Sync with phone and Mac

Use an **iCloud Drive folder** for the iPad. To keep the phone on Syncthing, point the Mac's Syncthing folder
`kotoba-sync` at a folder *inside* iCloud Drive (`~/Library/Mobile Documents/com~apple~CloudDocs/KotobaSync`). Then the
Mac bridges both: phone ↔ Syncthing ↔ Mac ↔ iCloud ↔ iPad. No code needed, but untested: "Optimize Mac Storage" could
evict the small JSON files, and the Mac's disk is tight.

## Phases

0. **Spike (decides A vs B).** Install Xcode; build J2ObjC; translate `MdictFile`, `Yomitan`, `Deinflect`, `Library` with new SQLite and Bitmap shims; run a lookup in a test app. Success: a dictionary imports and 食べさせられなかった finds 食べる with its grammar.
1. **Shell.** UIKit/SwiftUI app, WKWebView, a scheme handler replacing the Mac's local HTTP server, `Kotoba.*` bridge for iPad, Files pickers with security-scoped bookmarks.
2. **Reader on iPad.** Books and comics end to end; layout for iPad sizes and orientations; keyboard and pointer.
3. **OCR bridge** (Vision) and the sync folder.
4. Later items above, as wanted.

## What I need from you

- **Xcode** (≈15 GB; the Mac has ~27 GB free). Not installable from here.
- Which **iPad** (model, iPadOS version) and whether you have a **paid Apple Developer account**. Free signing works but the app must be reinstalled every 7 days ([Expo's explainer](https://expo.dev/blog/apple-developer-program-explained-free-vs-paid)); $99/yr removes that.
- Roughly how much space the iPad has: the index alone is ~350 MB, plus the dictionary files.

## Unverified

J2ObjC build and JRE coverage ([releases](https://github.com/google/j2objc/releases), [2025 roadmap](https://groups.google.com/g/j2objc-discuss/c/RbdLUtfSqys)); Vision's quality on vertical manga on iPad; iCloud-folder sync; Pleco scheme; external-drive reading.
