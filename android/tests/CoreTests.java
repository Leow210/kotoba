package app.kotoba.reader;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/**
 * Desktop tests for the Android-independent core (MDX reader, text extraction, FSRS, deinflection).
 * Run: android/tests/run.sh [path-to-an.mdx]
 */
public class CoreTests {
    static int failures=0,passed=0;
    static void check(boolean ok,String what){if(ok)passed++;else{failures++;System.out.println("FAIL: "+what);}}
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}

    public static void main(String[] args) throws Exception {
        // RIPEMD-128 reference vectors (used for encrypted MDX key indexes).
        check(hex(Ripemd128.digest(new byte[0])).equals("cdf26213a150dc3ecb610f18f6b38b46"),"ripemd128 empty");
        check(hex(Ripemd128.digest("abc".getBytes())).equals("c14a12199c66e4ba84636b0f69144c77"),"ripemd128 abc");

        // Normalization: kana folding, ▽ marks, Russian stress and ё.
        check(HtmlText.normalize("オトコ").equals("おとこ"),"katakana folds to hiragana");
        check(HtmlText.normalize("言葉▼咎め").equals("言葉咎め"),"▼ marks ignored");
        check(HtmlText.normalize("чита́ть").equals("читать"),"stress mark ignored");
        check(HtmlText.normalize("ёлка").equals("елка"),"ё folds to е");
        check(HtmlText.normalize("ก็").equals(HtmlText.normalize("ก็")),"Thai marks kept");

        // Definition vs example text.
        HtmlText t=HtmlText.parse("<span data-name=\"見出部\">ことば</span><span data-name=\"語釈\">ものの言い方。</span><span data-name=\"用例\">「丁寧な<b>━</b>」</span><span data-name=\"ルビG\">ごい</span><div id=\"index\"><a>x</a></div>");
        check(t.definitions.toString().trim().equals("ものの言い方。"),"definition text: "+t.definitions);
        check(t.examples.toString().equals("「丁寧な━」"),"example text: "+t.examples);
        check(HtmlText.tokens("ことば",10).equals("こ と ば"),"one token per character");

        // FSRS: new card learning steps, graduation, lapse.
        Fsrs f=new Fsrs(0.9,36500);
        Fsrs.Card c=new Fsrs.Card();long now=1_700_000_000L;
        Fsrs.Card again=f.answer(c,1,now),good=f.answer(c,3,now),easy=f.answer(c,4,now);
        check(again.interval==60&&again.state==1,"new→Again = 1 minute");
        check(good.interval==600&&good.state==1,"new→Good = 10 minutes");
        check(easy.state==2&&easy.interval>=86400*2,"new→Easy graduates");
        Fsrs.Card grad=f.answer(good,3,now+600);
        check(grad.state==2&&grad.interval>=86400,"second Good graduates");
        Fsrs.Card later=f.answer(grad,3,grad.due);
        check(later.interval>grad.interval,"review Good grows interval ("+grad.interval/86400+"d → "+later.interval/86400+"d)");
        Fsrs.Card lapse=f.answer(later,1,later.due);
        check(lapse.state==3&&lapse.lapses==1&&lapse.stability<later.stability,"lapse relearns");
        Fsrs.Card[] ratings={f.answer(grad,2,grad.due),f.answer(grad,3,grad.due),f.answer(grad,4,grad.due)};
        check(ratings[0].interval<ratings[1].interval&&ratings[1].interval<ratings[2].interval,"Hard < Good < Easy");

        // Japanese deinflection produces the expected base with an explanation.
        check(has(Deinflect.japanese("食べさせられなかった"),"食べる","causative-passive · negative · past"),"食べさせられなかった");
        check(has(Deinflect.japanese("書かれていた"),"書く",null),"書かれていた");
        check(has(Deinflect.japanese("勉強しています"),"勉強する",null),"勉強しています");
        check(has(Deinflect.japanese("行って"),"行く",null),"行って");
        check(!has(Deinflect.japanese("かった"),"かく",null),"no 行く-type rule for かった");
        check(has(Deinflect.japanese("高くなかった"),"高い",null),"高くなかった");
        // Literary, colloquial and contracted forms, te-form auxiliaries, honorific -aru verbs.
        String[][] jaCases={{"知らぬ","知る"},{"知らざる","知る"},{"行くべき","行く"},{"すべき","する"},{"知らねば","知る"},{"知らずに","知る"},{"知らん","知る"},{"分からん","分かる"},
            {"食べなきゃ","食べる"},{"行かなきゃ","行く"},{"高くなきゃ","高い"},{"行かなくちゃ","行く"},{"やっとく","やる"},{"食べとく","食べる"},{"読んどく","読む"},{"持ってく","持つ"},
            {"食べてもらった","食べる"},{"食べてみた","食べる"},{"読んでみる","読む"},{"書いてあげた","書く"},{"教えてくれた","教える"},{"来てほしい","来る"},{"食べちゃった","食べる"},{"行っちまった","行く"},
            {"いらっしゃいます","いらっしゃる"},{"いらっしゃいませ","いらっしゃる"},{"おっしゃいました","おっしゃる"},{"ください","くださる"},{"ございます","ござる"},{"なさいました","なさる"}};
        for(String[] k:jaCases)check(has(Deinflect.japanese(k[0]),k[1],null),"Japanese "+k[0]+" → "+k[1]);
        check(classOf(Deinflect.japanese("しない"),"しる")==Deinflect.V1&&classOf(Deinflect.japanese("しない"),"する")==Deinflect.VS,"candidates keep their word class");
        check((classOf(Deinflect.japanese("入れた"),"入れる")&Deinflect.V1)!=0,"入れた → 入れる as ichidan");
        // Breakdowns show the stem as written and each ending in its actual form.
        String[][] jaShown={{"食べさせられなかった","食べる","食べ + させ + られ + なかった"},{"書いてもらった","書く","書い + てもらっ + た"},{"食べてる","食べる","食べ + てる"},
            {"書きました","書く","書き + ました"},{"勉強しています","勉強する","勉強し + てい + ます"},{"いらっしゃいます","いらっしゃる","いらっしゃい + ます"},{"行くべき","行く","行く + べき"},
            {"高かった","高い","高 + かった"},{"行った","行く","行っ + た"},{"食べちゃった","食べる","食べ + ちゃっ + た"},{"知らなかった","知る","知ら + なかった"}};
        for(String[] k:jaShown)check(k[2].equals(shownOf(Deinflect.japanese(k[0]),k[1])),"breakdown "+k[0]+" = "+shownOf(Deinflect.japanese(k[0]),k[1]));
        // Word classes read from dictionary entries.
        check(Deinflect.entryClass("しる0【知る・領る】一（動ラ五［四］）一《知》","しる",null)==Deinflect.V5,"大辞林 godan mark");
        check(Deinflect.entryClass("きる【着る】［他上一］❶衣服を","きる",null)==Deinflect.V1,"明鏡 ichidan mark");
        check(Deinflect.entryClass("える【得る】（動ア下一［下二］）","える",null)==Deinflect.V1,"modern class before the classical one");
        check(Deinflect.entryClass("し・る【痴】〘自ラ下二〙 ⇒しれる","しる",null)==Deinflect.CLASSICAL&&!Deinflect.classFits(Deinflect.CLASSICAL,Deinflect.V1),"classical 下二 doesn't fit ichidan");
        check(Deinflect.entryClass("くる＊＊くる［来る］｟自カ｠〔終止形は","くる",null)==Deinflect.VK,"三省堂 カ変 mark");
        check(Deinflect.entryClass("べんきょう【勉強】［名・自他サ変］❶学問","べんきょう",null)==(Deinflect.NONCONJ|Deinflect.VS),"noun + する mark");
        check(Deinflect.entryClass("たかい【高】〘形口〙 たか・し","たかい",null)==Deinflect.ADJ,"日国 adjective mark");
        check(Deinflect.entryClass("しる1【汁】①物の内部にある液体。","しる",null)==Deinflect.NONCONJ,"unmarked kanji-only headword is a noun");
        check(Deinflect.entryClass("","シル",null)==Deinflect.NONCONJ,"katakana entry is not a verb");
        check(Deinflect.entryClass("しる【知る】知っての通り","しる",null)==0,"unmarked verb is unknown");
        check(!Deinflect.classFits(Deinflect.V5,Deinflect.V1)&&Deinflect.classFits(0,Deinflect.V1)&&Deinflect.classFits(Deinflect.NONCONJ|Deinflect.VS,Deinflect.VS),"class fit");

        // Korean: forms generated from real stems (the predicate stands in for the dictionary).
        Set<String> ko=new HashSet<>(Arrays.asList("춥다","먹다","하다","모르다","듣다","들다","하얗다","가다","보다","되다","마시다","살다","사다","예쁘다","학교","공부","쓰다","돕다","낫다"));
        java.util.function.Function<String,List<String>> stems=w->new ArrayList<>(ko);
        String[][] koCases={{"추워요","춥다"},{"먹었어요","먹다"},{"해요","하다"},{"몰라요","모르다"},{"들어요","듣다"},{"하얘요","하얗다"},{"봐요","보다"},{"됐어요","되다"},{"마셔요","마시다"},{"사는","살다"},{"예뻐요","예쁘다"},{"써요","쓰다"},{"도와요","돕다"},{"나아요","낫다"},{"공부했어요","공부"},{"학교에서","학교"},{"먹고싶어요","먹다"},{"안가요","가다"},{"먹을수있어요","먹다"},{"가세요","가다"},{"먹지않았어요","먹다"}};
        for(String[] k:koCases)check(has(Deinflect.korean(k[0],ko::contains,stems),k[1],null),"Korean "+k[0]+" → "+k[1]+" got "+names(Deinflect.korean(k[0],ko::contains,stems)));
        check(!has(Deinflect.korean("먹어요",ko::contains,stems),"하다",null),"Korean does not over-match");
        // Irregular stem changes only for verbs that are irregular: 밀어요 is 밀다, not 믿다; 내요 is 내다, not 낳다 or 넣다.
        Set<String> ko2=new HashSet<>(Arrays.asList("듣다","모르다","가다","돕다","먹다","짓다","하다","살다","밀다","믿다","달다","닫다","얼다","얻다","내다","낳다","넣다","대다","닿다","놓다","하얗다","춥다","보다","있다","않다","씻다","시다"));
        java.util.function.Function<String,List<String>> stems2=w->new ArrayList<>(ko2);
        String[][] notIrregular={{"밀어요","믿다"},{"달아요","닫다"},{"얼어요","얻다"},{"내요","낳다"},{"내요","넣다"},{"대요","닿다"},{"노면","놓다"},{"시어요","씻다"}};
        for(String[] k:notIrregular)check(!has(Deinflect.korean(k[0],ko2::contains,stems2),k[1],null),"Korean "+k[0]+" is not "+k[1]);
        String[][] stillIrregular={{"들었어요","듣다"},{"지어요","짓다"},{"하얘요","하얗다"},{"추워요","춥다"},{"도와요","돕다"},{"몰랐어","모르다"}};
        for(String[] k:stillIrregular)check(has(Deinflect.korean(k[0],ko2::contains,stems2),k[1],null),"Korean "+k[0]+" → "+k[1]);
        String[][] koShown={{"들었어요","듣다","듣 → 들 + 었 + 어요"},{"몰랐어","모르다","모르 → 몰ㄹ + 았 + 어"},{"도와요","돕다","돕 → 도오 + 아요"},{"했어요","하다","하 + 였 + 어요"},
            {"먹으면","먹다","먹 + 으면"},{"사세요","살다","살 → 사 + 세요"},{"들으셨어요","듣다","듣 → 들 + 으시 + 었 + 어요"},{"먹고 있었어요","먹다","먹 + 고 있 + 었 + 어요"},
            {"먹지 않았어요","먹다","먹 + 지 않 + 았 + 어요"},{"간","가다","가 + ㄴ"},{"먹은","먹다","먹 + 은"}};
        for(String[] k:koShown){String got="";for(Deinflect.Candidate x:Deinflect.korean(k[0],ko2::contains,stems2))if(x.base.equals(k[1])){got=x.shown();break;}check(k[2].equals(got),"breakdown "+k[0]+" = "+got);}

        // Russian.
        String[][] ruCases={{"читала","читать"},{"книги","книга"},{"говорю","говорить"},{"красивая","красивый"},{"занимаюсь","заниматься"},{"пишу","писать"},{"хожу","ходить"},{"столом","стол"}};
        for(String[] r:ruCases)check(has(Deinflect.russian(r[0]),r[1],null),"Russian "+r[0]+" → "+r[1]);

        // German: words in text, umlaut-free spellings, separable particles, compounds.
        check(German.firstWord("  „Geht's schon?“ sagte er").equals("Geht's"),"German first word keeps geht's");
        check(German.firstWord("E-Mail-Adresse, bitte").equals("E-Mail-Adresse"),"German hyphen compound");
        check(German.firstWord("gehen-").equals("gehen"),"German trailing hyphen dropped");
        check(German.startsLatin("„Haus“")&&!German.startsLatin("家")&&!German.startsLatin("дом"),"German startsLatin");
        check(German.respellings("fuer").contains("für")&&German.respellings("strasse").contains("straße")&&German.respellings("schoen").contains("schön"),"German respellings");
        check(German.respellings("gross").contains("groß")&&!German.respellings("haus").contains("haus"),"German respellings exclude the word itself");
        check(German.separableParticles(" um sieben Uhr auf.").equals(java.util.Arrays.asList("auf")),"German particle closes clause");
        check(German.separableParticles(" heute auf den Markt").isEmpty(),"German auf den Markt is not a particle");
        check(German.separableParticles(" mich an, weil").equals(java.util.Arrays.asList("an")),"German particle before comma");
        check(German.separableParticles(" früh auf und geht").equals(java.util.Arrays.asList("auf")),"German particle before und");
        check(German.separableParticles(", dann kommt er mit").isEmpty(),"German particle after comma belongs to another clause");
        check(German.splits("first-person singular present")&&German.splits("singular imperative")&&!German.splits("past participle")&&!German.splits("zu-infinitive"),"German which forms split");
        java.util.Set<String> deKnown=new java.util.HashSet<>(java.util.Arrays.asList("arbeit","zimmer","kind","kinder","haus","tür","donau","dampf","schiff","fahrt","gesellschaft","straße","bahn","erde","beere","handy","hülle","hand","schuh","zimmern"));
        check(German.split("Arbeitszimmer",deKnown::contains).equals(java.util.Arrays.asList("arbeits","zimmer")),"German compound with Fugen-s");
        check(German.split("Kinderzimmern",deKnown::contains).equals(java.util.Arrays.asList("kinder","zimmern")),"German compound with plural modifier and inflected head");
        check(German.split("Haustür",deKnown::contains).equals(java.util.Arrays.asList("haus","tür")),"German plain compound");
        check(German.split("Straßenbahn",deKnown::contains).equals(java.util.Arrays.asList("straßen","bahn")),"German compound with Fugen-n");
        check(German.split("Erdbeere",deKnown::contains).equals(java.util.Arrays.asList("erd","beere")),"German compound dropping final e");
        check(German.split("Donaudampfschifffahrt",deKnown::contains).equals(java.util.Arrays.asList("donau","dampf","schiff","fahrt")),"German four-part compound");
        check(German.split("Handschuh",deKnown::contains).equals(java.util.Arrays.asList("hand","schuh")),"German Handschuh");
        check(German.split("Zimmerxyz",deKnown::contains)==null&&German.split("Haus",deKnown::contains)==null,"German non-compounds");

        // Markup fixes for Monokakido exports.
        check(MarkupFix.html("<用例>x</用例>").equals("<span data-name=\"用例\">x</span>"),"non-ASCII tags become spans");
        check(MarkupFix.html("<audio><a href=\"sound://1.aac\">x</a></audio>").startsWith("<span data-name=\"audio\">"),"audio wrappers become spans");
        check(MarkupFix.css("@color red = #f00;\n用例 b{color:red}").contains("[data-name=\"用例\"] b{color:#f00}"),"CSS selectors and @color rewritten");

        // Plain-text books: encodings and chapter headings.
        String[][] txts={{"第一章 はじまり\n吾輩は猫である。名前はまだ無い。\n第二章 つづき\nどこで生れたかとんと見当がつかぬ。\n","Shift_JIS","ja"},
            {"제1장 시작\n오늘은 날씨가 좋다. 학교에 갔다.\n제2장 끝\n집에 왔다.\n","EUC-KR","ko"},
            {"บทที่ 1 เริ่มต้น\nวันนี้อากาศดีมาก ฉันไปโรงเรียน\nบทที่ 2 จบ\nกลับบ้านแล้ว\n","windows-874","th"},
            {"Глава 1\nМальчик, который выжил.\nГлава 2\nИсчезнувшее стекло.\n","windows-1251","ru"}};
        for(String[] tx:txts){
            BookParser.Book b=BookParser.txt(tx[0].getBytes(tx[1]),"sample.txt");
            check(b.language.equals(tx[2])&&b.spine.size()==2,"TXT "+tx[1]+" → "+b.encoding+" "+b.language+" "+b.spine.size()+" chapters");
        }

        // Yomitan: JSON reader, rows, structured content, meta.
        {
            Object v=Yomitan.parse("[\"a\\n\\u00e9\",1.5,true,null,{\"k\":[]}]");
            check(v instanceof List&&((List<?>)v).size()==5&&"a\né".equals(((List<?>)v).get(0))&&((List<?>)v).get(3)==Yomitan.NULL,"JSON values");
            List<Object> rows=new ArrayList<>();
            new Yomitan.Json(new java.io.StringReader(" [ [\"言葉\",\"ことば\",\"n\",\"\",5,[\"word\",\"speech\"],7,\"P\"] , [\"x\"] ] ")).eachInArray(rows::add);
            check(rows.size()==2,"JSON streams top-level array");
            Yomitan.Term yt=Yomitan.term(rows.get(0));
            check(yt.term.equals("言葉")&&yt.reading.equals("ことば")&&yt.sequence==7&&yt.glossary.size()==2&&yt.termTags.equals("P"),"term row");
            Map<String,Yomitan.Tag> tags=new HashMap<>();tags.put("n",new Yomitan.Tag("n","partOfSpeech",0,"noun"));
            String sense=Yomitan.senseHtml(yt,tags);
            check(sense.contains("<ol class=\"yt-gloss\"><li><span class=\"yt-text\">word</span></li>")&&sense.contains("data-category=\"partOfSpeech\" title=\"noun\""),"sense html: "+sense);
            Yomitan.Term sc=Yomitan.term(Yomitan.parse("[\"語\",\"ご\",\"\",\"\",0,[{\"type\":\"structured-content\",\"content\":[{\"tag\":\"span\",\"data\":{\"name\":\"見出部\",\"fooBar\":\"1\"},\"style\":{\"fontWeight\":\"bold\",\"marginLeft\":0.5},\"content\":\"ご<語>\"},{\"tag\":\"a\",\"href\":\"?query=%E8%A8%80&wildcards=off\",\"content\":\"言\"},{\"tag\":\"img\",\"path\":\"img/a b.png\",\"width\":2,\"height\":1,\"sizeUnits\":\"em\"},{\"tag\":\"script\",\"content\":\"x\"}]}]]"));
            String h=Yomitan.senseHtml(sc,null);
            check(h.contains("<span data-sc-name=\"見出部\" data-name=\"見出部\" data-sc-foo-bar=\"1\" style=\"font-weight:bold;margin-left:0.5em;\">ご&lt;語&gt;</span>"),"structured span: "+h);
            check(h.contains("href=\"entry://言\""),"yomitan query link → entry://");
            check(h.contains("src=\"img/a%20b.png\"")&&h.contains("width:2em"),"image path and size");
            check(!h.contains("script"),"unknown tags dropped, text kept: "+h);
            check(Yomitan.printsOwnHeading("<span>め–つぎ【芽接ぎ】</span>","芽接ぎ","めつぎ"),"dictionary's own heading detected");
            check(!Yomitan.printsOwnHeading("<span>2channel (online forum)</span>","２ちゃんねる","にちゃんねる"),"no own heading");
            Yomitan.Meta fq=Yomitan.meta(Yomitan.parse("[\"人\",\"freq\",{\"reading\":\"ひと\",\"frequency\":{\"value\":120,\"displayValue\":\"120㋕\"}}]"));
            check(fq.reading.equals("ひと")&&fq.value==120&&fq.display.equals("120㋕"),"freq with reading");
            Yomitan.Meta f2=Yomitan.meta(Yomitan.parse("[\"の\",\"freq\",5]"));
            check(f2.value==5&&f2.display.equals("5"),"plain freq number");
            Yomitan.Meta p=Yomitan.meta(Yomitan.parse("[\"箸\",\"pitch\",{\"reading\":\"はし\",\"pitches\":[{\"position\":1}]}]"));
            check(p.display.equals("1"),"pitch position");
        }

        // MDX files, EPUB books and Yomitan ZIPs given on the command line.
        for(String path:args){
            if(path.endsWith(".zip")){
                try(ZipSource z=new ZipSource(FileChannel.open(Paths.get(path)))){
                    Yomitan.Index idx=Yomitan.index(new String(z.bytes("index.json"),"UTF-8"));
                    int[] n={0,0};long started=System.currentTimeMillis();
                    StringBuilder sample=new StringBuilder();
                    for(ZipSource.Entry e:z.entries.values()){
                        String name=e.name.replaceAll(".*/","");
                        if(!name.matches("(term|kanji|term_meta)_bank_\\d+\\.json"))continue;
                        try(java.io.Reader r=new java.io.InputStreamReader(z.stream(e),"UTF-8")){
                            new Yomitan.Json(r).eachInArray(row->{
                                n[0]++;
                                if(name.startsWith("term_bank")){Yomitan.Term tr=Yomitan.term(row);String s=Yomitan.senseHtml(tr,null);n[1]+=s.length();if(sample.length()==0)sample.append(Yomitan.entryHtml(tr.term,tr.reading,"",s));}
                                else if(name.startsWith("kanji"))Yomitan.kanjiHtml(Yomitan.kanji(row),null);
                                else Yomitan.meta(row);
                            });
                        }
                    }
                    check(n[0]>0,"Yomitan rows in "+path);
                    System.out.println(idx.title+": "+n[0]+" rows · "+(n[1]/1024/1024)+" MB html · "+(System.currentTimeMillis()-started)+" ms");
                    if(System.getenv("YOMITAN_SAMPLES")!=null)java.nio.file.Files.writeString(Paths.get(System.getenv("YOMITAN_SAMPLES"),idx.title.replaceAll("[/\\s]","_")+".html"),sample.toString());
                }
                continue;
            }
            if(path.endsWith(".epub")){
                try(ZipSource z=new ZipSource(FileChannel.open(Paths.get(path)))){
                    BookParser.Book b=BookParser.epub(z);
                    check(!b.title.isEmpty()&&b.spine.size()>0&&!b.toc.isEmpty(),"EPUB parses: "+path);
                    System.out.println(Paths.get(path).getFileName().toString().substring(0,Math.min(30,Paths.get(path).getFileName().toString().length()))+"…: "+b.title+" · "+b.language+" · "+b.spine.size()+" chapters · toc "+b.toc.size()+(b.writing.isEmpty()?"":" · "+b.writing));
                }
                continue;
            }
            MdictFile m=new MdictFile(FileChannel.open(Paths.get(path)),path.endsWith(".mdd"));
            final int[] n={0};final long[] last={-1};final boolean[] ordered={true};
            m.keys((k,o)->{n[0]++;if(o<last[0])ordered[0]=false;last[0]=o;});
            check(n[0]==m.entryCount,"key count matches header in "+path);
            System.out.println(Paths.get(path).getFileName()+": "+m.title()+" · "+n[0]+" keys · "+m.recordStart.length+" blocks · ordered="+ordered[0]);
        }
        System.out.println(passed+" passed, "+failures+" failed");
        if(failures>0)System.exit(1);
    }
    static String shownOf(List<Deinflect.Candidate> list,String base){for(Deinflect.Candidate k:list)if(k.base.equals(base))return k.shown();return "";}
    static int classOf(List<Deinflect.Candidate> list,String base){int c=0;for(Deinflect.Candidate k:list)if(k.base.equals(base)&&k.steps.size()==1)c|=k.wordClass;return c;}
    static boolean has(List<Deinflect.Candidate> list,String base,String explain){
        for(Deinflect.Candidate c:list)if(c.base.equals(base)&&(explain==null||c.explain().equals(explain)))return true;
        return false;
    }
    static String names(List<Deinflect.Candidate> list){StringBuilder b=new StringBuilder();for(Deinflect.Candidate c:list)b.append(c.base).append(' ');return b.toString();}
}
