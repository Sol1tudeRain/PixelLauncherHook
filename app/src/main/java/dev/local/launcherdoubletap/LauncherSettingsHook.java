package dev.local.launcherdoubletap;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
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
        Object toggle = XposedHelpers.newInstance(switchClass, activity);
        boolean chinese = Locale.getDefault().getLanguage().equals("zh");
        XposedHelpers.callMethod(toggle, "setKey", KEY);
        XposedHelpers.callMethod(toggle, "setPersistent", false);
        XposedHelpers.callMethod(toggle, "setOrder", -100);
        XposedHelpers.callMethod(toggle, "setTitle", (CharSequence) (chinese
                ? "双击桌面空白处锁屏" : "Double tap to lock"));
        XposedHelpers.callMethod(toggle, "setSummary", (CharSequence) (chinese
                ? "双击主屏幕空白位置关闭屏幕" : "Double tap an empty area of the home screen to turn off the display"));
        XposedHelpers.callMethod(toggle, "setChecked", isEnabled(activity));

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
        XposedHelpers.callMethod(toggle, "setOnPreferenceChangeListener", listener);
        XposedHelpers.callMethod(screen, "addPreference", toggle);
        XposedBridge.log(TAG + "Native home settings toggle added");
    }
}
