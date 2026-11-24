package dev.local.launcherdoubletap;

/** Main-thread-only state machine; independent of Android for testing. */
final class TapTracker {
    private final long maxTap, minGap, maxGap;
    private final float slop2, doubleSlop2;
    private boolean down, previous;
    private long started, lastUp;
    private float x0, y0, lastX, lastY;
    private Object page, lastPage;
    TapTracker(long maxTap, long minGap, long maxGap, float slop, float doubleSlop) {
        this.maxTap=maxTap; this.minGap=minGap; this.maxGap=maxGap;
        slop2=slop*slop; doubleSlop2=doubleSlop*doubleSlop;
    }
    void start(long t, float x, float y, Object p) {
        down = p != null; page=p; started=t; x0=x; y0=y;
        long gap=t-lastUp;
        if (!down || !previous || lastPage!=p || gap<minGap || gap>maxGap
                || distance2(x,y,lastX,lastY)>doubleSlop2) clearPrevious();
    }
    void move(float x,float y) { if (down && distance2(x,y,x0,y0)>slop2) reset(); }
    boolean finish(long t,float x,float y,Object p) {
        if (!down || p!=page || t-started<0 || t-started>maxTap
                || distance2(x,y,x0,y0)>slop2) { reset(); return false; }
        boolean result=previous;
        down=false; page=null;
        if (result) clearPrevious();
        else { previous=true; lastUp=t; lastX=x; lastY=y; lastPage=p; }
        return result;
    }
    boolean isDown() { return down; }
    void reset() { down=false; page=null; clearPrevious(); }
    private void clearPrevious() { previous=false; lastPage=null; }
    private static float distance2(float x,float y,float a,float b) {
        float dx=x-a,dy=y-b; return dx*dx+dy*dy;
    }
}
