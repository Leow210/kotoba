# Kotoba roadmap

Kotoba is built around one simple path: **look up a word → choose a meaning → save it to a folder → review it later**. It does not matter whether the word came from a dictionary search, a Japanese novel, a Korean manhwa or a Thai EPUB; it ends up in the same vocabulary database with the original sentence attached.

```
Dictionary + SRS (done)
   ↓
Book reader: EPUB / TXT                  ← phase 1 ✓
   ↓
Comic reader: CBZ / image folders, webtoon + paged   ← phase 2 ✓
   ↓
Mihon: downloads scanner, then backup (.tachibk) metadata  ← phase 3 ✓
   ↓
OCR text layer over comic pages (Korean first)    ← phase 4 ✓
   ↓
Dictionary / flashcard actions on comic text
```

## Phase 1 — Book reader (EPUB, TXT) ✓

The book reader supports Japanese, Korean, Thai and Russian. Test books are kept outside the repository.

The **Reader** tab shows book covers, progress and the last-read time. You can import several books at once with Android's file picker, then sort them by title or recency. Imported books are copied into app storage and can be removed from the shelf later.

**Parsing (Java, no dependencies)**
- `container.xml` → OPF → metadata (title, author, language, cover), manifest and spine, and `page-progression-direction`.
- Table of contents from EPUB 3 `nav.xhtml` or EPUB 2 `toc.ncx`; if neither exists, fall back to spine titles.
- **TXT**
  - Encoding detection: BOM, UTF-8 validity, then the best of Shift_JIS / EUC-KR / windows-874 / windows-1251 by decode errors.
  - Chapters split on language-aware headings: 第N章/話, 제N장/화, บทที่ N, Глава N, Chapter N, or every ~20k characters.
- Each chapter file is served from inside the ZIP at `/book/<id>/<path>`. Scripts are blocked. The reader stylesheet is injected; the book's own CSS stays, except for font sizes and colors that fight user settings.

**Reading view**
- **Horizontal or vertical (縦書き).** "Auto" follows the book's CSS and page-progression-direction; it can be overridden per book.
- **Continuous scrolling** within a chapter, flowing into the next one. Vertical text scrolls right-to-left.
- **Paged mode:** tap zones or swipe turn a screen at a time, and page direction follows the book (RTL for Japanese vertical).
- **Settings:** font size, line spacing, margins, serif/sans (or the book's font), and light/sepia/dark themes. They're per book with global defaults.
- **Reader chrome:** a tap in the middle shows the top bar (title, TOC, bookmarks, settings) and the bottom bar (chapter title, progress slider, % read).
- **Position** is saved as chapter + element path + character offset, so it survives font changes, and restored exactly on reopen.

**Language features**
- **Selection menu:** select text for Look up · Card · Highlight · Copy · Search · Share. Cards store the surrounding sentence as context and the book title as the source.
- **Tap-to-look-up (toggle):** tap a word to open a popup with the longest dictionary match from that point.
  - It uses conjugation lookup, so 먹었어요 → 먹다 and 食べさせられた → 食べる.
  - Korean and Russian use the space-delimited word; Japanese and Thai use the longest match.
- **Highlights** in four colors, with optional notes. There's a list per book, and highlights export as Markdown/text.
- **Bookmarks** with a list per book.
- **Lookup history per book** ("words I looked up in this book"), which can be saved to a folder in one go.

This phase is complete: the five test books open with the correct direction, chapters and table of contents; reading position survives restarts and font changes; lookups work in all four languages; and highlights and bookmarks persist.

## Phase 2 — Comic / manhwa reader ✓

Comics share the Reader tab (Books | Comics). The structure is series → chapters → pages.

**Sources**
- CBZ/ZIP files and image folders, picked with the file picker. They're read in place, not copied (comics are large).
- ZIP files are read directly through Android's file access with a small ZIP reader of our own, so a 200 MB CBZ never has to be copied.
- Chapters are sorted by natural number (Ch. 2 before Ch. 10).

**Reading modes**
- **Webtoon (default for Korean):** one seamless vertical strip with no gaps between images. The next chapter is appended when you reach the end, so a whole series scrolls continuously. Images load lazily and unload out of view, keeping memory flat for 100+ image chapters.
- **Paged left-to-right** and **paged right-to-left** (manga): swipe or tap zones, with an optional double-page spread in landscape.
- **Fit width / fit height / original size**, pinch zoom, and optional border cropping.

**Tracking:** progress per chapter (page or scroll offset), read/unread chapters, chapter and page bookmarks, favourites, and "continue reading" on the shelf.

## Phase 3 — Mihon integration ✓
- Choose Mihon's storage folder once and Kotoba will list downloaded series and chapters, whether they are image folders or CBZ files. They are read in place rather than copied.
- Importing a `.tachibk` backup adds titles, categories, chapter lists and read status, matched against the downloaded files. Backups themselves contain no images.
- Kotoba does not run Mihon extensions or sources. It only reads material Mihon has already downloaded.

## Phase 4 — OCR text layer ✓
- **Engine:** PaddleOCR PP-OCRv5 mobile models on ONNX Runtime (`Ocr.java`). This replaces the ML Kit plan: it needs no Play Services and works without Gradle.
  - Detection uses the DB text detector. Tall webtoon strips are processed in overlapping tiles.
  - Recognition uses the Korean model for Korean and the multilingual model for Japanese, Chinese and Latin text. Vertical columns are rotated before recognition.
  - Speed on the OnePlus CPH2749 is about 0.2–0.6 s per page. The APK is about 48 MB with the models.
- **Cache:** results are stored per chapter, page and language in `ocr_cache`, so no page is recognized twice.
- **Text layer:**
  - The floating 文 button toggles outlined boxes over the speech bubbles. They're positioned in image coordinates, so they follow webtoon and paged layouts.
  - Tapping a bubble opens its text: tap a word to look it up (with conjugation lookup), fix mistakes, copy, translate, or save a card with the bubble as context.
  - ☰ lists all text on the page.
- **Still to improve:** handling bubbles whose text is broken across separate lines, and including the OCR cache in backups. Thai recognition has since been added.

## Cross-cutting
- **Scope:** everything stays offline; this is a design constraint, not just a current limitation.
- **Personal data:**
  - Books, highlights, bookmarks, comic progress and the OCR cache live in the personal database, so they're covered by backup/restore.
  - Dictionaries stay replaceable.
- **Tests:** desktop tests extend to the EPUB/TXT parsers, the ZIP reader, chapter detection and the protobuf reader, run against the sample books.
