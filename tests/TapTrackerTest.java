package dev.local.launcherdoubletap;
final class TapTrackerTest {
    static final Object PAGE=new Object(), OTHER=new Object();
    static TapTracker t(){return new TapTracker(220,40,300,10,60);}
    static void check(boolean b){if(!b)throw new AssertionError();}
    static boolean tap(TapTracker t,long start,float x,Object page){t.start(start,x,0,page);return t.finish(start+50,x,0,page);}
    public static void main(String[] args){
        TapTracker t=t();check(!tap(t,100,0,PAGE));check(tap(t,250,3,PAGE));check(!tap(t,400,3,PAGE));
        t=t();check(!tap(t,100,0,PAGE));check(!tap(t,500,0,PAGE));
        t=t();check(!tap(t,100,0,PAGE));check(!tap(t,250,100,PAGE));
        t=t();check(!tap(t,100,0,PAGE));check(!tap(t,250,0,OTHER));
        t=t();check(!tap(t,100,0,PAGE));t.start(250,0,0,null);check(!t.finish(300,0,0,null));check(!tap(t,350,0,PAGE));
        t=t();check(!tap(t,100,0,PAGE));t.start(250,0,0,PAGE);t.move(20,0);check(!t.finish(300,0,0,PAGE));
        t=t();t.start(100,0,0,PAGE);check(!t.finish(400,0,0,PAGE));check(!tap(t,500,0,PAGE));
        t=t();check(!tap(t,100,0,PAGE));t.reset();check(!tap(t,250,0,PAGE));
        t=t();check(!tap(t,100,0,PAGE));check(!tap(t,170,0,PAGE));
        System.out.println("TapTracker: all 9 scenarios passed");
    }
}
