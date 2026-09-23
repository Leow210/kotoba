# Kotoba for Mac

A Mac companion to the Kotoba phone app: the same dictionaries, search and cards, plus a video player whose
subtitles you can hover to look words up. It syncs cards and reviews with the phone through a shared folder.

## Build and run

Needs JDK 21, Swift (Xcode Command Line Tools), ffmpeg and mpv (`brew install ffmpeg mpv`).

```sh
desktop/build-app.sh          # → desktop/build/Kotoba.app
open desktop/build/Kotoba.app
```

Data (library index, cards, subtitle cache) lives in `~/Library/Application Support/Kotoba`. Furoku extras go in
its `extras/` folder, as on the phone.

## How it's put together

| Path | What it is |
|---|---|
| `mac/Sources/Kotoba/App.swift` | The app: starts the core, shows the interface in a WKWebView, pickers, menus, video windows |
| `mac/Sources/Kotoba/MpvView.swift` | Video through libmpv's OpenGL render API (the IINA approach); mpv doesn't draw subtitles |
| `mac/Sources/Kotoba/PlayerWindow.swift` | A video window: mpv under a transparent web layer, and the messages between them |
| `core/src/…/DesktopServer.java` | The core: the phone app's own `Library`, `Store`, `Routes`, `Sync`… behind a local server (127.0.0.1 + session cookie); subtitle tracks via ffprobe/ffmpeg |
| `core/shim/android/…` | Desktop stand-ins for the few Android APIs those classes use (SQLite over sqlite-jdbc, ContentValues, Context) |
| `web/desktop.js`, `desktop-after.js`, `desktop.css` | The bridge (`Kotoba.call` → fetch), the Video tab, the sidebar layout |
| `web/player.*` | The player layer: subtitles (SRT/VTT/ASS), hover lookup popup, controls, transcript |

Requests common to both apps are answered by `android/src/…/Routes.java`, so a change there reaches the phone and
the Mac alike. The interface files come from `android/assets` unchanged.

## Reader

The phone's book reader (EPUB and TXT, vertical or horizontal, tap-to-look-up, highlights, bookmarks) runs on the
Mac too: Reader › ＋ Add imports books from Finder. ←/→ turn pages in the book's direction (← is next in a
right-to-left book); Space, Page Up/Down and ↑/↓ also turn.

Comics work as on the phone, including the 文 text layer (the same PaddleOCR models on onnxruntime for macOS,
about 0.2 s a page). The phone's Mihon webtoons are mirrored to `/Volumes/T7/Mihon` by Syncthing (receive-only; its `.stignore` keeps only `downloads/* (KO)` and `autobackup/`, so manga stays on the phone);
while the drive is plugged in, Kotoba rescans `downloads/` and imports the newest `autobackup/*.tachibk` (titles,
categories, read chapters) by itself. Covers come from `mihon-covers/` in the data folder, as on the phone.

## Player

- Opens anything mpv plays. Subtitles are found beside the video (`Name.srt`, `Name.zh-HK.srt`, `subtitles/Name.srt`)
  and inside it (text tracks, extracted with ffmpeg and cached). Picture subtitles (PGS) can't be looked up.
- The main line is hoverable; a second line (e.g. English) can show underneath. The lookup language follows the
  track (ja, zh, ko, th, ru) and can be changed in the 字幕 menu. Chinese is tried as written, then simplified.
- Keys: Space play/pause · ←/→ 5 s (⇧ 1 s) · A/D previous/next line · S replay line · P pause after each line ·
  T transcript · Z/X subtitle delay · [ ] speed · −/= subtitle size · F full screen · M mute.
- ＋ Card saves the word with the subtitle line as its example and the episode and time as a note.

## Sync

Library › Sync: choose a folder that a sync tool keeps identical on both devices (Syncthing works on Android and
Mac). Each device writes `kotoba-<device>.json` there and merges the other's: folders, cards, review state and
history, deletions. The phone syncs every minute while open and when you leave it; the Mac every 30 seconds.

## Development

`defaults write app.kotoba.desktop DevHooks -bool true`, then `tools/mac.py` evaluates JavaScript in the window
(`--player` for the newest video) and takes snapshots (`--shot`, `--player-shot`), like `tools/cdp.py` for the phone.
