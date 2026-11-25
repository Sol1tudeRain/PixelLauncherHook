# Pixel Launcher Double Tap 2.5

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

## v1.9：Quickstep 返回桌面动画期间提前双击（实验性）

- v1.6～v1.8 监听 `Launcher.dispatchTouchEvent()`，系统在返回桌面动画期间可能尚未把触摸派发到 Launcher 窗口。仅放宽 Launcher 状态和 Workspace 过渡检查无法处理这种情况。
- 本版新增可选的 `TouchInteractionService.onInputEvent(InputEvent)` Hook，**只观察** Quickstep 已经创建的系统手势 `InputMonitor`。不创建新的监视器、不吞掉 `MotionEvent`、不修改系统导航。
- 严格限制 Quickstep 早期输入：Launcher 的 `onResume` 之后 2 秒内、窗口尚未获得焦点、当前目标是 `NORMAL`、锁屏未启用、设置开关已开、没有浮层，并且屏幕坐标位于未被图标或 Widget 占用的 Workspace 上；仍按现有双击时序和位移限制判断。
- 监视器事件与普通 Launcher 窗口事件使用同一个双击状态机，避免切换焦点时丢掉第一次点击。对同时分发的相同事件使用事件时间和动作去重。
- 如果设备上的 Quickstep 优化移除了 `onInputEvent`，模块只在 LSPosed 日志记录“Quickstep pre-focus input unavailable”并退回既有行为；不会影响正常桌面双击。
- 新日志：`Quickstep pre-focus input hook installed`、`Quickstep touch received before Launcher window focus` 和 `Quickstep pre-focus DOUBLE TAP ACCEPTED`。
- 这仍是需要真机验证的实验性机制；InputMonitor 不保证在所有 PixelOS 版本都覆盖动画期间的触摸。保留包名、稳定签名、设置入口和旧 Root 锁屏执行方式。

对应 AOSP 实现：[TouchInteractionService.java](https://android.googlesource.com/platform/packages/apps/Launcher3/+/f41858c23c5e7a7fbb14d0df6ab041154ec456ab/quickstep/src/com/android/quickstep/TouchInteractionService.java)。

## v2.0：适配 Pixel Launcher 优化后的 Quickstep 输入回调

- v1.9 在实际 PixelOS 日志中报告 `NoSuchMethodException: TouchInteractionService.onInputEvent(InputEvent)`，导致提前手势入口未安装。
- 不再只通过固定私有方法名 Hook，而是监听现有 `InputChannelCompat.InputEventReceiver` 的创建过程，从接收器构造函数参数定位实际 `InputEvent` 监听器，且只允许 Quickstep 相关调用栈或类名。
- 对监听器类中的实际 `InputEvent` 回调 Hook，兼容经过优化和重命名的回调实现；同时扫描 TouchInteractionService 中实际存在的 `InputEvent` 方法作为兼容回退。
- 去重来自多个入口的同一个触摸事件；观察而不修改输入，不创建新的输入监视器。
- 新日志：`Quickstep receiver constructor hooks=N`、`Quickstep monitor listener hook installed`、`Quickstep monitor received raw ACTION_DOWN`。如果监听器未找到，日志可明确区分“接收器未创建”和“已创建但过滤未通过”。
- 其余 Launcher 状态、Workspace 空白排除和 Root 锁屏逻辑保持不变。未经真机验证 Quickstep 观察到的事件是否覆盖返回桌面动画，故此机制仍属实验性。

参考 AOSP [InputChannelCompat](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/shared/src/com/android/systemui/shared/system/InputChannelCompat.java) 和 [InputMonitorCompat](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/shared/src/com/android/systemui/shared/system/InputMonitorCompat.java)。

## v2.1：直接观察 Quickstep 的实际输入接收器（实验性）

- v2.0 日志显示 `Quickstep receiver constructor hooks=1`，但没有 `Quickstep monitor listener hook installed`。构造函数 Hook 已注册，不代表监听器回调被找到，Quickstep lambda 经 R8 混淆/内联后可能无法按类名和调用栈识别。
- 直接在 `InputChannelCompat.InputEventReceiver` **构造完成后**查找其实际 `mReceiver`（或以 Android framework 的 `InputEventReceiver` 类型反射发现），只对名称 `swipe-up` 的接收器对象添加监听；对其框架回调覆写 `onInputEvent(InputEvent)` 安装 Hook，不再依赖混淆的 listener lambda。
- 限制到具体的 Quickstep receiver **实例**，不拦截其他输入监视器。仅读取输入事件，不吞触摸、不注入事件、不改变导航逻辑；避免系统手势受到影响。
- 加入日志 `Quickstep monitor receiver created: name=... argc=...`、`Quickstep swipe-up BatchedInputEventReceiver hook installed`、`Quickstep monitor received raw ACTION_DOWN`。用来判断接收器是否被创建、名称是否符合以及是否收到触摸。
- 当前依旧只在返回桌面 2 秒内，Launcher 已恢复、尚未获得焦点且无遮挡、当前处于 NORMAL 状态时尝试提前双击；其余触摸均沿用 v1.8 正常桌面处理。
- 原 Root 锁屏、设置开关、应用 ID 和签名证书不变。尚需真机检查优化后的 PixelOS 是否创建该接收器、真实输入的坐标范围以及返回动画时状态。

## v2.2：修复 PixelOS Quickstep 监视器名称匹配

- v2.1 的 PixelOS 日志确认监视器是 `swipe-up-disp0`（每个显示器一个），但 v2.1 只接受字符串 `swipe-up`，因此接收器被提前跳过。
- v2.2 精确接受 `swipe-up` 或 `swipe-up-dispN`（N 为数字）来兼容每显示器命名，不扩大到其他监视器。
- 新增 `Quickstep swipe-up monitor matched` 诊断，之后应继续出现 `Quickstep swipe-up BatchedInputEventReceiver hook installed`，若无法获取底层输入接收器则会明确打印原因。
- 本次保持原生设置开关、稳定签名、Launcher 普通桌面手势和 KernelSU Root 锁屏逻辑不变。动画期间提前锁屏仍需真机验证。

## v2.3：在返回桌面动画目标明确为 NORMAL 时提前接收双击

- v2.2 真机验证：成功 Hook `swipe-up-disp0` 的 `BatchedInputEventReceiver.onInputEvent`，并收到 `Quickstep monitor received raw ACTION_DOWN`。但之后出现 `not NORMAL launcher state`，说明一些返回动画早期事件仍被错误拒绝。
- 仅对 Quickstep 预焦点输入、Launcher 恢复后 2 秒内、当前窗口尚未取得焦点的情形，若能反射验证 `StateManager.mConfig.targetState == LauncherState.NORMAL`，即使当前 `mState` 还未切至 NORMAL，也允许后续空白触点判断；缺少明确 NORMAL 目标则继续拒绝。
- Quickstep 预焦点通道的 `Workspace.isPageInTransition()` 宽限与预焦点 2 秒窗口同步；普通桌面则仍沿用 1.2 秒页面过渡保护。滑动距离、多指输入、图标、Widget、浮层和锁屏检查均保留。
- 增加有限条、不记录屏幕坐标的 `Quickstep input gate: resumed=... ageMs=... focused=...` 日志，以及状态原因 `Quickstep early home: state transitioning toward NORMAL` / `Quickstep early state rejected: ...`。
- 保留 v2.2 的 Quickstep 输入 Hook、原 Root 休眠命令、系统桌面设置开关和固定签名。本版的改善仍需你的 PixelOS 真机验证。

## v2.4：以 Quickstep 的 HOME 手势目标判断返回桌面

v2.3 真机日志证明系统已有的 swipe-up-disp0 输入监听已成功 Hook，部分 ACTION_DOWN 出现在 Launcher onResume 后 260～319ms 且尚未获得窗口焦点，但 StateManager 无法提供明确 NORMAL 目标，导致 early state rejected。

- 新版 Hook GestureState.setEndTarget，捕获 Quickstep 手势真正决定进入 HOME 的信号，不再仅依赖可能缺失的 StateManager.mConfig.targetState。
- 只有 HOME 信号发生在 2.4 秒以内、Launcher 已恢复且尚无焦点、没有其他目标状态、未锁屏、设置开关开启、触点为空白 Workspace 时，才允许提前双击。RECENTS、NEW_TASK、LAST_TASK、ALL_APPS 将清除 HOME 标记；Launcher onPause 也清除。
- 保留原有图标/Widget/浮层、多指与滑动排除及 ROOT 锁屏流程。未获明确 HOME 信号时保守拒绝，不会把所有返回动画当作桌面。
- 新日志：Quickstep gesture end-target hooks=N / Quickstep gesture destination HOME confirmed / Quickstep input gate: homeGesture=true/false / Quickstep early home: confirmed HOME gesture while launcher state lags。
- 仍须真机验证，具体 ROM 可能优化了 GestureState.setEndTarget，届时仍可通过日志判断是否成功。

## v2.5：跨焦点交接保留双击状态

- v2.4 PixelOS 真机日志显示 HOME 已确认，Quickstep 在 Launcher 无窗口焦点时看到空白区域 DOWN；155ms 后 Launcher 有了焦点，但没有构成有效双击。旧版 Quickstep 路径在检测到 `hasWindowFocus()==true` 时直接 return，可能造成 DOWN/UP 分别由两种入口处理。
- v2.5 在 `onResume` 后 2 秒且近期 Quickstep 的 `GestureState` 已确认 HOME 目标的短窗口，Quickstep 的 swipe-up 输入回调**跨越焦点交接继续观察**触摸，并和 Launcher.dispatchTouchEvent 使用同一个 TapTracker。
- 依据 DOWN 时间、事件时间及动作，去重两条路径可能重复派发的同一个事件；如果 Quickstep 没观察到事件，Launcher 的正常分发路径仍可继续处理。焦点交接不再直接排除早期 HOME 触摸。
- 仍要求 HOME 手势目标、Launcher 已恢复、设置启用、未锁屏、没有浮层、触点位于空白 Workspace；不拦截或注入输入，保留滑动、多指、图标、Widget 与热座排除。
- 新增有限条 `Home handoff event: source=Quickstep|Launcher action=ACTION_DOWN|ACTION_UP|ACTION_CANCEL focused=... downTime=... eventTime=... trackingDown=... firstTap=...`，不用坐标就能逐次定位首次点击有无完成。
- 原 Root 锁屏命令、桌面设置开关、模块包名与签名证书不变。本版依然需要真机测试。
