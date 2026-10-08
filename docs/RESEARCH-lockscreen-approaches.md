# 参考项目调研：锁屏音乐覆盖层的三种实现路径

> 调研日期：2026-10-08 · 设备：`gauguinpro` / Android 16 / OS3.0.303.0.WNKCNXM
> 目的：考察同类模块如何处理「锁屏音乐沉浸封面」，为 Hyper MeloLock 找可移植方案。
>
> **证据等级标注**：`[机]` = 已在本机靴真机/DEX 上核实；`[源]` = 仅在参考项目源码中读到；`[推]` = 推断，未证实。
>
> 参考源码本地浅克隆位置（不入库，用完可删）：
> `D:\Workplace\_ref-insp\HMC`（HyperMusicCover）、`\HC`（HyperChanger）、`\HGCN`（HyperGlow CN+）。

---

## TL;DR

三个项目走了**三条不同的路**，其中一条我们完全没走过：

| 项目 | 封面画在哪 | 本质 | 对我们的价值 |
| --- | --- | --- | --- |
| **HyperMusicCover** (`zyl6932`, Java, 122★, 84k 行) | **`com.miui.miwallpaper` 进程里的 GL 纹理** | 让封面变成真正的系统壁纸 | ★★★★★ [机] 路径已在本机证实存在 |
| **HyperChanger** (`ColdP`, Kotlin, Apache-2.0) | 内嵌了上述同一套 + Shade 幕布层 | 同上，多了通知中心延展 | ★★★★ 同一套的演化版 |
| **HyperGlow CN+** (`aodianjun`, Kotlin, GPL-3.0) | SystemUI 内 `keyguard_translation_info` 的 **index 0** | 只画歌词，不铺封面 | ★★★ 工程方法论最强（行为规格 + 能力探测 + 单测） |

**最重要的一句话**：我们 Currently 把封面当作 **SystemUI 里的一层 View**（背景层挂 `windowRoot`）。而 MIUI 自己的时钟液态玻璃、通知卡模糊采样的是**壁纸窗口**——在 SystemUI 里加 View，**它们永远采样不到**。这不是能通过"挂得更深"解决的，需要在壁纸进程里换纹理。这条路我们**没走过**，且本机符号已验证存在。

---

## 一、HyperMusicCover / HyperChanger：把封面变成壁纸

### 1.1 为什么它要进壁纸进程 `[源]`

`HMC/app/src/main/java/com/os4/musiccover/WallpaperProbe.java:29-38` 的类注释讲得很直白：

> The clock's liquid-glass refraction and the notification/media card blur sample **that window (the wallpaper), not SystemUI's view tree**, so a cover added inside SystemUI can never be picked up by them — the album art has to become the wallpaper here.

这句话解释了我们的结构性困境：我们为了让上滑不露壁纸，把背景层提升到了 `windowRoot`（`LockScreenOverlay.java:1222-1228`），但**提升层级并不能让系统组件采样到它**——采样源根本不是 SystemUI 的 View 树。

两条路径的本质差异：

```
我们目前：     SystemUI 进程 → windowRoot.addView(封面层)     → 只是一层 View
HMC 的做法：      miwallpaper 进程 → hook GL 上传点换 Bitmap      → 成为真正的壁纸
```

### 1.2 本机验证结果 `[机]` ← 本次调研最有价值的产出

在 `gauguinpro` 上确认（非推测）：

| 验证项 | 命令 / 手段 | 结果 |
| --- | --- | --- |
| `com.miui.miwallpaper` 包存在 | `pm list packages \| grep miwall` | ✅ 存在，APK 在 `/product/app/WallpaperOS3/WallpaperOS3.apk` |
| 锁屏壁纸是**独立窗口** | `dumpsys window windows` | ✅ `MiuiKeyguardPictorialWallpaper`（`showWhenLocked=true`），另有桌面用的 `ImageWallpaper`（`showWhenLocked=false`） |
| hook 目标类存在 | 扒 `WallpaperOS3.apk` 的 dex | ✅ `com.miui.miwallpaper.opengl.ImageWallpaperRenderer` + 其内部类 `$WallpaperTexture` |
| **hook 目标方法存在** | dex 字符串检索 | ✅ `lambda$onSurfaceCreated$0$com-miui-miwallpaper-opengl-ImageWallpaperRenderer` |
| 配套符号存在 | 同上 | ✅ `getTextureDimensions`、`AnimImageWallpaperRenderer`、`KeyguardImageEngineImpl` |

> 注意那个方法名带 `$com-miui-...` 后缀——这是 **D8 混淆后的 lambda 名**。HMC 的 `Xp.hookAllLambdas()`（`Xp.java:116-131`）专门就能容错它；照硬编码精确名字去 `getDeclaredMethod` 必然落空（我们在 AGENTS.md 里已记过同类教训：「日志文案 ≠ 方法名」）。

关于「它是怎么区分锁屏和桌面的」——`WallpaperProbe.java:472-511` 用的是**类名里是否含 `Keyguard`**：

```java
Xp.hookAllLambdas(base, "lambda$onSurfaceCreated$0", chain -> {
    Object[] args = chain.getArgs().toArray();
    boolean keyguard = chain.getThisObject().getClass().getName().contains("Keyguard");
    if (keyguard && args.length > 0 && args[0] instanceof Bitmap) {
        Bitmap orig = (Bitmap) args[0];
        Bitmap screen = screenSized(orig);   // 按屏幕尺寸裁，不按壁纸文件尺寸分配
        args[0] = ov;                        // ← 换掉上传的图
        return chain.proceed(args);
```

本机 dex 里确实同时存在 `KeyguardAnimImageWallpaperRenderer` / `DesktopAnimImageWallpaperRenderer` / `KeyguardStreamAnimImageWallpaperRenderer`，命名规律吻合 `[机]`。

### 1.3 它的其余挂载点 `[源]`

除了壁纸进程，它在 SystemUI 侧还用了两处，**本机均已确认资源存在**：

| 挂载点 | 用途 | 本机 `[机]` |
| --- | --- | --- |
| `keyguard_background_layer`（资源 id） | 把封面贴在「整个时钟栈之后」的背景层：`Main.java:8761-8793` | ✅ 存在于 `resources.arsc` |
| `keyguard_foreground_layer` | 歌词层：`LockLyrics.java:1204-1225` | ✅ 存在 |
| `keyguard_clock_container` | 主挂载点，hook 它的 `onAttachedToWindow` `Main.java:65-66,978` | ✅ 存在 |
| `keyguard_translation_info` | HyperGlow 用的插入点，child index 0 | ✅ 存在 |
| `keyguard_root_view` | 字段回退 | ✅ 存在 |

> 我们目前只用了 `windowRoot`（由 `top.getRootView()` 推导得到）+ `notification_panel`。`keyguard_background_layer` 是一个更「正统」的候选：它本来就在时钟栈之后，不需要把层从 keyguard 根提升到窗口根，理论上能减少「这一层的显隐归宿不归我们管」那一类问题。

### 1.4 细节上它做对的几件小事 `[源]`

- **窗口根 index 0 之外还加 `setTranslationZ`**（`ImmersiveHost.java:742-774`）：
  注释说别人把岛放到 Z=-1 时，仅靠 index 会被 SurfaceView 打洞擦掉；并且 `slot.setId(View.generateViewId())` 是为了避免 ConstraintLayout 换蓝图时崩。
  → **我们目前两者都没做**（`LockScreenOverlay.java:1227` 只有 addView），属于低成本加固。
- **逐帧写前先比较** `ClockCollapse.java:2064-2099`：每个 `setScaleX/setTranslationY` 前都判值是否变化。这正是我们踩过的「全屏 RenderEffect 重绘卡顿」的同类防御。
- **反射表用 `ClassValue` 缓存** `Xp.java:334-372`：注释写明是为了消除过渡动画的 jank（`getDeclaredMethods()` 每次新建数组）。
- **bitmap 引用去重** `WallpaperProbe.java:1144-1153`：和我们 AGENTS.md 里记的第 8 条红线完全一致。

---

## 二、HyperGlow CN+：方法论比实现更值钱

它不铺封面，只画歌词，但**把时序/可见性/功耗写成了正式规格**。我们踩过的坑（跨周期复用读不到配置、解锁节奏、覆盖层卡住）本质上都是这类问题时——而我们目前把它们存在 AGENTS.md 的口述经验里。

### 2.1 把「锁屏可见性」写成 8 个 AND 条件 `[源]`

`docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md:805-816`：

```text
feature enabled / supported package versions and required symbol signatures /
default Xiaomi lockscreen theme / primary display / keyguard showing /
not bouncer / fresh visible snapshot / minimum safe scene area
```

代码里就是同一个纯函数 `LockscreenVisibilityPolicy.kt:32-40`。**这类东西可以完全无 Android 依赖地单元测试。**

### 2.2 能力探测取代版本黑名单 `[源]`

这是对我们 `Config.FINGERPRINT` 精确匹配门禁的一记重击。我们是「指纹不匹配 → 整个模块不工作」；他们是：

`XiaomiCapabilityResolver.kt:109-131`，13 个独立 capability，**缺哪个符号只禁掉依赖它的那个特性**：

```kotlin
val aodSurfaceEligible = symbols.aodSurface && symbols.aodHostContainer
val lockscreenHostEligible = symbols.lockscreenHost && symbols.lockscreenController &&
    symbols.lockscreenHostContainer
```

并留了 DexKit 作兜底（`SymbolResolver.kt:20-28`，策略 `DEXKIT_FALLBACK`：反射先答，miss 才用 DexKit）。`docs/DEVICE_COMPAT_MATRIX.md:9-12` 立魂：

> HyperGlow resolves capabilities from **exact symbol probes, not from a device whitelist**: unknown profiles remain fail-closed.

### 2.3 直接命中我们两个已知的坑 `[源]`

**坑一：跨周期复用读不到新配置。** 我们有 `appearanceSignature` 指纹比对，但他们的做法更完整 —— `SystemUiLyricProjection.kt:220-226`：新 surface 挂载时**立即补投**最新的 configuration + snapshot。以及去重时**显式打日志**（`acceptConfiguration`，`:379-409`）：

```kotlin
if (current != null && current.revision == configuration.revision && current.hash == configuration.hash) {
    // 配置已持有相同 revision/hash 时静默返回，此前无任何痕迹；「设置改了实机不变」
    // 类反馈要靠这条区分「没收到」与「收到但判定为重复而丢弃」。
    HookLogger.w(TAG, "Configuration deduplicated rev=${configuration.revision} hash=…")
    return false
}
```

复用判据本身只有一行：`shouldReuseLockscreenHost(currentHost === candidateHost && surfaceAttached)`。

**坑二：危险状态没有兜底。** `ShadeGate.java:39-47` 的 6 秒看门狗无条件还原 panel alpha，注释解释了动机——alpha=0 的面板仍收触摸，会让手机「看起来彻底死掉，只能重启」。我们的覆盖层同样会把原生视图设成 alpha 0 / GONE，**却没有等价兜底**。

### 2.4 增量影子（delta shadow）模式 `[源]`

`ShadeHeader.java:217-252`：改写 OEM 的属性时不写死值，而是记住上次加了多少、还原时减回去。

```java
} else if (cur == sWritten[slot]) {
    if (was == delta) return;     // 普通帧：三次比较，零写入
    next = cur - was + delta;
} else {
    next = cur + delta;           // 不是我们写的 → OEM 刚动过，偏移量叠加在它的值上
```

好处：不破坏 MIUI 自己动画中间态的值。我们 `hideNativeClockLayers` 类操作如果打算改成调属性而不是隐藏，必须按这个模式来。

### 2.5 回归台账 + 一键诊断 `[源]`

- `docs/REGRESSION.md` 是**硬件验证台账**不是脚本：六列 `Date|Version|Area|Result|Device+版本|Evidence`，Evidence 只允许 `device-verified` / `device-smoke-tested` / `trace-observed` 三标签，且**没有新证据不得升级标签**。另有 "Known unverified paths" 是待补验清单。
- `docs/DIAGNOSTIC_REPORTING_SPEC.md`：用户一键导出报告——只跑固定的 root 命令、用户文本永不进命令行、白名单字段、JSON 预览后本地定稿、自动生成 issue 草稿。字段名被单测钉死防止 schema 漂移。

---

## 三、建议清单（按 ROI 排序）

### A. 建议做 —— 低成本，明确收益

| # | 动作 | 依据 | 成本 |
| --- | --- | --- | --- |
| **A1** | 给 `windowRoot` 上的背景层补 `setTranslationZ(...)` + `setId(View.generateViewId())` | `ImmersiveHost.java:767-774` | ~5 行 |
| **A2** | 所有改写 OEM 视图属性的地方改成**增量影子**：记住上次加了多少，还原减回去 | `ShadeHeader.java:217-252`、`MiBlur.java:80-95` | 中，但能消一类还原 bug |
| **A3** | 引入 Detach/隐藏 的**无条件兜底定时器**（类似 6s 看门狗），保证不会让手机变砖态 | `ShadeGate.java:39-47` | ~20 行 |
| **A4** | 配置下发加 `revision + hash` 去重**并显式打日志**（区分"没收到" vs "收到判重丢弃"） | `SystemUiLyricProjection.kt:379-409` | ~15 行，直接治我们最难的 bug |
| **A5** | 建立 `docs/REGRESSION.md` 六列台账 + 三标签，把我们踩过的坑写成条目 | `docs/REGRESSION.md:30-38` | 纯文档，半天 |

### B. 值得做 —— 需要先做验证再决定

**B1. 增加 `com.miui.miwallpaper` 作用域，走「封面成为壁纸」这条路。** `[推]`

这是本次调研最大的发现。理由：
- 本机所有前置符号都已存在 `[机]`，不是理论方案；
- 它对我们的痼疾**结构性免疫**：上滑露壁纸、左侧下拉冲突（我们在 SystemUI 里装触摸钩子去吞事件）、解锁节奏慢——封面不在 SystemUI 视图树里，这些都不存在；
- 顺带解决睹点：封面会被系统时钟玻璃和通知卡模糊采样到，观感是另一档。

风险与未知（**必须说清楚**）：
- 跨进程传封面（它用 `CoverPush` 广播 + `ProbeGuard` 身份校验；SequenceId 在 GCD + 原子文件 tmp+rename）；
- 权限/开机时序：壁纸进程可能早于模块加载，`must "重启作用域"` 要带上它；
- HDR / 动效壁纸 `KeyguardStreamAnimImageWallpaperRenderer` 本机也存在，行为未知；
- **`α` 阶段应与现有 View 方案并存、默认关闭**，而不是替换。

建议第一步：写一个**独立的最小验证**（见下方第六节），只验证「能否替换掉 Keyguard 壁纸窗口的一帧」，拿到结论再谈集成。

**B2. 把 `Config.FINGERPRINT` 单体门禁改成小型 capability probe。** `[推]`
先把现有 3~5 个反射目标做成"符号存在性探测"，缺失就只停用对应特性。（可先不用 DexKit。）

**B3. 试用 `keyguard_background_layer` 作为背景层挂载点候选。** `[机]`
本机资源存在。可能比 "提升出 keyguard 根到 windowRoot" 归宿更干净。可作为 A/B 选项测量后再定。

### C. 不建议照搬

| # | 为什么 |
| --- | --- |
| **C1** 迁移到 libxposed API 102（HMC / HGCN 都用它） | 我们的框架是 **Vector（legacy API）**，迁移是破坏性改动，收益不足以覆盖风险。它们的 hook 套路（模糊匹配 lambda）我们可以在 legacy API 下自己实现。 |
| **C2** 在 SystemUI 进程内跑 Jetpack Compose | **`CoverCompose.java` 名字骗人**——它其实是纯 `Bitmap + Canvas` 合成工具，不是 Compose `[源]`。全包 grep `ComposeView / @Composable` 零命中。SystemUI 内跑 Compose 没有现成先例，且要引入大量依赖到宿主进程。 |
| **C3** 抄它们的歌词子系统（1693 行 `LyricSource` + 2655 行 `LyricView`） | 规模巨大且依赖外部数据源 / 第三方歌词模块。我们已撤回歌词，不应因这次调研重新计入。 |
| **C4** `MiBlur` 私有 API 的**渐变**模糊 | `MiBlur.java:10-15` 明确记录：渐变的 `setBackgroundGradientBlurParams` / `setMiBackgroundBlurType(2)` 被**永久删除**，因为 adreno GPU 上频繁更新会 RenderThread SIGSEGV in `vulkan.adreno.so`。 |

---

## 四、一个有意思的旁证

`WallpaperProbe.java:465-511` 之外，dex 里出现了一个值得注意的字符串：`ImageAlbumCover`（在 `WallpaperTexture` 附近）`[机]`。
`[推]` 这可能意味着 miwallpaper 本身就自带「专辑封面」这一壁纸类型——若属实，顺着官方结构走会比硬替换纹理更稳。**未验证**，留给 B1 的第一步去查。

---

## 五、下线前必读

**这套调研最重要的一条结论：把工作换到别的进程/别的宿主去做（结构性替换），比在自己的那一层里继续加耦合更有前途。**
在 SystemUI 里继续挂更多层、装更多钩子，只能不断逼近别人的观感；而进入壁纸进程，是可以一次性越过整类问题的那条路。

**其次是方法论：把时序规则写成可断言的纯函数 + 显式日志，比我们把经验堆积在 AGENTS.md 里更可持续。**

---

## 五之二、专题：解锁节奏「两头堵」的根因，以及换纹理为什么是解法

> 2026-10-08 追加。这是用户当前最痛的问题，也是判断是否值得走 B1（换纹理）的直接依据。

### 现象

解锁时两种都不对，且只能二选一：

- **早掀盖** → 看到「原生壁纸（只有壁纸，没有时钟/组件）」，能看见桌面入场动效；
- **晚掀盖** → 封面盖太久，看不到桌面入场动效，像「卡在专辑锁屏」。

### 根因：我们的封面不在系统解锁动画的作用域里

真机时间线（`keyguardGoingAway` 为 0 点，2026-10-08 实测）：

```text
+127ms  wms.showSurfaceRobustly …Launcher            ← 桌面窗口合成
+148ms  KeyguardService IRemoteAnimationRunner       ← 系统解锁动画开始
+340ms  updateKeyguardWallpaperStateAnim anim=true   ← 系统开始收「锁屏壁纸窗口」
+685ms  updateKeyguardWallpaperStateAnim onAnimationFinished
+742ms  WallpaperWindowToken{lock} isVisible=false / {desktop} isVisible=true
```

`updateKeyguardWallpaperState`（`[机]` 在 `WallpaperOS3.apk` 的 dex 中命中 9 次，含
`updateKeyguardWallpaperState: show = `）说明：**系统解锁时会让「锁屏壁纸窗口」自己跑一段退场动画**，
时长约 340→685ms（≈345ms）。

而我们的封面是 **SystemUI 视图树里的一层 View**：

- 不 lift：锁屏根会被系统置 `INVISIBLE + alpha 0`，封面**硬消失**（不是渐变），且此时桌面可能还没合成完 → 露出原生壁纸；
- lift 到窗口根：我们自己掐 `HOLD/FADE` 淡出；封面一淡，底下露出的是**还没退完的原生锁屏壁纸窗口**。

**两条路都有一个共同的本质问题：封面之外还活着一层原生壁纸，它比我们活得久。**
早掀盖它露脸，晚掀盖它虽然不露脸但我们把桌面动效也盖住了——**这是一个旋钮的两端，调参无解**。

> 这也解释了为什么 `UNLOCK_COVER_HOLD_MS` 从 130 → 240 → 280 → 760 反复调都不对：
> 我们是在用「自己一层 View 的 alpha 动画」去模拟「系统壁纸窗口的退场动画」，
> 两条时间线永远对不齐。

### 换纹理为什么能解

如果专辑图**就是锁屏壁纸的纹理**，那么：

| | 当前 | 换纹理后 |
| --- | --- | --- |
| 封面属于谁 | 我们的一层 View | 系统壁纸窗口的一部分 |
| 解锁时谁负责让它退场 | 我们掐 `HOLD/FADE` | **系统** `updateKeyguardWallpaperStateAnim` |
| 时间线 | 我们的 + 系统的，两条 | **同一条** |
| 背景层还需要吗 | 需要，且要 lift / 掐时机 | **不需要** —— 掀盖时机这个问题整体消失 |
| 中间态 | 必然露出原生壁纸 | 无中间态：专辑壁纸随系统动画直接交叉到桌面 |

换句话说：**不是把参数调准，而是把「我们自己掀盖子」这件事从架构里删掉**，
让系统用它对壁纸的原生处理方式带走我们的封面。桌面动效该露出就露出——因为那本来就是系统自己的节奏。

### 本机前提核查结果 `[机]`

| 核查项 | 结果 | 含义 |
| --- | --- | --- |
| `flag_lock_wallpaper_type` | **`image`** | 锁屏壁纸是静态图，**不是画报轮播** → 不会和系统「每次亮屏自动换图」打架 ✅ |
| `lockscreenInfo.wallpaperInfo.resourceType` | `image`，`originResourcePath` 指向 `02_XiaomiCar/XiaomiCar02.jpg` | 同上，单张静态图 |
| `wallpaper_changed_1/2` | `com.miui.aod` | 壁纸由 AOD 服务管理 |
| `wallpaper_matting_support_1/2` | `1`，且 `supportSubject: true` + 存在 `*_MASK.jpg` | ⚠️ 本机是**景深/抠图壁纸**（有 subject + mask）。换纹理时若不处理 mask，可能出现主体抠图异常——**新增的已知风险** |
| `com.miui.miwallpaper` 进程 | 存活（PID 3803） | 可注入 |

`MiuiKeyguardPictorialWallpaper` 是窗口类名，**不代表启用了画报轮播**（`flag_lock_wallpaper_type=image` 已证伪）。

### 必须说清的不确定性

1. **没有人替我们验证过这一点**。HMC / HyperChanger 自己**并未**宣称解决了解锁节奏——它们仍然保留自己的延迟释放逻辑（如 `ImmersiveHost` 解锁后 3000ms 才释放）。所以「换纹理能根治解锁节奏」是**基于机制的推断 `[推]`**，不是别人的实测结论。
2. **跨进程传封面的延迟**：切歌时壁纸更新可能滞后于前景卡片（它们用广播 + 原子文件交接）。
3. **景深壁纸路径未知**：本机壁纸带 subject/mask，换纹理后系统是否照常合成 subject 未验。
4. **注入成本**：要加 `com.miui.miwallpaper` 作用域，并重启**该进程**（不是 SystemUI）。

### 建议的推进方式

不要直接改 `LockScreenOverlay`。先做一个**只回答「能不能换掉一帧」**的最小探针（半天）：

1. 独立 Xposed 入口 `WallpaperTexProbe`（登记进 `xposed_init`，结论拿到后源码与清单一并删除——按 AGENTS.md 的约定）；
2. 只做一件事：在 `ImageWallpaperRenderer` 的 lambda 上传点把 Bitmap 换成**纯品红**；
3. **成功判据**：锁屏亮屏看到整屏品红。看到 = 核心假设成立，值得继续；看不到 = 立刻回头，省下几天。
4. 第二步才验证「换的是 Keyguard 那个实例」（用类名含 `Keyguard` 判据），以及景深壁纸下是否仍成立。

---

## 六、附录：验证方法与可复现命令命令

```bash
ADB="C:/Users/lirui/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# 1. 确认壁纸进程 / 窗口
"$ADB" -s 1b3a7d8 shell pm list packages | grep -iE "miwall|aod"
"$ADB" -s 1b3a7d8 shell dumpsys window windows | grep -iE "wallpaper"

# 2. 拉取 APK 扒 dex（锁屏/桌面壁纸两套渲染器、lambda 符号）
"$ADB" -s 1b3a7d8 shell pm path com.miui.miwallpaper     # → WallpaperOS3.apk
"$ADB" -s 1b3a7d8 pull /product/app/WallpaperOS3/WallpaperOS3.apk <目标>
python scan_dex.py <apk>          # 列出所有含 Render/Keyguard/opengl 的类型描述串
python find_syms.py <apk> "ImageWallpaperRenderer,onSurfaceCreated,getTextureDimensions"

# 3. 确认 SystemUI 侧的 keyguard 层资源名存在（在 resources.arsc，不在 dex）
"$ADB" -s 1b3a7d8 pull /system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk <目标>
python find_res.py                # 搜 keyguard_translation_info / *_background_layer / ...
```

脚本在 `D:\Workplace\_ref-insp\{scan_dex,find_syms,find_res}.py`（请随 `_ref-insp` 一起清理，或移入 `tools/` 保存）。
**注意**：`uiautomator dump` 拿 keyguard 层 id 必须在**锁屏且亮屏**时抓，息屏抓不到（与既有经验一致）。

## 七、参考索引

| 主题 | 位置 |
| --- | --- |
| 壁纸进程替换 | `HMC/.../WallpaperProbe.java:29-38, 465-511, 1144-1153`；`HC/.../hypermusiccover/WallpaperProbe.java:472-511, 2019-2037` |
| 窗口根插层的 Z 与 id | `HMC/.../ImmersiveHost.java:742-774` |
| 时钟收拢（增量比较） | `HMC/.../ClockCollapse.java:2064-2099`；`HC/.../ClockCollapse.java:8-27` |
| Xposed 工具层（模糊 lambda + ClassValue 缓存） | `HMC 与 HC/.../Xp.java:91-131, 242-280, 334-372` |
| Shade 幕布层 / 增量影子 / 看门狗 | `HC/.../hypermusiccover/ShadeLayer.java:14-36, 320-393`；`ShadeHeader.java:217-252`；`ShadeGate.java:39-47` |
| MIUI 私有模糊的配方与禁用项 | `HC/.../hypermusiccover/MiBlur.java:43-54, 80-95, 126-148, 10-15` |
| 逐 hook 独立降级 | `HC 与 HMC/.../Main.java:965-975` |
| 行为规格（可见性/功耗/burn-in） | `HGCN/docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md:805-816, 826-832, 835-867` |
| 能力探测 | `HGCN/app/.../XiaomiCapabilityResolver.kt:109-131`；`docs/DEVICE_COMPAT_MATRIX.md:9-12` |
| 复用判据 + attach 补投 | `HGCN/app/.../LockscreenSurfaceController.kt:169-202`；`SystemUiLyricProjection.kt:220-226, 379-409` |
| 回归台账与诊断报告 | `HGCN/docs/REGRESSION.md:30-38`；`docs/DIAGNOSTIC_REPORTING_SPEC.md:107-129` |
