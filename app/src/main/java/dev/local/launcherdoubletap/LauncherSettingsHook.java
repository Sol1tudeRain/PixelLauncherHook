package dev.local.launcherdoubletap;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.AttributeSet;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Locale;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Inserts a native AndroidX SwitchPreference into Pixel Launcher's actual Home settings.
 * All AndroidX references are resolved from the launcher's class loader at runtime to
 * avoid bundling a second incompatible copy of AndroidX Preference in the LSPosed APK.
 */
final class LauncherSettingsHook {
    private static final String TAG = "[LauncherDoubleTap] ";
    private static final String FILE = "launcher_double_tap_settings";
    private static final String KEY = "launcher_double_tap_sleep_enabled";
    private static final String ROOT_ARG = "androidx.preference.PreferenceFragmentCompat.PREFERENCE_ROOT";
    // Keep only weak references to injected UI preferences. The application context
    // is safe to retain (unlike SettingsActivity / fragment instances).
    private static final Set<Object> INJECTED_SWITCHES =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static volatile Context launcherApplication;

    private LauncherSettingsHook() {}

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(KEY, true);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static void install(ClassLoader loader) {
        // TwoStatePreference.onClick calls setChecked when the switch changes.
        // Hook that existing method instead of resolving a listener interface that
        // can be absent or obfuscated in PixelOS' prebuilt Pixel Launcher.
        Class<?> twoState = XposedHelpers.findClassIfExists(
                "androidx.preference.TwoStatePreference", loader);
        if(twoState!=null) {
            XposedBridge.hookAllMethods(
                    twoState,"setChecked",new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if(param.hasThrowable() || param.args.length!=1
                            || !(param.args[0] instanceof Boolean))return;
                    if(!INJECTED_SWITCHES.contains(param.thisObject))return;
                    Context app=launcherApplication;
                    if(app==null)return;
                    boolean enabled=(Boolean)param.args[0];
                    preferences(app).edit().putBoolean(KEY,enabled).apply();

                }
            });

        } else {
            XposedBridge.log(TAG+"No TwoStatePreference found; toggle changes cannot be persisted");
        }
        Class<?> fragmentClass = XposedHelpers.findClassIfExists(
                "androidx.preference.PreferenceFragmentCompat", loader);
        if (fragmentClass == null) {
            XposedBridge.log(TAG + "Home settings injection unavailable: AndroidX PreferenceFragmentCompat missing");
            return;
        }
        XposedBridge.hookAllMethods(fragmentClass, "onViewCreated", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                try {
                    addSwitch(param.thisObject, loader);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "Home settings injection failed: " + t);
                }
            }
        });

    }

    /**
     * Resolve the runtime AndroidX constructor explicitly. Some PixelOS Launcher builds
     * optimize away the one-Context overload, leaving only Context+AttributeSet.
     * Use the two-argument overload first so the Launcher's theme supplies its style.
     */
    private static Object newSwitchPreference(Class<?> cls, Context context) throws Exception {
        try {
            Constructor<?> c = cls.getDeclaredConstructor(Context.class, AttributeSet.class);
            c.setAccessible(true);
            return c.newInstance(context, null);
        } catch (NoSuchMethodException ignored) { }
        try {
            Constructor<?> c = cls.getDeclaredConstructor(Context.class, AttributeSet.class, int.class);
            c.setAccessible(true);
            return c.newInstance(context, null, 0);
        } catch (NoSuchMethodException ignored) { }
        try {
            Constructor<?> c = cls.getDeclaredConstructor(Context.class, AttributeSet.class, int.class, int.class);
            c.setAccessible(true);
            return c.newInstance(context, null, 0, 0);
        } catch (NoSuchMethodException ignored) { }
        throw new NoSuchMethodException(cls.getName() + " lacks a usable Context/AttributeSet constructor");
    }

    /** Compatible with R8-inlined AndroidX preference setters. */
    private static void setPreferenceValue(Object target, String method, String field, Object value) {
        try {
            XposedHelpers.callMethod(target, method, value);
        } catch (NoSuchMethodError ignored) {
            XposedHelpers.setObjectField(target, field, value);

        }
    }

    /** Find an existing working Launcher switch, including those inside preference groups. */
    private static Object findNativeSwitch(Object parent, Class<?> twoState, ClassLoader loader,
                                           int depth) {
        if(depth>5 || parent==null)return null;
        Class<?> group=XposedHelpers.findClassIfExists("androidx.preference.PreferenceGroup",loader);
        if(group==null || !group.isInstance(parent))return null;
        try {
            int count=((Number)XposedHelpers.callMethod(parent,"getPreferenceCount")).intValue();
            for(int i=0;i<count;i++) {
                Object child=XposedHelpers.callMethod(parent,"getPreference",new Class<?>[]{int.class},i);
                if(child==null)continue;
                if(twoState.isInstance(child)) {
                    // Reuse the actual working widget resource, not the default style of
                    // a programmatically-created SwitchPreferenceCompat.
                    try {
                        int widget=XposedHelpers.getIntField(child,"mWidgetLayoutResId");
                        if(widget>0)return child;
                    } catch(Throwable ignored) { }
                }
                Object nested=findNativeSwitch(child,twoState,loader,depth+1);
                if(nested!=null)return nested;
            }
        } catch(Throwable e) { XposedBridge.log(TAG+"Native switch lookup error: "+e); }
        return null;
    }

    private static void copyNativePreferenceLayout(Object source,Object destination) {
        for(String field : new String[]{"mLayoutResId","mWidgetLayoutResId"}) {
            try {
                int value=XposedHelpers.getIntField(source,field);
                if(value>0)XposedHelpers.setIntField(destination,field,value);
            } catch(Throwable ignored) { /* Optional layout field. */ }
        }
    }

    private static void addSwitch(Object fragment, ClassLoader loader) throws Exception {
        Activity activity = (Activity) XposedHelpers.callMethod(fragment, "getActivity");
        if (activity == null || !"com.google.android.apps.nexuslauncher".equals(activity.getPackageName())) return;
        String activityName = activity.getClass().getName().toLowerCase(Locale.ROOT);
        String fragmentName = fragment.getClass().getName().toLowerCase(Locale.ROOT);
        // Only the launcher's settings, not miscellaneous preference dialogs/screens.
        if (!activityName.contains("settings") || !fragmentName.contains("settings")) return;
        Bundle arguments = (Bundle) XposedHelpers.callMethod(fragment, "getArguments");
        if (arguments != null && arguments.getString(ROOT_ARG) != null) return;

        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (screen == null || XposedHelpers.callMethod(screen, "findPreference", KEY) != null) return;

        // Follow Pixel Launcher's own Preference style, theme, fonts and switch layout.
        Class<?> twoStateClass=XposedHelpers.findClassIfExists("androidx.preference.TwoStatePreference",loader);
        Object template=twoStateClass==null ? null : findNativeSwitch(screen,twoStateClass,loader,0);
        // Use the SAME concrete preference class as the Launcher settings' own
        // working switch. SwitchPreference uses android.R.id.switch_widget, while
        // SwitchPreferenceCompat uses androidx.preference.R.id.switchWidget.
        // Mixing them with a copied widget layout renders a visible but inert switch.
        Object toggle=null;
        if(template!=null) {
            try { toggle=newSwitchPreference(template.getClass(),activity); }
            catch(Exception ignored) { /* Use compatible switch fallback. */ }
        }
        if(toggle==null) {
            // Fallback preserves the class/widget pairing rather than copying an
            // incompatible widgetLayoutResId from some other subclass.
            Class<?> switchClass=XposedHelpers.findClassIfExists("androidx.preference.SwitchPreference",loader);
            if(switchClass==null) switchClass=XposedHelpers.findClass("androidx.preference.SwitchPreferenceCompat",loader);
            toggle=newSwitchPreference(switchClass,activity);
        }
        if(template!=null && template.getClass()==toggle.getClass()) {
            copyNativePreferenceLayout(template,toggle);
        }
        // No icon is supplied. Match Pixel Launcher's other settings rows (no empty
        // icon column), irrespective of the Preference default style.
        try { XposedHelpers.setBooleanField(toggle,"mIconSpaceReserved",false); }
        catch(Throwable ignored) {
            try { XposedHelpers.callMethod(toggle,"setIconSpaceReserved",false); }
            catch(Throwable other) { XposedBridge.log(TAG+"Cannot disable icon placeholder: "+other); }
        }
        boolean chinese = Locale.getDefault().getLanguage().equals("zh");
        setPreferenceValue(toggle, "setKey", "mKey", KEY);
        // setPersistent(boolean) is removed in this optimized PixelOS Launcher.
        // Prefer the backing field; our dedicated SharedPreferences remains authoritative.
        try { XposedHelpers.setBooleanField(toggle, "mPersistent", false); }
        catch (Throwable ignored) { /* Launcher persistence fallback. */ }
        try { XposedHelpers.callMethod(toggle, "setOrder", -100); }
        catch (NoSuchMethodError ignored) {
            try { XposedHelpers.setIntField(toggle, "mOrder", -100); }
            catch (Throwable ignoredField) { /* optional: append at end */ }
        }
        setPreferenceValue(toggle, "setTitle", "mTitle", (CharSequence) (chinese
                ? "双击桌面空白处锁屏" : "Double tap to lock"));
        setPreferenceValue(toggle, "setSummary", "mSummary", (CharSequence) (chinese
                ? "双击主屏幕空白位置关闭屏幕" : "Double tap an empty area of the home screen to turn off the display"));
        try { XposedHelpers.callMethod(toggle, "setChecked", isEnabled(activity)); }
        catch(NoSuchMethodError ignored) {
            XposedHelpers.setBooleanField(toggle, "mChecked", isEnabled(activity));
        }

        // Add the switch as a normal Launcher preference. Its AndroidX onClick
        // implementation triggers TwoStatePreference.setChecked, hooked above.
        // No OnPreferenceChangeListener reflection required.
        launcherApplication=activity.getApplicationContext();
        XposedHelpers.callMethod(screen, "addPreference", toggle);
        INJECTED_SWITCHES.add(toggle);
    }
}
