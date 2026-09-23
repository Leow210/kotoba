# Kotoba for Mac

A Mac companion to the Kotoba phone app: the same dictionaries, search and cards, plus a video player whose
subtitles you can look up by holding Shift and hovering. The hover key can be changed in Settings › Reading.
It syncs cards and reviews with the phone through a shared folder.

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
right-to-left book); Space, Page Up/Down and ↑/↓ also turn. ⌘+ / ⌘− change the text size (saved per book, like
Display › Text size) and ⌘0 returns to your default. Hold the configured hover key (Shift by default)
over a word to open its dictionary. A plain click does not look up a word on the Mac unless enabled in
Settings › Reading; click and drag still selects text.

Comics work as on the phone (⌘+ / ⌘− zoom from 25% to 400%, including smaller than the screen to see more of a webtoon at once; ⌘0 fits the page again), including the 文 text layer (the same PaddleOCR models on onnxruntime for macOS,
about 0.2 s a page). The phone's Mihon webtoons are mirrored to `/Volumes/T7/Mihon` by Syncthing (receive-only; its `.stignore` keeps only `downloads/* (KO)` and `autobackup/`, so manga stays on the phone);
while the drive is plugged in, Kotoba rescans `downloads/` and imports the newest `autobackup/*.tachibk` (titles,
categories, read chapters) by itself. Covers come from `mihon-covers/` in the data folder, as on the phone.

## Player

![Hovering a Korean subtitle: the compact popup](../docs/images/mac-player.jpg)

- Opens anything mpv plays. Subtitles are found beside the video (`Name.srt`, `Name.zh-HK.srt`, `subtitles/Name.srt`)
  and inside it (text tracks, extracted with ffmpeg and cached). For hardcoded subtitles, choose
  **OCR hardcoded subtitles** in the 字幕 menu. It reads the bottom of the local video every two seconds and displays
  recognized text for lookup. OCR accuracy depends on the video and language; picture subtitle tracks are not read as tracks.
- No subtitles in the spoken language? [Subtitle Generator](https://github.com/Leow210/Subtitle_Generator) transcribes
  the audio into an `.srt`. Save it beside the video as `Name.srt` (or `Name.ko.srt`, `Name.zh-HK.srt`) and the player
  picks it up.
- Hold Shift and hover over the main line or transcript to open a lookup popup. Settings › Reading can change
  the key to Option, Control, Command, or no key. A second line (e.g. English) can show underneath. The lookup language follows the
  track (ja, zh, ko, th, ru) and can be changed in the 字幕 menu. Chinese is tried as written, then simplified.
- The popup shows the entry as the dictionary lays it out (the same page as the main window). It is compact by default:
  the word, its grammar breakdown, the first dictionary's entry in a short scrolling box and ＋ Card. ⤢ adds a tab per
  dictionary, a taller box, Open in Kotoba and Copy; the size you pick is remembered.
- When a form fits two verbs (걸었다고: 걷다 "walk" or 걸다 "bet"; 들었어: 듣다 or 들다), the popup shows the first with
  **or 걸다** beside the grammar note; click it to switch.
- Words inside a definition can be looked up too: hold Shift over one and a second popup opens beside it (and so on).
  Releasing Shift while the pointer is over a popup keeps it open. × closes a popup (and any opened from it); Esc or
  clicking elsewhere closes them all. The browser helper's popup works the same way.
- Keys: Space play/pause · ←/→ 5 s (⇧ 1 s) · A/D previous/next line · S replay line · P pause after each line ·
  T transcript · Z/X subtitle delay · [ ] speed · −/= subtitle size · F full screen · M mute.
- ＋ Card saves the word with the subtitle line as its example and the episode and time as a note.

### Streaming subtitles in Firefox or Chrome

Kotoba › Video › **Browser subtitle helper** copies a one-time install link (valid ten minutes). Paste it into
Firefox or Chrome with Tampermonkey installed and accept the install. On YouTube and GagaOOLala, turn on the site's captions:
the helper redraws the current caption as text, and holding **Shift** over a word asks Kotoba for Mac for it. The
popup is the same as the player's: the entry as the dictionary lays it out, a tab per dictionary (⤢), frequency,
whether it's already a card, **or …** for a second reading, ＋ Card (the caption line becomes the example, the video
title and time the note), Open in Kotoba, ×, and Shift over a word inside an entry for a second popup. The video
pauses while a word is shown (⏸ on lookup in the helper's toolbar). Kotoba for Mac must be open. Reinstall from the
Video tab to update an older copy (current: 0.4.0).

The site can't load Kotoba's pages itself, so the helper asks for each entry with its stylesheets and images packed
in (`/helper/entry`) and shows it in a frame with scripts off. In Chrome, YouTube only accepts HTML through a Trusted
Types policy; the helper makes its own (`kotoba-helper`).

The helper talks to Kotoba on 127.0.0.1:47823 through Tampermonkey (`GM_xmlhttpRequest`) with a key built into the
installed script; that address only answers lookups, entries, definitions, frequency and card saves. Reinstall the helper
from the Video tab if the key changes. It reads only the caption text shown on the page.

## Sync

Library › Sync: choose a folder that a sync tool keeps identical on both devices (Syncthing works on Android and
Mac). Each device writes `kotoba-<device>.json` there and merges the other's: folders, cards, review state and
history, deletions. The phone syncs every minute while open and when you leave it; the Mac every 30 seconds.

## Development

`defaults write app.kotoba.desktop DevHooks -bool true`, then `tools/mac.py` evaluates JavaScript in the window
(`--player` for the newest video) and takes snapshots (`--shot`, `--player-shot`), like `tools/cdp.py` for the phone.
