# Pixel Launcher Double Tap 1.1

独立 LSPosed 模块，目标 PixelOS Android 15 的 Pixel Launcher (`com.google.android.apps.nexuslauncher`)。无需无障碍、设备管理员权限，不包含桌面图标、后台服务或常驻轮询。**直接将「双击桌面空白处锁屏」开关注入 Pixel Launcher → 主屏幕设置**，无需进入独立模块界面。默认开启，关闭后立即停止识别手势。只在桌面正常状态、空白 workspace 网格上识别双击；不消费原始触摸事件。不会修改 AOD / SystemUI / framework。通过正常休眠路径锁屏，是否立即锁屏取决于系统锁屏设置。

## 构建与固定签名

本项目使用 *固定的私有 PKCS#12 签名密钥*，不使用会随 CI runner 改变的 Android debug 签名。公开仓库中**绝不存放私钥**。第一次安装 APK 后，只要保持相同包名和签名，后续版本即可覆盖更新。

在 GitHub 仓库 Settings → Secrets and variables → Actions → New repository secret 设置：

1. `LAUNCHER_SIGNING_KEYSTORE_B64`：从私人签名包里 `LAUNCHER_SIGNING_KEYSTORE_B64.txt` 复制整行。
2. `LAUNCHER_SIGNING_PASSWORD`：从私人签名包里 `LAUNCHER_SIGNING_PASSWORD.txt` 复制整行。

然后 Actions → Build stable-signed Pixel Launcher APK → Run workflow；成功后下载 `PixelLauncherHook-stable-signed-APK`，解压得到 `app-release.apk`。每次构建都会通过 `apksigner` 校验**固定证书 SHA-256**：`da11dc91385a561fb014911aac165a6ea7b501c353aa3147367dc769404c9709`。缺少 Secret 或签名不对会失败，不会发布签名不一致的 APK。务必私下备份签名包，绝不提交到 GitHub，也不要公开分享。

LSPosed 推荐作用域已在 `AndroidManifest.xml` 的 `xposedscope` 声明为 `com.google.android.apps.nexuslauncher`；通常在 LSPosed 中可自动预选/一键选择。仍需手动启用模块；具体行为取决于管理器版本。

## 使用
1. 安装 APK；在 LSPosed 中启用 Pixel Launcher Double Tap，作用域仅选择 Pixel Launcher。
2. 重启手机，或在 Termux 中运行 `su -c 'am force-stop com.google.android.apps.nexuslauncher'` 后回到桌面。
3. 在桌面长按空白处 → 主屏幕设置，可以看到「双击桌面空白处锁屏」开关。默认开启，切换无需重启。随后双击桌面空白格。如果 KernelSU 弹出 Pixel Launcher 的 root 授权提示，可授权后再次尝试。若没有弹窗，可在 KernelSU 管理器显示系统应用并手动为 Pixel Launcher 开启超级用户权限。授权对象是 Launcher 进程（整个应用），而不是本模块自身，请知悉该权限范围。撤销授权或禁用模块可恢复原状。
4. 测试图标、文件夹、长按、左右滑动以及抽屉，确认不会误触。锁屏后测试指纹。

## 诊断
LSPosed verbose 日志中搜索 `[LauncherDoubleTap]`，也可以在 Termux 执行：

```
su -c 'grep -hF "[LauncherDoubleTap]" /data/adb/lspd/log/verbose_*.log | tail -n 30'
```

如 `Installation failed` 或 `Gesture disabled for this event` 出现，说明 Launcher 私有接口可能和预期不同。提供该日志及 Launcher 版本即可定位。禁用模块并重启 Launcher 可撤回 Hook。

## 实现与验证
- Hook Launcher Activity 的 dispatchTouchEvent；只观察事件，不拦截。
- 正常桌面、无浮窗、无过渡动画、网格未占用才计入双击。双击时间和距离阈值取 Android ViewConfiguration；滑动、多指、长按取消。
- 若 Launcher 自身拥有 DEVICE_POWER，尝试直接 goToSleep；否则在独立短时线程执行 `su -c '/system/bin/input keyevent 223'`（KEYCODE_SLEEP，而非可能唤醒屏幕的 POWER）。
- 纯 Java 手势状态机通过 9 组测试（`bash tests/run.sh`）。当前制作环境无 Android SDK，APK 编译和实际设备 Hook 尚未验证。

## 主屏幕设置开关实现
- Hook Pixel Launcher 进程里 AndroidX `PreferenceFragmentCompat.onViewCreated`，仅对 Launcher 设置首页插入原生 `SwitchPreferenceCompat`；沿用 Launcher 自己的设置页面主题，非截图/覆盖层。
- 开关使用 Launcher 进程自身的私有 `SharedPreferences(launcher_double_tap_settings)` 保存；手势处理时直接读取，关闭立即生效。
- 若 PixelOS 内置 Launcher 的设置页面类或 AndroidX 实现有变化，可能不会出现开关。请提供 LSPosed 日志（`[LauncherDoubleTap]`）和 Launcher APK 版本以便适配。