package app.kotoba.reader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds dictionary forms for conjugated Japanese, Korean and Russian words, with an explanation of each step.
 * Every candidate must still be confirmed against real headwords by the caller; unconfirmed guesses are never shown.
 * Pure Java so it can be tested on a desktop JVM.
 */
public final class Deinflect {
    /** One grammatical step, e.g. suffix "ます", label "polite". */
    public static final class Step {
        public final String suffix,label;
        Step(String suffix,String label){this.suffix=suffix;this.label=label;}
    }
    public static final class Candidate {
        public final String base;
        public final List<Step> steps;// from the dictionary form outwards
        public final int strength;// total characters explained by rules; weak single-kana rules score low
        public final List<String> via;// intermediate forms passed through (食べさせる for 食べさせた)
        public String suffixNote="";// e.g. "する verb" when the headword is the noun alone
        Candidate(String base,List<Step> steps,int strength){this(base,steps,strength,new ArrayList<String>());}
        Candidate(String base,List<Step> steps,int strength,List<String> via){this.base=base;this.steps=steps;this.strength=strength;this.via=via;}
        /** Grammar in plain words: "polite · past". */
        public String explain(){
            StringBuilder b=new StringBuilder(suffixNote);
            for(Step s:steps){if(b.length()>0)b.append(" · ");b.append(s.label.replaceAll("\\s*\\(～[^)]*\\)",""));}
            return b.toString();
        }
        /** Morpheme chain: "食べ + させ + られ + なかった" style, as base + added endings. */
        public String chain(){
            StringBuilder b=new StringBuilder(base);
            for(Step s:steps)if(!s.suffix.isEmpty())b.append(" + ").append(s.suffix);
            return b.toString();
        }
    }

    // ---------------------------------------------------------------- Japanese

    // Word classes (bit flags). A rule turns an inflected form of class `in` into a form of class `out`.
    static final int V1=1,V5=2,VS=4,VK=8,ADJ=16,TE=32,ANY_VERB=V1|V5|VS|VK;
    static final class Rule {
        final String in,out,suffix,label;final int rulesIn,rulesOut;
        Rule(String in,String out,int rulesIn,int rulesOut,String suffix,String label){this.in=in;this.out=out;this.rulesIn=rulesIn;this.rulesOut=rulesOut;this.suffix=suffix;this.label=label;}
    }
    static final List<Rule> JA=new ArrayList<>();
    static final String[] U={"う","く","ぐ","す","つ","ぬ","ぶ","む","る"};
    static final String[] A={"わ","か","が","さ","た","な","ば","ま","ら"};
    static final String[] I={"い","き","ぎ","し","ち","に","び","み","り"};
    static final String[] E={"え","け","げ","せ","て","ね","べ","め","れ"};
    static final String[] O={"お","こ","ご","そ","と","の","ぼ","も","ろ"};
    static final String[] TE_FORM={"って","いて","いで","して","って","んで","んで","んで","って"};
    static final String[] TA_FORM={"った","いた","いだ","した","った","んだ","んだ","んだ","った"};

    static void ja(String in,String out,int rulesIn,int rulesOut,String suffix,String label){JA.add(new Rule(in,out,rulesIn,rulesOut,suffix,label));}
    /** Adds a rule for ichidan verbs, every godan row, する and 来る at once. */
    static void verb(String v1,String[] godanStem,String godanTail,String suru,String kuru,int rulesIn,int rulesOut,String suffix,String label){
        if(v1!=null)ja(v1,"る",rulesIn,V1|(rulesOut&~ANY_VERB),suffix,label);
        if(godanStem!=null)for(int i=0;i<U.length;i++)ja(godanStem[i]+godanTail,U[i],rulesIn,V5|(rulesOut&~ANY_VERB),suffix,label);
        if(suru!=null)ja(suru,"する",rulesIn,VS|(rulesOut&~ANY_VERB),suffix,label);
        if(kuru!=null){ja(kuru,"くる",rulesIn,VK|(rulesOut&~ANY_VERB),suffix,label);ja(kuru.replaceFirst("^[こきく]","来"),"来る",rulesIn,VK|(rulesOut&~ANY_VERB),suffix,label);}
    }
    static{
        // Polite
        verb("ます",I,"ます","します","きます",0,ANY_VERB,"ます","polite");
        verb("ました",I,"ました","しました","きました",0,ANY_VERB,"ました","polite past");
        verb("ません",I,"ません","しません","きません",0,ANY_VERB,"ません","polite negative");
        verb("ませんでした",I,"ませんでした","しませんでした","きませんでした",0,ANY_VERB,"ませんでした","polite negative past");
        verb("ましょう",I,"ましょう","しましょう","きましょう",0,ANY_VERB,"ましょう","polite volitional “let’s”");
        verb("まして",I,"まして","しまして","きまして",0,ANY_VERB,"まして","polite te-form");
        verb("なさい",I,"なさい","しなさい","きなさい",0,ANY_VERB,"なさい","polite imperative");
        // Past / te-form
        ja("た","る",0,V1,"た","past");ja("して","する",TE,VS,"て","te-form");
        for(int i=0;i<U.length;i++){ja(TA_FORM[i],U[i],0,V5,TA_FORM[i].substring(TA_FORM[i].length()-1),"past");ja(TE_FORM[i],U[i],TE,V5,TE_FORM[i].substring(TE_FORM[i].length()-1),"te-form");}
        for(String iku:new String[]{"行","い","逝"}){ja(iku+"った",iku+"く",0,V5,"た","past");ja(iku+"って",iku+"く",TE,V5,"て","te-form");ja(iku+"ったら",iku+"く",0,V5,"たら","conditional “if/when”");}
        ja("した","する",0,VS,"た","past");ja("きた","くる",0,VK,"た","past");ja("来た","来る",0,VK,"た","past");
        ja("て","る",TE,V1,"て","te-form");ja("きて","くる",TE,VK,"て","te-form");ja("来て","来る",TE,VK,"て","te-form");
        ja("たら","る",0,V1,"たら","conditional “if/when”");ja("したら","する",0,VS,"たら","conditional “if/when”");ja("きたら","くる",0,VK,"たら","conditional “if/when”");
        for(int i=0;i<U.length;i++)ja(TA_FORM[i]+"ら",U[i],0,V5,"たら","conditional “if/when”");
        ja("たり","る",0,V1,"たり","listing actions (～たり)");for(int i=0;i<U.length;i++)ja(TA_FORM[i]+"り",U[i],0,V5,"たり","listing actions (～たり)");
        // Aspect after te-form: 〜ている / 〜てる / 〜てしまう / 〜ちゃう
        ja("ている","て",V1,TE,"ている","progressive / resulting state");ja("でいる","で",V1,TE,"でいる","progressive / resulting state");
        ja("てる","て",V1,TE,"てる","progressive (casual)");ja("でる","で",V1,TE,"でる","progressive (casual)");
        ja("てしまう","て",V5,TE,"てしまう","completion / regret");ja("でしまう","で",V5,TE,"でしまう","completion / regret");
        ja("ちゃう","て",V5,TE,"ちゃう","completion (casual)");ja("じゃう","で",V5,TE,"じゃう","completion (casual)");
        ja("ておく","て",V5,TE,"ておく","do in advance");ja("でおく","で",V5,TE,"でおく","do in advance");
        ja("てある","て",V5,TE,"てある","has been done");ja("てくる","て",VK,TE,"てくる","come to / start to");ja("ていく","て",V5,TE,"ていく","go on doing");
        ja("てください","て",0,TE,"てください","polite request");ja("でください","で",0,TE,"でください","polite request");
        // Negative
        verb("ない",A,"ない","しない","こない",ADJ,ANY_VERB,"ない","negative");
        verb("ず",A,"ず","せず","こず",0,ANY_VERB,"ず","negative (written)");
        verb("ないで",A,"ないで","しないで","こないで",0,ANY_VERB,"ないで","without doing / please don’t");
        verb("なくて",A,"なくて","しなくて","こなくて",0,ANY_VERB,"なくて","negative te-form");
        // Potential / passive / causative
        ja("られる","る",V1,V1,"られる","passive or potential");
        for(int i=0;i<U.length;i++){ja(E[i]+"る",U[i],V1,V5,"える","potential");ja(A[i]+"れる",U[i],V1,V5,"れる","passive");ja(A[i]+"せる",U[i],V1,V5,"せる","causative");ja(A[i]+"される",U[i],V1,V5,"される","causative-passive");ja(A[i]+"せられる",U[i],V1,V5,"せられる","causative-passive");}
        ja("れる","る",V1,V1,"れる","potential (casual ら抜き)");
        ja("させる","る",V1,V1,"させる","causative");ja("させられる","る",V1,V1,"させられる","causative-passive");
        ja("される","する",V1,VS,"される","passive");ja("させる","する",V1,VS,"させる","causative");ja("できる","する",V1,VS,"できる","potential");
        ja("られる","くる",V1,VK,"られる","passive or potential");ja("こられる","くる",V1,VK,"られる","passive or potential");ja("こさせる","くる",V1,VK,"させる","causative");
        // Volitional, conditional, imperative
        verb("よう",O,"う","しよう","こよう",0,ANY_VERB,"う/よう","volitional “let’s / I will”");
        verb("れば",E,"ば","すれば","くれば",0,ANY_VERB,"ば","conditional “if”");
        verb("ろ",E,"","しろ","こい",0,ANY_VERB,"","imperative");
        ja("よ","る",0,V1,"よ","imperative (written)");ja("せよ","する",0,VS,"せよ","imperative (written)");
        // Desire, ease, manner (these conjugate like i-adjectives)
        verb("たい",I,"たい","したい","きたい",ADJ,ANY_VERB,"たい","want to");
        verb("やすい",I,"やすい","しやすい","きやすい",ADJ,ANY_VERB,"やすい","easy to");
        verb("にくい",I,"にくい","しにくい","きにくい",ADJ,ANY_VERB,"にくい","hard to");
        verb("すぎる",I,"すぎる","しすぎる","きすぎる",V1,ANY_VERB,"すぎる","too much");
        verb("ながら",I,"ながら","しながら","きながら",0,ANY_VERB,"ながら","while doing");
        verb("そう",I,"そう","しそう","きそう",0,ANY_VERB,"そう","looks about to");
        // i-adjectives
        ja("かった","い",0,ADJ,"かった","past");ja("くない","い",ADJ,ADJ,"くない","negative");ja("くて","い",0,ADJ,"くて","te-form");
        ja("ければ","い",0,ADJ,"ければ","conditional “if”");ja("かったら","い",0,ADJ,"かったら","conditional “if/when”");
        ja("く","い",0,ADJ,"く","adverb (～く)");ja("さ","い",0,ADJ,"さ","noun “-ness” (～さ)");ja("そう","い",0,ADJ,"そう","looks (～そう)");
        ja("すぎる","い",V1,ADJ,"すぎる","too (～すぎる)");ja("くなる","い",V5,ADJ,"くなる","become");ja("かろう","い",0,ADJ,"かろう","presumptive");
        ja("いです","い",0,ADJ,"です","polite");ja("かったです","い",0,ADJ,"かったです","polite past");ja("くありません","い",0,ADJ,"くありません","polite negative");
        ja("よくない","いい",ADJ,ADJ,"くない","negative (いい→よく)");ja("よかった","いい",0,ADJ,"かった","past (いい→よか)");ja("よくて","いい",0,ADJ,"くて","te-form (いい→よく)");
    }

    public static List<Candidate> japanese(String word){
        ArrayList<Candidate> out=new ArrayList<>();
        walk(word,0,new ArrayList<Step>(),0,out,new HashSet<String>(),0,new ArrayList<String>());
        return out;
    }

    static void walk(String word,int mask,ArrayList<Step> steps,int strength,List<Candidate> out,Set<String> seen,int depth,ArrayList<String> via){
        if(depth>=5)return;
        for(Rule r:JA){
            if(!word.endsWith(r.in))continue;
            // A rule may consume the whole word only for multi-kana bases (した→する, よかった→いい).
            if(word.length()==r.in.length()&&r.out.length()<2)continue;
            if(mask!=0&&(mask&r.rulesIn)==0)continue;
            String base=word.substring(0,word.length()-r.in.length())+r.out;
            if(base.length()<2)continue;
            ArrayList<Step> next=new ArrayList<>(steps.size()+1);
            next.add(new Step(r.suffix,r.label));next.addAll(steps);
            int s=strength+r.in.length();
            if((r.rulesOut&(V1|V5|VS|VK|ADJ))!=0&&seen.add(base+"|"+next.size()))out.add(new Candidate(base,next,s,via));
            ArrayList<String> nextVia=new ArrayList<>(via);nextVia.add(base);
            walk(base,r.rulesOut,next,s,out,seen,depth+1,nextVia);
        }
    }

    // ---------------------------------------------------------------- Korean

    static final int SBASE=0xAC00;
    static final String CHO="ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    static final String JUNG="ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    static final String JONG=" ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
    static boolean hangul(char c){return c>=0xAC00&&c<=0xD7A3;}
    static int cho(char c){return (c-SBASE)/588;}
    static int jung(char c){return ((c-SBASE)%588)/28;}
    static int jong(char c){return (c-SBASE)%28;}
    static char compose(int cho,int jung,int jong){return (char)(SBASE+cho*588+jung*28+jong);}
    static int J(char jamo){return JUNG.indexOf(jamo);}
    static int F(char jamo){return JONG.indexOf(jamo);}
    static String withFinal(String s,char finalJamo){
        char last=s.charAt(s.length()-1);
        if(!hangul(last)||jong(last)!=0)return null;
        return s.substring(0,s.length()-1)+compose(cho(last),jung(last),F(finalJamo));
    }

    /** Stem forms before 아/어 endings, with a note when an irregular rule applied. */
    static List<String[]> infinitive(String stem){
        ArrayList<String[]> out=new ArrayList<>();
        char last=stem.charAt(stem.length()-1);
        String head=stem.substring(0,stem.length()-1);
        if(!hangul(last))return out;
        int ch=cho(last),ju=jung(last),jo=jong(last);
        if(last=='하'){out.add(new String[]{head+"해",""});out.add(new String[]{head+"하여",""});return out;}
        boolean bright=ju==J('ㅏ')||ju==J('ㅗ')||ju==J('ㅑ');
        if(jo==0){
            String a=bright?"아":"어";
            out.add(new String[]{stem+a,""});
            if(ju==J('ㅏ')||ju==J('ㅓ')||ju==J('ㅐ')||ju==J('ㅔ')||ju==J('ㅕ'))out.add(new String[]{stem,""});
            if(ju==J('ㅗ'))out.add(new String[]{head+compose(ch,J('ㅘ'),0),""});
            if(ju==J('ㅜ'))out.add(new String[]{head+compose(ch,J('ㅝ'),0),""});
            if(ju==J('ㅣ'))out.add(new String[]{head+compose(ch,J('ㅕ'),0),""});
            if(ju==J('ㅚ'))out.add(new String[]{head+compose(ch,J('ㅙ'),0),""});
            if(ju==J('ㅡ')){
                // 으 drops; harmony comes from the previous syllable (바쁘 → 바빠, 쓰 → 써).
                boolean prevBright=!head.isEmpty()&&hangul(head.charAt(head.length()-1))&&(jung(head.charAt(head.length()-1))==J('ㅏ')||jung(head.charAt(head.length()-1))==J('ㅗ'));
                out.add(new String[]{head+compose(ch,J(prevBright?'ㅏ':'ㅓ'),0),""});
                if(last=='르'&&!head.isEmpty()){
                    char p=head.charAt(head.length()-1);
                    if(hangul(p)&&jong(p)==0)out.add(new String[]{head.substring(0,head.length()-1)+compose(cho(p),jung(p),F('ㄹ'))+(prevBright?"라":"러"),"르-irregular"});
                }
            }
            return out;
        }
        String a=bright?"아":"어";
        out.add(new String[]{stem+a,""});
        char open=compose(ch,ju,0);
        if(jo==F('ㅂ')){out.add(new String[]{head+open+(last=='돕'||last=='곱'?"와":"워"),"ㅂ-irregular"});}
        if(jo==F('ㄷ')){out.add(new String[]{head+compose(ch,ju,F('ㄹ'))+a,"ㄷ-irregular"});}
        if(jo==F('ㅅ')){out.add(new String[]{head+open+a,"ㅅ-irregular"});}
        if(jo==F('ㅎ')){out.add(new String[]{head+compose(ch,ju==J('ㅑ')?J('ㅒ'):J('ㅐ'),0),"ㅎ-irregular"});}
        return out;
    }
    /** Stem forms before 으-initial endings (으면, 은, 을, 으세요…). */
    static List<String[]> euStem(String stem){
        ArrayList<String[]> out=new ArrayList<>();
        char last=stem.charAt(stem.length()-1);String head=stem.substring(0,stem.length()-1);
        if(!hangul(last))return out;
        int jo=jong(last);
        if(jo==0||jo==F('ㄹ')){out.add(new String[]{stem,""});return out;}
        out.add(new String[]{stem+"으",""});
        char open=compose(cho(last),jung(last),0);
        if(jo==F('ㅂ'))out.add(new String[]{head+open+"우","ㅂ-irregular"});
        if(jo==F('ㄷ'))out.add(new String[]{head+compose(cho(last),jung(last),F('ㄹ'))+"으","ㄷ-irregular"});
        if(jo==F('ㅅ'))out.add(new String[]{head+open+"으","ㅅ-irregular"});
        if(jo==F('ㅎ'))out.add(new String[]{head+open,"ㅎ-irregular"});
        return out;
    }
    /** Attaches ㄴ/ㄹ/ㅁ/ㅂ endings that merge into the last syllable (간, 갈, 감, 갑니다). */
    static List<String[]> finalJamo(String stem,char jamo){
        ArrayList<String[]> out=new ArrayList<>();
        char last=stem.charAt(stem.length()-1);String head=stem.substring(0,stem.length()-1);
        if(!hangul(last))return out;
        int jo=jong(last);
        if(jo==F('ㄹ')){
            // ㄹ stems drop ㄹ before ㄴ/ㅂ/ㅅ (사는, 삽니다) but keep it for ㄹ/ㅁ (살, 삶).
            if(jamo=='ㄹ')out.add(new String[]{stem,""});
            else if(jamo=='ㅁ')out.add(new String[]{head+compose(cho(last),jung(last),F('ㄻ')),""});
            else out.add(new String[]{head+compose(cho(last),jung(last),F(jamo)),"ㄹ drops"});
            return out;
        }
        for(String[] e:euStem(stem)){
            String s=e[0];
            if(s.endsWith("으")){if(jamo!='ㅂ'){String w=withFinal(s,jamo);if(w!=null)out.add(new String[]{w,e[1]});}}
            else{String w=withFinal(s,jamo);if(w!=null)out.add(new String[]{w,e[1]});}
        }
        return out;
    }

    static final class Form { final String surface; final ArrayList<Step> steps; Form(String s,ArrayList<Step> st){surface=s;steps=st;} }
    static ArrayList<Step> steps(Object...pairs){ArrayList<Step> l=new ArrayList<>();for(int i=0;i+1<pairs.length;i+=2)if(pairs[i+1]!=null)l.add(new Step((String)pairs[i],(String)pairs[i+1]));return l;}

    /** Generates the common conjugated forms of a Korean verb/adjective stem. */
    static List<Form> koreanForms(String stem,boolean withAux){
        ArrayList<Form> out=new ArrayList<>();
        String[][] direct={{"다","dictionary form"},{"고","and (～고)"},{"지만","but (～지만)"},{"지","right? / negation base (～지)"},{"게","so that / -ly (～게)"},{"기","nominal (～기)"},
            {"네요","exclamation, polite (～네요)"},{"는데","background (～는데)"},{"는","adnominal present (～는)"},{"겠다","future / conjecture (～겠)"},{"겠어요","future / conjecture, polite (～겠어요)"},
            {"습니다","formal polite (～습니다)"},{"습니까","formal polite question (～습니까)"},{"자","let’s (～자)"},{"죠","right?, polite (～죠)"},{"지요","right?, polite (～지요)"},{"는다","plain present (～는다)"},{"도록","so that (～도록)"},{"거든요","you see (～거든요)"},{"잖아요","as you know (～잖아요)"},{"네","exclamation (～네)"},{"군요","exclamation (～군요)"}};
        for(String[] d:direct){
            if(d[0].equals("습니다")||d[0].equals("습니까")||d[0].equals("는다")){
                char last=stem.charAt(stem.length()-1);
                if(hangul(last)&&jong(last)!=0&&jong(last)!=F('ㄹ')){out.add(new Form(stem+d[0],steps(d[0],d[1])));}
                continue;
            }
            if((d[0].startsWith("는")||d[0].startsWith("네"))&&hangul(stem.charAt(stem.length()-1))&&jong(stem.charAt(stem.length()-1))==F('ㄹ')){
                String s=stem.substring(0,stem.length()-1)+compose(cho(stem.charAt(stem.length()-1)),jung(stem.charAt(stem.length()-1)),0);
                out.add(new Form(s+d[0],steps(d[0],d[1]+", ㄹ drops")));continue;
            }
            out.add(new Form(stem+d[0],steps(d[0],d[1])));
        }
        for(String[] f:finalJamo(stem,'ㅂ')){out.add(new Form(f[0]+"니다",steps("ㅂ니다","formal polite",null,null)));out.add(new Form(f[0]+"니까",steps("ㅂ니까","formal polite question")));out.add(new Form(f[0]+"시다",steps("ㅂ시다","let’s, formal")));}
        for(String[] f:finalJamo(stem,'ㄴ')){out.add(new Form(f[0],steps("ㄴ/은",note("adnominal past / adjective (～ㄴ)",f[1]))));out.add(new Form(f[0]+"다",steps("ㄴ다","plain present")));out.add(new Form(f[0]+"데",steps("ㄴ데","background (～ㄴ데)")));}
        for(String[] f:finalJamo(stem,'ㄹ')){out.add(new Form(f[0],steps("ㄹ/을",note("adnominal future (～ㄹ)",f[1]))));out.add(new Form(f[0]+"까요",steps("ㄹ까요","shall we? (～ㄹ까요)")));out.add(new Form(f[0]+"게요",steps("ㄹ게요","I will (～ㄹ게요)")));out.add(new Form(f[0]+"거예요",steps("ㄹ 거예요","future, polite")));out.add(new Form(f[0]+"수있다",steps("ㄹ 수 있다","can")));out.add(new Form(f[0]+"수있어요",steps("ㄹ 수 있어요","can, polite")));out.add(new Form(f[0]+"수없어요",steps("ㄹ 수 없어요","cannot, polite")));out.add(new Form(f[0]+"때",steps("ㄹ 때","when")));}
        for(String[] f:finalJamo(stem,'ㅁ'))out.add(new Form(f[0],steps("ㅁ/음",note("nominal (～ㅁ)",f[1]))));
        for(String[] e:euStem(stem)){
            String b=e[0];String n=e[1];
            // ㄹ stems lose the ㄹ before ㄴ and ㅅ (살다 → 사니까, 사세요; 알다 → 아세요) but keep it before 면, 려고, 러, 며.
            String bn=b,nn=n;
            char lb=b.charAt(b.length()-1);
            if(b.equals(stem)&&hangul(lb)&&jong(lb)==F('ㄹ')){bn=b.substring(0,b.length()-1)+compose(cho(lb),jung(lb),0);nn=note("ㄹ drops",n);}
            out.add(new Form(b+"면",steps("으면",note("if (～면)",n))));out.add(new Form(bn+"니까",steps("으니까",note("because (～니까)",nn))));
            out.add(new Form(bn+"세요",steps("으세요",note("honorific polite / please (～세요)",nn))));out.add(new Form(bn+"셨어요",steps("으시+었+어요",note("honorific past polite",nn))));
            out.add(new Form(bn+"십니다",steps("으십니다",note("honorific formal",nn))));out.add(new Form(bn+"십시오",steps("으십시오",note("formal imperative",nn))));out.add(new Form(bn+"시다",steps("으시다",note("honorific",nn))));
            out.add(new Form(b+"려고",steps("으려고",note("in order to (～려고)",n))));out.add(new Form(b+"러",steps("으러",note("to go/come to (～러)",n))));out.add(new Form(b+"며",steps("으며",note("while / and (～며)",n))));
        }
        for(String[] f:infinitive(stem)){
            String b=f[0];String n=f[1];
            out.add(new Form(b,steps("아/어",note("informal (반말)",n))));out.add(new Form(b+"요",steps("아요/어요",note("polite",n))));
            out.add(new Form(b+"서",steps("아서/어서",note("because / and then",n))));out.add(new Form(b+"도",steps("아도/어도",note("even if",n))));
            out.add(new Form(b+"야",steps("아야/어야",note("must / only if",n))));out.add(new Form(b+"라",steps("아라/어라",note("plain imperative",n))));
            out.add(new Form(b+"야해요",steps("아야/어야 해요",note("have to, polite",n))));out.add(new Form(b+"주세요",steps("아/어 주세요",note("please do (for me)",n))));
            out.add(new Form(b+"봐요",steps("아/어 봐요",note("try doing, polite",n))));out.add(new Form(b+"있어요",steps("아/어 있어요",note("resulting state, polite",n))));
            String past=withFinal(b,'ㅆ');
            if(past==null)continue;
            String[][] tails={{"다","plain"},{"어","informal"},{"어요","polite"},{"습니다","formal polite"},{"습니까","formal question"},{"는데","background"},{"지만","but"},{"고","and"},{"으면","if"},{"을","adnominal"},{"네요","exclamation"},{"겠다","conjecture"},{"어서","because / and then"},{"던","recollective (～던)"}};
            for(String[] t:tails)out.add(new Form(past+t[0],steps("았/었",note("past",n),t[0],t[1])));
        }
        if(withAux){
            // Auxiliary constructions: stem + connector + another verb conjugated normally.
            String[][] aux={{"고있","고 있다","progressive (～고 있다)"},{"지않","지 않다","negation (～지 않다)"},{"고싶","고 싶다","want to (～고 싶다)"},{"지못하","지 못하다","cannot (～지 못하다)"},{"기시작하","기 시작하다","start to"},{"게되","게 되다","come to / end up"}};
            for(String[] a:aux){
                String auxStem=a[0].replaceFirst("^(고|지|기|게)","");
                String connector=a[0].substring(0,a[0].length()-auxStem.length());
                for(Form f:koreanForms(auxStem,false)){
                    ArrayList<Step> st=new ArrayList<>();st.add(new Step(a[1],a[2]));
                    if(!f.steps.isEmpty()&&!f.steps.get(0).label.equals("dictionary form"))st.addAll(f.steps);
                    out.add(new Form(stem+connector+f.surface,st));
                }
            }
            for(String[] f:infinitive(stem)){
                // 안 negation prefix is handled by the caller; here: -아/어 버리다, -아/어 보다 (dictionary forms)
                out.add(new Form(f[0]+"버렸어요",steps("아/어 버렸어요",note("completely (regret), past polite",f[1]))));
                out.add(new Form(f[0]+"봤어요",steps("아/어 봤어요",note("tried doing, past polite",f[1]))));
                out.add(new Form(f[0]+"줬어요",steps("아/어 줬어요",note("did for someone, past polite",f[1]))));
            }
        }
        return out;
    }
    static String note(String label,String irregular){return irregular==null||irregular.isEmpty()?label:label+", "+irregular;}

    /**
     * Korean candidates are generated forwards: for each plausible stem (checked by the caller) all common forms are built
     * and compared with the input, so irregular verbs (추워요, 들어요, 몰라요, 해요) resolve exactly.
     */
    public static List<Candidate> korean(String word,java.util.function.Predicate<String> isHeadword,java.util.function.Function<String,List<String>> stemsLike){
        ArrayList<Candidate> out=new ArrayList<>();
        String w=word.replace(" ","");
        if(w.isEmpty()||!hangul(w.charAt(0)))return out;
        LinkedHashMap<String,Candidate> found=new LinkedHashMap<>();
        boolean negated=w.startsWith("안")&&w.length()>2;
        for(int pass=0;pass<(negated?2:1);pass++){
            String target=pass==0?w:w.substring(1);
            for(String headword:stemsLike.apply(target)){
                if(!headword.endsWith("다")||headword.length()<2)continue;
                String stem=headword.substring(0,headword.length()-1);
                for(Form f:koreanForms(stem,true)){
                    if(!f.surface.equals(target)||f.surface.equals(headword))continue;
                    ArrayList<Step> st=new ArrayList<>();
                    if(pass==1)st.add(new Step("안","negation (안)"));
                    st.addAll(f.steps);
                    found.putIfAbsent(headword,new Candidate(headword,st,target.length()));
                    break;
                }
            }
        }
        // Noun + 하다 verbs whose dictionary only lists the noun (공부했어요 → 공부 + 하다).
        if(found.isEmpty()){
            for(int cut=w.length()-1;cut>=1;cut--){
                char next=w.charAt(cut);
                if(!hangul(next)||cho(next)!=CHO.indexOf('ㅎ'))continue;
                String noun=w.substring(0,cut);
                if(!isHeadword.test(noun))continue;
                for(Form f:koreanForms(noun+"하",true)){
                    if(!f.surface.equals(w))continue;
                    ArrayList<Step> st=new ArrayList<>();st.add(new Step("하다","하다 verb"));st.addAll(f.steps);
                    found.put(noun,new Candidate(noun,st,w.length()-cut));
                    break;
                }
                if(!found.isEmpty())break;
            }
        }
        boolean verbFound=!found.isEmpty();
        out.addAll(found.values());
        // Nouns with particles: 학교에서 → 학교 + 에서.
        String[][] particles={{"에서","at / from (에서)"},{"에게","to (에게)"},{"한테","to (한테)"},{"께서","subject, honorific (께서)"},{"으로","by / toward (으로)"},{"로","by / toward (로)"},{"부터","from (부터)"},{"까지","until (까지)"},{"보다","than (보다)"},{"처럼","like (처럼)"},{"하고","and / with (하고)"},{"이랑","and / with (이랑)"},{"랑","and / with (랑)"},
            {"이에요","is (이에요)"},{"예요","is (예요)"},{"입니다","is, formal (입니다)"},{"이다","is (이다)"},{"이","subject (이)"},{"가","subject (가)"},{"은","topic (은)"},{"는","topic (는)"},{"을","object (을)"},{"를","object (를)"},{"에","to / at (에)"},{"의","possessive (의)"},{"도","also (도)"},{"만","only (만)"},{"와","and (와)"},{"과","and (과)"},{"들","plural (들)"}};
        for(String[] p:particles){
            if(w.length()>p[0].length()&&w.endsWith(p[0])){
                String base=w.substring(0,w.length()-p[0].length());
                if(verbFound&&base.length()<2)continue;
                if(!found.containsKey(base)&&isHeadword.test(base)){
                    ArrayList<Step> st=new ArrayList<>();st.add(new Step("","noun + particle: "+p[1]));
                    out.add(new Candidate(base,st,p[0].length()));
                    found.put(base,null);
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Russian

    static final String[][] RU={
        // verbs: present/future
        {"ю","ть","1st person singular (я)"},{"у","ть","1st person singular (я)"},{"ешь","ть","2nd person singular (ты)"},{"ёшь","ть","2nd person singular (ты)"},{"ет","ть","3rd person singular (он/она)"},{"ёт","ть","3rd person singular (он/она)"},
        {"ем","ть","1st person plural (мы)"},{"ём","ть","1st person plural (мы)"},{"ете","ть","2nd person plural (вы)"},{"ёте","ть","2nd person plural (вы)"},{"ют","ть","3rd person plural (они)"},{"ут","ть","3rd person plural (они)"},
        {"ю","ить","1st person singular (я)"},{"у","ить","1st person singular (я)"},{"лю","ить","1st person singular (я), л inserted"},{"ишь","ить","2nd person singular (ты)"},{"ит","ить","3rd person singular (он/она)"},{"им","ить","1st person plural (мы)"},{"ите","ить","2nd person plural (вы) / imperative"},{"ят","ить","3rd person plural (они)"},{"ат","ить","3rd person plural (они)"},
        {"ю","еть","1st person singular (я)"},{"лю","еть","1st person singular (я), л inserted"},{"ишь","еть","2nd person singular (ты)"},{"ит","еть","3rd person singular (он/она)"},{"им","еть","1st person plural (мы)"},{"ите","еть","2nd person plural (вы)"},{"ят","еть","3rd person plural (они)"},{"ат","ать","3rd person plural (они)"},{"ишь","ать","2nd person singular (ты)"},{"ит","ать","3rd person singular (он/она)"},
        // verbs: past
        {"л","ть","past, masculine"},{"ла","ть","past, feminine"},{"ло","ть","past, neuter"},{"ли","ть","past, plural"},
        // verbs: imperative
        {"й","ть","imperative"},{"йте","ть","imperative, polite/plural"},{"и","ить","imperative"},{"ите","ить","imperative, polite/plural"},{"ь","ить","imperative"},{"ьте","ить","imperative, polite/plural"},
        // verbs: gerunds and participles
        {"я","ть","imperfective gerund (-я)"},{"в","ть","perfective gerund (-в)"},{"вши","ть","perfective gerund (-вши)"},{"ющий","ть","present active participle"},{"ющая","ть","present active participle"},{"ющее","ть","present active participle"},{"ющие","ть","present active participle"},{"вший","ть","past active participle"},{"вшая","ть","past active participle"},{"нный","ть","past passive participle"},{"емый","ть","present passive participle"},
        // adjectives
        {"ого","ый","masculine/neuter genitive"},{"ому","ый","masculine/neuter dative"},{"ым","ый","masculine/neuter instrumental, or dative plural"},{"ом","ый","masculine/neuter prepositional"},{"ая","ый","feminine nominative"},{"ую","ый","feminine accusative"},{"ой","ый","feminine genitive/dative/instrumental/prepositional"},{"ое","ый","neuter nominative"},{"ые","ый","plural nominative"},{"ых","ый","plural genitive/prepositional"},{"ыми","ый","plural instrumental"},
        {"ого","ой","masculine/neuter genitive"},{"ому","ой","masculine/neuter dative"},{"ая","ой","feminine nominative"},{"ую","ой","feminine accusative"},{"ое","ой","neuter nominative"},{"ые","ой","plural nominative"},{"ых","ой","plural genitive/prepositional"},{"ым","ой","instrumental"},
        {"его","ий","masculine/neuter genitive"},{"ему","ий","masculine/neuter dative"},{"им","ий","masculine/neuter instrumental"},{"ем","ий","masculine/neuter prepositional"},{"яя","ий","feminine nominative"},{"юю","ий","feminine accusative"},{"ей","ий","feminine oblique case"},{"ее","ий","neuter nominative"},{"ие","ий","plural nominative"},{"их","ий","plural genitive/prepositional"},{"ими","ий","plural instrumental"},
        {"ая","ий","feminine nominative"},{"ую","ий","feminine accusative"},{"ое","ий","neuter nominative"},{"ого","ий","masculine/neuter genitive"},
        {"","ый","short form, masculine"},{"а","ый","short form, feminine"},{"о","ый","short form, neuter / adverb"},{"ы","ый","short form, plural"},{"о","ий","adverb"},{"ее","ый","comparative (-ее)"},{"ее","ий","comparative (-ее)"},{"ейший","ый","superlative (-ейший)"},
        // nouns
        {"а","","genitive singular"},{"у","","dative singular"},{"ом","","instrumental singular"},{"е","","prepositional singular"},{"ы","","nominative plural"},{"и","","nominative plural"},{"ов","","genitive plural"},{"ев","","genitive plural"},{"ей","","genitive plural"},{"ам","","dative plural"},{"ами","","instrumental plural"},{"ах","","prepositional plural"},{"ем","","instrumental singular"},
        {"ы","а","genitive singular / nominative plural"},{"и","а","genitive singular / nominative plural"},{"е","а","dative/prepositional singular"},{"у","а","accusative singular"},{"ой","а","instrumental singular"},{"ою","а","instrumental singular"},{"ам","а","dative plural"},{"ами","а","instrumental plural"},{"ах","а","prepositional plural"},
        {"и","я","genitive singular / nominative plural"},{"е","я","dative/prepositional singular"},{"ю","я","accusative singular"},{"ей","я","instrumental singular / genitive plural"},{"ям","я","dative plural"},{"ями","я","instrumental plural"},{"ях","я","prepositional plural"},{"ь","я","genitive plural"},
        {"а","о","genitive singular / nominative plural"},{"у","о","dative singular"},{"ом","о","instrumental singular"},{"е","о","prepositional singular"},{"ам","о","dative plural"},{"ами","о","instrumental plural"},{"ах","о","prepositional plural"},
        {"я","е","genitive singular"},{"ю","е","dative singular"},{"ем","е","instrumental singular"},{"и","е","prepositional singular"},
        {"и","ь","genitive/dative/prepositional singular, or plural"},{"я","ь","genitive singular"},{"ю","ь","dative singular"},{"ем","ь","instrumental singular"},{"ью","ь","instrumental singular (feminine)"},{"ей","ь","genitive plural"},{"ям","ь","dative plural"},{"ями","ь","instrumental plural"},{"ях","ь","prepositional plural"},
        {"ия","ие","genitive singular"},{"ию","ие","dative singular"},{"ии","ие","prepositional singular"},{"ии","ия","genitive/dative/prepositional singular"},{"ию","ия","accusative singular"},{"ией","ия","instrumental singular"},
    };
    static final List<String[]> RU_ALL=new ArrayList<>();
    static{
        for(String[] r:RU)RU_ALL.add(r);
        // Consonant alternation in the present tense: пишу → писать, хожу → ходить, плачу → платить, прошу → просить.
        String[][] mutations={{"ш","с"},{"ж","з"},{"ж","д"},{"ж","г"},{"ч","т"},{"ч","к"},{"щ","ст"},{"щ","ск"}};
        String[][] firstConj={{"у","1st person singular (я)"},{"ешь","2nd person singular (ты)"},{"ет","3rd person singular (он/она)"},{"ем","1st person plural (мы)"},{"ете","2nd person plural (вы)"},{"ут","3rd person plural (они)"},{"и","imperative"},{"ите","imperative, polite/plural"}};
        for(String[] m:mutations){
            for(String[] e:firstConj){RU_ALL.add(new String[]{m[0]+e[0],m[1]+"ать",e[1]+", "+m[1]+"→"+m[0]});}
            RU_ALL.add(new String[]{m[0]+"у",m[1]+"ить","1st person singular (я), "+m[1]+"→"+m[0]});
            RU_ALL.add(new String[]{m[0]+"у",m[1]+"еть","1st person singular (я), "+m[1]+"→"+m[0]});
        }
    }
    public static List<Candidate> russian(String word){
        ArrayList<Candidate> out=new ArrayList<>();
        String w=word.toLowerCase(java.util.Locale.ROOT).replace('ё','е');
        if(w.isEmpty()||!(w.charAt(0)>='а'&&w.charAt(0)<='я'))return out;
        // Reflexive verbs: strip ся/сь, then conjugate, then restore ся.
        String[] reflexive=w.endsWith("ся")||w.endsWith("сь")?new String[]{w,w.substring(0,w.length()-2)}:new String[]{w};
        for(int r=0;r<reflexive.length;r++){
            String x=reflexive[r];
            for(String[] rule:RU_ALL){
                if(!x.endsWith(rule[0])||x.length()-rule[0].length()<2)continue;
                String base=x.substring(0,x.length()-rule[0].length())+rule[1];
                if(r==1)base+="ся";
                if(base.equals(w))continue;
                ArrayList<Step> st=new ArrayList<>();
                st.add(new Step(rule[0].isEmpty()?"":"-"+rule[0],rule[2]));
                if(r==1)st.add(new Step("-ся","reflexive"));
                out.add(new Candidate(base,st,rule[0].length()));
            }
        }
        return out;
    }
}
