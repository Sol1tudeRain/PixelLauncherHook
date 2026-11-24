package dev.local.launcherdoubletap;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.AttributeSet;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
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

    private LauncherSettingsHook() {}

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(KEY, true);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static void install(ClassLoader loader) {
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
        XposedBridge.log(TAG + "Home settings hook installed");
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
            XposedBridge.log(TAG + method + " optimized away; assigned " + field + " directly");
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
        Class<?> switchClass = XposedHelpers.findClassIfExists("androidx.preference.SwitchPreferenceCompat", loader);
        if (switchClass == null) switchClass = XposedHelpers.findClass("androidx.preference.SwitchPreference", loader);
        Object toggle = newSwitchPreference(switchClass, activity);
        boolean chinese = Locale.getDefault().getLanguage().equals("zh");
        setPreferenceValue(toggle, "setKey", "mKey", KEY);
        // setPersistent(boolean) is removed in this optimized PixelOS Launcher.
        // Prefer the backing field; our dedicated SharedPreferences remains authoritative.
        try { XposedHelpers.setBooleanField(toggle, "mPersistent", false); }
        catch (Throwable ignored) { XposedBridge.log(TAG + "mPersistent field unavailable, using Launcher default preference persistence"); }
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

        Class<?> changeListener = XposedHelpers.findClass(
                "androidx.preference.Preference$OnPreferenceChangeListener", loader);
        InvocationHandler callback = (proxy, method, args) -> {
            if ("onPreferenceChange".equals(method.getName()) && args != null && args.length == 2) {
                if (!(args[1] instanceof Boolean)) return false;
                boolean enabled = (Boolean) args[1];
                preferences(activity).edit().putBoolean(KEY, enabled).apply();
                XposedBridge.log(TAG + "Double tap lock " + (enabled ? "enabled" : "disabled"));
                return true;
            }
            if ("toString".equals(method.getName())) return "LauncherDoubleTapPreferenceListener";
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return args != null && args.length > 0 && proxy == args[0];
            return null;
        };
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{changeListener}, callback);
        setPreferenceValue(toggle, "setOnPreferenceChangeListener", "mOnChangeListener", listener);
        XposedHelpers.callMethod(screen, "addPreference", toggle);
        XposedBridge.log(TAG + "Native home settings toggle added");
    }
}
