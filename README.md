# Kotoba 詞

**A personal, offline dictionary and flashcard app for Android.** Kotoba runs on the MDict (`.mdx`/`.mdd`) dictionaries you already own, e.g. Monokakido exports. It shows them in their original typography and turns anything you look up into spaced-repetition flashcards. It works without an account or internet connection, and nothing leaves the phone.

Languages it's built around: **Japanese, Korean, Chinese, Thai, Russian**. There's also a [project page](https://leow210.github.io/kotoba/) with the videos playing inline.

### Demos
Tap a picture to play the video (15–25 s, no sound).

| Scan a game on another screen | Dictionary word lists | Kanji grid and radicals |
|---|---|---|
| [![Scan demo](docs/media/scan-poster.jpg)](docs/media/scan.mp4) | [![Word lists demo](docs/media/lists-poster.jpg)](docs/media/lists.mp4) | [![Kanji demo](docs/media/kanji-poster.jpg)](docs/media/kanji.mp4) |
| Photograph the screen, tap the text box, tap a word | 専門用語 → 56 categories → 医学, with hanja | Radicals by stroke count; filters kept across 漢検 and 漢辞海 |

### Screenshots
| Search | Entry | Flashcard with NHK audio | Review |
|---|---|---|---|
| ![Search](docs/images/search.png) | ![Entry](docs/images/entry.png) | ![Card](docs/images/card-audio.png) | ![Review](docs/images/review.png) |

| Scanned text | Look up from a scan | Dictionary word lists | Categories |
|---|---|---|---|
| ![Scan](docs/images/scan.png) | ![Scan lookup](docs/images/scan-lookup.png) | ![Dictionary lists](docs/images/dict-lists.png) | ![Categories](docs/images/categories.png) |

| Hanja under Korean words | Radicals by stroke count | Furoku (付録) | Vertical furoku |
|---|---|---|---|
| ![Hanja grid](docs/images/hanja-grid.png) | ![Radicals](docs/images/radicals.png) | ![Furoku](docs/images/furoku.png) | ![Vertical furoku](docs/images/furoku-vertical.png) |

| Browse a dictionary | Vertical dictionaries | Look up a selection |
|---|---|---|
| ![Browse](docs/images/browse.png) | ![Vertical](docs/images/vertical.png) | ![Lookup](docs/images/lookup.png) |

## Features

### Dictionaries
- **Import from a phone folder:** point Kotoba at a folder of `.mdx` files with their `.mdd` media (e.g. `Download/Monokakido_Ciyue`). The files stay in place and are indexed on the phone. A 13-dictionary Monokakido set takes about 3 minutes and 350 MB.
- **Reads MDict** versions 1 and 2, with zlib/LZO compression and encrypted key indexes. Images, audio, CSS and cross-references come from the `.mdd`.
- **Dictionary groups:** All · Japanese 国語 · Kanji 漢字 · Pronunciation 発音 · Korean · Chinese · Thai · Russian, or a single dictionary. Groups are assigned automatically and can be edited.
- **Order and settings:** dictionaries can be reordered, renamed, turned off, or marked as kanji dictionaries.

### Search
- **Headword search** by prefix. It ignores kana vs katakana (男 / おとこ / オトコ), Russian stress marks and ё, and Daijirin's ▽▼ marks.
- **Contains**, **In definitions** and **In examples** modes. Definitions and examples are indexed separately, and matches are substring matches, so Thai works without spaces.
- **Dictionary form lookup** for conjugated words, with the grammar explained:
  - `食べさせられなかった` → **食べる** + させられる + ない + かった · causative-passive · negative · past
  - `추웠어요` → **춥다** + 았/었 + 어요 · past · polite, ㅂ-irregular
  - `공부했어요` → **공부** + 하다 + 았/었 + 어요 · 하다 verb · past · polite
  - `занимаюсь` → **заниматься** · 1st person singular · reflexive

  Only real headwords are shown, at most three, so the list stays short.
- **Kanji strip:** every kanji in the query links to your kanji dictionaries (漢辞海, 漢検).
- **Browse a whole dictionary** like a paper one:
  - scroll through every headword in both directions;
  - jump with the index rail (あかさ… / 가나다 / ก / А–Я / A–Z) or the "Jump to…" box;
  - 🎲 opens a random word.

### Entries
- **Original styling:** each dictionary's own CSS, images and audio. Text size is adjustable (125% by default).
- **Focused entries:** you see the word you asked for (言葉), not the 60 compounds on the same page. Those are listed under "On this page", and "Show whole page" shows everything.
- **Navigation:**
  - tabs switch between dictionaries that have the same word;
  - ‹ › browses to the previous or next word;
  - cross-references open the linked entry.
- **Vertical text (縦書き)** is available for any dictionary. Dictionaries that are vertical by design switch to it automatically.
- **Audio:** one consistent 🔊 button in every dictionary that has audio: NHK, 新明解, Thai, Korean, Russian and 中日. Auto-play is optional.
- **Text selection:** select any text to get Look up · Search · Copy · Card · Share. Look up and Save card are also added to Android's selection menu, and "Look up in Kotoba" works from other apps.

### Vocabulary & flashcards
- **Bookmarks and cards:** ☆ bookmarks and ＋ makes a flashcard. Sub-words save separately from their parent word.
- **Keep only the meanings you want,** e.g. just ② of 言葉. Cards keep the dictionary's formatting.
- **Mix sources:** the definition can come from one dictionary and the audio from another, e.g. Daijirin's definition with NHK's pronunciation. NHK clips show pitch accent (タベ＼ル drawn with a line over the high morae) and include NHK's conjugated forms.
- **Editing:** every field is editable, and you can add a note and an example sentence.
- **Folders** with filters, sorting, multi-select move/copy/delete, and review per folder.
- **FSRS-5 scheduling** with learning steps, interval previews, undo, daily new-card limits, target recall, a 7-day forecast and a streak.
- **Export** to Anki (TSV with HTML and folder tags) and CSV. JSON backup/restore merges and skips duplicates.

### Kanji, word lists, furoku
- **Kanji grid:** every kanji in 漢辞海 and 漢検 as a grid you can filter by Kanken level, stroke count, radical and 常用/教育/人名. Old and variant forms (舊 → 旧) find their main entry.
  - Radicals are picked from their own grid, grouped by stroke count.
  - Filters stay on when you switch between 漢検 and 漢辞海 (漢検's ⺡ becomes 漢辞海's 水).
- **Word lists:** import frequency, JLPT or TOPIK lists (TXT/CSV, optionally with rank). A whole list can become a deck.
- **Dictionary selections:** each dictionary's own lists, kept separate from imported ones: 大辞林's 地名, 人名, 四字熟語, 季語…, 新明解's 最重要語/重要語, 漢検's 四字熟語, NHK's 助数詞一覧, 朝鮮語辞典's 最重要語/基本語/品詞/専門用語, and more.
  - Lists with categories open as a grid of categories with word counts (地名 › 東北地方 › 青森), plus すべて for everything.
  - Korean words show their hanja underneath (가격 / 價格).
- **List or grid, list order or あいう:** every word list can be shown as a list or as a grid of headwords (no numbers), in its own order (with sections) or sorted by reading. Flashcard folders also have a grid view.
- **Furoku (付録):** each dictionary's appendix pages and PDFs, with Monokakido's Japanese titles and grouping, as a grid. Vertical pages fill the screen.
- **Translate:** sends a selection to the Google Translate app. Kotoba itself has no internet permission.

### Scanning text on other screens
- **写 Scan text** (search screen): photograph a game, visual novel or any screen with the phone's camera app, or open or share a screenshot to Kotoba.
- The text is read on the phone with the same OCR as comics. Tap a text box to look words up, fix the text, translate it, or save a card with the line as context.
- **Select area** reads just the part you drag over, such as the dialogue box (about 0.2 s). A short first line, like a speaker's name, stays on its own line.
- **Languages:** Japanese, Chinese (simplified and traditional), Korean, Thai and Russian.
  - Tapping a word in Chinese or Thai text looks in that language's dictionaries first.
  - Traditional characters also match simplified headwords (穿過 → 穿过, using OpenCC's character table).
- Scans are kept in the app's `files/scans/` folder (the newest 40).

### Reading
- **Books:** EPUB and TXT in Japanese, Korean, Thai and Russian. The text encoding is detected automatically.
  - Layout: horizontal or vertical (縦書き), scrolling or pages, left-to-right or right-to-left page order, page separators, an optional page-turn animation, and swiping to turn pages.
  - Tap a word to look it up. Furigana is skipped, so 食(た)べる is looked up as 食べる.
  - Highlights, bookmarks, a table of contents and in-book search.
- **Comics and manhwa:** CBZ/ZIP files and image folders, read in place. This includes Mihon's downloads folder.
  - Webtoon mode is one seamless vertical strip across chapters. Paged mode reads left-to-right or right-to-left.
  - A Mihon backup (`.tachibk`) import brings over read progress, categories and covers (see Development notes). Mihon's `_a1b2c3` hash suffix is removed from chapter names.
  - A series cover can also be set from an image file (series ⋯ → Change cover image) or from the current page (reader bookmark button → Use this page as the series cover).
- **Text in comics (OCR):** the 文 button outlines every speech bubble. Tap a bubble to see its text, then tap a word to look it up; conjugations are analysed (먹었어요 → 먹다 · past · polite). You can also fix recognition mistakes, copy, translate, save a card with the bubble as context, or open ☰ for all text on the page.
  - Korean uses a Korean model. Japanese uses the multilingual model, which also reads vertical columns right-to-left.
  - Everything runs on the phone (PaddleOCR PP-OCRv5 mobile on ONNX Runtime), at about 0.2–0.6 s per page. Results are cached.

## Build

Needs the Android SDK (build-tools 35, platform 35) and JDK 21. It doesn't use Gradle or network access.

```sh
python3 android/build.py                 # → android/build/kotoba.apk
adb install -r android/build/kotoba.apk
android/tests/run.sh [some.mdx …]        # desktop tests: MDX reader, text extraction, FSRS, deinflection
```

On first run: **Library → Import from a folder…** → choose your dictionary folder → Import.

## Code layout

| Path | What it is |
|---|---|
| `android/src/app/kotoba/reader/MdictFile.java` | MDX/MDD reader |
| `…/Library.java` | Import, indexes, search, resources, audio finder, browsing |
| `…/Deinflect.java` | Japanese / Korean / Russian conjugation rules with explanations |
| `…/HtmlText.java` | Splits entry text into definitions and examples; search normalization |
| `…/Store.java`, `Fsrs.java` | Folders, cards, reviews, export and backup; scheduler |
| `…/Books.java`, `BookParser.java`, `ZipSource.java` | E-reader: library, EPUB/TXT parsing, random-access ZIP |
| `…/Comics.java`, `MihonBackup.java` | Comic library, page serving, Mihon backup reader |
| `…/WordLists.java`, `MarkupFix.java`, `Fsrs.java` | Word lists; Monokakido markup/CSS fixes; scheduler |
| `…/Extras.java`, `tools/export_extras.py` | Furoku titles/pages and dictionary selections exported from the Monokakido Mac app |
| `…/Ocr.java` | On-device text detection and recognition for comic pages and scans |
| `…/Scans.java`, `ScanProvider.java`, `assets/scan.js` | Camera/screenshot scanner: the photo file, EXIF rotation, cropping; the camera app writes through `ScanProvider` |
| `…/MainActivity.java` | WebView host, local resource server, file pickers, selection menu |
| `android/assets/` | The interface (HTML/CSS/JS) |
| `docs/ROADMAP.md` | What's being built next and how |

## Limits
- MDict files with registration-code encryption and MDict 3.0 can't be opened.
- Splitting an entry into individual meanings depends on Monokakido-style markup. Other MDX files show and save as whole entries.
- Dictionary-form lookup covers common conjugations, not every rare or classical form.

## Third-party
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT). Bundled for arm64, so OCR needs a 64-bit ARM phone.
- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) PP-OCRv5 mobile detection and recognition models: multilingual, Korean, Thai, East Slavic (Apache-2.0).
- [OpenCC](https://github.com/BYVoid/OpenCC) traditional → simplified character table, `assets/t2s.txt` (Apache-2.0).

## Roadmap
Phases 1–4 are done: the book reader, the comic reader, Mihon support and OCR. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Development notes (handoff)

This section holds the context needed to keep developing Kotoba in a new session.

### Environment
- **Paths:**
  - repo: `/Users/leowoo/Documents/ChatGPT/New Chinese typing/kotoba`;
  - Android SDK: `~/Library/Android/sdk` (build-tools 35.0.0, platform 35);
  - JDK 21: `/Library/Java/JavaVirtualMachines/jdk-21.jdk`.
- **`android/build.py`:**
  - runs aapt2 → javac (`--release 11`) → d8 → zipalign → apksigner, signing with `~/.android/debug.keystore`;
  - `android/libs/**.jar` goes on the classpath and into the dex, and `libs/**/jni/<abi>/*.so` goes into `lib/<abi>/`;
  - `assets/` is copied recursively, and `.onnx` files are stored uncompressed.
- **Target:** `minSdk` 26, `targetSdk` 34, app ID `app.kotoba.reader`, version 0.3.0.
- **Test phone:** OnePlus CPH2749 (Android 16), connected over adb. Install with `adb install -r android/build/kotoba.apk`, which keeps the data.
- **Desktop tests:** `android/tests/run.sh [mdx…]` runs the pure-Java classes (MDict, text, FSRS, deinflection, ZIP, book parsing). 60 pass.

### Architecture
- **UI host:** one Activity (`MainActivity`) holds a WebView. The UI is plain HTML/JS in `android/assets/`, served from `https://appassets.androidplatform.net/`.
  - Scripts load in order: `app.js` (core, search, entries, vocabulary, review, settings), `reader.js` (books), `comics.js` (comics and OCR layer, plus the shared `ocrTextSheet`), `extras.js` (word lists, furoku, Mihon menu), `scan.js` (scanner).
  - Later scripts wrap earlier functions (e.g. `renderFolders`) instead of editing them.
- **Bridge:**
  - JS calls `Kotoba.call(id, route, jsonBody)`, and Java answers through `window.__reply`. In JS this is wrapped as `api(route, body)`.
  - Every route is a `case "…"` in `MainActivity.route()`.
  - Java → JS events go through `window.__event`, handled in JS with `on(type, fn)`.
  - Direct JS interface methods: `Kotoba.pickFolder`, `pickBooks`, `pickComicFolder`, `pickComicFiles`, `pickComicCover`, `pickMihonBackup`, `pickWordList`, `copy`, `share`, `translate`, `exportFile`, `restoreBackup`, `openResource`, `setBars`.
- **Resource URLs served by `shouldInterceptRequest`:**
  - `/d/<dict>/<rec>.entry` returns entry HTML, passed through `MarkupFix.html`;
  - `/d/<dict>/<file>` returns MDD resources (CSS goes through `MarkupFix.css`);
  - `/book/<id>/<path>`;
  - `/comic/<chapter>/<page>`;
  - `/comic/cover/<series>`.
- **Databases:**
  - The library DB (dictionary index) has the tables `dicts`, `records`, `keys`, `anchors`, `resources` and `kanji`, plus per-dictionary FTS4 tables `body_<id>`. `dict_ids` keeps IDs stable across re-imports.
  - The personal DB (`Store`) holds folders (`study` = included in review), items/cards, FSRS state, history and settings. Books, comics (`series`, `chapters`, `comic_marks`) and `ocr_cache` also live in it.
- **Entry focus (`app.js` `findFocus`/`unitInfo`):** the `UNIT`/`HEAD`/`WORD`/`READING` name sets decide which part of a dictionary page is "the word". When a new dictionary focuses the wrong unit, add its tag or class names there. Kanjikai's `親字TD` / `親字-常用…` were added for exactly this.
- **Deinflection:** lives in `Deinflect.java` (JA/KO/RU rules). The Korean analysis order is in `Library.koreanAnalyses`: whole word → rules → stem + dictionary-listed ending → noun + particles → compound → prefix. Keep results narrow: the real word first, at most three.
- **OCR:** `Ocr.java` runs the PaddleOCR PP-OCRv5 mobile models on ONNX Runtime 1.30.0 (arm64 only; the jar and `.so` files are in `android/libs/onnxruntime/`).
  - Model files in `assets/ocr/`:
    - `det.onnx`: detection;
    - `rec-ko.onnx` + `dict-ko.txt`: Korean recognition;
    - `rec-ja.onnx` + `dict-ja.txt`: multilingual CJK and Latin recognition (Japanese, and Chinese simplified/traditional);
    - `rec-th.onnx` + `dict-th.txt`: Thai;
    - `rec-ru.onnx` + `dict-ru.txt`: East Slavic (Russian, Ukrainian, Belarusian) and Latin.
  - They were downloaded from `huggingface.co/PaddlePaddle/{PP-OCRv5_mobile_det_onnx, korean_PP-OCRv5_mobile_rec_onnx, PP-OCRv5_mobile_rec_onnx, th_PP-OCRv5_mobile_rec_onnx, eslav_PP-OCRv5_mobile_rec_onnx}`.
  - Only one recognizer is loaded at a time, and ONNX Runtime's memory arena is off, so memory goes back after each page. The dictionaries are the `character_dict` lists in each model's `inference.yml`.
  - Routes: `ocr.page {chapter, page, lang, refresh}` and `ocr.clear`.
  - How a page is read, as tuned on real Mihon chapters:
    - Detection runs at a 1280-pixel long side. Webtoon strips taller than 3× their width go in tiles 2.5× the width tall.
    - Recognition pads each line to at least 320 pixels wide, as PaddleOCR does; without this, trailing characters are dropped.
  - Vertical Japanese columns:
    - The side padding around each column is 0.3× normal, so neighbouring furigana doesn't get into the crop.
    - Furigana columns are dropped: those under 0.6× the page's median column width, or thin columns alongside a column at least 1.7× wider.
    - Each column is read twice, once rotated and once cut into upright characters in a row. The reading whose length best matches height ÷ width × 1.6 wins, weighted by confidence.
  - Grouping:
    - Columns join a bubble right-to-left when the gap is under 1.1× the column width.
    - Lines stack into a bubble when the gap is under 0.9× the line height.
    - Lines with no letters (…, ※), and a lone Latin letter or digit, are dropped.
  - Korean after recognition:
    - `fixKorean` turns 에/예 into 어/여 after a syllable ending in ㅆ (기다렸에 → 기다렸어).
    - Short lines with no Hangul or CJK are dropped.
    - In the bubble sheet, every syllable is tappable and lookup runs from it to the end of the word, because the model often drops spaces.
  - `Ocr.VERSION` invalidates `ocr_cache`, so bump it whenever recognition changes.
  - Tuning tools:
    - `tools/ocr_eval.py` is a desktop mirror of the pipeline: `python3 tools/ocr_eval.py ja page.jpg --det 1280 --hx 0.3 --out outdir` draws the boxes.
    - `tools/ocr_pick.py` compares the two readings of vertical columns.
    - Both expect `det.onnx`, `kor.onnx`/`ja.onnx` and the `*_dict.txt` files next to them. Copy them from `android/assets/ocr/`: `rec-ko.onnx` becomes `kor.onnx`, `rec-ja.onnx` becomes `ja.onnx`, `dict-ko.txt` becomes `kor_dict.txt`, `dict-ja.txt` becomes `ja_dict.txt`.
    - Test chapters come from `/sdcard/Mihon/downloads/…` via `adb pull`.
- **Rules to keep:**
  - The app has no INTERNET permission and stays offline. Translate hands off to the Google Translate app.
  - Never modify the user's original dictionary, book or comic files.
  - Test files go in the app's folder (`/sdcard/Android/data/app.kotoba.reader/files/…`), never in `/sdcard/Download`.

### Dictionary extras (furoku titles, missing furoku, selections)
- The MDX exports lost Monokakido's menus: furoku titles were file names (romaji for 中日/NHK), and 大辞林/新明解 furoku weren't in the `.mdd` at all.
- `python3 tools/export_extras.py OUT` reads the Monokakido Mac app's data (`~/Library/Group Containers/group.jp.monokakido.Dictionaries/…/dictionaries/<PRODUCT>/Contents`) and writes `OUT/<mdx stem>/kotoba-extras.json` plus any pages the `.mdd` lacks under `files/`. Push it to `/sdcard/Android/data/app.kotoba.reader/files/extras/` (see below).
  - Menus: `Root.plist`/`Appendix.plist` (plist trees), and for 漢検/漢辞海 `index/Top.idx` (runs of UTF-16 titles followed by as many targets).
  - Selections: `.entries` files are a 36-byte header (u32 count) + 8-byte records (u32 page, u16 item, u16 type). Page 506 item 0x4001 is anchor `00506-4001`, which `Library.reference` resolves via the `anchors` table. Names come from `headline/headline.headlinestore` (header 8×u32: …, count, record offset, words offset; 24-byte records `page u32, item u16, …, offset u32`; UTF-16 NUL-terminated strings).
  - 漢検's 四字熟語/故事・ことわざ/熟字訓 lists are `／level／word／reading／` strings inside `index/Jyukugo_*.idx`; they resolve by headword.
  - Sort keys: idioms without a reading get one from their page (青は藍より… → あおは藍より…).
  - 朝鮮語辞典 uses its own menu plists (`Entries` of `{Title, PlistName}` / `{EntryID}`, or `EntriesFileName`) and `.entries` with a 4-byte header. 索引 becomes 4 lists; 品詞 and 専門用語 keep their categories as sections, with a jump bar in the app. Headlines are `／1／가격／價格`, so the hanja shows under the word.
  - Its appendix pages (発音解説, 用言活用表, 助数詞一覧…, ids 101092+) were dropped by the MDX conversion. The exporter copies them from the extracted page XML listed in `PAGES` (on the T7 drive; file name = id − 1).
  - Push with a folder swap so the app never reads a half-copied manifest: `adb push OUT /sdcard/Android/data/app.kotoba.reader/files/extras.new`, then `mv extras extras.old && mv extras.new extras`.
- The app matches `extras/<name>/` to a dictionary by its `.mdx` file name. `/d/<dict>/files/…` URLs are served from that folder. A broken manifest only drops that dictionary's extras.
- Imported word lists sort あいう by `wordlist_items.sortkey`: the reading, or else the page reading of the word's first Japanese dictionary entry (`Library.readingOf`).

### README media and the project page
- `docs/index.html` is a static page for GitHub Pages (Settings → Pages → deploy from `main`, folder `/docs`). It uses the same images and videos as this README.
- `python3 tools/demo.py shots docs/images` retakes the newer screenshots. `python3 tools/demo.py video scan|lists|kanji docs/media` re-records a demo: it drives the phone with `adb input` taps and records the WebView through DevTools (`screenrecord` is blocked on the OnePlus). Leave the phone alone while it runs.
- The scanner demo uses a made-up visual-novel scene (`scan-1000000000004.jpg` in the app's `files/scans/`), not a real game.

### Debugging on the phone
- `python3 tools/cdp.py 'return await api("search",{q:"学",dict:6})'` evaluates async JS inside the running app's WebView through the DevTools socket. It needs `pip install websocket-client`, and the app must be open.
- `python3 tools/cdp.py --shot out.png` takes a screenshot of the WebView.
- Test-only routes that read from the app's external files folder:
  - `comic.importBackupFile {name}`;
  - `wordlist.importFile`;
  - `book.importPath`;
  - `comic.scanLocal` (scans `files/comics/`).

### Data on the phone
- **Dictionaries** were imported from `/sdcard/Download/Monokakido_Ciyue` (13 MDX files). Test copies sit in `…/files/test/<dict>/` and take about 1.8 GB; they can be deleted.
  - 漢辞海 (Kanjikai) uses a rebuilt MDX from `/Volumes/T7/Monokakido Android Dictionaries/Kanjikai2/output-v4/`: 8,861 main kanji.
  - Monokakido's "12,500字" also counts about 3,600 旧字/異体字 variants that appear inside those entries, many as images without Unicode.
  - The converter is `../tools/build_generic_mdict.py`. It was patched so that `親字-*` tags other than `親字相当部` count as headwords.
- **Mihon:** the downloads are in `/sdcard/Mihon/downloads/<source>/<series>/<chapter>_<hash>.cbz`, and auto-backups are in `/sdcard/Mihon/autobackup/`.
  - Mihon's cover cache (`/sdcard/Android/data/app.mihon/files/covers/<md5(thumbnailUrl)>`) is unreadable by other apps on Android 11+.
  - Kotoba instead looks in its own `…/files/mihon-covers/<md5>`. The md5 comes from each series' thumbnail URL in an imported backup (`series.thumb`).
  - To refresh the covers from a computer, copy them over, then import the newest backup in the app (Comics ⋯ → Import a Mihon backup):
    ```sh
    adb shell 'mkdir -p /sdcard/Android/data/app.kotoba.reader/files/mihon-covers && cp /sdcard/Android/data/app.mihon/files/covers/* /sdcard/Android/data/app.kotoba.reader/files/mihon-covers/'
    ```
  - Cover lookup order is: custom cover → Mihon cover → `cover.jpg` in the series folder → first page.

### Comic reader details
- **Zoom:** pinch and double-tap zoom work by widening the images (the `--cz` CSS variable) and letting the view pan. The OCR layer keeps working while zoomed, and sheets stay normal size. The WebView's own zoom is off.
- **Top bar:** Search (the same dictionary sheet as the book reader), Bookmark or use this page as the cover, Reading mode, Hide.
- **Korean compounds** not in the dictionary (장례식장에서) come back as separate words: 장례, then 식장 + 에서. See `koCompound` and `split` in `Library.java`.
- **Kanji dictionaries:** a single kanji opens its whole entry (`kanjiHead` in `app.js`). Compounds are still focused.

### Known issues / ideas
- **Korean glosses:** KRJ keeps every homograph on one page, so the gloss for 먹다 shows the first definition on that page.
- **OCR:**
  - Remaining errors are mostly stylized fonts, sound effects and similar-looking kanji (令/命). Korean often drops spaces (handled in the bubble sheet by tapping any syllable).
  - A bubble split across two webtoon images is read as two halves.
  - Tuning for real scans may be needed: box/line thresholds in `Ocr.java` and bubble grouping in `group()`.
  - The OCR cache isn't included in backup/restore.
- **Mihon covers:** new Mihon series only get their cover after the adb copy and a fresh backup import. The app can't read Mihon's cache itself.
- **Other ideas:**
  - a frequency-rank badge on entries;
  - desktop tests for the `MihonBackup` and `WordLists` parsers.
- **`legacy-preview/`:** the old Mac preview (1.6 GB of data). It isn't used by the app.

