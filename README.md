# 120Hz 常驻（Ltpo120Hz）

一个用来消除 **LTPO 面板换刷新率时亮度跳动** 的小工具。
包名 `com.ltpo120hz`，纯 Java + 零第三方依赖，APK 约 0.9 MB。

---

## 一、先回答"这个方案可行吗"

**可行，但目标要改写。** 不是"在屏幕上播一个 120Hz 动画"，而是"让面板一直待在你选的那个 display mode 上"。这两件事的差别决定了耗电、可见性和成功率。

Android 的刷新率不是"有动画就高、静止就低"这么简单：

1. 每个可见图层（Surface）可以向上投票自己希望的帧率，SurfaceFlinger 汇总所有可见图层的投票后，再决定用哪个 display mode。
   → 所以一个 **1×1 像素、几乎全透明** 的悬浮窗，只要它投票 120Hz，就能把整体拉上去。你**不需要**一块占满屏幕的动画。
2. 但多数 ROM 会把"长时间没有新帧"的图层判定为静止内容，然后整体降频。
   → 所以还得**真的每帧提交一次 buffer**，这就是必须持续出帧的原因。
3. 亮度为什么会跳：LTPO 面板在不同刷新率下，每帧的发光占空比 / 驱动频率不同，同样的灰阶值会呈现略微不同的亮度（很多机器低刷时更暗或发绿）。只要不发生"切换"这个动作，亮度就不会跳。
   → 所以正确的解法是**钉死某一个挡位**，不一定是 120Hz。锁 60Hz 一样不跳，还更省电。

**代价与局限（说实话）：**

- 耗电。持续高刷输出 + 常驻前台服务，锁 120Hz 时视机型大约多 4%–12%/小时；锁 60Hz 基本可以忽略。
- 发热。持续高刷会让 SoC/面板温度上升，而**过热恰恰会触发系统的亮度限制**——如果你把手机捂在被子里用，可能得不偿失。
- 成功率因 ROM 而异。小米 HyperOS、一加 ColorOS、三星 OneUI、vivo OriginOS 都有"per-app 刷新率白名单"和"静态内容自动降频"策略，有的只认**焦点窗口**的投票，第三方悬浮窗压不住。这种情况下用应用里的 **"系统级锁定"**（写 `min_refresh_rate` / `peak_refresh_rate`）通常能压住，代价是需要一次 adb 授权。
- 有些机型压根没有 120Hz 的 display mode（只有 60/90/120 中的一部分），或者 120Hz 只在特定分辨率下可用，应用里的"支持的模式"会如实列出来。

所以这个应用把三件事都做了：**悬浮窗投票 + 持续出帧 + 可选的系统级写设置**，并自带一个 10 秒自检告诉你到底有没有生效。

---

## 二、应用做了哪些事

| 机制 | 位置 | 说明 |
| --- | --- | --- |
| `Surface.setFrameRate(挡位, FIXED_SOURCE, ALWAYS)` | `OverlayLockView` | API 30+ 的主手段，显式投票 |
| `LayoutParams.preferredRefreshRate` + `preferredDisplayModeId` | `LockService` | 老接口，很多 ROM 只认这两个 |
| Choreographer 逐帧 `lockCanvas → draw → unlockCanvasAndPost` | `OverlayLockView` | 保证图层不被判定为静止 |
| 每 2 秒守护检查，掉频就重新投票 | `LockService` | 应对系统把它降下去 |
| `Settings.System.min_refresh_rate / peak_refresh_rate` | `SysLock` | 可选，需 `WRITE_SECURE_SETTINGS` |
| 刷新率变化日志 + 10 秒自检 | `RateMonitor` / `MainActivity` | 判断是否真的生效 |
| **挡位可选（60 / 90 / 120 …）** | `RateMonitor.supportedRates` / `MainActivity` | 挡位列表来自 `Display.getSupportedModes()`，只列真实支持的 |
| **游戏时自动暂停** | `GameDetector` / `ForegroundTracker` / `GameSenseService` | 见下节 |

### 挡位

「锁定挡位」那张卡片会把当前面板上报的所有刷新率列出来（常见是 60 / 90 / 120，有的机器还有 144 或 48）。点一下就换挡，正在运行时会立即重建悬浮窗，不用手动重开。

- 选 **60**：亮度同样稳定，耗电几乎为零，适合刷信息流、看小说。
- 选 **120**：滑动跟手，适合游戏和长时间滑动。
- 存的挡位如果这块屏不支持，会自动吸附到最近的真实挡位（诊断日志里会写一行）。
- 「系统级锁定」写的也是当前选中的挡位，按钮文案会跟着变。

悬浮窗有三种样式：

- **隐形 1px**：窗口 1×1 像素，画 `alpha=1/255` 的纯黑。物理上不可能被察觉，也不挡触摸。默认用这个。
- **微点**：18dp 小圆点 + 一个缓慢旋转的指针，用来肉眼确认"它真的在跑"。
- **指示条**：显示 `当前挡位 · 实际出帧 fps`，适合调试。

---

## 二·五、游戏时自动暂停

游戏里系统本来就会把帧率顶满，亮度不会跳，这时候再常驻高刷纯属耗电+发热。所以加了"自动让位"。

**判定逻辑**（`GameDetector`）：把前台包名分成三类

| 分类 | 来源 |
| --- | --- |
| **GAME** | ① 用户手动名单（优先级最高）② `ApplicationInfo.category == CATEGORY_GAME` 或 `FLAG_IS_GAME`（Play 上架的游戏都带，系统自己的分类）③ 一小撮明确的包名前缀兜底（国产渠道服经常不写 category） |
| **NEUTRAL** | SystemUI、安装器、WebView、Chrome、GMS、本应用。游戏内的广告页/登录页/权限弹窗都会短暂落到这里，所以**不改变状态** |
| **OTHER** | 其余一切（含桌面）→ 恢复锁定 |

**防抖动**（`LockService.evaluateForeground`）

- 连续 **2 秒**都是 GAME 才撤下悬浮窗（躲开启动闪屏、加载页）
- 连续 **3 秒**都不是 GAME 才恢复
- 游戏结束后 **90 秒**内的 NEUTRAL 仍按"游戏上下文"处理，避免游戏内广告把锁切来切去
- 识别不到前台（没授权）时**保持现状**——宁可不暂停，也不误暂停

**两种检测手段**

1. **使用情况访问权限** + `UsageStatsManager` 每秒轮询（主手段，需要你在系统里授权一次）。
2. **无障碍服务**（可选）：事件驱动，延迟接近 0，只读 `TYPE_WINDOW_STATE_CHANGED` 的包名，不读屏幕内容。

**误判了怎么办**：面板上有「标记 / 取消标记当前前台应用」，手动名单优先级最高，随时可以把某个游戏加进去或踢出去。诊断日志里会有 `[game]` / `[fg]` 记录，能看它到底认成了什么。

暂停期间服务仍在跑（前台通知会写"游戏中，已暂停出帧"），只是把悬浮窗撤掉了，所以不会投票、也不纠正刷新率。

---

## 三、安装

从 [Releases](../../releases) 下载 `120HzChangZhu-vX.Y.apk` 直接安装，或者自己构建（见第七节）。

```bat
:: 也可以用 adb
adb install -r 120HzChangZhu-v1.1.apk
```

首次打开后依次授权：

1. **悬浮窗权限**（必须，没有它连窗口都加不进去）
2. **通知权限**（Android 13+，前台服务需要）
3. **忽略电池优化**（强烈建议，否则进程容易被杀）
4. **使用情况访问权限**（想要"游戏时自动暂停"就必须给，否则检测不到前台应用）
5. （可选）**无障碍服务**，让游戏识别变成瞬时
6. （可选）**adb 授权 WRITE_SECURE_SETTINGS**，用于系统级锁定

然后点「开启锁定」。下拉通知栏的快捷开关里还能加一个 `120Hz` 磁贴，出门时一键关掉省电。

### 权限都用在哪了

| 权限 | 用途 | 不给会怎样 |
| --- | --- | --- |
| `SYSTEM_ALERT_WINDOW` | 那个 1×1 的悬浮表面 | 完全不能用 |
| `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` | 常驻保活 | 进程容易被回收，刷新率回落 |
| `PACKAGE_USAGE_STATS` | 读当前前台应用包名 | 「游戏时自动暂停」失效 |
| 无障碍服务（可选） | 实时读窗口切换的包名 | 退回到 1 秒轮询 |
| `WRITE_SECURE_SETTINGS`（可选） | 写 `min/peak_refresh_rate` | 「系统级锁定」不可用 |

### 隐私

- 无网络权限，**不联网、不上传任何数据**。
- 读取前台包名只用于本地判断"是不是游戏"，不落盘、不外发。
- 无障碍服务配置为 `canRetrieveWindowContent=false`，只接收 `TYPE_WINDOW_STATE_CHANGED` 事件里的包名，不读取界面内容、不执行任何操作。

---

## 四、怎么验证真的生效了

按可靠性排序：

1. **应用内「自检 10 秒」**：一直采样 `Display.getRefreshRate()`，输出最低/最高/切换次数。锁定时应该是 `最低 120.0 / 最高 120.0 / 切换 0 次`。
2. **开发者选项 → 显示刷新率**：屏幕左上角会实时显示当前 Hz，肉眼最直观。
3. **查看 display mode 变化**：
   ```bat
   adb shell dumpsys display | findstr /i "mDisplayId renderFrameRate modeId"
   ```
4. **看系统是否接受了写入的设置**：
   ```bat
   adb shell settings get system min_refresh_rate
   adb shell settings get system peak_refresh_rate
   ```

---

## 五、如果悬浮窗压不住（按顺序试）

1. 点「写入 120Hz」（先按下面授权）。这条路径绕过 per-app 白名单，成功率最高。
   ```bat
   adb shell pm grant com.ltpo120hz android.permission.WRITE_SECURE_SETTINGS
   ```
2. 系统设置里关掉「智能刷新率 / 自适应刷新率 / 内容自适应」之类的开关，把刷新率手动选成 120Hz。
3. 部分 ROM 的开发者选项里有「强制峰值刷新率 / Force peak refresh rate」，打开它。
4. 把应用加进后台白名单（自启动、后台运行、省电策略改为"无限制"）。
5. 都不行就说明这个 ROM 的刷新率策略是写死的（常见于锁定 60Hz 的视频场景/省电场景），需要 root 或换 ROM。

彻底恢复原状：

```bat
adb shell settings delete system min_refresh_rate
adb shell settings delete system peak_refresh_rate
adb shell pm revoke com.ltpo120hz android.permission.WRITE_SECURE_SETTINGS
```

---

## 六、风险提示

- **耗电/发热**：长期 120Hz 是真费电。建议配合快捷开关，只在"需要稳定亮度"的时候开。
- **残影/烧屏**：隐形模式是静止的 1 个像素，风险可忽略；但「指示条」样式如果几个月停在同一位置，OLED 上可能留下淡淡痕迹。换个角落或改用隐形模式即可。
- **别把它当护眼工具**：它只解决"换挡时的亮度跳变"，不解决低亮度下的 PWM 频闪。

---

## 七、自己构建

本机环境：JDK 17、Android SDK（路径写在 `local.properties` 的 `sdk.dir`，该文件不入库）、Gradle 9.5.0、AGP 9.3.0。

```bat
:: 用 Android Studio 打开本目录直接 Build 也行
:: 或者命令行：
gradlew.bat assembleDebug
```

如果 wrapper 下载慢，把 `gradle\wrapper\gradle-wrapper.properties` 里的 `distributionUrl` 换成任意 Gradle 9.5.0 镜像，或直接用本机已装好的 Gradle：

```bat
gradle -p . assembleDebug
```

> 构建脚本特意用 `buildscript { classpath(...) } + apply(plugin=...)` 而不是 `plugins {}` 版本目录，
> 这样离线构建时不用去解析插件标记构件；相应地 `app/build.gradle.kts` 里用
> `configure<ApplicationExtension> { }` 配置，而不是类型安全访问器。

---

## 八、代码结构

```
app/src/main/java/com/ltpo120hz/
├── MainActivity.java      主界面（纯代码搭建，无 AppCompat/Material 依赖）
├── LockService.java       前台服务，持有悬浮窗 + 守护检查 + 游戏感知 + 通知
├── OverlayLockView.java   核心：投票 + 逐帧出帧（SurfaceView + Choreographer）
├── RateMonitor.java       DisplayListener 监听刷新率/display mode 变化
├── GameDetector.java      前台应用 → 游戏 / 中性 / 普通 三分类
├── ForegroundTracker.java 前台应用追踪（UsageStats 轮询 + 无障碍上报）
├── GameSenseService.java  可选的无障碍服务，只上报窗口变化
├── SysLock.java           写 min/peak_refresh_rate（可选增强）
├── LockTileService.java   下拉快捷开关磁贴
├── BootReceiver.java      开机自启（默认关闭）
├── StopReceiver.java      通知栏"停止"按钮
├── Prefs.java             配置
├── Logbuf.java            环形日志
└── Ui.java                界面小工具（圆角卡片、分段选择器等）
```

---

## 九、已知限制

- 逐机型没做适配。锁定能否成功取决于 ROM 的刷新率策略（见第五节），面板本身的亮度补偿曲线也不在本项目的能力范围内。
- 游戏识别靠「系统分类 + 包名前缀 + 手动名单」。冷门渠道服可能认不出来，手动标记一下即可。
- 没有单元测试和 CI，验证方式是应用内的 10 秒自检。

## 十、License

[MIT](LICENSE)。随便用、随便改，出问题别找我。
