# Pixel Launcher Double Tap 1.8

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
## v1.2 修复（依据 PixelOS 真机 LSPosed 日志）
- 修复正常桌面识别：不再调用缺失的 Launcher#isInState，改用 StateManager#getState 与 NORMAL 比较。
- 修复主屏幕设置开关创建：优先使用 SwitchPreferenceCompat(Context, AttributeSet) 反射构造方法，兼容无单 Context 构造函数的 Launcher。
- 保持原 applicationId、推荐 LSPosed 作用域和固定证书 SHA-256，提升 versionCode 至 3，可覆盖 v1.1。
- 没有提供签名 secrets 的 Actions 运行将上传未签名 APK，供本地使用备份签名密钥签名。

## v1.3 修复
针对 PixelOS 真机日志：`StateManager#getState()` 缺失与 `SwitchPreferenceCompat#setPersistent(boolean)` 缺失。优先采用 `isInStableState` 或字段检查，移除不兼容的 `setPersistent` 调用，并兼容其它可优化的 Preference setter。保留原包名、LSPosed 作用域与相同发布签名。

## v1.4：根据 Launcher3 与 AndroidX 源码重构

- **桌面对象**：Launcher3 中 `Launcher#getWorkspace()` 只是返回 `mWorkspace`。PixelOS 真机这个 getter 被优化掉，因此优先读取 `mWorkspace`，再按 Workspace 字段类型查找，最后只在当前 DecorView 视图树中寻找真正的 `Workspace` 实例；不再调用缺失的 `getWorkspace()` 方法。
- **开关保存**：AndroidX `TwoStatePreference.onClick()` 会调用 `setChecked()`。因此直接 Hook `TwoStatePreference.setChecked`，仅处理本模块动态加入的开关，不再查找不存在的 `Preference$OnPreferenceChangeListener`。
- **安全边界**：只在正常桌面、无浮层和空白网格双击时尝试锁屏；找不到 Workspace 时保持无操作。继续使用固定证书及原包名，推荐作用域仍然是 Pixel Launcher。
- **来源区别**：参考 PixelOS 公开的 Launcher3/AOSP Launcher3 实现，但设备实际运行的是优化后的 Pixel Launcher APK，因此不能把开源 API 名称视为运行时保证。

参考资料：
- [AOSP Launcher.java（mWorkspace 与 getWorkspace）](https://android.googlesource.com/platform/packages/apps/Launcher3/+/250f92bbe00d219f82c573fbbc28eb12ba3ba61a/src/com/android/launcher3/Launcher.java)
- [AOSP Launcher3 SettingsActivity.java（PreferenceScreen）](https://android.googlesource.com/platform/packages/apps/Launcher3/+/9367f98/src/com/android/launcher3/settings/SettingsActivity.java)
- [AndroidX TwoStatePreference.java（onClick 与 setChecked）](https://android.googlesource.com/platform/frameworks/support/+/f2e05c341382db64d127118a13451dcaa554b702/preference/preference/src/main/java/androidx/preference/TwoStatePreference.java)

## v1.5：修复原生开关布局并诊断双击识别

- 参照 Pixel Launcher 主屏幕设置中**已有的原生开关**，复制其 Preference row/widget 布局参数，不再依赖手动实例化开关的默认布局。
- 禁用空图标占位，使新设置项与其它设置项左对齐。
- 用 `getPreference(int)` 的原始参数类型查找参考设置，兼容优化后的 AndroidX Preference。
- 手势检测不再要求 ShortcutAndWidgetContainer 的裁剪区域覆盖全部页面留白；但仍拒绝点击图标、widget、热座和前景浮层。
- 为实际用户设备增加去重日志 `[LauncherDoubleTap] Gesture trace:`，报告各判断路径，不记录触摸坐标。
- 维持 `dev.local.launcherdoubletap` 包名、原签名证书及 LSPosed 推荐作用域，versionCode=6。

## v1.6：真正匹配原生滑块控件

用户 v1.5 日志显示参考开关的实际类型为 `androidx.preference.SwitchPreference`，而新建项却为 `SwitchPreferenceCompat`。两者布局使用不同的 switch view ID，复用前者 widget layout 给后者导致滑块不能正常随点击更新。现在优先实例化与参考项完全相同的具体类，再复制其 row/widget 布局；没有匹配模板时避免复制错误布局。

同一日志最后为 `Double tap lock disabled`，因此后续触摸不会进入双击识别。现在首次触摸时会在 LSPosed 日志记录 `double-tap setting is OFF`，便于辨别开关状态问题和手势识别问题。保持应用 ID、证书和推荐作用域，versionCode 7。

## v1.7：返回桌面立即支持双击锁屏

- 修复返回桌面后需要等状态动画结束才允许双击的问题。Launcher3 的 `StateManager.isInStableState(NORMAL)` 要求 `mCurrentStableState` 也为 `NORMAL`，但它只在动画结束后更新；直接观察 `mState` 可确认当前已进入桌面。
- 只允许当前 `mState == NORMAL`，并且若能读取动画目标 `mConfig.targetState`，必须是 `NORMAL` 或为空。这样不会在切换到应用抽屉、最近任务时误触发。
- 继续要求 Launcher 有焦点、非锁屏状态、无浮层以及触点位于未占用的 Workspace 网格区域。
- 不增加后台常驻服务，不修改 KernelSU 的 Root 授权方式，也不改变已修复的原生设置开关实现。保持相同包名、LSPosed 作用域和签名证书，可直接覆盖 v1.6。
- 新日志 `NORMAL home accepted before stable transition ends` 表示在返回桌面动画尚未结束时提前放行手势。

参考 [AOSP Launcher3 StateManager.java](https://android.googlesource.com/platform/packages/apps/Launcher3/+/d0aa5b82db0ba4a173f8baf721f07351123c2290/src/com/android/launcher3/statemanager/StateManager.java) 中 `isInStableState()` 和 `onStateTransitionStart()` 的实现。

## v1.8：进一步改善返回桌面立即双击

- 上一版（v1.7）只放宽 StateManager 状态判断，但 `Workspace.isPageInTransition()` 仍可能在返回动画期间直接丢弃点击，且无错误日志。
- 本版 Hook Launcher 的 Activity.onResume，记录单个进程中的最近恢复时间；只有在恢复后 1200 毫秒内且满足 `NORMAL`、窗口焦点和浮层检查时，才允许在 Workspace 页面过渡阶段继续检查空白点击。普通跨桌面页面滑动仍会受原检查限制。
- 仍拒绝图标、Widget、热座、浮层、多指输入和移动超出阈值的手势。
- 增加去重诊断信息：`Launcher resumed`、`Workspace transition bypassed during recent home resume`、`first tap armed for double tap`、`tap discarded on finger lift`。不记录触摸坐标。
- 和 v1.7 一样继续使用既有的 Root 锁屏执行路径及开关样式；应用包名、签名证书和 LSPosed 推荐作用域不变，可直接覆盖。

注意：仅依据开源 Launcher3 和既有真机日志修复潜在阻塞，具体生效时机及退场动画交互必须通过 PixelOS 真机测试确认；不保证系统尚未向 Launcher 派发触摸事件时也能提前锁屏。
