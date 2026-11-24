package dev.local.launcherdoubletap;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.WeakHashMap;
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
    private final AtomicBoolean sleeping=new AtomicBoolean();
    private boolean loggedFailure;
    private boolean loggedWorkspaceFailure;
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
                    try { observe(a,(MotionEvent)param.args[0],param); }
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
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("onPause"),new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (launcherClass.isInstance(p.thisObject)) reset((Activity)p.thisObject);
                }
            });
            XposedBridge.log(TAG+"Hook installed: "+dispatch);
        } catch(Throwable e) { XposedBridge.log(TAG+"Installation failed: "+e); }
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
    private void observe(Activity a, MotionEvent e, XC_MethodHook.MethodHookParam p) throws Exception {
        if (!LauncherSettingsHook.isEnabled(a)) { reset(a); return; }
        TapTracker t=tracker(a);
        if (e.getPointerCount()!=1 || e.getToolType(0)!=MotionEvent.TOOL_TYPE_FINGER) {t.reset(); return;}
        float x=e.getRawX(),y=e.getRawY();
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                t.start(e.getEventTime(),x,y,emptyPage(a,x,y)); break;
            case MotionEvent.ACTION_MOVE:
                if(t.isDown()) {
                    for(int i=0;i<e.getHistorySize();i++) {
                        t.move(e.getHistoricalX(i)+(x-e.getX()),e.getHistoricalY(i)+(y-e.getY()));
                    }
                    t.move(x,y);
                }
                break;
            case MotionEvent.ACTION_UP:
                if(t.isDown() && t.finish(e.getEventTime(),x,y,emptyPage(a,x,y))) {
                    p.setObjectExtra("launcherDoubleTap",true);
                } else if(t.isDown()) t.reset();
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                t.reset(); break;
            default: break;
        }
    }
    private boolean isHome(Activity a) throws Exception {
        if(!a.hasWindowFocus() || a.isFinishing()) return false;
        KeyguardManager km=(KeyguardManager)a.getSystemService(Context.KEYGUARD_SERVICE);
        if(km==null || km.isKeyguardLocked()) return false;
        Object manager=XposedHelpers.callMethod(a,"getStateManager");
        if(manager==null || !normalLauncherState(manager)) return false;
        if(topOpen.invoke(null,a)!=null) return false;
        return true;
    }
    /**
     * PixelOS' optimized Launcher strips some StateManager accessor methods. Prefer
     * isInStableState(), then use its actual state fields. Never assume NORMAL if
     * no state can be verified (avoids sleeping in All Apps and Recents).
     */
    private boolean normalLauncherState(Object manager) {
        try {
            return Boolean.TRUE.equals(XposedHelpers.callMethod(manager,"isInStableState",normalState));
        } catch (NoSuchMethodError ignored) { }
        try {
            Object active=XposedHelpers.getObjectField(manager,"mState");
            if(active!=normalState) return false;
            try {
                Object stable=XposedHelpers.getObjectField(manager,"mCurrentStableState");
                if(stable!=normalState) return false;
            } catch(NoSuchFieldError ignored) { }
            try {
                if(Boolean.TRUE.equals(XposedHelpers.callMethod(manager,"isInTransition"))) return false;
            } catch(NoSuchMethodError ignored) { }
            return true;
        } catch(NoSuchFieldError ignored) { }
        // Optional public accessor, retained in some variants.
        try {
            Object stable=XposedHelpers.callMethod(manager,"getCurrentStableState");
            if(stable!=normalState) return false;
            try {
                if(Boolean.TRUE.equals(XposedHelpers.callMethod(manager,"isInTransition"))) return false;
            } catch(NoSuchMethodError ignored) { }
            return true;
        } catch(NoSuchMethodError ignored) { }
        report(new NoSuchMethodError("No supported Pixel Launcher state API or mState field in "+manager.getClass().getName()));
        return false;
    }
    private View emptyPage(Activity a,float x,float y) throws Exception {
        if(!isHome(a)) return null;
        ViewGroup workspace=findWorkspace(a);
        if(workspace==null)return null;
        if(!hit(workspace,x,y))return null;
        try {
            if(Boolean.TRUE.equals(XposedHelpers.callMethod(workspace,"isPageInTransition")))return null;
        } catch(NoSuchMethodError ignored) { /* removed by Launcher optimizer */ }
        // The visible workspace may have multiple pages on large displays.
        for(int i=0;i<workspace.getChildCount();i++) {
            View page=workspace.getChildAt(i);
            if(!cellClass.isInstance(page) || !hit(page,x,y))continue;
            ViewGroup grid=null;
            for(int j=0;j<((ViewGroup)page).getChildCount();j++) {
                View child=((ViewGroup)page).getChildAt(j);
                if(containerClass.isInstance(child))grid=(ViewGroup)child;
                else if(hit(child,x,y))return null;
            }
            if(grid==null || !hit(grid,x,y))return null;
            for(int j=0;j<grid.getChildCount();j++) {
                if(hit(grid.getChildAt(j),x,y))return null;
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
                            || name.contains("Qsb") || name.contains("QSB")) return null;
                    if(j>index && (sibling.isClickable() || sibling.isLongClickable()
                            || floatingClass.isInstance(sibling)))return null;
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
