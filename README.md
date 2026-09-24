# Kotoba 詞

Kotoba is a personal Android dictionary and flashcard app that I built around my own MDict (`.mdx`/`.mdd`) files, including Monokakido exports. It keeps the dictionaries' original typography, images and audio, and makes it easy to save a lookup as a spaced-repetition card. It does not need an account or an internet connection.

I mainly use it for **Japanese, Korean, Chinese, Thai and Russian**. The [project page](https://leow210.github.io/kotoba/) has short videos of the main features.

### Demos
Tap a preview to play the video. Each one is silent and about 15–25 seconds long.

| Scan a game on another screen | Dictionary word lists | Kanji grid and radicals |
|---|---|---|
| [![Scan demo](docs/media/scan-poster.jpg)](docs/media/scan.mp4) | [![Word lists demo](docs/media/lists-poster.jpg)](docs/media/lists.mp4) | [![Kanji demo](docs/media/kanji-poster.jpg)](docs/media/kanji.mp4) |
| Photograph a screen, select a text box, then tap a word | Browse 専門用語 by category, with hanja shown below each word | Pick radicals by stroke count and keep the same filters when switching dictionaries |

| Flashcards |
|---|
| [![Flashcards demo](docs/media/cards-poster.jpg)](docs/media/cards.mp4) |
| Look up 言葉, keep only meaning ②, attach NHK audio, then review it |

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

| Books | E-reader (縦書き) | Comics & manhwa |
|---|---|---|
| ![Books](docs/images/library-books.png) | ![E-reader](docs/images/reader-book.png) | ![Comics](docs/images/library-comics.png) |

| Manhwa OCR | Look up from a bubble | Manga OCR | Look up from manga |
|---|---|---|---|
| ![Manhwa OCR](docs/images/comic-ocr.png) | ![Manhwa lookup](docs/images/comic-lookup.png) | ![Manga OCR](docs/images/manga-ocr.png) | ![Manga lookup](docs/images/manga-lookup.png) |

## Features

### Dictionaries
- Import a folder of `.mdx` files and their `.mdd` media from the phone. The files stay where they are; Kotoba builds its index locally. My 13-dictionary Monokakido set takes about three minutes to import and uses roughly 350 MB for the index.
- MDict versions 1 and 2 are supported, including zlib/LZO compression and encrypted key indexes. Images, audio, CSS and cross-references are read from the `.mdd` files.
- **Yomitan dictionaries** (`.zip`, e.g. from [MarvNC's collection](https://github.com/MarvNC/yomitan-dictionaries)) import natively rather than through MDX. Entries keep the dictionary's own `styles.css`, structured content and images, which are read from the ZIP in place. Frequency dictionaries (JPDB, CC100) become frequency ranks, and pitch data becomes pitch notes. A frequency dictionary can also be browsed as a ranked word list (search screen › Frequency lists, or its ⋯ menu), with jump to rank and "only words in my dictionaries"; tapping a word opens it.
  - The Yomitan format has no appendix (付録) pages, so those dictionaries have no furoku.
- Kotoba sorts dictionaries into groups, and a language's groups can have types: Japanese 国語, Kanji 漢字, and under Japanese 発音, 古語, 四字熟語, 慣用句・ことわざ, 類語, 文法, 人名・地名, 方言, 語源, 助数詞, 擬音語, 百科 and Frequency; then Korean (Hanja, Frequency), Chinese, Thai and Russian. Yomitan collection file tags such as `[JA-JA Kogo]` choose the default type.
  - Search chips: All · Japanese 国語 · Kanji 漢字 · **More 日本語 ▾** (each type, or every Japanese dictionary) · Korean…
- **Library › Dictionaries** shows the groups as collapsible sections. Each group has a switch that turns it off entirely (it is left out of search, lookups, tabs and frequency), a ⋯ menu to move, rename or switch it, and a ≡ handle on every dictionary for dragging it into place. That order decides which dictionary's entry comes first when several have the word (e.g. 大辞林, then 明鏡).
- Dictionaries can also be renamed, moved to another group or type, or marked as kanji dictionaries.
- If dictionary files are moved, importing from the new folder points the existing dictionaries at them again (same name and size), without re-importing. A dictionary that is already imported can't be imported a second time.

### Search
- Headword search uses prefixes and ignores differences that usually get in the way, such as hiragana versus katakana, Russian stress marks and Daijirin's ▽▼ marks.
- A word is found however the dictionary files it:
  - by its reading with or without separator dots (大辞林 おちあ・う is found by おちあう and 落ち合う, since the spellings in a page's heading are indexed too);
  - by alternative forms in 《》〈〉 (NHK お鉢《×御鉢》 is found by お鉢 and 御鉢);
  - honorific 御 as the kana it's read as (大辞林 おはち【御鉢】 → お鉢, ごはん【御飯】 → ご飯);
  - with some kanji written in kana (相まみえる finds 大辞林's 相▽見える, as long as it fits the reading あいまみえる).
- **One row per word.** Results from different dictionaries join into one row with a tag per dictionary, even when one writes the reading おちあう and another 落ち合う. Yomitan dictionaries that list the same entry once per spelling (明鏡 落ち合う, 落合う, あからさま/明白) show it once.
- **Homophones stay apart.** A kana search gives each written word its own row (けんのう → 権能, 献納, 賢能, 検納), with only that word's dictionaries as tabs. A dictionary page that holds several homophones (大辞林's けんのう) appears under each, and opens at the matching one.
- **類語 in search:** the **類語 ON/OFF** chip beside the search box leaves thesauruses (日本語シソーラス, 類語例解) out of regular results; they stay available under More 日本語 › 類語. With it on, a headword search also shows the シソーラス index (the word's numbered meaning groups).
- **Frequency on kana searches:** each written word gets its own rank (けんのう → 権能 25,553, 献納 78,367); the kana rank only shows for words written in kana.
- **Ambiguous Korean forms keep both readings.** 걸었다고 can be 걷다 ("walk") or 걸다 ("bet"); the popups show the first with **or 걸다** to switch. Context decides where it can: 비운 right before another word (비운 자리) is 비우다's modifier form first, while 비운의 is the noun 비운 (否運) + 의.
- **Lookups are fast** even on a whole speech bubble: a lookup stops at the longest start of the text that begins any headword, and verb stems and kana-for-kanji spellings are cached, so tapping a word takes a few milliseconds (at most ~0.1 s for Korean) on the phone.
- There are separate modes for text contained in a headword, a definition or an example. Substring matching also makes the search usable for Thai text without spaces.
- Conjugated words are traced back to their dictionary form, with a short grammar breakdown:
  - `食べさせられなかった` → **食べる** + させられる + ない + かった · causative-passive · negative · past
  - `추웠어요` → **춥다** + 았/었 + 어요 · past · polite, ㅂ-irregular
  - `사세요`, `사니까` → **사다**, or **살다** (ㄹ drops before ㅅ/ㄴ; `아세요` → 알다, `만드세요` → 만들다)
  - `먹었더라고요` → **먹다** · past + -더라고요 (polite 요 on a listed ending); `가나면서` → **가다** + -나면서 (colloquial -냐면서)
  - `대줬잖아` → **대주다** · past + -잖아 (endings listed under their own dictionary form, -잖다, are found too)
  - `공부했어요` → **공부** + 하다 + 았/었 + 어요 · 하다 verb · past · polite
  - `занимаюсь` → **заниматься** · 1st person singular · reflexive

  Kotoba only suggests forms that actually exist as headwords, and shows at most three.
- Every kanji in a query links to the installed kanji dictionaries, such as 漢辞海 and 漢検.
- You can also browse a dictionary from beginning to end:
  - scroll through every headword in both directions;
  - jump with the index rail (あかさ… / 가나다 / ก / А–Я / A–Z) or the "Jump to…" box;
  - 🎲 opens a random word.

### Entries
- Entries keep the dictionary's own CSS, images and audio. Text size is adjustable and defaults to 125%.
- When a dictionary puts many words on one page, Kotoba opens at the word you searched for instead of dropping you at the top. The other entries remain available under "On this page" or "Show whole page."
- To move around:
  - tabs switch between dictionaries that have the same word;
  - ‹ › browses to the previous or next word;
  - cross-references open the linked entry.
- Any dictionary can be switched to vertical text (縦書き), and dictionaries designed that way open vertically by default.
- Dictionaries with audio use the same 🔊 control throughout the app. Audio can optionally play as soon as an entry opens.
- Entries without audio of their own (大辞林, 明鏡…) get a 🔊 button with the NHK pronunciation dictionary's clip for the same word and reading.
- Entries show frequency bars and ranks from the frequency dictionaries in the entry's language (JPDB for Japanese, CC100 for Korean).
- Selecting text brings up Look up, Search, Copy, Card and Share. Android's selection menu also gets Look up and Save card actions, including from other apps.

### Vocabulary & flashcards
- Use ☆ for a bookmark or ＋ for a flashcard. A sub-entry can be saved separately from its parent word.
- A card can keep only the meaning you need—for example, just sense ② of 言葉—without losing the dictionary's formatting.
- Definitions and audio can come from different dictionaries. I often pair a Daijirin definition with NHK pronunciation; NHK audio also includes its pitch-accent display and conjugated forms.
- Every field is editable, with room for a note and an example sentence.
- Cards live in folders that can be filtered, sorted and reviewed separately. Multi-select actions cover moving, copying and deleting.
- Reviews use FSRS-5, with learning steps, interval previews, undo, daily new-card limits, target recall, a seven-day forecast and a streak.
- **Sentence cards:** keep the sentence where you met a word, with the sentence on the front and a translation or notes on the back.
  - Books: **Sentence** in the selection bar, or in a word's pop-up (the whole sentence around it).
  - Comics: **Save** in a speech bubble's sheet, or **Sentence** in a word's pop-up; the card gets a crop of the bubble.
  - Mac video player: **＋ Sentence** (or C) saves the subtitle line with a still of the scene; the second subtitle line (e.g. English) goes on the back, and the show, episode and time in the note. No audio is kept, so cards stay small (~15 KB with the still). Images sync with the card.
- **Known words** (can be switched off in Library › Reading): track your vocabulary size and estimate how much of a text you'd understand. Nothing is highlighted while you read.
  - A word counts as known if you tapped **✓** on it (entries, word pop-ups, the Mac player and browser-helper popups), or if its card is learned (a review interval of 3+ weeks). Marks sync between devices.
  - Vocabulary shows the count per language; tap it to see or unmark the words.
  - **% known** for a book chapter (Contents sheet), a comic episode (the page-text sheet; pages not yet OCR'd are read first) or a video episode (**✓ %** in the Mac player's transcript).
  - Each one lists the most frequent new words to look up or tick before you start. Words are counted by dictionary form (conjugations are folded; Korean uses its own analysis). It is an estimate: names and OCR slips count as unknown.
- Cards export to Anki (TSV with HTML and folder tags) or CSV, and Chinese cards export to **Pleco** (flashcard text file; each folder becomes a `//Kotoba/<folder>` category).
- **Already a card:** an entry notes when the same word is already a card saved from another dictionary (e.g. 言葉 from 大辞林 while reading 明鏡), and so does the save sheet. The word and, when both have one, the reading must match, so homophones (橋/箸) and other readings (人気 にんき/ひとけ) aren't confused. JSON backups merge with existing data and skip duplicates when restored.

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
- **写 Scan text** (search screen): photograph a game, visual novel or any screen with Kotoba's own camera (flashlight toggle; photos never go to the phone's gallery), or open or share a screenshot to Kotoba.
- The text is read on the phone with the same OCR as comics. Tap a text box to look words up, fix the text, translate it, or save a card with the line as context.
- **Select area** reads just the part you drag over, such as the dialogue box (about 0.2 s). A short first line, like a speaker's name, stays on its own line.
- **Languages:** Japanese, Chinese (simplified and traditional), Korean, Thai and Russian.
  - Tapping a word in Chinese or Thai text looks in that language's dictionaries first.
  - Traditional characters also match simplified headwords (穿過 → 穿过, using OpenCC's character table).
- Scans are kept in the app's `files/scans/` folder (the newest 40, at most 4000 px).

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
  - **PaddleOCR-VL (optional, much more accurate):** with the 1.4 GB PaddleOCR-VL 1.6 model on the phone, each speech bubble is read again by a vision-language OCR model on the GPU (LiteRT-LM). It reads manga perfectly in testing (vertical columns, furigana, small っ) and Korean dialogue far better than the mobile model. A page shows the first reading at once (boxes slightly faded) and the better text replaces it about 10–25 s later; the next two pages are read ahead. It pauses whenever you scroll, touch the page or have a sheet open, so scrolling and lookups stay smooth. Runaway answers, Korean sound effects of ≤3 letters and kana on a Korean page keep the first reading.
  - **Korean word spacing (optional):** letterers squeeze words together and the mobile model drops spaces inside a line, so a Korean spacing model (RoBERTa, ~110 MB, ~5 ms a bubble) re-spaces bubble text: 난언제쯤 평범하게 살수있지? → 난 언제쯤 평범하게 살 수 있지?. It only splits runs of 4+ syllables without a space and never removes a space, so sound effects and words already spaced right stay as they are. Works on the phone and the Mac.

## Kotoba for Mac
[`desktop/`](desktop/README.md) is a Mac companion app. It runs this app's own Java classes, so it has the same dictionaries, search and cards, plus:
- a video player (mpv) whose subtitles (SRT/VTT/ASS files or embedded tracks) you hover with Shift to look words up. The popup is compact so it doesn't cover the scene, with ⤢ to show every dictionary; words inside a definition can be looked up the same way (a second popup opens beside it); and ＋ Card saves the word with the line as its example;
- the book reader and the comic reader, with the Mihon webtoons mirrored from the phone;
- a browser helper (Tampermonkey, Firefox or Chrome) that looks up YouTube and other streaming captions in Kotoba instead of Yomitan, with the same popup as the player; a menu beside ＋ Card picks the folder, remembered across sites;
- **Korean comic text read by Apple Vision** (the macOS text recognizer) instead of the mobile model: in testing it read stylized webtoon lettering that every other recognizer got wrong (그래도 윤치영이가 전에 니가 팬 놈 깽값도 대줬잖아.);
- **translation inside Kotoba** (Settings › Translation): a local Gemma 4 26B model (best with idioms and slang — 생각보다 손이 맵네 → "You've got a heavier hand than I thought" — and for comic bubbles it reads the rest of the page as context), Google Cloud Translation with your own API key (500,000 characters a month free; the Translation LLM with a project ID), Google's free web service, or Google Translate in the browser. TranslateGemma 12B and Hy-MT2 7B were tried and translated idioms word for word;
- ⌘+ / ⌘− in the readers: book text size, and comic zoom from 25% (see more of a webtoon at once) to 400%.
- **Screen text over games and apps (⌃`, or ⌃1 / ⌃Q / ⌃⌘O in Settings):** a see-through layer over whatever is on screen (tested on Wuthering Waves) with a box around each line of text, read by Apple Vision. Hold Shift over a word to highlight it and open its entry (＋ Card with folder, ✓ known, Sentence card with a crop of the screen), or click a line for the whole line, translation and fixes. **Freeze** shows the screenshot instead of the live game; languages 日本語 / 한국어 / 中文 / English; Esc or the shortcut again closes it and hands the game its mouse back.
  - It only captures the screen (like a screenshot or screen share) and never reads or touches the game's memory, so anti-cheat has nothing to see. The capture stays in memory and is dropped on closing; nothing is saved.
  - Needs Screen Recording permission (System Settings › Privacy & Security). Builds are signed with a local "Kotoba Local Signing" certificate when it exists, so the permission survives rebuilds.
- **Write**, a small editor for writing in the language you're learning. Documents are HTML files in `~/Documents/Kotoba`, so they open in any browser with their furigana.
  - Bold, italic, underline, headings and lists; **縦** switches to vertical writing; **ルビ** (⌘R) adds furigana. The reading is suggested from your dictionaries, and okurigana stay outside it: 考(かんが)える.
  - Beside the page, **辞書** shows the selected word in your dictionaries (conjugations folded; save it as a card with its sentence).
  - **類語** is a brainstormer, following the paper 日本語シソーラス: type or select a word (conjugated works) and its index lists the numbered meaning groups it belongs to (きれい → 0021.02 美しい, 0024.03 清潔…), with where each sits in the classification. Open a group and the word you came from is marked (綺麗・奇麗). ‹ › step to the neighbouring groups, and ≪…≫ numbers jump to related ones. Click any word, then **Use** puts it in your text, or **類語 of this** searches from it. 類語例解's entries appear below.
  - **文法** asks the local Gemma 4 model to check grammar, particles, conjugation, spelling and naturalness, all on the Mac. Each suggestion shows the fix and a short explanation, with Apply, Show and Ignore. In testing it caught 面白いでした, 昨日に, 学校を行きました and 考えかた; a check takes about 5 s once the model is loaded.

| Subtitle lookup | Expanded popup |
|---|---|
| ![Hovering 영혼까지 in a Korean drama](docs/images/mac-player.jpg) | ![The popup expanded to every dictionary](docs/images/mac-player-expanded.jpg) |

| Search: one row per word | Entry with NHK audio and frequency |
|---|---|
| ![けんのう: 権能, 献納, 賢能, 検納](docs/images/mac-search.png) | ![権能 in 日本国語大辞典](docs/images/mac-entry.png) |

### Subtitles for any video
Most shows don't come with subtitles in the language being spoken. My other project, [Subtitle Generator](https://github.com/Leow210/Subtitle_Generator), transcribes a video's audio into native-language subtitles. Save the `.srt` next to the video with the same name (`Episode.srt`, or `Episode.ko.srt`) and Kotoba for Mac loads it, so every line can be looked up and turned into cards.

## Sync
**Library › Sync** keeps cards, bookmarks, folders and reviews the same on the phone and the Mac, with no account or server. Each device writes `kotoba-<device>.json` to a folder that a sync tool (Syncthing) mirrors between them, and merges the other devices' files: the newest change wins and deletions carry over. Dictionaries are matched by title, so both devices need the same dictionaries. The phone syncs every minute while open and when you leave the app.

## Build

Needs the Android SDK (build-tools 35, platform 35) and JDK 21. It doesn't use Gradle or network access.

```sh
python3 tools/fetch_android_libs.py      # optional, once: LiteRT-LM for PaddleOCR-VL (android/libs/aar/, not in git)
python3 android/build.py                 # → android/build/kotoba.apk
adb install -r android/build/kotoba.apk
android/tests/run.sh [some.mdx …]        # desktop tests: MDX reader, text extraction, FSRS, deinflection
```

On first run: **Library → Import from a folder…** → choose your dictionary folder → Import.

Korean spacing: download `onnx/model_quantized.onnx` and `vocab.txt` from [noticemkjung/korean-spacing-ONNX](https://huggingface.co/noticemkjung/korean-spacing-ONNX) into `files/models/korean-spacing/` (phone: `/sdcard/Android/data/app.kotoba.reader/files/models/korean-spacing/`; Mac: `~/Library/Application Support/Kotoba/models/korean-spacing/`).

PaddleOCR-VL: download `PaddleOCR-VL-1.6.litertlm` from [litert-community/PaddleOCR-VL-1.6](https://huggingface.co/litert-community/PaddleOCR-VL-1.6) and `adb push` it to `/sdcard/Android/data/app.kotoba.reader/files/models/` (over USB it takes ~30 s; wireless ~15 min). Without it, or without LiteRT-LM in the build, PaddleOCR's mobile models read everything as before.

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
| `…/Scans.java`, `assets/scan.js` | Scanner: in-app camera (`getUserMedia`/`ImageCapture`, saved through `scan.save`), shared screenshots, EXIF rotation, cropping |
| `…/Yomitan.java`, `assets/yomitan.css` | Yomitan format: streaming JSON reader, term/kanji/meta rows, structured content → HTML; base style for Yomitan entries |
| `…/MainActivity.java` | WebView host, local resource server, file pickers, selection menu |
| `android/assets/` | The interface (HTML/CSS/JS) |
| `docs/ROADMAP.md` | What's being built next and how |

## Current limits
- MDict files with registration-code encryption and MDict 3.0 can't be opened.
- Splitting an entry into individual meanings depends on Monokakido-style markup. Other MDX files show and save as whole entries.
- Dictionary-form lookup covers common conjugations, not every rare or classical form.

## Third-party
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT). Bundled for arm64, so OCR needs a 64-bit ARM phone.
- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) PP-OCRv5 mobile detection and recognition models: multilingual, Korean, Thai, East Slavic (Apache-2.0).
- [OpenCC](https://github.com/BYVoid/OpenCC) traditional → simplified character table, `assets/t2s.txt` (Apache-2.0).

## Roadmap
The book reader, comic reader, Mihon support and OCR are all working. [The roadmap](docs/ROADMAP.md) records how those pieces fit together and what is still unfinished.

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
  - Over USB, choose **File transfer** in the phone's USB notification, or the Mac may not see it. Wireless debugging works too (`adb connect <ip>:<port>` from the Wireless debugging screen); with both connected, set `ANDROID_SERIAL`.
  - If the app crashes at once with `ClassNotFoundException … Failed to extract 'classes.dex'`, an install was cut off. Install again.
- **Desktop tests:** `android/tests/run.sh [mdx|epub|zip…]` runs the pure-Java classes (MDict, text, FSRS, deinflection, ZIP, book parsing, Yomitan). 73 pass. Given Yomitan ZIPs, it parses and renders every row and prints timings (`YOMITAN_SAMPLES=dir` also writes a sample entry per dictionary).

### Architecture
- **UI host:** one Activity (`MainActivity`) holds a WebView. The UI is plain HTML/JS in `android/assets/`, served from `https://appassets.androidplatform.net/`.
  - Scripts load in order: `app.js` (core, search, entries, vocabulary, review, settings), `reader.js` (books), `comics.js` (comics and OCR layer, plus the shared `ocrTextSheet`), `extras.js` (word lists, furoku, Mihon menu), `scan.js` (scanner).
  - Later scripts wrap earlier functions (e.g. `renderFolders`) instead of editing them.
- **Bridge:**
  - JS calls `Kotoba.call(id, route, jsonBody)`, and Java answers through `window.__reply`. In JS this is wrapped as `api(route, body)`.
  - Platform-neutral routes (dictionaries, search, cards, books, comics, OCR, export) are `case "…"` entries in `Routes.java`, which the Mac app's `DesktopServer` also uses; `MainActivity.route()` adds the Android-only ones (pickers, SAF scans, relink, sync folder).
  - Java → JS events go through `window.__event`, handled in JS with `on(type, fn)`.
  - Direct JS interface methods: `Kotoba.pickFolder`, `pickBooks`, `pickComicFolder`, `pickComicFiles`, `pickComicCover`, `pickMihonBackup`, `pickWordList`, `copy`, `share`, `translate`, `exportFile`, `restoreBackup`, `openResource`, `setBars`.
- **Resource URLs served by `shouldInterceptRequest`:**
  - `/d/<dict>/<rec>.entry` returns entry HTML, passed through `MarkupFix.html`;
  - `/d/<dict>/<file>` returns MDD resources (CSS goes through `MarkupFix.css`);
  - `/book/<id>/<path>`;
  - `/comic/<chapter>/<page>`;
  - `/comic/cover/<series>`.
- **Yomitan storage:** `dicts.format='yomitan'`, `mdx` = the ZIP. Term rows are rendered to HTML at import (`Yomitan.senseHtml`) and stored deflated in `ytext(rec, reading, tags, body)`, with a per-dictionary preset dictionary sampled from its own entries (`ydict.zdict`). Rows with the same headword and reading share one page. `Library.recordHtml` builds the page (`yomitan.css`, the ZIP's `styles.css`, heading). Our heading is hidden (`yt-head-dup`) when the dictionary prints its own. Frequency, pitch and IPA rows go to `meta(dict, norm, reading, mode, value, display)`. Rendering changes need a re-import.
- **Search keys:** `keys(norm, dict, rec, key)` maps normalized spellings to pages. Besides each page's own key, import adds the heading's spellings (`標準表記`/`表記`, `《》〈〉` forms), separator-free readings and honorific 御 forms (`Library.extraKeys`). When these rules change, bump `Library.KEYS_VERSION` and describe the change in the comment above it: `Routes.upgradeIndexesLater()` then upgrades existing dictionaries in the background on the next start ("Updating the search index"), with no re-import. Yomitan dictionaries also merge identical entries there (`mergeSameEntries`). Search is blocked while that pass runs, so keep it cheap for MDX dictionaries when only Yomitan rules change.
- **Result grouping:** `groupResults` in `app.js` makes one row per word. For a kana query the server adds `words` (the page's spellings: its key, or each 【…】 heading on a page filed under its reading) so homophones get separate rows. `Library.mixedSpellings` handles queries with some kanji written in kana.
- **Groups:** `dicts.grp` is `Language` or `Language/Type` (`Japanese/古語`). Search accepts `g:Japanese` (that group only) or `g:Japanese/*` (with its types). Pronunciation dictionaries are `Japanese/発音`; check with `Library.isPronunciation`. Group order lives in localStorage `groupOrder`, and dragging saves the whole display order as `dicts.position`.
- **Databases:**
  - The library DB (dictionary index) has the tables `dicts`, `records`, `keys`, `anchors`, `resources` and `kanji`, plus per-dictionary FTS4 tables `body_<id>`. `dict_ids` keeps IDs stable across re-imports.
  - The personal DB (`Store`) holds folders (`study` = included in review), items/cards, FSRS state, history and settings. Books, comics (`series`, `chapters`, `comic_marks`) and `ocr_cache` also live in it.
- **Entry focus (`app.js` `findFocus`/`unitInfo`):** the `UNIT`/`HEAD`/`WORD`/`READING` name sets decide which part of a dictionary page is "the word". When a new dictionary focuses the wrong unit, add its tag or class names there. Kanjikai's `親字TD` / `親字-常用…` were added for exactly this.
- **Deinflection:** lives in `Deinflect.java` (JA/KO/RU rules). The Korean analysis order is in `Library.koreanAnalyses`: whole word → rules → stem + dictionary-listed ending → noun + particles → compound → prefix. Keep results narrow: the real word first, at most three. Noun-only particles (의, 을/를, 에…) never count as verb endings; a modifier form before another word puts the verb first (`Library.lookup`).
- **Lookup speed:** `Library.keyReach` finds how many characters of the text begin some key (a LIMIT 1 range query per length); exact matches are only tried up to there, conjugations up to 10 characters past it, and kana-for-kanji spellings (`mixedSpellings`, only for the looked-up text, not internal checks) up to 8. Korean verb stems per initial consonant (`verbsByInitial`) and spelling candidates per leading kanji with their compiled patterns and readings (`mixedCandidates`) are cached in memory and cleared by `dictionariesChanged()`.
- **OCR:** `Ocr.java` runs the PaddleOCR PP-OCRv5 mobile models on ONNX Runtime 1.30.0 (arm64 only; the jar and `.so` files are in `android/libs/onnxruntime/`).
  - Model files in `assets/ocr/`:
    - `det.onnx`: detection;
    - `rec-ko.onnx` + `dict-ko.txt`: Korean recognition;
    - `rec-ja.onnx` + `dict-ja.txt`: multilingual CJK and Latin recognition (Japanese, and Chinese simplified/traditional);
    - `rec-th.onnx` + `dict-th.txt`: Thai;
    - `rec-ru.onnx` + `dict-ru.txt`: East Slavic (Russian, Ukrainian, Belarusian) and Latin.
  - They were downloaded from `huggingface.co/PaddlePaddle/{PP-OCRv5_mobile_det_onnx, korean_PP-OCRv5_mobile_rec_onnx, PP-OCRv5_mobile_rec_onnx, th_PP-OCRv5_mobile_rec_onnx, eslav_PP-OCRv5_mobile_rec_onnx}`.
  - Only one recognizer is loaded at a time, and ONNX Runtime's memory arena is off, so memory goes back after each page. The dictionaries are the `character_dict` lists in each model's `inference.yml`.
  - Routes: `ocr.page {chapter, page, lang, refresh}` and `ocr.clear`. They run on their own low-priority thread (`MainActivity.ocrPool`), so a page being read never holds up taps or other requests.
  - **Other recognizers** plug into `Ocr`: a `LineReader` replaces detection and recognition (the Mac's Apple Vision helper, `desktop/mac/Sources/KotobaOCR`, for Korean; its boxes are grouped as they are, then padded), and a `BlockReader` reads each grouped bubble again (the phone's PaddleOCR-VL, `android/src-extra/…/VlOcr.java`, only compiled when `android/libs/aar/` has LiteRT-LM). Cached results carry `v` = which recognizer made them, so switching re-reads pages.
  - **PaddleOCR-VL refinement:** `ocr.page` returns PaddleOCR's text at once with `refining:true`; `Ocr.refine` re-reads each bubble on a background thread (the requested page first, then the next two) and sends `ocr-refined`, and `comics.js` redraws that page's layer. Each bubble is padded onto a white square (the model resizes input to 560×560, which distorted tall or wide bubbles), one image per conversation, greedy, max 160 tokens. `Ocr.touch()` (from `Kotoba.interacting()`, sent on any touch/scroll and while a sheet or entry is open) makes it wait until the app has been still for 2.5 s: each bubble takes the GPU for ~2.5 s and would otherwise stall scrolling. The GPU needs `uses-native-library libOpenCL.so` in the manifest.
  - **Korean spacing:** `Spacing.java` (ONNX Runtime, shared with the Mac) tags each character without spaces (UNK, PAD, O, B, I, E, S; a space after E and S) and `Ocr.respace` applies it to Korean bubbles when a page is recognized, refined or first loaded after the model was added. Bubbles keep the recognizer's own text in `raw`, and pages record `spaced` = `Ocr.SPACING`, so a rule change re-spaces from `raw`.
  - The comic layer's boxes live inside the scrolling page, so they are only re-placed on zoom, resize or image load, never on scroll (re-measuring every page on each scroll event made scrolling stutter).
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
- **Dictionary fonts:** fonts in `extras/<name>/files/fonts/*.ttf|otf|woff2` become `@font-face` rules (family = file name) on that dictionary's entry pages and cards (`Extras.fontFaces`). 朝鮮語辞典 writes 315 hanja (Korean forms such as 鄕 稱, rare hanja; 14,488 headings) as private-use characters in `<Gaiji>` drawn by `CHOUSENGO_Symbol.ttf` from the Monokakido app's `Contents/CHOUSENGO-KJ/fonts/`; copy it to `extras/korean-krj/files/fonts/` (and `korean-jkr`) on both devices, or those characters show as unknown boxes.
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
- **Dictionaries** were imported from `/sdcard/Download/Monokakido_Ciyue` (13 MDX files) and read from there. The exception is 漢辞海, whose rebuilt MDX (see below) is in the app's `…/files/dictionaries/Kanjikai2/`, because the copy in Download is the older conversion.
- **Yomitan dictionaries** are in `/sdcard/Download/Yomitan` (23 ZIPs, 977 MB). They were downloaded on the Mac into `../yomitan-dicts/` from MarvNC's Google Drive folders (main collection and Salwynn's), Kuuube's JPDB v2.1, Lyroxide's STDICT/KRDICT JA/CC100, jarjumarvin's hanja and Kanjipedia 同訓異義 (MediaFire).
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
  - With the mobile models, remaining errors are mostly stylized fonts, sound effects and similar-looking kanji (令/命), and Korean often drops spaces (handled in the bubble sheet by tapping any syllable). PaddleOCR-VL (phone) and Apple Vision (Mac) fix most of this; Google ML Kit was tried on the phone and was no better on comic lettering.
  - A bubble split across two webtoon images is read as two halves.
  - Tuning for real scans may be needed: box/line thresholds in `Ocr.java` and bubble grouping in `group()`.
  - The OCR cache isn't included in backup/restore.
- **Mihon covers:** new Mihon series only get their cover after the adb copy and a fresh backup import. The app can't read Mihon's cache itself.
- **Other ideas:**
  - frequency in the reader/comic lookup popups (entries and search results have it);
  - desktop tests for the `MihonBackup` and `WordLists` parsers.
- **`legacy-preview/`:** the old Mac preview (1.6 GB of data). It isn't used by the app.
