package app.kotoba.reader;

/**
 * FSRS-5 scheduler (default parameters) with Anki-style learning and relearning steps.
 * States: 0 new, 1 learning, 2 review, 3 relearning. Ratings: 1 Again, 2 Hard, 3 Good, 4 Easy.
 */
public final class Fsrs {
    static final double[] W={0.40255,1.18385,3.173,15.69105,7.1949,0.5345,1.4604,0.0046,1.54575,0.1192,1.01925,1.9395,0.11,0.29605,2.2698,0.2315,2.9898,0.51655,0.6621};
    static final double DECAY=-0.5, FACTOR=19.0/81.0;
    static final long[] LEARN_STEPS={60,600};
    static final long[] RELEARN_STEPS={600};
    static final long FIRST_DAYS=1;
    static final double FIRST_STABILITY=1.0;
    static final long EASY_FIRST_DAYS=4;  // Easy on a new card: a few days, not weeks (it's only roughly known)
    static final double EASY_FIRST_STABILITY=4.0;  // what a day's interval means for recall, so the next Good is about four days

    public static final class Card {
        public int state, step, reps, lapses;
        public double stability, difficulty;
        public long due, lastReview;
        public long interval; // seconds until due, for display
        public Card copy(){Card c=new Card();c.state=state;c.step=step;c.reps=reps;c.lapses=lapses;c.stability=stability;c.difficulty=difficulty;c.due=due;c.lastReview=lastReview;c.interval=interval;return c;}
    }

    final double retention;
    final long maximumDays;
    public Fsrs(double retention,long maximumDays){this.retention=Math.max(0.7,Math.min(0.99,retention));this.maximumDays=maximumDays;}

    static double clampD(double d){return Math.max(1,Math.min(10,d));}
    static double initialStability(int g){return Math.max(0.1,W[g-1]);}
    static double initialDifficulty(int g){return clampD(W[4]-Math.exp(W[5]*(g-1))+1);}
    static double retrievability(double days,double s){return Math.pow(1+FACTOR*days/s,DECAY);}
    static double nextDifficulty(double d,int g){
        double delta=-W[6]*(g-3);
        double next=d+delta*(10-d)/9;
        return clampD(W[7]*initialDifficulty(4)+(1-W[7])*next);
    }
    static double recallStability(double d,double s,double r,int g){
        double hard=g==2?W[15]:1,easy=g==4?W[16]:1;
        return s*(1+Math.exp(W[8])*(11-d)*Math.pow(s,-W[9])*(Math.exp(W[10]*(1-r))-1)*hard*easy);
    }
    static double forgetStability(double d,double s,double r){
        double f=W[11]*Math.pow(d,-W[12])*(Math.pow(s+1,W[13])-1)*Math.exp(W[14]*(1-r));
        return Math.min(f,s/Math.exp(W[17]*W[18]));
    }
    static double shortTermStability(double s,int g){return s*Math.exp(W[17]*(g-3+W[18]));}

    long intervalDays(double s){
        double days=s/FACTOR*(Math.pow(retention,1/DECAY)-1);
        return Math.max(1,Math.min(maximumDays,Math.round(days)));
    }

    /** Returns the card after answering; the input is not modified. */
    public Card answer(Card input,int g,long now){
        if(g<1||g>4)throw new IllegalArgumentException("Rating must be 1–4");
        Card c=input.copy();
        double elapsed=c.lastReview>0?Math.max(0,(now-c.lastReview)/86400.0):0;
        if(c.state==0){
            c.stability=initialStability(g);c.difficulty=initialDifficulty(g);
            c.state=1;c.step=0;
            learning(c,g,now,LEARN_STEPS);
        }else if(c.state==1||c.state==3){
            c.difficulty=nextDifficulty(c.difficulty,g);
            c.stability=Math.max(0.1,shortTermStability(c.stability,g));
            learning(c,g,now,c.state==1?LEARN_STEPS:RELEARN_STEPS);
        }else{
            double r=retrievability(elapsed,c.stability);
            double d=c.difficulty;
            c.difficulty=nextDifficulty(d,g);
            if(g==1){
                c.stability=Math.max(0.1,elapsed<1?shortTermStability(c.stability,g):forgetStability(d,c.stability,r));
                c.lapses++;c.state=3;c.step=0;
                c.interval=RELEARN_STEPS[0];
            }else{
                if(elapsed<1){
                    c.stability=Math.max(c.stability,shortTermStability(c.stability,g));
                }else{
                    c.stability=recallStability(d,c.stability,r,g);
                }
                long hard=intervalDays(g==2?c.stability:recallOrShort(input,d,r,elapsed,2));
                long good=intervalDays(g==3?c.stability:recallOrShort(input,d,r,elapsed,3));
                long easy=intervalDays(g==4?c.stability:recallOrShort(input,d,r,elapsed,4));
                hard=Math.min(hard,good);good=Math.max(good,hard+1);easy=Math.max(easy,good+1);
                long days=g==2?hard:g==3?good:easy;
                c.interval=Math.min(maximumDays,days)*86400;
            }
        }
        c.reps++;
        c.lastReview=now;
        c.due=now+c.interval;
        return c;
    }

    double recallOrShort(Card input,double d,double r,double elapsed,int g){
        return elapsed<1?Math.max(input.stability,shortTermStability(input.stability,g)):recallStability(d,input.stability,r,g);
    }

    void learning(Card c,int g,long now,long[] steps){
        if(g==1){c.step=0;c.interval=steps[0];return;}
        if(g==2){
            long delay=c.step==0&&steps.length>1?(steps[0]+steps[1])/2:steps[Math.min(c.step,steps.length-1)];
            c.interval=delay;return;
        }
        if(g==3&&c.step+1<steps.length){c.step++;c.interval=steps[c.step];return;}
        // Graduate. A new card's first interval with Good is a day (then about four), not the several days FSRS would give.
        long days=intervalDays(c.stability);
        if(g!=4&&c.state==1){days=FIRST_DAYS;c.stability=Math.min(c.stability,FIRST_STABILITY);}
        else if(g==4&&c.state==1){days=EASY_FIRST_DAYS;c.stability=Math.min(c.stability,EASY_FIRST_STABILITY);}
        else if(g==4)days=Math.max(days,Math.max(2,intervalDays(c.stability)));
        c.state=2;c.step=0;c.interval=days*86400;
    }
}
