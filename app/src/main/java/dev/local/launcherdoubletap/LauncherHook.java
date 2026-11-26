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
import java.util.concurrent.atomic.AtomicInteger;
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
    // Predictive / legacy BACK-to-home does not use GestureState.setEndTarget(HOME).
    // Predictive back can also run before Launcher.onResume().
    private Activity backReturningLauncher;
    private long lastBackReturnCommitAt=-1;
    private static final long BACK_RETURN_WINDOW_MS=2400;
    // Shared event identity for the Quickstep monitor / Launcher window.
    // Both can see the same finger event around the focus handoff.
    private long handledDownTime=-1, handledEventTime=-1;
    private int handledAction=-1;
    private final AtomicBoolean sleeping=new AtomicBoolean();
    private boolean loggedFailure;
    private boolean loggedWorkspaceFailure;
    private final AtomicInteger diagnosticCount=new AtomicInteger();
    private void diag(String message) {
        if(diagnosticCount.getAndIncrement()<110)
            XposedBridge.log(TAG+"BACK_DIAG "+message);
    }
    private Class<?> launcherClass, workspaceClass, cellClass, containerClass, floatingClass;
    private Object normalState, backgroundAppState;
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
            try { backgroundAppState=XposedHelpers.getStaticObjectField(state,"BACKGROUND_APP"); }
            catch(NoSuchFieldError ignored) { /* Device-specific Launcher may optimize it. */ }
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
                        if(isHandledDuplicate(event))return;
                        if(!a.hasWindowFocus())return;
                        boolean handoff=inHomeHandoff(a);
                        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)
                            diag("Launcher DOWN handoff="+handoff+" back="+hasRecentBackReturn(a)
                                +" home="+hasRecentQuickstepHomeTarget());
                        observe(a,event,param,handoff);
                        markHandledEvent(event);
                    }
                    catch(Throwable e) { reset(a); report(e); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getObjectExtra("launcherDoubleTap")) && !param.hasThrowable()) {
                        try {
                            Activity a=(Activity)param.thisObject;
                            if (isHome(a) || (inHomeHandoff(a) && isHome(a,true)))
                                sleep(a);
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
                        diag("Launcher onResume back="+hasRecentBackReturn(a));
                    }
                }
            });
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("onPause"),new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (launcherClass.isInstance(p.thisObject)) {
                        Activity a=(Activity)p.thisObject;
                        diag("Launcher onPause back="+hasRecentBackReturn(a));
                        reset(a);
                        if(resumedLauncher==a)resumedLauncher=null;
                        lastQuickstepHomeTargetAt=-1;
                        if(backReturningLauncher==a) {
                            backReturningLauncher=null;
                            lastBackReturnCommitAt=-1;
                        }
                    }
                }
            });
            installQuickstepInputHook(cl);
            installQuickstepHomeTargetHook(cl);
            installBackToHomeHook(cl);
            diag("diagnostic build initialized");
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
    private long lastQuickstepHomeTargetAt=-1;
    private static final long QUICKSTEP_HOME_TARGET_MAX_AGE_MS=2400;
    private void installQuickstepInputHook(ClassLoader cl) {
        try {
            Class<?> wrapper=XposedHelpers.findClass(
                    "com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver",cl);
            XposedBridge.hookAllConstructors(wrapper,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { captureQuickstepReceiver(p.thisObject,p.args); }
                    catch(Throwable e) {
                        XposedBridge.log(TAG+"Quickstep receiver discovery error: "+e);
                    }
                }
            });
        } catch(Throwable e) {
            XposedBridge.log(TAG+"Quickstep receiver wrapper not found: "+e);
        }
        // Backward-compatible fallback for variants preserving the method
        // signature on TouchInteractionService.
        try {
            Class<?> tis=XposedHelpers.findClass(
                    "com.android.quickstep.TouchInteractionService",cl);
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
            }
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
        // PixelOS names its per-display gesture monitor "swipe-up-disp0".
        // Match only Quickstep's known swipe-up pattern, not unrelated monitors.
        if(name==null || !name.matches("swipe-up(?:-disp[0-9]+)?"))return;
        Object underlying=null;
        try {underlying=XposedHelpers.getObjectField(wrapper,"mReceiver");}
        catch(NoSuchFieldError ignored) { }
        if(underlying==null) {
            // Field names can be obfuscated, but the Android framework base
            // class of the receiver cannot be renamed.
            Class<?> framework=Class.forName("android.view.InputEventReceiver");
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
        }
    }
    /**
     * GestureState records whether the swipe's actual destination is HOME.
     * The launcher StateManager can remain BACKGROUND_APP and expose no
     * targetState while Quickstep's app-closing animation is still running.
     * Do not guess that a resume implies home; require a real HOME gesture.
     */
    private void installQuickstepHomeTargetHook(ClassLoader cl) {
        try {
            Class<?> gs=XposedHelpers.findClass("com.android.quickstep.GestureState",cl);
            XposedBridge.hookAllMethods(gs,"setEndTarget",new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if(p.args.length==0 || p.args[0]==null)return;
                    String target=String.valueOf(p.args[0]);
                    if("HOME".equals(target)) {
                        lastQuickstepHomeTargetAt=SystemClock.uptimeMillis();
                    } else {
                        lastQuickstepHomeTargetAt=-1;
                    }
                }
            });
        } catch(Throwable e) {
            XposedBridge.log(TAG+"Quickstep gesture destination hook unavailable: "+e);
        }
    }
    /**
     * Predictive back-to-home is dispatched by WM Shell to the LauncherBackAnimationController.
     * Hook only the committed transition, NEVER onBackStarted/onBackProgressed,
     * since those may be cancelled without navigating home.
     */
    private void installBackToHomeHook(ClassLoader cl) {
        try {
            Class<?> back=XposedHelpers.findClass(
                    "com.android.quickstep.LauncherBackAnimationController",cl);
            int hooks=XposedBridge.hookAllMethods(back,"startTransition",new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    diag("predictive startTransition CALLED argc="+p.args.length);
                    try {
                        Object launcher=XposedHelpers.getObjectField(p.thisObject,"mLauncher");
                        Object departing=XposedHelpers.getObjectField(p.thisObject,"mBackTarget");
                        diag("predictive launcherValid="+launcherClass.isInstance(launcher)
                                +" backTargetPresent="+(departing!=null));
                        if(departing!=null && launcherClass.isInstance(launcher)) {
                            markCommittedBackToHome((Activity)launcher);
                        }
                    } catch(Throwable e) {report(e);}
                }
            }).size();
            diag("predictive startTransition hooks="+hooks);
            for(final String method:new String[]{"onBackStarted","onBackInvoked","onBackCancelled"}) {
                int matched=XposedBridge.hookAllMethods(back,method,new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        diag("predictive "+method+" called");
                    }
                }).size();
                if(matched>0)diag("predictive "+method+" hooks="+matched);
            }
            if(hooks==0)
                XposedBridge.log(TAG+"Predictive back transition entry point unavailable");
        } catch(Throwable e) {
            XposedBridge.log(TAG+"Predictive back-to-home hook unavailable: "+e);
        }
        // Older apps use legacy back without an OnBackInvokedDispatcher.
        // Quickstep's wallpaper-open animation is a distinct return-home
        // transition for legacy app-close, not Quickstep GestureState HOME.
        try {
            Class<?> transitions=XposedHelpers.findClass(
                    "com.android.launcher3.QuickstepTransitionManager",cl);
            int hooks=XposedBridge.hookAllMethods(transitions,
                    "createWallpaperOpenAnimations",new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    diag("legacy wallpaper animation CALLED argc="+p.args.length);
                    try {
                        // Launcher3's current animation has SIX parameters:
                        // appTargets, wallpaperTargets, nonAppTargets, startRect,
                        // cornerRadius, fromPredictiveBack. Some ROMs vary;
                        // verify the actual target type, not a fixed count or
                        // an unrelated third argument.
                        if(p.args.length<1 || !(p.args[0] instanceof Object[]))
                            return;
                        Object[] targets=(Object[])p.args[0];
                        boolean hasClosingApp=false;
                        for(Object target:targets) {
                            if(target==null)continue;
                            // MODE_CLOSING is 1 in Android RemoteAnimationTarget.
                            if(XposedHelpers.getIntField(target,"mode")==1) {
                                hasClosingApp=true;
                                break;
                            }
                        }
                        diag("legacy targets="+targets.length+" closingApp="+hasClosingApp);
                        if(!hasClosingApp)return;
                        Object launcher=XposedHelpers.getObjectField(p.thisObject,"mLauncher");
                        diag("legacy launcherValid="+launcherClass.isInstance(launcher));
                        if(launcherClass.isInstance(launcher))
                            markCommittedBackToHome((Activity)launcher);
                    } catch(Throwable e) {report(e);}
                }
            }).size();
            diag("legacy wallpaper hook count="+hooks);
            if(hooks==0)
                XposedBridge.log(TAG+"Legacy app-close-to-home animation entry point unavailable");
        } catch(Throwable e) {
            XposedBridge.log(TAG+"Legacy app-close-to-home hook unavailable: "+e);
        }
    }
    private void markCommittedBackToHome(Activity a) {
        if(a==null || a.isFinishing())return;
        backReturningLauncher=a;
        lastBackReturnCommitAt=SystemClock.uptimeMillis();
        diag("BACK COMMITTED");
    }
    private boolean hasRecentBackReturn(Activity a) {
        if(a==null || a!=backReturningLauncher || lastBackReturnCommitAt<0)return false;
        long age=SystemClock.uptimeMillis()-lastBackReturnCommitAt;
        return age>=0 && age<=BACK_RETURN_WINDOW_MS;
    }
    private boolean hasRecentQuickstepHomeTarget() {
        long age=SystemClock.uptimeMillis()-lastQuickstepHomeTargetAt;
        return lastQuickstepHomeTargetAt>=0 && age>=0
                && age<=QUICKSTEP_HOME_TARGET_MAX_AGE_MS;
    }
    private boolean isHandledDuplicate(MotionEvent e) {
        return e.getDownTime()==handledDownTime
                && e.getEventTime()==handledEventTime
                && e.getActionMasked()==handledAction;
    }
    private void markHandledEvent(MotionEvent e) {
        handledDownTime=e.getDownTime();
        handledEventTime=e.getEventTime();
        handledAction=e.getActionMasked();
    }
    private boolean inHomeHandoff(Activity a) {
        if(a==null || a.isFinishing())return false;
        // A confirmed back-to-home transition is enough, even when onResume
        // is deferred until the predictive-back animation has completed.
        if(hasRecentBackReturn(a))return true;
        Long resumedAt=lastResumeTimes.get(a);
        if(resumedAt==null || !hasRecentQuickstepHomeTarget())return false;
        long age=SystemClock.uptimeMillis()-resumedAt;
        return age>=0 && age<=EARLY_INPUT_WINDOW_MS;
    }
    private void observeQuickstepInput(MotionEvent e) throws Exception {
        // The existing swipe-up monitor sees the gesture while the Launcher
        // window gains focus. Continue through that handoff so the DOWN and UP
        // cannot be split between two independent event streams.
        if(Looper.myLooper()!=Looper.getMainLooper())return;
        Activity a=resumedLauncher;
        // During predictive BACK, Launcher may not receive onResume until the
        // animation fully ends. Use the verified controller's own Launcher.
        if(backReturningLauncher!=null && hasRecentBackReturn(backReturningLauncher))
            a=backReturningLauncher;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN)
            diag("Quickstep DOWN launcher="+(a!=null)
                    +" back="+(a!=null && hasRecentBackReturn(a))
                    +" handoff="+(a!=null && inHomeHandoff(a))
                    +" focused="+(a!=null && a.hasWindowFocus()));
        if(a==null || a.isFinishing() || !inHomeHandoff(a))return;
        if(!LauncherSettingsHook.isEnabled(a))return;
        if(isHandledDuplicate(e))return;
        if(!isHome(a,true)) {
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN)diag("Quickstep DOWN isHome rejected");
            return;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN)diag("Quickstep DOWN isHome accepted");
        observe(a,e,null,true);
        markHandledEvent(e);
    }
    private static Method findDispatch(Class<?> c) throws NoSuchMethodException {
        for (;c!=null;c=c.getSuperclass()) {
            try { return c.getDeclaredMethod("dispatchTouchEvent",MotionEvent.class); }
            catch(NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException("dispatchTouchEvent");
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
            reset(a);
            return;
        }
        TapTracker t=tracker(a);
        if (e.getPointerCount()!=1 || e.getToolType(0)!=MotionEvent.TOOL_TYPE_FINGER) {t.reset(); return;}
        float x=e.getRawX(),y=e.getRawY();
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
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
                        if(p==null) {
                            if(isHome(a,true)) {
                                sleep(a);
                            }
                        } else {
                            p.setObjectExtra("launcherDoubleTap",true);
                        }
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
        // Early HOME input can continue after focus is obtained. The caller
        // must have already verified the recent Quickstep HOME destination.
        if(a.isFinishing() || (!prefocus && !a.hasWindowFocus())) {
            return false;
        }
        KeyguardManager km=(KeyguardManager)a.getSystemService(Context.KEYGUARD_SERVICE);
        if(km==null || km.isKeyguardLocked()) {return false;}
        Object manager=XposedHelpers.callMethod(a,"getStateManager");
        if(manager==null || !normalLauncherState(manager,prefocus,a)) {
            if(prefocus)diag("isHome reject: state"); return false;
        }
        if(topOpen.invoke(null,a)!=null) {return false;}
        return true;
    }
    /**
     * In a Quickstep return-to-home animation, mState can still refer to
     * OVERVIEW while mConfig.targetState is already NORMAL. Requiring
     * mState==NORMAL discards early empty taps. Only a verified NORMAL target
     * can relax this check in the short pre-focus window; a null or foreign
     * target never enables that path. Regular Launcher dispatch remains strict.
     */
    private boolean normalLauncherState(Object manager,boolean prefocus,Activity launcher) {
        Object current;
        try {
            current=XposedHelpers.getObjectField(manager,"mState");
        } catch(NoSuchFieldError missingField) {
            try {current=XposedHelpers.callMethod(manager,"getState");}
            catch(NoSuchMethodError missingGetter) {
                try {
                    return Boolean.TRUE.equals(XposedHelpers.callMethod(
                            manager,"isInStableState",normalState));
                } catch(NoSuchMethodError missingAll) {
                    return false;
                }
            }
        }
        Object target=null;
        try {
            Object config=XposedHelpers.getObjectField(manager,"mConfig");
            if(config!=null)
                target=XposedHelpers.getObjectField(config,"targetState");
        } catch(NoSuchFieldError missing) {
            // Do not infer a NORMAL target when OEM fields are removed.
        }
        if(target==null) {
            try {target=XposedHelpers.callMethod(manager,"getTargetState");}
            catch(NoSuchMethodError ignored) { }
        }
        final boolean committedBack=prefocus && hasRecentBackReturn(launcher);
        // During predictive back, Launcher may still be in BACKGROUND_APP
        // until its closing-app animation completes. This exception is never
        // enabled for ALL_APPS, OVERVIEW or an unconfirmed/cancelled back.
        if(target!=null && target!=normalState
                && !(committedBack && backgroundAppState!=null
                     && target==backgroundAppState)) {
            return false;
        }
        if(prefocus)diag("state current="+String.valueOf(current)
                +" target="+String.valueOf(target)+" committedBack="+committedBack);
        if(current==normalState)return true;
        if(prefocus && target==normalState)return true;
        if(committedBack && backgroundAppState!=null
                && (current==backgroundAppState || target==backgroundAppState))
            return true;
        // Some optimized builds expose no target during the return animation.
        // Require either explicit HOME gesture or committed back-to-home
        // evidence; never infer HOME from the onResume timestamp alone.
        if(prefocus && target==null && (hasRecentQuickstepHomeTarget()
                || committedBack))return true;
        return false;
    }
    private View emptyPage(Activity a,float x,float y,boolean prefocus) throws Exception {
        if(!isHome(a,prefocus)) return null;
        ViewGroup workspace=findWorkspace(a);
        if(workspace==null) {return null;}
        if(!hit(workspace,x,y)) {return null;}
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
                // Predictive BACK may keep Launcher paused until animation
                // completes, so its page transition grace must use the
                // committed back timestamp rather than the stale onResume.
                if(prefocus && hasRecentBackReturn(a))
                    age=SystemClock.uptimeMillis()-lastBackReturnCommitAt;
                if(age<0 || age>(prefocus ? EARLY_INPUT_WINDOW_MS : HOME_RESUME_GRACE_MS)) {
                    return null;
                }
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
                    return null;
                }
            }
            if(grid==null) {return null;}
            // In Pixel Launcher the grid's clip rectangle can exclude the outer
            // page padding; use CellLayout's visible bounds to judge emptiness.
            // Still reject every actual icon/widget below.
            for(int j=0;j<grid.getChildCount();j++) {
                if(hit(grid.getChildAt(j),x,y)) {return null;}
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
                        return null;
                    }
                    if(j>index && (sibling.isClickable() || sibling.isLongClickable()
                            || floatingClass.isInstance(sibling))) {
                        return null;
                    }
                }
                branch=parent;
            }
            return page;
        }
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
        if(!sleeping.compareAndSet(false,true))return;
        Context context=a.getApplicationContext();
        new Thread(()->{
            try {
                if(context.checkSelfPermission("android.permission.DEVICE_POWER")==PackageManager.PERMISSION_GRANTED) {
                    try {
                        PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
                        XposedHelpers.callMethod(pm,"goToSleep",SystemClock.uptimeMillis());
                        return;
                    } catch(Throwable ignored) { /* Fall back to the existing root command. */ }
                }
                Process proc=new ProcessBuilder("su","-c","/system/bin/input keyevent 223").redirectErrorStream(true).start();
                if(!proc.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroy(); XposedBridge.log(TAG+"Root request timed out");
                } else if(proc.exitValue()!=0) XposedBridge.log(TAG+"Sleep failed; check launcher root permission, exit="+proc.exitValue());
            } catch(Throwable e) {XposedBridge.log(TAG+"Sleep failed: "+e);}
            finally {sleeping.set(false);}
        },"LauncherDoubleTap-sleep").start();
    }
    private void report(Throwable e) { if(!loggedFailure) {loggedFailure=true; XposedBridge.log(TAG+"Gesture disabled for this event: "+e);} }
}
