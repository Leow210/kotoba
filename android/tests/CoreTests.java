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

        // Korean: forms generated from real stems (the predicate stands in for the dictionary).
        Set<String> ko=new HashSet<>(Arrays.asList("춥다","먹다","하다","모르다","듣다","들다","하얗다","가다","보다","되다","마시다","살다","사다","예쁘다","학교","공부","쓰다","돕다","낫다"));
        java.util.function.Function<String,List<String>> stems=w->new ArrayList<>(ko);
        String[][] koCases={{"추워요","춥다"},{"먹었어요","먹다"},{"해요","하다"},{"몰라요","모르다"},{"들어요","듣다"},{"하얘요","하얗다"},{"봐요","보다"},{"됐어요","되다"},{"마셔요","마시다"},{"사는","살다"},{"예뻐요","예쁘다"},{"써요","쓰다"},{"도와요","돕다"},{"나아요","낫다"},{"공부했어요","공부"},{"학교에서","학교"},{"먹고싶어요","먹다"},{"안가요","가다"},{"먹을수있어요","먹다"},{"가세요","가다"},{"먹지않았어요","먹다"}};
        for(String[] k:koCases)check(has(Deinflect.korean(k[0],ko::contains,stems),k[1],null),"Korean "+k[0]+" → "+k[1]+" got "+names(Deinflect.korean(k[0],ko::contains,stems)));
        check(!has(Deinflect.korean("먹어요",ko::contains,stems),"하다",null),"Korean does not over-match");

        // Russian.
        String[][] ruCases={{"читала","читать"},{"книги","книга"},{"говорю","говорить"},{"красивая","красивый"},{"занимаюсь","заниматься"},{"пишу","писать"},{"хожу","ходить"},{"столом","стол"}};
        for(String[] r:ruCases)check(has(Deinflect.russian(r[0]),r[1],null),"Russian "+r[0]+" → "+r[1]);

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

        // MDX files and EPUB books given on the command line.
        for(String path:args){
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
    static boolean has(List<Deinflect.Candidate> list,String base,String explain){
        for(Deinflect.Candidate c:list)if(c.base.equals(base)&&(explain==null||c.explain().equals(explain)))return true;
        return false;
    }
    static String names(List<Deinflect.Candidate> list){StringBuilder b=new StringBuilder();for(Deinflect.Candidate c:list)b.append(c.base).append(' ');return b.toString();}
}
