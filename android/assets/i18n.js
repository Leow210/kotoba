// Interface language: English (the text as written in the code) or 日本語. Every visible string — text, placeholders,
// titles, toasts — is translated on the page as it appears, so the rest of the code stays in English.
(function(){
  const JA={
    'Cancel':'キャンセル','All':'すべて','No matches':'一致なし','Dictionary form':'辞書形','KANJI':'漢字',
    'Add your dictionaries':'辞書を追加','Browse a dictionary':'辞書を見る','Frequency lists':'頻度リスト','Recent':'最近','Clear':'消去',
    'Most common first':'頻度順','Only words in my dictionaries':'手持ちの辞書にある語のみ','Kanji grid':'漢字一覧',
    '— start of dictionary —':'— 辞書の先頭 —','— end of dictionary —':'— 辞書の末尾 —','from':'出典','部首 Radical':'部首',
    'No kanji match these filters.':'この条件に合う漢字はありません。','Reading the text…':'テキストを読み取り中…','No text here yet.':'まだテキストがありません。',
    'of the words here are known':'この中の既知語','Most frequent new words':'よく出る新出語','Tap a word to look it up, ✓ if you already know it.':'語をタップで検索、知っていれば ✓。',
    'Remove image':'画像を削除','Reading':'読み','Definition from':'語義の出典','Keep these parts':'残す部分','Use dictionary text':'辞書のテキストを使う',
    'You edited the text, so the card shows your wording instead of the dictionary layout.':'テキストを編集したため、カードは辞書のレイアウトではなく編集後の文言を表示します。',
    'Example / context':'例文・文脈','Note':'メモ','Pronunciation audio':'発音音声','Folder':'フォルダ','Already a card':'すでにカードあり',
    'Tap ▶ to listen, tap a clip to attach or remove it.':'▶ で再生、クリップをタップで添付／解除。','Known words':'既知語','All saved words':'保存した語すべて',
    'Switch on the folders you want to study — they become your review decks.':'学習したいフォルダをオンにすると復習デッキになります。',
    'Suspended':'保留中','New':'新規','Due':'期限','Learning':'学習中','Nothing here yet':'まだ何もありません',
    'Tap ☆ or ＋ on any dictionary entry to keep it here.':'辞書の項目の ☆ か ＋ をタップするとここに保存されます。',
    'Own limit':'個別の上限','New cards per day':'1日の新規カード数','To review':'復習待ち',
    'Save words with ☆ on any dictionary entry — each folder becomes a deck.':'辞書の項目の ☆ で語を保存 — 各フォルダがデッキになります。',
    'Decks':'デッキ','tick the ones you’re studying':'学習中のものにチェック','Study':'学習','Next 7 days':'今後7日間','cards':'枚','saved items':'保存項目',
    'Target recall':'目標記憶率','Higher means more frequent reviews':'高いほど復習が増えます','Done':'完了','Show answer':'答えを見る',
    'Looking for dictionaries…':'辞書を探しています…','Every dictionary in this folder is already in your library.':'このフォルダの辞書はすべて取り込み済みです。',
    'Definition & example search':'語義・例文検索','Builds a full-text index. Takes longer and uses more space.':'全文インデックスを作成します。時間と容量が増えます。',
    'Import':'取り込み','You can keep using dictionaries that are already imported.':'取り込み済みの辞書は引き続き使えます。','Dictionaries':'辞書','Drag':'ドラッグ',
    'No dictionaries yet.':'辞書がまだありません。','Scan the app’s own folder':'アプリのフォルダを検索','Entry text size':'項目の文字サイズ',
    'Also adjustable from any entry’s ⋯ menu':'各項目の ⋯ メニューからも調整できます','Show entries in vertical writing':'項目を縦書きで表示',
    'Theme':'テーマ','Track known words':'既知語を記録','Recent searches':'検索履歴','Show your recent searches on the Dictionary home screen':'辞書ホームに最近の検索を表示',
    'Show reading on card front':'カード表面に読みを表示','Otherwise the reading appears with the answer':'オフの場合、読みは答えと一緒に表示されます',
    'Audio':'音声','Auto-play in entries':'項目で自動再生','Play the first pronunciation when an entry opens':'項目を開くと最初の発音を再生',
    'Auto-play in review':'復習で自動再生','When a card’s attached audio plays by itself':'カードの音声が自動で再生されるタイミング',
    'Play button on the question side':'問題面に再生ボタン','Your data':'データ','Back up vocabulary':'単語帳をバックアップ','Save…':'保存…',
    'Restore backup':'バックアップから復元','Open…':'開く…','Export everything for Anki':'Anki 用にすべて書き出し','Tab-separated, with dictionary formatting':'タブ区切り・辞書の書式付き',
    'Export…':'書き出し…','Export as spreadsheet':'表計算として書き出し','CSV for Excel, Sheets or Numbers':'Excel・Sheets・Numbers 用 CSV',
    'Export Chinese cards for Pleco':'Pleco 用に中国語カードを書き出し','Sync with your other devices':'他のデバイスと同期','Sync folder':'同期フォルダ',
    'Loading…':'読み込み中…','Import in progress…':'取り込み中…','Sync':'同期','Sync now':'今すぐ同期','Close':'閉じる','Lyrics':'歌詞','Listening':'聴解',
    'Thai script':'タイ文字','Scan text':'テキストをスキャン','Screen text':'画面のテキスト','Word lists':'単語リスト','Random word':'ランダムな語',
    'heiban':'平板','Play pronunciation':'発音を再生','Can’t open web links here':'ここではウェブリンクを開けません','Back':'戻る','Known word':'既知語',
    'Save to a folder':'フォルダに保存','More':'その他','Dictionary entry':'辞書項目','Could not display this entry':'この項目を表示できません',
    'That reference isn’t in this dictionary':'この参照はこの辞書にありません','Larger text':'文字を大きく','Smaller text':'文字を小さく',
    'Other entries on this page…':'このページの他の項目…','Copy word':'語をコピー','Copied':'コピーしました','Copy entry text':'項目のテキストをコピー',
    'Dictionary not found':'辞書が見つかりません','Jump to rank, e.g. 5000':'順位へ移動（例: 5000）','Not in your dictionaries':'手持ちの辞書にありません',
    'Jump to…':'移動…','No kanji dictionary imported':'漢字辞書が未取り込みです','No sentence here':'ここに文はありません','Known':'既知','Filter':'絞り込み',
    'Unmark':'解除','Highlights work inside books':'ハイライトは本の中で使えます','Look up':'調べる','Read the sentence in Pleco':'Pleco で文を読む',
    'Optional':'任意','Optional sentence':'文（任意）','Play':'再生','Removed':'削除しました','Add the word first':'先に語を追加してください',
    'Add something for the back of the card':'カードの裏面に何か追加してください','Study this folder':'このフォルダを学習','List or grid':'リスト／グリッド',
    'Review this folder':'このフォルダを復習','Select items':'項目を選択','Rename folder':'フォルダ名を変更','Delete folder':'フォルダを削除',
    'Delete folder, move words to Inbox':'フォルダを削除し、語を受信箱へ移動','Delete folder and its words':'フォルダと語を削除','Folder deleted':'フォルダを削除しました',
    'Select some items first':'先に項目を選んでください','Deleted':'削除しました','Open in dictionary':'辞書で開く','Edit':'編集',
    'The source dictionary isn’t installed any more':'元の辞書はもうインストールされていません','Move to folder…':'フォルダへ移動…',
    'Reset review progress':'復習の進捗をリセット','Reset':'リセット','Copy':'コピー','Delete':'削除','Undo':'元に戻す','Remove from review':'復習から外す',
    'Undone':'元に戻しました','Edit card':'カードを編集','Source dictionary not installed':'元の辞書が未インストール','Search index updated':'検索インデックスを更新しました',
    'Import dictionaries':'辞書を取り込む','Choose at least one dictionary':'辞書を1つ以上選んでください','Cancelling…':'キャンセル中…','Drag to reorder':'ドラッグで並べ替え',
    'Group options':'グループの設定','Move group up':'グループを上へ','Move group down':'グループを下へ','Turn all on':'すべてオン','Turn all off':'すべてオフ',
    'Rename group':'グループ名を変更','New top-level group…':'新しいトップグループ…','Rename':'名前を変更','Browse by rank':'順位で見る','Appendix / 付録':'付録',
    'Sort all by group and size':'グループと大きさで並べ替え','Remove from library':'ライブラリから削除','Books':'本','Comics & manhwa':'マンガ・マンファ',
    'Add comics':'マンガを追加','＋ Add comics':'＋ マンガを追加','Chapters':'章','Hide controls ⌄':'操作を隠す ⌄','Reading mode':'読書モード',
    'Webtoon scrolls every chapter as one strip':'ウェブトゥーンは各章を1本の縦スクロールで表示','Fit':'フィット','For page modes':'ページモード用',
    'Chapter titles between chapters':'章の間に章題を表示','In webtoon mode':'ウェブトゥーンモード','Small gap between images':'画像の間に小さな隙間',
    'Page turn animation':'ページめくりアニメーション','Use for all series':'すべてのシリーズに使う','Tap a word to look it up.':'語をタップして調べます。',
    'Cover updated':'表紙を更新しました','Scanning folder…':'フォルダを検索中…','Choose CBZ / ZIP files':'CBZ / ZIP ファイルを選択',
    'Scan the app’s own comics folder':'アプリのマンガフォルダを検索','Change cover image…':'表紙画像を変更…','Use automatic cover':'自動の表紙を使う','Cover reset':'表紙をリセットしました',
    'Mark all as read':'すべて既読にする','Mark all as unread':'すべて未読にする','Page bookmarks':'ページのしおり','No bookmarks yet':'しおりはまだありません',
    'Mark this and earlier as read':'これ以前を既読にする','Search dictionary':'辞書を検索','Bookmark page':'ページにしおり','Hide controls':'操作を隠す','Page':'ページ',
    'All text on this page':'このページのテキストすべて','Text layer':'テキストレイヤー','Last chapter':'最終章','Text layer on · tap a speech bubble to look it up':'テキストレイヤー オン ・ 吹き出しをタップで検索',
    'Reading text…':'テキストを読み取り中…','No text found on this page':'このページにテキストがありません','Speech bubble':'吹き出し','First chapter':'最初の章',
    'Bookmark this page':'このページにしおり','Use this page as the series cover':'このページをシリーズの表紙にする','Page bookmarked':'しおりを付けました','Saved as default':'既定として保存しました',
    '＋ Import':'＋ 取り込み','From your dictionaries':'手持ちの辞書から','No words':'語がありません','Sort':'並べ替え','Find in this list':'このリスト内を検索',
    'Adding words… this can take a minute for long lists':'語を追加中… 長いリストは1分ほどかかります','Delete word list':'単語リストを削除','Make flashcards':'フラッシュカードを作る',
    'Entry not found':'項目が見つかりません','Appendix':'付録','Link target not found':'リンク先が見つかりません','hear it, repeat it, shadow it':'聞く・繰り返す・シャドーイング',
    'No one by that name.':'その名前の人はいません。','Character stories':'キャラクターストーリー','Hear each':'各行の再生回数','Speed':'速度','Pauses':'ポーズ',
    '說 Time to say it':'說 話す時間','懂 Time to understand':'懂 理解する時間','Pause to repeat':'復唱のポーズ','Display':'表示','Text':'テキスト','Playback':'再生',
    'At the end':'最後に','Pause on lookup':'検索中は一時停止','Word gloss':'語の注釈','Pitch accent':'ピッチアクセント','Play every character in turn':'全キャラクターを順に再生',
    'Play everything shuffled':'すべてシャッフル再生','Play everything, shuffled':'すべてシャッフル再生','Find a character':'キャラクターを探す','Play these characters in turn':'これらのキャラクターを順に再生',
    'Pitch accent over each word, from the NHK accent dictionary':'NHK アクセント辞典による各語のピッチアクセント','Keep the whole line as a sentence card':'この行全体を文カードとして保存',
    'Previous line':'前の行','Play or pause':'再生／一時停止','Next line':'次の行','Loop':'ループ','That was the last line':'最後の行でした','Nothing playing':'再生中なし',
    'Earlier':'前へ','Later':'後へ','Play a song in Spotify, YouTube Music, NetEase… and its lyrics appear here.':'Spotify・YouTube Music・NetEase などで曲を再生すると、ここに歌詞が表示されます。',
    'Open Notification access':'通知へのアクセスを開く','Looking for lyrics…':'歌詞を探しています…','Search lyrics…':'歌詞を検索…','No lyrics found for this song.':'この曲の歌詞は見つかりませんでした。',
    'Searching…':'検索中…','Nothing found. Try the title in its original script, or fewer words.':'見つかりません。原語の題名か、より少ない語で試してください。','Find lyrics':'歌詞を探す',
    'Pause the music while a word is looked up':'語の検索中は音楽を一時停止','Show translations under the lines':'各行の下に翻訳を表示','Keep the current line in view':'現在の行を表示し続ける',
    'Play a song first':'先に曲を再生してください','Freeze':'フリーズ','All text':'すべてのテキスト','Rescan':'再スキャン','Show the screenshot instead of the live game':'ライブ画面の代わりにスクリーンショットを表示',
    'Take a new screenshot':'新しいスクリーンショットを撮る','Add a book':'本を追加','＋ Add books':'＋ 本を追加','Continue reading':'続きを読む','All books':'すべての本','Save':'保存','Contents':'目次',
    'No bookmarks yet. Tap 🔖 while reading.':'しおりはまだありません。読書中に 🔖 をタップ。','No highlights yet. Select text and tap Highlight.':'ハイライトはまだありません。テキストを選んで「ハイライト」をタップ。',
    'Slide and tilt when turning pages':'ページめくりでスライドと傾き','Page separators':'ページ区切り','In scroll mode, mark each screenful like a printed page':'スクロールモードで、1画面ごとに印刷ページのような区切りを表示',
    'Tap a word to look it up':'語をタップして調べる','Use these settings for all books':'この設定をすべての本に使う','Open':'開く','Export highlights':'ハイライトを書き出し','Book':'本',
    'Search the dictionary':'辞書を検索','Bookmark':'しおり','Display settings':'表示設定','Previous chapter':'前の章','Position in chapter':'章内の位置','Next chapter':'次の章',
    'Couldn’t open this chapter':'この章を開けませんでした','End of book':'本の終わり','Web links are disabled in offline mode':'オフラインモードではウェブリンクは無効です',
    'Link target isn’t in this book':'リンク先はこの本にありません','No dictionary entry here':'ここに辞書項目はありません','Couldn’t highlight this selection':'この選択範囲はハイライトできません',
    'Highlighted':'ハイライトしました','Highlight':'ハイライト','Bookmark removed':'しおりを削除しました','Bookmarked':'しおりを付けました','Look up a word':'語を調べる','Dictionary':'辞書',
    'Starting the camera…':'カメラを起動中…','Take a photo of the text':'テキストを撮影','Take photo':'撮影','Flashlight':'ライト','No text found yet':'テキストはまだ見つかりません',
    'Delete this scan':'このスキャンを削除','Scanned text':'スキャンしたテキスト','Last 30 days':'過去30日','Stats ›':'統計 ›','reviews':'復習','/30 days':'/30日','-day streak':'日連続',
    '/day':'/日','Statistics':'統計','All decks':'すべてのデッキ','Less':'少なく','Young: in review, stable for less than 21 days. Mature: 21 days or more.':'若い: 復習中で定着が21日未満。成熟: 21日以上。',
    'Reviews by hour · bar: count, number: answered correctly':'時間帯別の復習 · 棒: 回数、数字: 正解数','Days between reviews, now':'現在の復習間隔（日）','letters · vowels · tones':'子音 · 母音 · 声調',
    'Again':'もう一度','Next':'次へ','Trace guide':'なぞりガイド','Got it':'できた','Kotoba':'Kotoba','Headword':'見出し語','Contains':'含む','In definitions':'語義内','In examples':'例文内',
    'Show more':'もっと見る','Reader':'リーダー','Books you’re reading':'読書中の本','＋ Add':'＋ 追加','Vocabulary':'単語帳','Folders of words you’ve kept':'保存した語のフォルダ','Review':'復習',
    'Spaced repetition · FSRS':'間隔反復 · FSRS','Library':'ライブラリ','Dictionaries and settings':'辞書と設定','Search your dictionaries':'辞書を検索',
    'Show or hide thesaurus dictionaries in regular search':'通常の検索で類語辞書を表示／非表示','Home':'ホーム','Search':'検索','Settings':'設定','Language':'言語','Interface language':'表示言語',
    'Light':'ライト','Sepia':'セピア','Dark':'ダーク','Inbox':'受信箱','Statistics ›':'統計 ›','Reading':'読み','Definition':'語義','On':'オン','Off':'オフ','Show':'表示','Hide':'非表示',
    'show':'表示','hide':'非表示','after hearing':'聞いた後','next one':'次へ','again':'もう一度','stop':'停止','start over':'最初から','on':'オン','off':'オフ','none':'なし','brief':'短','short':'中','long':'長',
    'Passive':'聴く','Produce':'話す','Understand':'理解','Translation':'翻訳','English':'英語','Text size':'文字サイズ','Add':'追加','Remove':'削除','Move':'移動','Yes':'はい','No':'いいえ','OK':'OK',
    'Command':'コマンド','Control':'コントロール','No key':'キーなし','Option':'オプション','Shift':'シフト',
    'Add':'追加','Bigger / smaller popup':'ポップアップの拡大／縮小','Counting words…':'語を数えています…','Earlier by 0.1 s — Z':'0.1秒早く — Z','Folder for new cards':'新しいカードのフォルダ',
    'Mark as known':'既知にする','New folder name':'新しいフォルダ名','None':'なし','OCR hardcoded subtitles · bottom of video':'焼き込み字幕を OCR · 動画の下部','Open in Kotoba':'Kotoba で開く',
    'Smaller':'小さく','of this episode’s words are known':'このエピソードの既知語','＋ Card':'＋ カード','＋ New folder…':'＋ 新しいフォルダ…','Auto language':'言語を自動判定',
    'Pause the video while a word is shown':'語の表示中は動画を一時停止','Subtitle language':'字幕の言語','⏸ on lookup':'⏸ 検索時に停止','🎙 Live subs':'🎙 ライブ字幕',
    'All documents':'すべての書類','Apply':'適用','Bigger text':'文字を大きく','Body text':'本文','Check selection':'選択範囲をチェック','Check the document':'書類をチェック','Empty':'空',
    'Heading':'見出し','Ignore':'無視','List':'リスト','Looking…':'検索中…','Not in your thesaurus. Try the dictionary form or a simpler word.':'類語辞典にありません。辞書形か、より簡単な語を試してください。',
    'Nothing to check yet':'チェックする内容がまだありません','Select a word in the text first':'先にテキスト内の語を選択してください','Select a word in your text, or type one here.':'テキスト内の語を選ぶか、ここに入力してください。',
    'Select one word at a time for furigana':'ふりがなは1語ずつ選択してください','Select some text first':'先にテキストを選択してください','Show in Finder':'Finder で表示','That text has changed':'そのテキストは変更されました',
    'Use':'使う','Write something':'何か書きましょう','to put it in your text.':'でテキストに挿入します。','類語 of this':'この語の類語','＋ New document':'＋ 新しい書類',
    'Browser subtitle helper':'ブラウザ字幕ヘルパー','Click a book word to look it up':'本の語をクリックして調べる','Google Cloud':'Google Cloud',
    'Hold the key over a book word or subtitle to open its dictionary. “No key” opens on hover.':'本の語や字幕の上でキーを押し続けると辞書が開きます。「キーなし」ではホバーで開きます。',
    'Hover lookup key':'ホバー検索キー','Languages':'言語','OCR hardcoded subtitles':'焼き込み字幕を OCR','Off by default. You can still click and drag to select text.':'既定ではオフです。クリック＆ドラッグでテキストを選択できます。',
    'Reading, audio, data and sync':'読書・音声・データ・同期','Saved':'保存しました','Screen text shortcut':'画面テキストのショートカット','Test':'テスト','Translate with':'翻訳に使う','Video':'動画',
    'Watch with subtitles':'字幕付きで視聴','from the 字幕 menu in the player.':'プレーヤーの 字幕 メニューから。','subtitles':'字幕','＋ Open video…':'＋ 動画を開く…','Audio track':'音声トラック',
    'How much of this episode you’d know':'このエピソードの既知語の割合','Kotoba Player':'Kotoba プレーヤー','Position':'位置','Subtitles':'字幕','Transcript':'書き起こし','Volume':'音量',
    '⏸︎ each line':'⏸︎ 各行で停止','＋ Sentence':'＋ 文','Dictionary':'辞書',
    'for the song that’s playing':'再生中の曲の歌詞','hear, repeat, shadow':'聞く・繰り返す・シャドーイング','letters, vowels, tones':'子音・母音・声調','photo or screenshot':'写真またはスクリーンショット',
    'over games and apps':'ゲームやアプリの上で','by radical and strokes':'部首と画数から','and your saved words':'保存した語も','from your dictionaries':'手持ちの辞書から','Browse':'見る',
    'Import MDX dictionaries from your phone — for example the':'スマホから MDX 辞書を取り込みます — 例:','folder in Downloads.':'（ダウンロード内のフォルダ）','Monokakido_Ciyue':'Monokakido_Ciyue',
    'An estimate: words are counted by their dictionary form, and names or OCR slips count as unknown.':'推定値です。語は辞書形で数え、固有名詞や OCR の誤りは未知語として数えます。',
    'Everything in a folder is part of that folder’s deck. Choose which decks you study in Review.':'フォルダの中身はすべてそのフォルダのデッキになります。学習するデッキは「復習」で選びます。',
    'folder':'フォルダ','Saved to':'保存先:','Moved to':'移動先:','For dictionaries copied over USB into':'USB でコピーした辞書は次の場所へ:','Kotoba 0.3 · works fully offline · nothing leaves your phone':'Kotoba 0.3 · 完全オフラインで動作 · データはスマホの外に出ません',
    'Pick your Mihon downloads folder, a series folder, or CBZ files. Nothing is copied — Kotoba reads them where they are.':'Mihon のダウンロードフォルダ、シリーズのフォルダ、または CBZ ファイルを選んでください。コピーはされず、Kotoba がその場所から読み込みます。',
    'after':'後','No listening sets on this device yet. Sets are folders in Kotoba’s':'この端末にはまだ聴解セットがありません。セットは Kotoba の次のフォルダに入れます:','listening':'listening',
    'EPUB and TXT books in Japanese, Korean, Thai and Russian. Select any word to look it up and save it as a card.':'日本語・韓国語・タイ語・ロシア語の EPUB／TXT の本。語を選ぶと調べられ、カードとして保存できます。',
    'Tip: fill the frame with the text box and hold the phone straight to the screen. Dimming the room lights cuts glare.':'ヒント: 枠いっぱいにテキストを写し、スマホを画面にまっすぐ向けてください。部屋の照明を暗くすると反射が減ります。',
    'Choose dictionary folder':'辞書フォルダを選ぶ','Import from a folder…':'フォルダから取り込む…','Add your own word':'自分の語を追加','New folder':'新しいフォルダ','Copy to':'コピー先','Suspend':'保留',
    'Search definitions':'語義を検索','Copy all':'すべてコピー','Translate all':'すべて翻訳','Translate':'翻訳','Fix':'修正','Photo':'写真','Image':'画像','Select area':'範囲を選択','Listen':'聞く','Hear':'聞く',
    'Play all':'すべて再生','Shuffle':'シャッフル','Story':'ストーリー','Sentence':'文','✓ Read':'✓ 既読','Words in this episode · % known':'このエピソードの語 · 既知率','Words in this chapter · % known':'この章の語 · 既知率',
    'New — not reviewed yet.':'新規 — まだ復習していません。','No other device yet':'他の端末はまだありません','Correct: anything but Again.':'正解: 「もう一度」以外すべて。',
    'due':'期限','Import .lrc':'.lrc を取り込む','Save for offline':'オフライン用に保存','Save lyrics for offline':'歌詞をオフライン用に保存','Starting…':'開始中…','Not available here':'ここでは使えません',
    'Use a .lrc file (or several) as lyrics, offline':'.lrc ファイル（複数可）を歌詞として使います（オフライン）','Fetch lyrics for a list of songs now, to read them offline':'曲のリストの歌詞を今のうちに取得し、オフラインで読めるようにします',
    'One song per line, as':'1行に1曲、形式は','(or just the title). Needs internet now; afterwards their lyrics show offline. Songs already saved are skipped.':'（または曲名だけ）。今はインターネットが必要ですが、その後は歌詞がオフラインで表示されます。保存済みの曲はスキップされます。',
    'YouTube Music playlist link':'YouTube Music のプレイリストリンク','Load playlist':'プレイリストを読み込む','Reading the playlist…':'プレイリストを読み込み中…',
    'Learn':'学ぶ','Quiz':'クイズ','Write':'書く','Clear':'消去','Nothing found':'見つかりません','Copy entry':'項目をコピー','Share':'共有','Retry':'再試行'
  };
  // Text with numbers or names in it.
  const UNIT={m:'分',h:'時間',d:'日',mo:'か月',y:'年'};
  const PAT=[
    [/^(\d+) songs · public or unlisted playlists only$/,'$1 曲 · 公開または限定公開のプレイリストのみ'],
    [/^Imported lyrics for (\d+) songs?(?: · (\d+) failed)?$/,(_,n,f)=>n+' 曲の歌詞を取り込みました'+(f?' · '+f+' 件失敗':'')],[/^Saved (\d+) of (\d+) songs$/,'$2 曲中 $1 曲を保存しました'],
    [/^(\d+) \/ (\d+) · (\d+) saved(.*)$/,(_,a,b,c,r)=>a+' / '+b+' · '+c+' 曲保存'+r.replace(/ · (\d+) already saved/,' · 保存済み $1 曲').replace(' · not found: ',' · 見つからず: ')],
    [/^in (\d[\d.]*)(mo|m|h|d|y)$/,(_,n,u)=>n+UNIT[u]+'後'],[/^Next card in (\d[\d.]*)(mo|m|h|d|y)\.$/,(_,n,u)=>'次のカード: '+n+UNIT[u]+'後。'],[/^Next card due\.$/,'次のカードは期限です。'],
    [/^Next review in (\d[\d.]*)(mo|m|h|d|y)(.*)$/,(_,n,u,r)=>'次の復習: '+n+UNIT[u]+'後'+r.replace(/(\d+) reviews?/,'復習 $1 回')],
    [/^Show whole page · (\d+) more (?:entry|entries)$/,'ページ全体を表示 · あと $1 件'],[/^On this page · (\d+)$/,'このページ · $1'],[/^Show only “(.+)”$/,'「$1」のみ表示'],
    [/^([\d,]+) pages · browse$/,'$1 ページ · 閲覧'],[/^(\d+) radicals · by stroke count$/,'部首 $1 個 · 画数順'],[/^No headword starts with “(.+)”\.$/,'「$1」で始まる見出し語はありません。'],
    [/^(\d+) entries$/,'$1 件'],[/^([\d,]+) marked known · ([\d,]+) learned cards$/,'既知 $1 語 · 学習済みカード $2 枚'],[/^(\d+) items$/,'$1 件'],
    [/^([\d,]+) of ([\d,]+) words · ([\d,]+) different, (.+)$/,'$2 語中 $1 語が既知 · 異なり $3 語、$4'],
    [/^(\d+) words$/,'$1 語'],[/^(\d+) word$/,'$1 語'],[/^(\d+) new$/,'新規 $1'],[/^(\d+) due$/,'期限 $1'],[/^(\d+) new waiting in this deck$/,'このデッキで新規 $1 枚が待機中'],
    [/^Off: the default, (\d+) a day$/,'オフ: 既定の 1日 $1 枚'],[/^For each deck without its own limit · (\d+) new waiting$/,'個別の上限がない各デッキ用 · 新規 $1 枚が待機中'],
    [/^Next card (.+)\.$/,'次のカード $1。'],[/^Next review (.+)$/,'次の復習 $1'],[/^(\d+) reviews?$/,'復習 $1 回'],[/^You reviewed (\d+) cards?\.$/,'$1 枚を復習しました。'],
    [/^(.+) removed from review · Vocabulary › Suspended brings it back$/,'$1 を復習から外しました · 単語帳 › 保留中で元に戻せます'],
    [/^Restored (\d+) items?/,'$1 件を復元しました'],[/^Found the files of (\d+) dictionar(?:y|ies) here$/,'$1 個の辞書のファイルが見つかりました'],
    [/^Found (\d+) dictionar(?:y|ies)\. Files stay where they are — keep them in this folder\.$/,'辞書が $1 個見つかりました。ファイルは元の場所に残るので、このフォルダに置いたままにしてください。'],
    [/^Syncing with (\d+) other devices?$/,'他の $1 台の端末と同期中'],[/^This device: (.+?) · last synced (.+)$/,'この端末: $1 · 最終同期 $2'],[/^Synced from your other device: (\d+) new/,'他の端末から同期: 新規 $1 件'],
    [/^(.*)(\d+)\/(\d+) read$/,'$1$2/$3 読了'],[/^(\d+) of (\d+) read$/,'$2 章中 $1 章が既読'],[/^Page (\d+) of (\d+)$/,'$1 / $2 ページ'],[/^(\d+) pages$/,'$1 ページ'],
    [/^Imported ([\d,]+) words$/,'$1 語を取り込みました'],[/^Added ([\d,]+) cards/,'$1 枚のカードを追加しました'],[/^Mihon backup: (\d+) of (\d+) series matched · (\d+) chapters updated/,'Mihon バックアップ: $2 シリーズ中 $1 件が一致 · $3 章を更新'],
    [/^([\d,]+) words · ranked$/,'$1 語 · 順位付き'],[/^(\d+) groups · ([\d,]+) lines$/,'$1 グループ · $2 行'],[/^(\d+) (.+) · ([\d,]+) lines$/,'$1 $2 · $3 行'],[/^(?:(\d+)\/)?(\d+) lines$/,'$2 行'],
    [/^Added (\d+) books?/,'$1 冊を追加しました'],[/^Bookmarks · (\d+)$/,'しおり · $1'],[/^Highlights · (\d+)$/,'ハイライト · $1'],[/^Text (\d+px)$/,'文字 $1'],[/^Every (\d+) ?(.*)$/,'$1$2 ごと'],
    [/^(.+) class · (.+) · final (.+)$/,'$1 クラス · $2 · 末子音 $3'],[/^(.+) tone$/,'$1 声調'],[/^(\d+) of (\d+) written correctly so far$/,'ここまでに正しく書けた数: $1 / $2'],
    [/^Your target recall is (\d+%)/,'目標記憶率は $1 です。'],
    [/^(\d+) chapters$/,'$1 章'],[/^(\d+) PDFs?$/,'PDF $1 件'],[/^Text recognition failed:\s*(.*)$/,'テキスト認識に失敗: $1'],
    [/^Known (\d+)$/,'既知 $1'],[/^Screen text: (.+)$/,'画面テキスト: $1'],[/^Another app is using port (\d+)/,'別のアプリがポート $1 を使用中です'],[/^Inserted (.+)$/,'$1 を挿入しました'],[/^(\d+) cards?$/,'$1 枚'],[/^(\d+) words?$/,'$1 語'],[/^(\d+) items?$/,'$1 件'],[/^(\d+) due$/,'$1 件が期限'],
    [/^(\d+) new$/,'新規 $1'],[/^(\d+) reviews?$/,'復習 $1'],[/^(\d+) books?$/,'$1 冊'],[/^(\d+) chapters?$/,'$1 章'],[/^(\d+) pages?$/,'$1 ページ'],
    [/^Text size (\d+%)$/,'文字サイズ $1'],[/^Saved to (.+)$/,'$1 に保存しました'],[/^Moved to (.+)$/,'$1 に移動しました'],[/^Sort: (.+)$/,'並べ替え: $1'],
    [/^Group: (.+)$/,'グループ: $1'],[/^New type inside (.+)$/,'$1 内に新しい種類'],[/^Move up in (.+)$/,'$1 内で上へ'],[/^Move down in (.+)$/,'$1 内で下へ'],
    [/^Search definitions for “(.+)”$/,'「$1」を語義から検索'],[/^New folder named “(.+)”/,'新しいフォルダ「$1」'],[/^New folder “(.+)”/,'新しいフォルダ「$1」'],
    [/^Text recognition failed: (.+)$/,'テキスト認識に失敗: $1'],[/^Every (.+)$/,'$1 ごと'],[/^End of (.+) · press play for (.+)$/,'$1 の終わり · 再生で $2 へ'],
    [/^Chapter (\d+)$/,'第$1章'],[/^Line (\d+)$/,'$1 行目'],[/^Lesson (\d+) \/ (\d+)/,'レッスン $1 / $2'],[/^(\d+) \/ (\d+)$/,'$1 / $2'],
    [/^Character (\d+) \/ (\d+)/,'キャラクター $1 / $2'],[/^Unit (\d+) \/ (\d+)/,'ユニット $1 / $2'],[/^Topic (\d+) \/ (\d+)/,'トピック $1 / $2'],[/line (\d+) \/ (\d+)/,'$1 / $2 行目'],
    [/^Reviews? ?· ?(\d+)$/,'復習 · $1'],[/^Looping this character$/,'このキャラクターをループ中'],[/^Looping: starts over at the end$/,'ループ中: 最後で最初に戻ります'],[/^Not looping$/,'ループなし']
  ];
  const ATTRS=['placeholder','title','aria-label'];
  const orig=new WeakMap();// node → its English text
  let lang='en';
  try{lang=(JSON.parse(localStorage.getItem('settings')||'{}').ui_lang)||'en';}catch(e){}
  function tr(s){
    if(lang!=='ja')return s;
    const m=/^(\s*)([\s\S]*?)(\s*)$/.exec(s);if(!m||!m[2])return s;
    const t=m[2];let r=JA[t];
    if(r==null)for(const [re,to] of PAT){if(re.test(t)){r=t.replace(re,to);break;}}
    if(r==null&&t.includes(' · ')){const ps=t.split(' · '),ts=ps.map(x=>tr(x));if(ts.some((x,i)=>x!==ps[i]))r=ts.join(' · ');}
    return r==null?s:m[1]+r+m[3];
  }
  const SKIP=new Set(['SCRIPT','STYLE','TEXTAREA','IFRAME','CODE']);
  const skip=el=>!el||SKIP.has(el.tagName)||(el.closest&&el.closest('[data-no-i18n],.mu-w,.entry,.card-frame'));
  function text(n){
    if(skip(n.parentElement))return;
    const cur=n.nodeValue,o=orig.get(n);
    if(lang==='ja'){
      const src=o&&cur===o.ja?o.en:cur,out=tr(src);
      if(out!==src&&cur!==out){orig.set(n,{en:src,ja:out});n.nodeValue=out;}
    }else if(o&&cur===o.ja){n.nodeValue=o.en;orig.delete(n);}
  }
  function attrs(el){
    if(skip(el))return;
    for(const a of ATTRS){
      const v=el.getAttribute&&el.getAttribute(a);if(v==null)continue;
      const key='i18n-'+a,en=el.getAttribute('data-'+key);
      if(lang==='ja'){
        const src=en!=null&&v===tr(en)?en:v,out=tr(src);
        if(out!==src){if(en==null||v!==out)el.setAttribute('data-'+key,src);if(v!==out)el.setAttribute(a,out);}
      }else if(en!=null){el.setAttribute(a,en);el.removeAttribute('data-'+key);}
    }
  }
  function walk(root){
    if(root.nodeType===3){text(root);return;}
    if(root.nodeType!==1||skip(root))return;
    attrs(root);
    const w=document.createTreeWalker(root,NodeFilter.SHOW_ELEMENT|NodeFilter.SHOW_TEXT);
    for(let n=w.nextNode();n;n=w.nextNode()){if(n.nodeType===3)text(n);else attrs(n);}
  }
  let busy=false;
  const mo=new MutationObserver(ms=>{
    if(busy)return;busy=true;
    try{for(const m of ms){
      if(m.type==='characterData')text(m.target);
      else if(m.type==='attributes')attrs(m.target);
      else m.addedNodes.forEach(walk);
    }}finally{busy=false;}
  });
  function start(){
    document.documentElement.lang=lang;tell();
    walk(document.body);
    mo.observe(document.body,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:ATTRS});
  }
  function set(l){
    lang=l==='ja'?'ja':'en';
    try{const s=JSON.parse(localStorage.getItem('settings')||'{}');s.ui_lang=lang;localStorage.setItem('settings',JSON.stringify(s));}catch(e){}
    document.documentElement.lang=lang;tell();
    walk(document.body);
  }
  const tell=()=>{try{window.webkit.messageHandlers.kotoba.postMessage({type:'uiLang',lang});}catch(e){}};
  window.I18N={get lang(){return lang;},set,tr};
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();
})();
