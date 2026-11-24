# PixelLauncherHook

LSPosed module for Pixel Launcher (`com.google.android.apps.nexuslauncher`). Double-tapping an empty home screen area locks the phone.

The **双击桌面空白处锁屏 / Double tap to lock** switch is injected into **Pixel Launcher → Home settings**, enabled by default. Root permission for Pixel Launcher is necessary to send the sleep key event when its process lacks DEVICE_POWER.

After the GitHub Actions **Build APK** workflow succeeds, download its `PixelLauncherDoubleTap-APK` artifact. Install the APK, enable it in LSPosed with Pixel Launcher as scope and restart the launcher. No separate settings app is necessary.

Only tested for Java gesture state machine locally; UI hook needs verification on the actual PixelOS Launcher version.
