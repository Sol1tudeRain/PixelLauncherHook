package dev.local.launcherdoubletap;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.InputEvent;
import android.os.Looper;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LauncherHook implements IXposedHookLoadPackage {
    private static final String TARGET="com.google.android.apps.nexuslauncher";
    private static final String TAG="[LauncherDoubleTap] ";
    private final WeakHashMap<Activity,TapTracker> trackers=new WeakHashMap<>();
    private final WeakHashMap<Activity,Long> lastResumeTimes=new WeakHashMap<>();
    private static final long HOME_RESUME_GRACE_MS=1200;
    // Short-lived pre-focus input handoff while Quickstep returns to home.
    private static final long EARLY_INPUT_WINDOW_MS=2000;
    private Activity resumedLauncher;
    private long monitoredDownTime=-1, monitoredEventTime=-1;
    private int monitoredAction=-1;
    private final AtomicBoolean sleeping=new AtomicBoolean();
    private boolean loggedFailure;
    private boolean loggedWorkspaceFailure;
    private final HashSet<String> gestureTraceOnce=new HashSet<>();
    private Class<?> launcherClass, workspaceClass, cellClass, containerClass, floatingClass;
    private Object normalState;
    private Method topOpen;
    private final Rect rect=new Rect();
    private final int[] screen=new int[2];

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!TARGET.equals(p.packageName) || !TARGET.equals(p.processName)) return;
        try { LauncherSettingsHook.install(p.classLoader); }
        catch (Throwable e) { XposedBridge.log(TAG+"Home settings hook error: "+e); }
        try {
            ClassLoader cl=p.classLoader;
            launcherClass=XposedHelpers.findClass("com.android.launcher3.Launcher",cl);
            workspaceClass=XposedHelpers.findClass("com.android.launcher3.Workspace",cl);
            cellClass=XposedHelpers.findClass("com.android.launcher3.CellLayout",cl);
            containerClass=XposedHelpers.findClass("com.android.launcher3.ShortcutAndWidgetContainer",cl);
            floatingClass=XposedHelpers.findClass("com.android.launcher3.AbstractFloatingView",cl);
            Class<?> state=XposedHelpers.findClass("com.android.launcher3.LauncherState",cl);
            normalState=XposedHelpers.getStaticObjectField(state,"NORMAL");
            for (Method m:floatingClass.getDeclaredMethods()) {
                if (m.getName().equals("getTopOpenView") && Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount()==1 && m.getParameterTypes()[0].isAssignableFrom(launcherClass)) {
                    m.setAccessible(true); topOpen=m; break;
                }
            }
            if (topOpen==null) throw new NoSuchMethodException("getTopOpenView");
            Method dispatch=findDispatch(launcherClass);
            XposedBridge.hookMethod(dispatch,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!launcherClass.isInstance(param.thisObject)) return;
                    Activity a=(Activity)param.thisObject;
                    try {
                        MotionEvent event=(MotionEvent)param.args[0];
                        // The system gesture monitor can see a touch shortly before
                        // the Launcher window takes focus. Never count it twice.
                        if(isMonitoredDuplicate(event))return;
                        if(!a.hasWindowFocus())return;
                        observe(a,event,param,false);
                    }
                    catch(Throwable e) { reset(a); report(e); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getObjectExtra("launcherDoubleTap")) && !param.hasThrowable()) {
                        try {
                            Activity a=(Activity)param.thisObject;
                            if (isHome(a)) sleep(a);
                        } catch(Throwable e) { report(e); }
                    }
                }
            });
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("onResume"),new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if(launcherClass.isInstance(p.thisObject)) {
                        Activity a=(Activity)p.thisObject;
                        lastResumeTimes.put(a,SystemClock.uptimeMillis());
                        resumedLauncher=a;
                        trace("Launcher resumed; recent-home gesture window active");
                    }
                }
            });
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("onPause"),new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (launcherClass.isInstance(p.thisObject)) {
                        Activity a=(Activity)p.thisObject;
                        reset(a);
                        if(resumedLauncher==a)resumedLauncher=null;
                    }
                }
            });
            XposedBridge.log(TAG+"Hook installed: "+dispatch);
            installQuickstepInputHook(cl);
        } catch(Throwable e) { XposedBridge.log(TAG+"Installation failed: "+e); }
    }
    /**
     * Hook the actual BatchedInputEventReceiver created by Quickstep's
     * existing InputChannelCompat wrapper. It must override the framework's
     * onInputEvent(InputEvent), unlike optimizable listener lambdas.
     *
     * Only instances of the system's "swipe-up" input monitor are observed.
     * Never consume or alter the system gesture stream.
     */
    private final HashSet<Class<?>> hookedMonitorReceiverClasses=new HashSet<>();
    private final WeakHashMap<Object,Boolean> selectedMonitorReceivers=new WeakHashMap<>();
    private int discoveredReceiverCount;
    private void installQuickstepInputHook(ClassLoader cl) {
        try {
            Class<?> wrapper=XposedHelpers.findClass(
                    "com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver",cl);
            int n=XposedBridge.hookAllConstructors(wrapper,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { captureQuickstepReceiver(p.thisObject,p.args); }
                    catch(Throwable e) {
                        XposedBridge.log(TAG+"Quickstep receiver discovery error: "+e);
                    }
                }
            }).size();
            XposedBridge.log(TAG+"Quickstep receiver constructor hooks="+n);
        } catch(Throwable e) {
            XposedBridge.log(TAG+"Quickstep receiver wrapper not found: "+e);
        }
        // Backward-compatible fallback for variants preserving the method
        // signature on TouchInteractionService.
        try {
            Class<?> tis=XposedHelpers.findClass(
                    "com.android.quickstep.TouchInteractionService",cl);
            int hooked=0;
            for(Method m:tis.getDeclaredMethods()) {
                Class<?>[] args=m.getParameterTypes();
                if(args.length!=1 || args[0]!=InputEvent.class)continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m,new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if(p.args[0] instanceof MotionEvent) {
                            try {observeQuickstepInput((MotionEvent)p.args[0]);}
                            catch(Throwable e) {report(e);}
                        }
                    }
                });
                hooked++;
                XposedBridge.log(TAG+"Quickstep service InputEvent hook installed: "+m);
            }
            if(hooked==0)
                XposedBridge.log(TAG+"Quickstep direct method unavailable; receiver monitor active");
        } catch(Throwable e) {
            XposedBridge.log(TAG+"Quickstep service scan unavailable: "+e);
        }
    }
    private void captureQuickstepReceiver(Object wrapper,Object[] args) throws Exception {
        String name=null;
        if(args.length>=1 && args[0] instanceof String)name=(String)args[0];
        if(name==null) {
            try {
                Object n=XposedHelpers.getObjectField(wrapper,"mName");
                if(n instanceof String)name=(String)n;
            } catch(NoSuchFieldError ignored) { }
        }
        if(discoveredReceiverCount++<5) {
            XposedBridge.log(TAG+"Quickstep monitor receiver created: name="
                    +(name==null?"unknown":name)+" argc="+args.length);
        }
        if(!"swipe-up".equals(name))return;

        Object underlying=null;
        try {underlying=XposedHelpers.getObjectField(wrapper,"mReceiver");}
        catch(NoSuchFieldError ignored) { }
        if(underlying==null) {
            // Field names can be obfuscated, but the Android framework base
            // class of the receiver cannot be renamed.
            Class<?> framework=android.view.InputEventReceiver.class;
            for(Class<?> c=wrapper.getClass();c!=null && underlying==null;c=c.getSuperclass()) {
                for(Field field:c.getDeclaredFields()) {
                    if(!framework.isAssignableFrom(field.getType()))continue;
                    try {
                        field.setAccessible(true);
                        underlying=field.get(wrapper);
                        if(underlying!=null)break;
                    } catch(Throwable ignored) { }
                }
            }
        }
        if(underlying==null) {
            XposedBridge.log(TAG+"Quickstep swipe-up receiver has no framework input receiver");
            return;
        }
        selectedMonitorReceivers.put(underlying,Boolean.TRUE);
        Class<?> receiverClass=underlying.getClass();
        if(!hookedMonitorReceiverClasses.add(receiverClass))return;
        int hooked=0;
        for(Method m:receiverClass.getDeclaredMethods()) {
            if(!m.getName().equals("onInputEvent")
                    || m.getParameterCount()!=1
                    || m.getParameterTypes()[0]!=InputEvent.class)continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if(!Boolean.TRUE.equals(selectedMonitorReceivers.get(p.thisObject)))return;
                    if(!(p.args[0] instanceof MotionEvent))return;
                    try {observeQuickstepInput((MotionEvent)p.args[0]);}
                    catch(Throwable e) {report(e);}
                }
            });
            hooked++;
        }
        XposedBridge.log(TAG+"Quickstep swipe-up BatchedInputEventReceiver hook installed: "
                +receiverClass.getName()+" methods="+hooked);
    }
    private boolean isMonitoredDuplicate(MotionEvent e) {
        return e.getDownTime()==monitoredDownTime
                && e.getEventTime()==monitoredEventTime
                && e.getActionMasked()==monitoredAction;
    }
    private void observeQuickstepInput(MotionEvent e) throws Exception {
        // InputMonitorCompat normally dispatches Quickstep on the main looper;
        // do not access View hierarchy or the TapTracker from other threads.
        if(Looper.myLooper()!=Looper.getMainLooper())return;
        if(isMonitoredDuplicate(e))return;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN)
            trace("Quickstep monitor received raw ACTION_DOWN");
        Activity a=resumedLauncher;
        if(a==null || a.hasWindowFocus() || a.isFinishing())return;
        Long last=lastResumeTimes.get(a);
        if(last==null)return;
        long age=SystemClock.uptimeMillis()-last;
        if(age<0 || age>EARLY_INPUT_WINDOW_MS)return;
        if(!LauncherSettingsHook.isEnabled(a))return;
        if(!isHome(a,true))return;
        // Only mark accepted pre-focus events: normal window dispatch may
        // still receive the same MotionEvent during a focus handoff.
        monitoredDownTime=e.getDownTime();
        monitoredEventTime=e.getEventTime();
        monitoredAction=e.getActionMasked();
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN)
            trace("Quickstep touch received before Launcher window focus");
        observe(a,e,null,true);
    }
    private static Method findDispatch(Class<?> c) throws NoSuchMethodException {
        for (;c!=null;c=c.getSuperclass()) {
            try { return c.getDeclaredMethod("dispatchTouchEvent",MotionEvent.class); }
            catch(NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException("dispatchTouchEvent");
    }
    /** At most one anonymous diagnostic for each decision path per launcher process. */
    private void trace(String message) {
        if(gestureTraceOnce.size()<40 && gestureTraceOnce.add(message))
            XposedBridge.log(TAG+"Gesture trace: "+message);
    }
    private TapTracker tracker(Activity a) {
        TapTracker t=trackers.get(a);
        if(t==null) {
            ViewConfiguration v=ViewConfiguration.get(a);
            t=new TapTracker(220,40,ViewConfiguration.getDoubleTapTimeout(),
                    v.getScaledTouchSlop(),v.getScaledDoubleTapSlop());
            trackers.put(a,t);
        }
        return t;
    }
    private void reset(Activity a) { TapTracker t=trackers.get(a); if(t!=null)t.reset(); }
    private void observe(Activity a, MotionEvent e, XC_MethodHook.MethodHookParam p,
                         boolean prefocus) throws Exception {
        if (!LauncherSettingsHook.isEnabled(a)) {
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN)
                trace("double-tap setting is OFF; gesture ignored");
            reset(a);
            return;
        }
        TapTracker t=tracker(a);
        if (e.getPointerCount()!=1 || e.getToolType(0)!=MotionEvent.TOOL_TYPE_FINGER) {t.reset(); return;}
        float x=e.getRawX(),y=e.getRawY();
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if(!prefocus)trace("Launcher receives touch events");
                t.start(e.getEventTime(),x,y,emptyPage(a,x,y,prefocus)); break;
            case MotionEvent.ACTION_MOVE:
                if(t.isDown()) {
                    for(int i=0;i<e.getHistorySize();i++) {
                        t.move(e.getHistoricalX(i)+(x-e.getX()),e.getHistoricalY(i)+(y-e.getY()));
                    }
                    t.move(x,y);
                }
                break;
            case MotionEvent.ACTION_UP:
                if(t.isDown()) {
                    View page=emptyPage(a,x,y,prefocus);
                    if(t.finish(e.getEventTime(),x,y,page)) {
                        if(prefocus) {
                            if(isHome(a,true)) {
                                trace("Quickstep pre-focus DOUBLE TAP ACCEPTED");
                                sleep(a);
                            }
                        } else {
                            p.setObjectExtra("launcherDoubleTap",true);
                            trace("DOUBLE TAP ACCEPTED");
                        }
                    } else if(t.hasPrevious()) {
                        trace("first tap armed for double tap");
                    } else {
                        trace("tap discarded on finger lift");
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                t.reset(); break;
            default: break;
        }
    }
    private boolean isHome(Activity a) throws Exception {return isHome(a,false);}
    private boolean isHome(Activity a,boolean prefocus) throws Exception {
        if(a.isFinishing() || (!prefocus && !a.hasWindowFocus())
                || (prefocus && a.hasWindowFocus())) {
            trace("not focused or finishing");return false;
        }
        KeyguardManager km=(KeyguardManager)a.getSystemService(Context.KEYGUARD_SERVICE);
        if(km==null || km.isKeyguardLocked()) {trace("keyguard active");return false;}
        Object manager=XposedHelpers.callMethod(a,"getStateManager");
        if(manager==null || !normalLauncherState(manager)) {trace("not NORMAL launcher state");return false;}
        if(topOpen.invoke(null,a)!=null) {trace("floating view open");return false;}
        return true;
    }
    /**
     * StateManager.isInStableState(NORMAL) waits for mCurrentStableState and
     * transition completion. On return-home animations mState can already
     * be NORMAL, while the previous stable state has not updated yet.
     *
     * Allow taps as soon as Launcher is targeting NORMAL, but never during
     * an outgoing transition to All Apps or Overview. Keep the existing
     * window-focus, visible Workspace, floating-view and hit tests.
     */
    private boolean normalLauncherState(Object manager) {
        Object current;
        try {
            current=XposedHelpers.getObjectField(manager,"mState");
        } catch(NoSuchFieldError ignored) {
            try {
                current=XposedHelpers.callMethod(manager,"getState");
            } catch(NoSuchMethodError missing) {
                // If this optimized build exposes neither state field nor getter,
                // fail safely: the stable-state check is the only proof available.
                try {
                    return Boolean.TRUE.equals(XposedHelpers.callMethod(
                            manager,"isInStableState",normalState));
                } catch(NoSuchMethodError alsoMissing) {
                    trace("Launcher current state unavailable; refusing double tap");
                    return false;
                }
            }
        }
        if(current!=normalState)return false;

        // Reject outgoing transitions even if the visible workspace hasn't
        // disappeared yet. Launcher3 stores the animation's destination here.
        Object target=null;
        boolean targetKnown=false;
        try {
            Object config=XposedHelpers.getObjectField(manager,"mConfig");
            if(config!=null) {
                target=XposedHelpers.getObjectField(config,"targetState");
                targetKnown=true;
            }
        } catch(NoSuchFieldError missing) { /* Device build may rename the field. */ }
        if(!targetKnown) {
            try {
                target=XposedHelpers.callMethod(manager,"getTargetState");
                targetKnown=true;
            } catch(NoSuchMethodError ignored) { }
        }
        if(targetKnown && target!=null && target!=normalState) {
            trace("transition targets another Launcher state");
            return false;
        }
        // Confirm this is an incoming home transition without waiting for
        // mCurrentStableState, which changes only when animation completes.
        try {
            if(!Boolean.TRUE.equals(XposedHelpers.callMethod(
                    manager,"isInStableState",normalState)))
                trace("NORMAL home accepted before stable transition ends");
        } catch(NoSuchMethodError ignored) { }
        return true;
    }
    private View emptyPage(Activity a,float x,float y,boolean prefocus) throws Exception {
        if(!isHome(a,prefocus)) return null;
        ViewGroup workspace=findWorkspace(a);
        if(workspace==null) {trace("Workspace not found");return null;}
        if(!hit(workspace,x,y)) {trace("touch outside Workspace bounds");return null;}
        try {
            if(Boolean.TRUE.equals(XposedHelpers.callMethod(workspace,"isPageInTransition"))) {
                // A returning Launcher may already be NORMAL and have focus
                // while PagedView still reports a page transition. During
                // a short window after Activity.onResume, we can accept
                // stationary blank taps; TapTracker still cancels swipes,
                // icon/widget hits, multi-touch and gestures on overlays.
                Long resumedAt=lastResumeTimes.get(a);
                long age=resumedAt==null ? Long.MAX_VALUE
                        : SystemClock.uptimeMillis()-resumedAt;
                if(age<0 || age>HOME_RESUME_GRACE_MS) {
                    trace("Workspace page transition blocks tap outside home-resume window");
                    return null;
                }
                trace("Workspace transition bypassed during recent home resume");
            }
        } catch(NoSuchMethodError ignored) { /* optimized Pixel Launcher */ }
        // The visible workspace may have multiple pages on large displays.
        for(int i=0;i<workspace.getChildCount();i++) {
            View page=workspace.getChildAt(i);
            if(!cellClass.isInstance(page) || !hit(page,x,y))continue;
            ViewGroup grid=null;
            for(int j=0;j<((ViewGroup)page).getChildCount();j++) {
                View child=((ViewGroup)page).getChildAt(j);
                if(containerClass.isInstance(child))grid=(ViewGroup)child;
                // Decorative CellLayout overlays are allowed. Only interactive
                // children outside the shortcut container block an empty tap.
                else if(hit(child,x,y) && (child.isClickable() || child.isLongClickable())) {
                    trace("interactive CellLayout overlay");
                    return null;
                }
            }
            if(grid==null) {trace("ShortcutAndWidgetContainer missing");return null;}
            // In Pixel Launcher the grid's clip rectangle can exclude the outer
            // page padding; use CellLayout's visible bounds to judge emptiness.
            // Still reject every actual icon/widget below.
            for(int j=0;j<grid.getChildCount();j++) {
                if(hit(grid.getChildAt(j),x,y)) {trace("icon/widget hit");return null;}
            }
            // Exclude other visible workspace children on top of this page.
            for(int j=i+1;j<workspace.getChildCount();j++) {
                if(hit(workspace.getChildAt(j),x,y))return null;
            }
            // Exclude hotseat and any other sibling overlay touching this point.
            View branch=workspace;
            while(branch.getParent() instanceof ViewGroup) {
                ViewGroup parent=(ViewGroup)branch.getParent();
                int index=parent.indexOfChild(branch);
                for(int j=0;j<parent.getChildCount();j++) {
                    View sibling=parent.getChildAt(j);
                    if(sibling==branch || !hit(sibling,x,y))continue;
                    String name=sibling.getClass().getName();
                    if(name.contains("Hotseat") || name.contains("Search") || name.contains("Smartspace")
                            || name.contains("Qsb") || name.contains("QSB")) {
                        trace("hotseat/search region");return null;
                    }
                    if(j>index && (sibling.isClickable() || sibling.isLongClickable()
                            || floatingClass.isInstance(sibling))) {
                        trace("intercepted by foreground overlay");return null;
                    }
                }
                branch=parent;
            }
            trace("empty workspace accepted");
            return page;
        }
        trace("no matching CellLayout at touch");
        return null;
    }
    /**
     * AOSP Launcher3 keeps its Workspace in mWorkspace. Optimized Pixel Launcher
     * builds can inline/remove getWorkspace(), as the device's LSPosed logs show.
     * Retrieve the object by type/field and finally from the live view hierarchy.
     * Only actual Workspace instances are accepted, never an arbitrary empty view.
     */
    private ViewGroup findWorkspace(Activity activity) {
        try {
            Object v=XposedHelpers.getObjectField(activity, "mWorkspace");
            if(workspaceClass.isInstance(v))return (ViewGroup)v;
        } catch (NoSuchFieldError ignored) { }
        for(Class<?> c=activity.getClass();c!=null;c=c.getSuperclass()) {
            for(Field f:c.getDeclaredFields()) {
                if(!workspaceClass.isAssignableFrom(f.getType()))continue;
                try {
                    f.setAccessible(true);
                    Object v=f.get(activity);
                    if(workspaceClass.isInstance(v))return (ViewGroup)v;
                } catch(Throwable ignored) { }
            }
        }
        try {
            View root=activity.getWindow().getDecorView();
            return findWorkspaceView(root,0);
        } catch(Throwable ignored) { }
        if(!loggedWorkspaceFailure) {
            loggedWorkspaceFailure=true;
            XposedBridge.log(TAG+"No Workspace field/view found on "+activity.getClass().getName());
        }
        return null;
    }

    private ViewGroup findWorkspaceView(View node,int depth) {
        if(workspaceClass.isInstance(node))return (ViewGroup)node;
        if(!(node instanceof ViewGroup) || depth>24)return null;
        ViewGroup parent=(ViewGroup)node;
        for(int i=0;i<parent.getChildCount();i++) {
            ViewGroup result=findWorkspaceView(parent.getChildAt(i),depth+1);
            if(result!=null)return result;
        }
        return null;
    }

    private boolean hit(View v,float x,float y) {
        // getGlobalVisibleRect uses root coordinates, whereas MotionEvent raw uses screen coordinates.
        if(!v.isShown() || v.getAlpha()<=0.01f || !v.getGlobalVisibleRect(rect))return false;
        View root=v.getRootView();
        root.getLocationOnScreen(screen);
        rect.offset(screen[0],screen[1]);
        return x>=rect.left && x<rect.right && y>=rect.top && y<rect.bottom;
    }
    private void sleep(Activity a) {
        trace("sleep action dispatched");
        if(!sleeping.compareAndSet(false,true))return;
        Context context=a.getApplicationContext();
        new Thread(()->{
            try {
                if(context.checkSelfPermission("android.permission.DEVICE_POWER")==PackageManager.PERMISSION_GRANTED) {
                    try {
                        PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
                        XposedHelpers.callMethod(pm,"goToSleep",SystemClock.uptimeMillis());
                        return;
                    } catch(Throwable e) {XposedBridge.log(TAG+"Direct sleep unavailable; using root");}
                }
                Process proc=new ProcessBuilder("su","-c","/system/bin/input keyevent 223").redirectErrorStream(true).start();
                if(!proc.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroy(); XposedBridge.log(TAG+"Root request timed out");
                } else if(proc.exitValue()!=0) XposedBridge.log(TAG+"Sleep failed; check launcher root permission, exit="+proc.exitValue());
                else XposedBridge.log(TAG+"Sleep key sent");
            } catch(Throwable e) {XposedBridge.log(TAG+"Sleep failed: "+e);}
            finally {sleeping.set(false);}
        },"LauncherDoubleTap-sleep").start();
    }
    private void report(Throwable e) { if(!loggedFailure) {loggedFailure=true; XposedBridge.log(TAG+"Gesture disabled for this event: "+e);} }
}
