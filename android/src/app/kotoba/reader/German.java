package app.kotoba.reader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The parts of reading German that need no dictionary data: the word at the start of a text, the spellings behind a
 * word typed without umlauts, the particles that split off a separable verb, and the pieces of a compound.
 * (Inflected forms come from the dictionary's own form index; see Library.germanAnalyses.) Pure Java for desktop tests.
 */
public final class German {
    private German(){}

    static boolean letter(int c){return Character.isLetter(c)&&c<0x250;}
    static boolean apostrophe(int c){return c=='\''||c==0x2019;}

    /** True when the text starts with a Latin letter (German, English… the caller decides which). */
    public static boolean startsLatin(String text){
        for(int i=0;i<text.length();){
            int c=text.codePointAt(i);i+=Character.charCount(c);
            if(Character.isWhitespace(c)||c=='"'||c=='('||c=='«'||c=='»'||c==0x201e||c==0x201c||c==0x2018||c=='–'||c=='—'||c=='-')continue;
            return letter(c);
        }
        return false;
    }

    /** The first word: letters, with inner hyphens and apostrophes (E-Mail-Adresse, geht's) but no trailing ones. */
    public static String firstWord(String text){
        int i=0,n=text.length();
        while(i<n&&!letter(text.codePointAt(i)))i+=Character.charCount(text.codePointAt(i));
        int start=i,end=i;
        while(i<n){
            int c=text.codePointAt(i);
            if(letter(c)){i+=Character.charCount(c);end=i;}
            else if((c=='-'||apostrophe(c))&&i+1<n&&letter(text.codePointAt(i+1))&&i>start){i++;}
            else break;
        }
        return text.substring(start,end);
    }

    /** The first few words, for phrases (zum Beispiel) and for finding a separable verb's particle later in the clause. */
    public static List<String> words(String text,int max){
        ArrayList<String> out=new ArrayList<>();
        int i=0,n=text.length();
        while(i<n&&out.size()<max){
            while(i<n&&!letter(text.codePointAt(i))){
                int c=text.codePointAt(i);
                // A clause ends at sentence punctuation: a particle past it belongs to another verb.
                if(!out.isEmpty()&&(c=='.'||c=='!'||c=='?'||c==';'||c==':'||c=='\n'||c==0x2026))return out;
                i+=Character.charCount(c);
            }
            if(i>=n)break;
            String w=firstWord(text.substring(i));
            if(w.isEmpty())break;
            out.add(w);i+=w.length();
        }
        return out;
    }

    /**
     * Spellings a word typed on a keyboard without umlauts may stand for: fuer → für, strasse → straße, gross → groß,
     * schoen → schön. Every combination of the places where ae/oe/ue/ss occur, the plainest first, the word itself excluded.
     */
    public static List<String> respellings(String word){
        String w=word.toLowerCase(java.util.Locale.ROOT);
        LinkedHashSet<String> out=new LinkedHashSet<>();
        out.add(w);
        String[][] swaps={{"ae","ä"},{"oe","ö"},{"ue","ü"},{"ss","ß"}};
        for(String[] s:swaps){
            ArrayList<String> next=new ArrayList<>(out);
            for(String base:out){
                for(int at=base.indexOf(s[0]);at>=0&&next.size()<48;at=base.indexOf(s[0],at+1)){
                    next.add(base.substring(0,at)+s[1]+base.substring(at+2));
                }
            }
            out.addAll(next);
        }
        out.remove(w);
        return new ArrayList<>(out);
    }

    /** Particles that part from a separable verb (stehe … auf), and so begin its dictionary form (aufstehen). */
    public static final Set<String> PARTICLES=new LinkedHashSet<>(java.util.Arrays.asList(
        "ab","an","auf","aus","bei","dar","ein","empor","entgegen","fest","fort","her","herab","heran","herauf","heraus","herbei",
        "herein","herüber","herum","herunter","hervor","herzu","hin","hinab","hinauf","hinaus","hinein","hinüber","hinunter",
        "hinweg","hinzu","los","mit","nach","nieder","um","voran","voraus","vorbei","vor","vorüber","vorweg","weg","weiter",
        "wieder","zu","zurecht","zurück","zusammen","durch","über","unter","hinterher","umher","zugrunde","teil","statt","bereit",
        "frei","fern","gegenüber","heim","kennen","kaputt","wahr","stand","spazieren","sicher"));

    /** Verb forms a particle can split from: present, preterite, imperative and subjunctive (not the participle or infinitive). */
    public static boolean splits(String label){
        String l=label.toLowerCase(java.util.Locale.ROOT);
        return (l.contains("present")||l.contains("preterite")||l.contains("imperative")||l.contains("subjunctive"))
            &&!l.contains("participle")&&!l.contains("zu-infinitive");
    }

    /** Words that open a new clause: a particle right before one of them still closes the clause before it. */
    static final Set<String> CLAUSE_OPENERS=new LinkedHashSet<>(java.util.Arrays.asList(
        "und","oder","aber","denn","sondern","weil","dass","damit","obwohl","während","bevor","nachdem","sodass","doch","bis",
        "wenn","als","ob","da","falls","sobald","solange","indem","sowie","dann","also"));

    /**
     * The particles of a separable verb that might close its clause: in "stehe um sieben auf" (the text after
     * stehe) → [auf]. A particle counts only at the end of its clause (before punctuation, the end of the text or
     * a conjunction), so "gehe heute auf den Markt" isn't aufgehen. Stops at the first comma or sentence end.
     */
    public static List<String> separableParticles(String after){
        ArrayList<String> out=new ArrayList<>();
        int i=0,n=after.length();
        while(i<n){
            int c=after.codePointAt(i);
            if(!letter(c)){
                if(c==','||c=='.'||c=='!'||c=='?'||c==';'||c==':'||c=='\n'||c==0x2026||c=='—'||c=='–')break;
                i+=Character.charCount(c);continue;
            }
            String w=firstWord(after.substring(i));
            if(w.isEmpty())break;
            int end=i+w.length();
            String lw=w.toLowerCase(java.util.Locale.ROOT);
            if(PARTICLES.contains(lw)){
                int j=end;
                while(j<n&&(after.charAt(j)==' '||after.charAt(j)==' '))j++;
                boolean closes=j>=n;
                if(!closes){
                    int d=after.codePointAt(j);
                    if(!letter(d))closes=true;
                    else closes=CLAUSE_OPENERS.contains(firstWord(after.substring(j)).toLowerCase(java.util.Locale.ROOT));
                }
                if(closes)out.add(lw);
            }
            i=end;
        }
        return out;
    }

    static final String[] LINKS={"ens","ns","es","en","er","s","n","e"};

    /**
     * The pieces of a compound (Arbeitszimmer → arbeits + zimmer), each a word or an inflected form of one, with the
     * linking s/es/n/en/er/e taken off or put on where a piece alone isn't a word. Fewest pieces first, then the longest
     * last piece (the head decides what the compound is). Null when it doesn't split into 2–4 pieces of 3+ letters.
     * `known` tells whether a lower-case string is a headword or a form.
     */
    public static List<String> split(String word,Predicate<String> known){
        String w=word.toLowerCase(java.util.Locale.ROOT);
        if(w.length()<6||w.length()>40)return null;
        Map<String,Boolean> memo=new HashMap<>();
        Predicate<String> k=s->memo.computeIfAbsent(s,known::test);
        List<String> best=pieces(w,0,4,k,new HashMap<>());
        return best==null||best.size()<2?null:best;
    }

    /** A piece's lemma-ish spelling when it is acceptable as a non-final part, else null. */
    static String modifier(String piece,Predicate<String> known){
        if(known.test(piece))return piece;
        for(String link:LINKS){
            if(piece.length()-link.length()>=3&&piece.endsWith(link)){
                String stem=piece.substring(0,piece.length()-link.length());
                if(known.test(stem))return stem;
                // Straßenbahn: stem + n; Haustür/Erdbeere: stem + e
            }
        }
        if(known.test(piece+"e"))return piece+"e";
        return null;
    }

    static List<String> pieces(String w,int from,int left,Predicate<String> known,Map<Integer,List<String>> memo){
        int n=w.length();
        if(left==0)return null;
        if(memo.containsKey(from*8+left))return memo.get(from*8+left);
        List<String> best=null;
        // The whole rest as the last piece.
        String rest=w.substring(from);
        if(rest.length()>=3&&known.test(rest))best=java.util.Collections.singletonList(rest);
        if(best==null||left>1){
            for(int j=from+3;j<=n-3;j++){
                String piece=w.substring(from,j);
                if(modifier(piece,known)==null)continue;
                List<String> tail=pieces(w,j,left-1,known,memo);
                if(tail==null)continue;
                ArrayList<String> cand=new ArrayList<>();cand.add(piece);cand.addAll(tail);
                if(best==null||better(cand,best))best=cand;
            }
        }
        memo.put(from*8+left,best);
        return best;
    }

    static boolean better(List<String> a,List<String> b){
        if(a.size()!=b.size())return a.size()<b.size();
        String la=a.get(a.size()-1),lb=b.get(b.size()-1);
        if(la.length()!=lb.length())return la.length()>lb.length();
        return a.get(0).length()>b.get(0).length();
    }

    /** The word a piece of a compound stands for, for display: arbeits → arbeit. */
    public static String stem(String piece,Predicate<String> known){
        String m=modifier(piece,known);
        return m==null?piece:m;
    }

    /** Readable compound: Arbeit(s)·zimmer from pieces arbeits, zimmer. */
    public static String show(List<String> pieces){
        StringBuilder b=new StringBuilder();
        for(int i=0;i<pieces.size();i++){if(i>0)b.append(" + ");b.append(pieces.get(i));}
        return b.toString();
    }
}
