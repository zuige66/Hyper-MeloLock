# Hyper MeloLock — 开发文档

> 本文件是 Hyper MeloLock 的**开发与实现文档**：当前实现细节、SystemUI 侧机制、真机验收步骤、故障恢复手段与完整变更日志。
> 面向使用者的项目介绍、功能说明与安装指引见仓库根目录的 [README.md](../README.md)；面向 AI 协作者的工程约定见 [AGENTS.md](../AGENTS.md)。
>
> 下文中的路径、命令、设备序列号与版本号均以 2026-10-06 的验证环境为准。

独立的 Android Vector/LSPosed 模块；不修改 Cool Music 或其他播放器。只给 `com.android.systemui` 注入一个锁屏视图适配器。媒体发现、封面和播放控制使用 Android `MediaSessionManager` / `MediaController`。当前仅为指定真机构建开放，默认关闭。

- **仓库**：<https://github.com/zuige66/Hyper-MeloLock>
- **下载**：[Releases](https://github.com/zuige66/Hyper-MeloLock/releases) → 最新版 [v0.2.0](https://github.com/zuige66/Hyper-MeloLock/releases/download/v0.2.0/Hyper-MeloLock-v0.2.0.apk)（`Hyper-MeloLock-v0.2.0.apk`，正式签名，37.1 MB）
- **许可**：AGPL-3.0（见 `LICENSE`）

> 当前唯一验证设备是下面那台 Redmi Note 9 Pro；模块按**精确构建指纹**门禁（`Config.deviceSupported()`），换机型不会生效。

## 已核实的设备

2026-10-06 通过 ADB 读取：

| 项目 | 值 |
| --- | --- |
| 设备 | Redmi Note 9 Pro，`M2007J17C`，`gauguinpro` |
| Android | 16，API 36 |
| 构建指纹 | `Redmi/gauguinpro/gauguinpro:16/BP2A.250605.031.A3/OS3.0.303.0.WNKCNXM:user/release-keys` |
| SystemUI | `com.android.systemui`，`16.03.251211.r`，versionCode `202501210` |
| SystemUI 插件 | `miui.systemui.plugin`，`17.1.4.26.0`，versionCode `171042600` |
| SukiSU Ultra 应用 | `com.sukisu.ultra`，`v3.1.8` |

已从该设备 APK 的 DEX 确认 `com.android.keyguard.widget.HyperOSKeyguardRootView`、`KeyguardClockContainer`、`NotificationStackScrollLayout` 类存在。当前适配器只在上述**精确构建指纹和两个 APK 版本**匹配时工作；根视图中缺少时钟或通知栈时也会放弃覆盖。

## 当前实现

### 配置应用界面

配置端现直接迁入并使用 HyperIsland 的 Kotlin、Jetpack Compose、Miuix 页面壳层、主题、动画和液态导航栏（MIT License），四个入口的版式也改为直接复用 HyperIsland 原版组件，不再自绘样式：

- **首页**：对齐 HyperIsland 首页版式 —— 顶部大标题加刷新按钮，第一行是「方形激活卡（大号对勾底纹）」加右侧两张数据卡，下面是（按需出现的）适配告警卡、系统信息卡、链接卡和使用说明卡。
  - 状态卡点击即切换模块总开关，标签在 `已激活` / `未激活` 之间切换。
  - 左数据卡为「已开启应用」，取 `Config.enabledAppCount()`：未设置时显示 `全部`（表示允许所有播放器），全部取消时显示 `0`，否则是勾选数量；点击跳到「音乐应用」页。
  - 右数据卡为「封面圆角」，取 `Config.cornerRadiusDp()`；点击跳到「外观」页。
  - 告警卡仅在 `Config.deviceSupported()` 为假（设备构建指纹不在已验证列表内）时出现，与 `HookEntry` 的加载门禁同一判据。
  - 系统信息卡四行：系统版本、应用版本、Xposed 框架、设备型号。前三行中 Xposed 框架依赖 Vector/LSPosed 服务绑定（`XposedPrefsSyncApp.awaitReady()`）；legacy 模块拿不到服务时显示 `未知`。
- **音乐应用**：对齐 HyperIsland「应用」页 —— 搜索栏加应用行（图标、名称、包名、开关），整行点击也可切换。**列表是全部已安装应用**（含系统应用，可用「显示系统应用」开关过滤），由用户自行勾选哪些是音乐播放器。首次进入表示允许全部；取消勾选后落成显式白名单，全部取消后锁屏不再接管任何播放器。HyperOS 上枚举全部应用需要 `com.android.permission.GET_INSTALLED_APPS`，**该权限必须在 Manifest 里显式声明**：申请一个未声明的权限，系统会直接返回拒绝、连授权框都不弹（这正是 2026-10-06 第一次改完列表为空、看不到授权框的原因）。进入该页时会自动申请，实测弹出 MIUI 的「获取已安装的应用信息」授权框。
- **外观**：锁屏三元素（时间 / 专辑封面 / 播放器）的编辑器，外加原有的背景设置。配置通过只读 `ContentProvider` 同步给 SystemUI。
- **开发者**：应用图标 + 名称 + 版本、开发者 `zuige`、GitHub `zuige66`、开源说明。**联系方式与捐赠/教程/资源外链仍留空**，对应行显示「待填写」并置灰不可点击。

四页共用的卡片来自 HyperIsland 原版实现：`OverviewPage.kt` 里原先私有的 `StatusGrid` / `StatusCard` / `StatCard` / `InfoCard` / 告警卡已提升为 `internal` 的 `OverviewStatusGrid` / `OverviewStatusCard` / `OverviewStatCard` / `OverviewInfoCard` / `OverviewAlertCard`，只把标题与数值参数化，视觉与交互代码未改动。HyperIsland 自己的首页（`OverviewPage`）改为调用同一批组件，因此不存在第二份样式实现。音乐应用页的行样式沿用 HyperIsland `AppsPage` 的 `Card` + `BasicComponent` 组合，图标复用 `InstalledAppsRepository` 的缓存与解码逻辑。

AppShell 的根分页把 `isActive` 传给首页，首页在重新可见时重读配置，因此从其他页改完设置回到首页，数据卡会刷新。

原有 Java/Xposed 锁屏适配链路保留；Compose 页面通过公开的 `Config` API 与同一 ContentProvider 配置同步。`Config.java` 新增 `selectedPackages()`、`allPackagesDisabled()`、`enabledAppCount()`、`deviceSupported()` 四个只读辅助方法，用于区分「未设置（允许全部）」与「已全部取消」两种空集，没有改动任何配置键或读写语义。构建环境已升级到 AGP 9.3.1、Gradle 9.5、Kotlin/Compose 2.4.10。

界面文案现状：这四个页面沿用原有实现，标题与说明文本直接写中文，只有导航栏、系统信息行和链接行使用 `strings.xml` 资源；`待填写` 也是硬编码中文。后续要补多语言时需要一并抽到资源。

### 锁屏元素编辑（时间 / 专辑封面 / 播放器）

`外观` 页把锁屏上三个自绘元素拆成三张卡，每张卡都能调大小、圆角和间距：

| 元素 | 控制项 | 默认值 | 备注 |
| --- | --- | --- | --- |
| 时间 | 字号、粗细、字体圆润、颜色、距顶部 | 字号 75、粗细 770、圆润 0、白色、距顶部 52dp | 时间是文本，不设固定宽高，始终自适应字号与字体，不会被裁剪 |
| 专辑封面 | 锁定比例、缩放或宽/高、圆角、间距 | 缩放 118%、R角 28dp、间距 24dp | 圆角沿用原有的 `corner_radius_dp`，首页数据卡读的就是它 |
| 播放器 | 锁定比例、缩放或宽/高、圆角、间距 | 缩放 100%、R角 28dp、间距 20dp | 卡片宽度默认是「屏宽 − 24dp」 |

**上面的默认值是 2026-10-06 从真机调好的一套抄回来的**（`content query .../elements`）：字号 75 / 粗细 770 / 封面缩放 118% / 圆角 28dp。背景三项的默认值（遮罩样式 0、颜色 `0xFF111827`、强度 150）当时已与真机一致，未改。时间是文本，**锁定比例与宽/高已在同日移除**——固定高度会裁掉大字号和自定义字体，位置改由「距顶部」控制。

尺寸单位是 dp 绝对值。**锁定比例**开启时用一个缩放滑杆（时间用字号）等比调整；关闭后出现宽、高两个滑杆，封面在这种模式下按居中裁切填充，所以宽高比不同不会变形只会裁切。

配置实现：键名与默认值集中在 `Config.java` 的 `ELEMENT_DEFAULTS`，统一按字符串存取。`ConfigProvider` 新增 `elements` 路径（`content://io.github.melolock.config/elements`），返回 key/value 两列，因此以后再加参数不需要改 Provider 的列投影。SystemUI 侧由 `Config.elementValues()` 一次查询解析成整数表，读不到时回落默认值，不会撤掉沉浸页。

覆盖层把三元素算尺寸的逻辑集中在 `LockScreenOverlay.measureElements()`：锁定比例取默认尺寸乘百分比，解锁则取各自 dp 值，`0` 表示跟随默认。间距统一成「距上一个元素」：时间=距内容区顶部、封面=距时间、播放器=距封面，默认值与改版前完全一致。

**改完参数后重启 SystemUI 才稳妥**：覆盖层在 `create()` 时读一次配置。自「熄屏唤醒防闪」之后 `ACTION_SCREEN_OFF` 不再撤层，场景若仍存活就会继续沿用旧参数——灭屏再亮屏**不一定**生效，只有场景已被销毁（解锁、关闭模块、锁屏根视图分离）后重建才会读到新值。没有做实时重建——在锁屏期间动态增删 SystemUI 视图风险不可控。

### 左侧通知栏下拉：改成直接禁用该手势（当前方案）

**目标**：锁屏沉浸场景显示期间，不响应左侧下拉（通知栏），从源头避免重叠；右侧控制中心不受影响。

**为什么不再做「状态检测」**：左边通知栏是否展开，试过两个信号，都被真机证伪：

| 信号 | 为什么不能用 |
| --- | --- |
| 通知栈（`NotificationStackScrollLayout`）的可见性 | 亮屏、锁屏重排、我们自己的 `restoreChangedViews()` 都会改它 → 误判后状态永久卡住，**沉浸场景彻底消失且不恢复** |
| `notification_panel` 的可见性 | 真机日志显示**亮屏后 76ms 它自己就变 VISIBLE**（它表示「锁屏在显示、通知栏可下拉」，不是「拉下来了」）→ 每次亮屏把刚恢复的场景再藏掉，表现为「原生屏保 + 沉浸式闪一下」 |

**根因确实是 z 序**（这也是「左重叠、右正常」的原因）。`uiautomator dump` + 模块自打的 `Window tree:` 显示窗口根的子节点顺序为 `notification_panel` → … → `control_center_container` → `keyguard_root_view`，自绘层挂在最后一个子节点（画在最上）→ 压在左侧通知栏之上，但在右侧控制中心之下。

**当前做法：在通知面板的触摸入口吃掉左半屏手势。** 自绘层压在面板之上且不消费整块触摸，所以手势仍会派发到面板；在面板的 `dispatchTouchEvent` / `onInterceptTouchEvent` / `onTouchEvent` 上挂 Xposed 钩子，沉浸场景显示期间对**左半屏的 ACTION_DOWN** 直接返回 false（当作没处理）→ 通知栏不会展开。右侧控制中心在独立容器 `control_center_container`，不经过这个视图，所以照旧可用。

**踩过的坑（不要再犯）：钩继承来的方法必须判 `thisObject`。** `NotificationPanelView` **没有覆写** `dispatchTouchEvent`，`panelClass.getMethod("dispatchTouchEvent", …)` 返回的是**框架基类 `android.view.View` 的实现**。钩它等于给 SystemUI 里**所有 View** 装钩子：第一版没有 `thisObject` 判定，结果把左半屏的触摸**全部**吃掉——播放器的 ◀ 播放/暂停（横跨屏幕中线左侧）和「展开通知」按钮（居中）一起失灵。修法是在 `beforeHookedMethod` 里第一行就 `if (hook.thisObject != leftShadePanel) return;`，只处理面板自己。`findTouchMethod()` 会优先取类自己声明的方法，但退回继承实现是常态，所以这个判定**必须**保留。

同理，**不要把这些放进触摸路径**：曾经为了找可靠信号，在 pre-draw 守卫里每帧读 12 个视图属性 + 反射调用 7 个 getter（`Shade watch:` / `Expansion getters:`），既耗 CPU 又诱导后人用不可靠信号；手势拦截落地后已整体删除。

判断「场景正在显示」用的是模块自己的状态（`sceneShowing()` = 场景存在 ∧ 未 suspend ∧ 锁屏周期），**不依赖任何系统视图的可见性**。

**`sceneShowing()` 里绝对不能带「播放器页在前」（`playerSceneVisible`）**：展开通知时它会被置 false，但场景（模糊背景 + 时钟）仍在锁屏上，此时左下拉照样会拉出原生通知面板 → 原生锁屏时钟和我们的时钟就重叠出现了（真机踩过，日志表现为展开期间 `Left-shade gesture consumed` 一条都没有）。判据必须是「场景是否还占着锁屏」。

**代价（已知并接受）**：左半屏起始的「上滑解锁」也会被吃掉。右半屏、指纹、电源键不受影响。因此配置里带一个开关：

- 外观 → 播放器 → **锁屏禁止左下拉**（`Config.BLOCK_LEFT_SHADE`，默认开）
- 开关**每次手势现读**，改完立即生效，不需要重建场景或重启 SystemUI

真机核对：

```bash
adb -s 1b3a7d8 logcat -d | grep -E "Left-shade touch block armed|Left-shade gesture consumed"
# armed    → 钩子装上了（含面板真实类名）
# consumed → 每次成功吃掉的手势，带 x 坐标与序号
```

**铁律**：不改 `suspend()` / `resume()` 内部时序（那是「熄屏秒显 + 解锁撤层」的地基，改过就出「快速开关屏露原屏保」回归）。

### 切歌的空窗期：保住上一帧，不撤层

真机日志显示，换歌时 App 会先**摘掉 metadata 里的封面 bitmap、只留 URI**，约 300ms 后才补齐新封面；这段时间内会话也可能短暂不合格（playbackState 为空或非 PLAYING）。三条旧的处理会把这个过渡态当成「播放停了」：

| 旧行为 | 后果 |
| --- | --- |
| 封面 URI 解码失败就 `lastReady = null` 并下发 `onMedia(null)` | 后续 refresh 全部落到「无缓存」，最后被判成无会话 → 撤层 |
| 会话一不合格立刻下发 `onMedia(null)` | `render()` 走 `restore("render-no-session")`，**露原生锁屏** |
| 会话不合格时把 `current` 置空（注销回调） | 新封面到达时没人唤醒，只能等下一次「活动会话变化」事件 |

现在统一在 `MediaSource` 里收敛处理：

1. **封面解不出来 ≠ 播放停了**（SystemUI 常常读不到 App 给的 `content://` 权限或私有文件）：保留上一帧继续显示，超时也一样保留，等 App 补齐；
2. **会话失去给 1500ms 宽限期**（`SESSION_GRACE_MS`）：期间继续交出上一帧，超时确认没会话才回退；真的暂停另有分支保持画面；
3. **空窗期按包名继续跟踪会话**（日志 `Empty gap detected; keep tracking …`），保住 `MediaController` 回调，新封面一到就立刻刷新。

对应日志只有三行，一眼可判：`Session unavailable … keeping last frame up to 1500ms` / `Empty gap detected` / `Media ready from bitmap title=<新歌>`。**验收标准：切歌全程不应出现 `restore reason=render-no-session` 与 `create()`。**

### 播放器页 ↔ 通知页的切换动效（共享元素）

两页之间有两个"播放器"：自绘的播放器卡片，和通知列表里的原生媒体通知卡。以前 `showNotifications()`
只是 `show(notifications)` 让通知**瞬间满不透明出现**，而播放器还在跑 220ms 的淡出 → 两者并列，
就是用户看到的"通知先出来、播放器还没消失"。

现在改成一次**共享元素的位移过渡**：

1. **`measureSwapOffset()`**：取通知列表里第一张高度够大的卡片（通常是原生媒体卡）与自绘播放器卡片的
   屏幕坐标差，**上限 `dp(96)`**；没完成布局时回退 `-dp(52)`。
   上限是后加的：实测这个偏移很容易量到 600px 以上，按原值让卡片飞过去会**穿过时钟区域**再回来，
   看起来就是「时间闪一下」。而且**测量值每次不同**（真机日志展开 `-605px` vs 返回 `-228px`），
   所以展开时量一次存进 `lastSwapOffset`，返回时复用它——两个方向必须对称。
2. **展开**：播放器卡沿该位移滑向目的地，`alpha → 0`、`scale → 0.94`（220ms）；封面走 35% 位移（180ms）；
   通知栈以 `alpha 0 → 1`、18% 位移**延迟 70ms** 淡入（210ms），期间给通知栈开硬件层
   （`beginNotificationsLayer()`，300ms 后 Handler 兜底拆除）。
3. **返回**：通知**直接收起，不做淡出**；播放器从目的地滑回原位伴 `scale 0.94 → 1`（延迟 40ms、240ms）。

三个"看起来不对"的坑：

- **返回时不要淡入 `foreground`**（真机反馈「切回播放器时间闪一下」的**真凶**）：前景（含时钟）
  在通知页一直是显示的，旧代码把它从 `alpha 0` 淡入 180ms，等于让时钟先消失再回来。只让封面和
  卡片滑进来就够。
- **返回时通知不做淡出**：淡出层正好盖在时钟区域上，那一层任何合成抖动看起来都是「时间在闪」。
- **`bringToFront()` 不要每帧调**：pre-draw 守卫每帧调用它会让窗口根每帧 `requestLayout`，
  是切页掉帧的实打实来源。现在走 `ensureOnTop()`，只有确实不在最上层时才动 z 序。

另一个独立的泄漏（用户反馈"桌面左下拉会露出原屏保时间"）：`suspended`（场景实例保留但置 GONE）期间，
系统在**桌面下拉通知栏**时会把原生锁屏时钟重新显示出来。所以 suspended 分支里**继续按住原生时钟层**
（`hideNativeClockLayers`），但**绝不碰壁纸层**（解锁动画要靠它，隐藏会露黑底）。

日志：`Page swap: to notifications, shared offset=<n>px` / `Page swap: back to player, shared offset=<n>px`。
**两个值应该一致**（有缓存）；一直等于回退值（-52dp 附近）说明测量没生效。

### 改配置后必须重启 SystemUI

这是本项目最容易误判成 bug 的地方：**Hook 代码是注入 SystemUI 进程执行的，装完新 APK 不重启 SystemUI，跑的还是旧代码。**

2026-10-06 实测案例：外观页改完字号/粗细/封面缩放后，锁屏上「只有圆角生效，其他都不行」。查下来 `content query` 两条通道都返回了正确的新值，问题在于 SystemUI 进程启动时间早于 APK 安装时间，仍在跑不认识新键的旧覆盖层代码——旧代码只读 `corner_radius_dp`，所以正好只有圆角生效。

排查这类问题先看两条命令：

```bash
# 进程启动时间是否晚于 APK 安装时间
adb -s 1b3a7d8 shell "ps -A -o PID,ETIME,NAME | grep -i com.android.systemui"
# 覆盖层每次创建都会打印本次实际取到的参数，有这一行才说明跑的是新代码
adb -s 1b3a7d8 logcat -d | grep "Elements: clock="
```

**本机怎么重启 SystemUI**（2026-10-06 实测，从 adb 就能做，不需要 root）：

```bash
adb -s 1b3a7d8 shell am crash com.android.systemui    # ← 有效：SystemUI 会立刻以新 PID 重启
adb -s 1b3a7d8 shell am force-stop com.android.systemui   # 无效：返回成功但进程不死（HyperOS 保护）
adb -s 1b3a7d8 shell su -c 'killall com.android.systemui' # 不可用：su 未放行 shell（SukiSU）
```

`am crash` 会让 SystemUI 进程崩掉并自动重启，实测 PID 立刻变化、模块重新注入。重启后确认这三行出现，说明模块已重新注入：`SystemUI root constructor hook installed` → `Keyguard root attached` → `Module enabled=true`。重启后确认 `Keyguard root attached` / `Module enabled=true` 出现，说明模块已重新注入。

**顺序很重要：先装 APK，再重启 SystemUI。** 装之前重启等于白重启。

### 时间字体的圆润与粗细

文字没有几何圆角，Android 也没有「圆角字体」API。实测本机 `MiSansVF.ttf` 与 `MiSansLatinVF.ttf` 只有 `wght` 一个可变轴，`fonts.xml` 里也没有 `sans-serif-rounded`，所以「圆润度」只能靠换字体实现。

做法是往 APK 里内置两个 OFL 开源圆体数字字体，`圆润` 下拉提供三档：

| 档位 | 字体 | 来源 | 文件 |
| --- | --- | --- | --- |
| 0 直角 | 系统字体（MiSans / sans-serif） | 系统 | 无 |
| 1 中等圆 | Quicksand（可变，wght 300–700） | Google Fonts，OFL-1.1 | `assets/fonts/clock_round_1.ttf`，122 KB |
| 2 很圆 | Baloo 2（可变，wght 400–800） | Google Fonts，OFL-1.1 | `assets/fonts/clock_round_2.ttf`，667 KB |

锁屏时间只渲染 `0-9` 和 `:`，所以这两个字体不覆盖中文也不影响显示，中文仍走系统字体。**粗细**用可变字体的 `wght` 轴实现，是 100–900 连续可调（`Paint.setFontVariationSettings`），档 0 走系统字体的 `wght`，档 1/2 走内置字体的 `wght` 区间。

字体由 SystemUI 进程在首次需要时从模块 APK 的 `assets` 解出、写入自己的缓存目录再 `Typeface.createFromFile`（`LockScreenOverlay.roundTypeface()`），每个档位全进程只解一次；任何失败都会静默退回系统字体并打日志，绝不会因为字体问题撤掉沉浸页。

为避免把 HyperIsland 的更新内容带入本项目，当前配置端已关闭启动时环境统计，并在最终 APK 中移除 `INTERNET` 权限。HyperIsland 的更新检查、预设云端下载和外部资源链接源码仍随迁入代码保留，但当前四页不会调用；APK 不声明安装权限，也不会自动安装其他 APK。

- 播放中的标准 MediaSession 提供标题、歌手、封面及上一首、暂停、下一首操作；支持封面 Bitmap 和 URI。无播放会话、封面无效、URI 读取失败时恢复原生锁屏。
- 封面显示在中间，同一封面经 `RenderEffect` 模糊并加暗色遮罩作为背景。顶部用 `TextClock` 显示时间，下方显示播放控制。
- 有媒体时收起原生时钟及通知栈；`展开通知` 按钮可恢复原生通知区域，`返回播放器` 可收起。原生解锁与紧急操作视图没有被移除，底部区域留给系统交互。
- **解锁时覆盖层淡出后保留实例**（`LockScreenOverlay.suspend()` / `resume()`），不销毁场景。锁屏重新出现时直接复用同一批视图，亮屏即显示、零重建。只有在模块关闭、媒体不可用、锁屏根视图分离时才真正销毁。
- 0.1.2 增加锁屏态门禁：仅在系统报告锁屏、屏幕交互中、媒体会话和封面有效时创建覆盖层；并以 `MeloLock` 标签记录钩子、视图适配和开关状态。
- 应用内开关默认关闭，保存在模块私有配置中；SystemUI 通过模块的只读配置接口读取，不需要“修改系统设置”权限。后台钩子仍须先在 Vector 中启用并以 `SystemUI` 为唯一作用域。

## 构建与安装

使用 JDK 21、Android SDK 36 和项目自带的 Gradle Wrapper。

```bash
./gradlew.bat :app:assembleDebug      # 调试包：app/build/outputs/apk/debug/app-debug.apk
./gradlew.bat :app:assembleRelease    # 发布包：app/build/outputs/apk/release/app-release.apk
```

### 发布签名

Release 用仓库根的 `melolock-release.keystore`（PKCS12，10 年有效）签名，口令放在 `keystore.properties`。**这两个文件都在 `.gitignore` 里，绝不进仓库**——密钥与口令丢了就无法再给同一个应用升级，请自行另存备份。

```properties
STORE_FILE=melolock-release.keystore
STORE_PASSWORD=<口令>
KEY_ALIAS=melolock
KEY_PASSWORD=<口令>
```

`app/build.gradle.kts` 只在 `keystore.properties` 存在时才创建 `release` signingConfig；没有它时 release 构建照样能跑（回退到 debug 签名），但**那样的包不能上传 Release**。正式签名目前是 v1 + v2 + v3 全签，证书指纹：

```
SHA-1:   2B:73:26:5B:F5:0D:BA:65:76:A7:75:8C:55:23:40:CD:C5:4E:C1:F7
SHA-256: 65:7C:4D:30:52:BC:13:18:98:1E:65:F6:7E:8B:0A:DD:DE:E6:72:01:DB:AC:92:A1:86:F2:E8:58:69:53:D0:FC
```

校验方式：`apksigner verify --print-certs -v app/build/outputs/apk/release/app-release.apk`。

**Release 不开混淆**：这是 Xposed 模块，Hook 与 ROM 内部视图都靠类名/方法名字符串定位，R8 收益极小、风险不小。另外迁入的 HyperIsland 多语言资源里有第三方库遗留的 `ExtraTranslation`，所以 `lint.checkReleaseBuilds` 关掉了，否则 release 会被它们拦住。

Windows 上 `:app:dexBuilderDebug` 偶尔会以 `Unable to delete directory ... project_dex_archive` 或 `desugar_graph\\...\\graph.bin (拒绝访问)` 失败：这是杀毒/索引进程仍占用刚生成的 `.dex`，不是代码问题（Kotlin 与 Java 编译此时已通过）。删掉被占用的中间目录后重跑即可，必要时降并发：

```bash
rm -rf app/build/intermediates/project_dex_archive app/build/intermediates/desugar_graph
./gradlew.bat --no-daemon --max-workers=2 :app:assembleDebug
```

增量构建时这两个目录可能残留，建议先清理再判断是否编译失败。

还有一类失败长这样，**和代码无关**：

```
Gradle could not start your build.
> ... FileNotFoundException: C:\Users\<user>\.gradle\caches\journal-1\journal-1.lock (拒绝访问。)
```

原因：`--no-daemon` 的单次 daemon 有时不会退出，一直占着 Gradle 用户目录的 journal 锁。处理方式是用 Gradle 自己的命令停掉它，再删掉残留锁文件：

```bash
./gradlew.bat --stop
rm -f ~/.gradle/caches/journal-1/journal-1.lock
```

安装：

```powershell
adb -s 1b3a7d8 install -r app/build/outputs/apk/debug/app-debug.apk
```

在 Vector 中启用“Hyper MeloLock”，只勾选 `com.android.systemui`，然后按 Vector 提示重启 SystemUI 或设备。首次启动应用时开关应显示关闭；先确认无音乐锁屏、通知、解锁和紧急操作都保持原样，再开启模块开关。之前授予的“修改系统设置”权限不再使用，可以在系统设置中撤销。

## 真机逐步验收

1. **关闭状态**：安装后保持开关关闭。锁屏、解锁、紧急拨号和通知完全使用原生界面。此阶段不要求播放音乐。
2. **单个播放器**：开启开关，使用一个提供标准 MediaSession 的音乐应用播放带封面的曲目。确认时间在上、封面在中、控制在下，背景模糊来自封面；暂停或结束后原生锁屏立即恢复。
3. **通知与安全入口**：有普通通知时确认默认收起、点击“展开通知”能看见通知、点击“返回播放器”能返回。再分别确认手势解锁、密码/指纹入口及紧急操作。
4. **跨播放器**：依次测试 Cool Music 和另一款提供 MediaSession 的播放器，包括上一首/下一首、切歌换封面、无封面、停止播放和多会话切换。
5. **故障日志**：若没有效果或 SystemUI 异常，记录 `adb logcat -d -s LSPosed:* MeloLock:* AndroidRuntime:E` 和 Vector 模块日志；不要继续扩大系统版本适配。

第一次现场验收暴露了开关故障：0.1.0 向 `Settings.System` 写入自定义键时，设备抛出 `IllegalArgumentException: You cannot keep your settings in the secure settings`，导致应用闪退，开关并未开启。0.1.1 改用模块私有配置和只读接口，不再请求 `WRITE_SETTINGS`。已在设备内验证配置开启→只读接口读取→关闭的往返测试，最终状态为 `enabled=0`；更新后已重启设备，SystemUI 进程保持运行。设置页按钮的手动点击与锁屏交互仍待验收。ADB 已确认该 SystemUI 获 `MEDIA_CONTENT_CONTROL` 权限。**锁屏交互及第 2–4 步尚未完成真机验收**，不应将其视为稳定版；仍须核实视图层级、通知入口位置及解锁/紧急操作触控区域。该设备拒绝 ADB shell 注入按键事件（缺少 `INJECT_EVENTS`），这些交互须在设备上手动测试。

第二次现场验收中，应用开关可以开启，但锁屏未变化，音乐播放后立即暂停。系统 `MediaSessionService` 在 2026-10-06 09:33:52 记录了 `callingPackage:com.android.bluetooth` 发出的 `KEYCODE_MEDIA_PAUSE`，之后播放器状态变为 `PAUSED`；这次暂停事件并非模块的自定义按钮调用。需在蓝牙关闭时复测，以区分蓝牙设备/系统服务与模块行为。锁屏覆盖层仍未验收通过。

0.1.2 安装并重启后，开关仍为 `enabled=1`，但当前 logcat 中没有 `MeloLock` 钩子日志，也没有 Vector 加载本模块的记录；同一次启动能看到 Vector 加载其他模块。下一步须核实 Vector 中本模块总开关与 `com.android.systemui` 作用域是否实际生效，再用诊断日志判断根视图和媒体条件。设备同时保持蓝牙开启且有连接，蓝牙关闭对照尚待完成。

用户随后确认：0.1.2 重启后 Vector 中本模块总开关变为关闭，手动打开后作用域仍为 `com.android.systemui`。这足以解释该次启动缺少注入日志。0.1.3 已安装到设备（versionCode 4），将 Xposed 元数据的最低 API 和作用域资源格式调整为设备上已正常加载的 legacy 模块使用的格式；应用内开关仍为 `enabled=1`。每次更新 APK 后都应重新检查 Vector 总开关，避免把未注入误判为视图钩子失败。

0.1.3 经用户重新启用 Vector 模块后由 ADB 重启，设备启动完成。SystemUI 进程日志出现 `Keyguard root attached`、`Keyguard root compatible; observing module switch` 和 `Module enabled=true`，说明模块已经在 SystemUI 的锁屏根视图上执行。启动日志也出现一次 `HyperOSKeyguardRootView` 类查找失败；后续根视图挂载成功，需在后续复测中继续留意该异常是否来自早期类加载时机。检查时 `dumpsys media_session` 显示 0 个会话，蓝牙仍开启，因此尚无法判断封面层能否显示，也未完成蓝牙关闭的暂停对照。用户正在进行带封面播放与锁屏测试。

09:52 再次播放 Cool Music 时，系统将多次 `KEYCODE_MEDIA_PAUSE` 发送给 `moe.ouom.coolmusic`，每次日志的 `callingPackage` 均为 `com.android.bluetooth`；随后 MediaSession 为 `PAUSED`，未出现封面层创建日志。此时 `bluetooth_on=1`。需先关闭蓝牙、保持一首带封面的歌处于 `PLAYING`，再判断 OS 3 视图适配是否成功。

蓝牙断开后用户确认音乐可以正常播放；双设备耳机连接是暂停的原因，暂不处理。带封面曲目处于 `PLAYING` 时，0.1.3 的诊断日志为 `Native views missing: clock=true notifications=false`：媒体层已提供有效封面，适配器却错误地只在 `HyperOSKeyguardRootView` 子树内查找通知栈。参考 [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover) 的架构后仍以本机日志为准；Android SystemUI 的通知栈也可能与锁屏子树分离。0.1.4 改在同一窗口的根视图里按精确类名查找通知栈，找不到仍保持原生锁屏；增加解锁时的恢复检查，并修正应用内故障恢复文案。没有复制参考项目的源码或资源。

0.1.4 已构建、安装、重新启用 Vector 并由 ADB 重启。SystemUI 日志确认精确 OS3 构建的构造函数钩子已安装，播放带封面的 Cool Music 曲目时出现 `Media overlay created from active session artwork`。ADB 锁屏截图目视确认：顶部时间、大幅封面、同一封面模糊生成的背景、下方标题和播放控制及“展开通知”按钮均显示；原生手电筒和相机图标可见。**通知展开、解锁、紧急操作触控以及第二播放器尚待用户手动验收**，仅可将视觉布局视为通过。

用户对 0.1.4 的进一步验收发现：锁屏原有时钟仍露出，手动展开通知约一秒后被媒体回调收回，切歌时曾短暂返回原生锁屏；同时希望保留系统原生媒体卡片而移除模块绘制的标题、歌手和三枚按钮。已在 0.1.5 源码中修正展开状态不随媒体回调重置、将新时间文字居中、增加 0–48 dp 的封面圆角设置，并在同一会话的下一张 URI 封面读取期间最多保留旧封面 2 秒。若媒体会话无效或新封面加载失败，仍退回原生。0.1.5 还加入只记录视图类名、资源 ID 和可见状态的诊断日志，以定位系统媒体卡片及残留时钟；不会记录通知正文。0.1.5 已构建并安装，应用内开关保持关闭。原生卡片持续播放时 `uiautomator dump` 连续两次因 `could not get idle state` 失败；待用户重新启用 Vector 并重启后用模块日志观察层级。**原生卡片复用和残留时钟修复尚未完成。**

0.1.5 经重启后用户确认“展开通知”可保持在通知页。视图诊断确定：系统原生播放器是通知栈的首个子视图 `com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView`，其中包含 `mi_media_controls`、`media_progress_bar` 和原生动作按钮；普通通知是其后的 `ExpandableNotificationRow`。锁屏主题的大号前景时钟位于独立的 `miui_keyguard_foreground_clock_container`，与 `KeyguardClockContainer` 分开。这解释了旧版隐藏一个时钟容器后仍有残影。

0.1.6 保留并平移原生媒体头至封面下方，仅收起通知栈其他直接子视图；移除模块绘制的歌名、歌手和播放按钮。自定义时间居中，封面圆角可在 APK 中调节。两层原生时钟和主题背景装饰只在沉浸页隐藏，展开通知或退出时恢复；通知入口加在通知窗根层以避免被媒体头遮挡。大量视图树诊断日志已移除。0.1.6 已完整编译并安装，**待 Vector 重新确认启用、重启和真机视觉/触控验收**。

0.1.6 真机重启后，日志曾记录媒体头向封面下方平移，但截图确认 SystemUI 随后把卡片和普通通知重新排回原位，卡片遮挡封面；此版未通过收起页验收。展开页可以看到原生时钟、播放器和通知，并有“返回播放器”入口。0.1.7 将绘制前守卫绑定到当前 `NotificationShadeWindowView` 的视图观察器，并在每次媒体更新时重新检查观察器，以应对锁屏视图重挂载或 SystemUI 覆盖位置。已完整编译并安装，**待重新启用 Vector、重启及复测；如果系统仍覆盖，需放弃直接平移媒体头。**

0.1.7 经用户启用 Vector、重启并复测，原生媒体卡片与普通通知仍覆盖在封面上。SystemUI 日志确认绘制前守卫执行，但媒体头的位移每帧变化，因此直接调整通知栈子视图的位移和可见性不足以控制此构建的布局。现改为在沉浸页临时将原生 `MiuiMediaHeaderView` 移到窗口级容器、放在封面下方，并整体收起原生通知栈；展开通知或退出沉浸页时把原生卡片放回原位置。若卡片的父视图意外变化或移动失败，立即恢复原生锁屏。此改动已编译通过，需重点检查 SystemUI 稳定性、原生卡片触控、通知切换、解锁和紧急操作。用户确认当前 `0.2.0` Compose 应用是预期的并行开发改动；已将包含本次锁屏修复的 `0.2.0` APK 安装到设备，ADB 确认 versionCode 9、配置接口 `enabled=1`。**待确认 Vector 启用、重启和真机验收。**

0.2.0 第一次真机启动时，SystemUI 日志确认有效媒体会话和封面已被发现，却在移动原生 `MiuiMediaHeaderView` 时抛出 `IllegalStateException: The specified child already has a parent`。此 ROM 的通知栈拦截了按对象调用的 `removeView(view)`，使卡片没有脱离原父容器；模块的失败关闭逻辑随即撤掉沉浸层，因此用户只看到原生锁屏且没有页面切换入口。修复改为使用已验证的直接子索引调用 `removeViewAt(index)`，并在加入窗口级容器前检查父视图已清空。若 ROM 仍拒绝移除，则保留可见的沉浸页面并记录日志，不再撤掉封面和入口。修复 APK 已编译通过，**待安装、重启并复测。**

用户确认本机 OS 3 的原生媒体头不能作为可移动组件，要求改为模块自绘、外观贴近原生的播放器卡片。当前实现已移除对 `MiuiMediaHeaderView` 的移动尝试：沉浸页在窗口顶层绘制深色圆角卡片，包含小封面、标题、歌手、上一首、暂停、下一首、进度条和时间；所有媒体数据与控制仍通过通用 `MediaSession` / `MediaController` 获取和发送。通知页继续显示系统原生播放器和通知；两页共用黑色圆角卡片、小封面、文字层级和控制行，减少切换突兀感。沉浸页每 500 ms 根据 MediaSession 的位置、速率和时长更新进度；无有效播放会话或封面时立即恢复原生锁屏。新实现不改 SystemUI 原生播放器的父子关系，Debug 构建已通过，**待安装、重启及真机验收。**

用户已在真机验收“专辑封面 + 自绘播放器”布局：封面、时间、原生风格卡片、播放进度和通知入口均可显示。该验收版本已创建本地 Git 提交 `1ce2c93 feat: add immersive album and media player card`；工作目录原先没有 Git 仓库，因此该提交是新仓库的首个提交，仅包含 `README.md`、`LockScreenOverlay.java` 和 `MediaSource.java`，未纳入并行开发的 Compose 界面文件。截图同时发现底部快捷入口区域露出原壁纸。后续修复在锁屏根视图底部插入一层相同的模糊专辑图与遮罩，位于系统手电筒/相机快捷入口下方；窗口顶层交互场景仍保留底部空间，避免遮挡快捷入口和解锁手势。Debug 构建已通过，**待安装、重启及真机验收。**

用户确认包含三段布局、全屏专辑背景和正常通知页切换的版本可用，已创建第二个本地 Git 提交 `e976aec feat: complete immersive layout and notification transition`。在此提交之后，通知切换改为 220 ms 的重叠过渡：恢复系统通知页时，自绘播放器卡片上移 52 dp 并淡出，背景场景同时淡出，通知页在下方出现；返回播放器页时卡片自上方滑回。切歌时，如果 `MediaSession` 仍报告播放状态但新封面元数据暂时为空，模块保留现有封面和背景最多 2.5 秒，等待新封面回调，避免短暂露出系统原壁纸；超时或播放停止仍恢复原生锁屏。Debug 构建已通过，**待安装、重启及真机验收。**

## 故障恢复

优先在模块应用中关闭开关。无法操作应用时，从已授权的电脑停用模块应用，随后重启设备；恢复前保持停用：

```powershell
adb -s 1b3a7d8 shell pm disable-user --user 0 io.github.melolock
adb -s 1b3a7d8 reboot
```

设备已验证 `pm disable-user` 可以停用本应用；修复后可用 `adb -s 1b3a7d8 shell pm enable io.github.melolock` 重新启用。若 SystemUI 无法正常工作，也可在 Vector 中停用本模块的 `SystemUI` 作用域并重启；必要时通过恢复模式停用 Vector 模块。模块不改系统 APK、壁纸或播放器数据。再次测试前保存 SystemUI/Vector 日志。该 ROM 已拒绝 0.1.0 文档中的 `settings put system` 以及 `pm clear`，都不能作为恢复手段。

## 许可证与参考

本仓库按 AGPL-3.0 发布。仅参考 HyperMusicCover 的产品思路，没有复制其源码、资源或钩子；其澎湃 OS 4 适配未用于本项目。Vector 的 legacy Xposed API 用作 `compileOnly` 依赖，不打包到 APK。

应用标识：应用名 `Hyper MeloLock`（`values` / `values-zh` 的 `app_name`，其他语言回落到英文），包名与 applicationId 为 `io.github.melolock`，Gradle 根项目名 `Hyper MeloLock`。

**2026-10-06 做过一次整体改名**（原 `io.github.hypermusicscape.lock` / `Hyper Music Scape Lock`）：包目录与 7 个 Java 文件的 `package`、`applicationId`、`Config.PACKAGE`（`AUTHORITY` 与 `URI` 由其派生）、`assets/xposed_init`、Manifest 的 provider authorities、日志 tag（覆盖层 `HyperMusicScapeLock` → `MeloLock`，配置端 → `MeloLock[App]`）以及文档全部同步。**改名后是一个全新的应用**：不会原地覆盖升级旧的 `io.github.hypermusicscape.lock`，Vector 里会出现两个模块；必须卸载旧模块应用并重新启用本模块、重新勾选 `com.android.systemui` 作用域，旧配置数据也不会继承。仓库目录名仍是 `Hyper Music Scape Lock`（工作区路径未动）。启动器图标源图为 `docs/images/icon.png`（已复制一份到 `app/src/main/res/drawable-nodpi/ic_hmsc.png` 作为应用图标，两份 MD5 一致），它不是自适应图标，部分启动器可能加自己的遮罩。

**首页大标题字号**：Miuix `TopAppBar` 把大标题字号写死为 `MiuixTheme.textStyles.title1`（默认 32sp）且没有参数可传，`LocalTextStyles` 又是 internal。为此 `CollapsingPage` 新增 `largeTitleFontSize`：非空时用同一个 `ThemeController` 再开一层 `MiuixTheme`，只替换 `title1`，颜色与深浅色模式不受影响（`HyperIslandTheme` 通过 `LocalThemeController` 暴露控制器）。`Hyper MeloLock` 只有 12 个字符，32sp 下已能单行，所以首页**不再传这个参数**；机制保留，将来名字变长或想要更紧凑的标题时可用。

**启动更新检查已禁用**：本模块在 Manifest 里移除了 `INTERNET`，HyperIsland 原本的启动期更新检查必然失败并弹「检查更新失败」，已在 `AppShell` 中移除该调用。

**滑条点击行为**：HyperIsland 的 `PreferenceSlider` 把点击当作手动输入入口（弹对话框）。本模块的调参滑条通过新增的 `allowManualInput = false` 让点击轨道直接跳到位。默认值 `true`，HyperIsland 自身页面行为未变。

**第三方字体许可**：`app/src/main/assets/fonts/` 下的 `clock_round_1.ttf`（Quicksand）与 `clock_round_2.ttf`（Baloo 2）来自 Google Fonts，按 SIL Open Font License 1.1 授权，可随应用一起分发。OFL 要求分发时附带许可证全文并保留字体名称，**对外发布前需要把 OFL-1.1 全文一并放进仓库并在应用内可查看**（当前尚未加入，只在文档里记录）。

## 后续

先完成上面的真机验收并修复观察到的问题，再增加其他系统版本的适配器和对应构建门禁。

界面部分待办：

1. 2026-10-06 的首页/音乐应用/外观/开发者四页改版已构建通过并确认进入 APK（APK 内可见 `enabledAppCount`、`OverviewStatusGrid`、`已开启应用`、`待填写` 等新增符号），**但尚未做真机视觉验收**：安装时设备 `1b3a7d8` 已从 ADB 掉线。
2. 重新连接设备后按上面的安装命令刷入，重点看：状态卡点击切换是否可靠（这是模块唯一的开关入口）、两张数据卡的数字是否与「音乐应用」页勾选状态一致、数据卡点击跳页是否正确、暗色主题下绿色/红色状态卡对比度。
3. 作者署名与三个外链常量留在 `LockScreenPages.kt` 末尾的 `TODO(作者信息)` 处，填上后对应行会自动从「待填写」置灰恢复为可点击。
4. 中文文案目前硬编码在这四个页面里，补多语言时需抽到 `strings.xml`。

5. 2026-10-06 第二轮：外观页加入三元素编辑器（时间/封面/播放器的大小·圆角·间距，时间的粗细·颜色·字体圆润），应用名与图标换成 Hyper MeloLock，开发者填 `zuige` / `zuige66`，修复首页状态卡关掉后点不回来的 bug。构建通过、已装到 `1b3a7d8`，**锁屏实际效果待验收**：改完要灭屏再亮屏才生效，需确认时间三档字体是否真的换了字形、粗细是否连续可辨、封面宽高不同时的裁切、以及播放器卡片改尺寸后控件是否被压变形。

6. `LockScreenOverlay.java` 在 2026-10-06 18:00 前后被本会话之外改动过（加入了播放器卡入场动画、封面超时回退、`deferArtworkFallback`）。本轮改动是在那份内容之上叠加的，两边的改动都保留了。**同一个文件不要并行编辑**，否则会互相覆盖。

7. 2026-10-06 第二轮收尾（已提交 `f344d75 app页面配置`）：应用名改 `Hyper MeloLock`、音乐应用页改为列出全部应用并申请应用列表权限、滑条改为点击跳位、禁用启动更新检查、首页系统信息加 `Build.*` 兜底并对失败打日志。

8. 2026-10-06 第三轮（**已实拍验收**）：① 首页大标题降到 22sp，`Hyper MeloLock` 单行放下；② Manifest 补声明 `com.android.permission.GET_INSTALLED_APPS`，进入「音乐应用」页实测弹出 MIUI 授权框；③ 系统信息卡恢复真值（系统版本 `OS3.0.303.0.WNKCNXM`）；④ 启动时不再弹「检查更新失败」。

   **仍未验收**：锁屏三元素调参（需要重启 SystemUI，见上文「改配置后必须重启 SystemUI」）。「Xposed 框架」一行在 legacy 模块下固定显示「未知」——`XposedPrefsSyncApp` 依赖 libxposed 的 service 绑定，legacy 模块拿不到；设备上也没找到可识别的框架管理器包（`pm list packages | grep -i vector/lsposed` 无结果），所以暂时无法用包名版本号兜底。这不算 bug，但要显示真值需要换读取方式。

7. 2026-10-06 熄屏唤醒防闪：不再在 `ACTION_SCREEN_OFF` 时移除已经渲染的沉浸层，也不会因为熄屏期间的媒体回调撤掉它；`ACTION_SCREEN_ON` 先立即恢复缓存场景，再刷新 `MediaSession`。解锁、关闭模块、锁屏根视图分离，或亮屏后确认没有有效会话时仍恢复原生锁屏。该修复已完成 Debug 构建，待真机验证首次唤醒是否消除原生锁屏的一秒闪现。

8. 2026-10-06 时间裁切修复：时间字号为 91 时，用户把自定义容器高度设为 100 dp，圆体/粗体字形的实际绘制高度超过容器，导致底部被裁掉；布局剩余空间不能参与该文本控件测量。现移除时间的锁定比例、宽度和高度设置，时间始终 `MATCH_PARENT × WRAP_CONTENT`，外观页只保留字号、粗细、圆润、颜色和“距顶部”。通知入口的底部边距从 125 dp 调整为 78 dp，移动到系统底部快捷入口上方。Debug 构建通过，待真机验收。

9. 2026-10-06 解锁滑动背景裂缝修复：此前同一张模糊专辑图分别绘制在锁屏根视图和通知窗根视图；解锁手势期间两个根视图由 SystemUI 分别做位移/淡出动画，底部会暴露原壁纸。现删除通知窗根视图里的重复模糊图和遮罩，只保留锁屏根视图内的单一全屏背景层；窗口顶层仅放封面、播放器和通知入口。普通通知继续由守卫隐藏。Debug 构建已通过，待真机滑动解锁验收。

10. 2026-10-06 播放中壁纸与时间页修复：通知页过去调用完整视图恢复，连同系统壁纸层与系统时钟一起恢复，因此播放时可见原壁纸，且该构建的系统时钟会丢失小时。通知页现只临时恢复通知栈，继续隐藏原壁纸、原生前景和两组系统时钟；模块自己的 `TextClock` 留在通知页上方。其后验证发现等待 `ACTION_USER_PRESENT` 会让覆盖层残留到桌面，因此撤回该延迟撤层策略：`isKeyguardLocked()` 变为 false 时立即恢复原生界面，根视图分离仍为第二道清理。为阻止主题在布局动画中动态加入第二个时间或壁纸层，守卫现在遍历锁屏根视图并隐藏所有时钟类及 `wallpaper`/`keyguard_background` 标识的原生层。Debug 构建已通过，待真机验收通知页、上滑解锁和无媒体回退。**其中「`isKeyguardLocked()` 变为 false 时立即恢复原生界面」这条策略已在第 13 条被「淡出 + 保留实例」取代——第 12 条的日志证明它正是上滑露壁纸一秒的直接原因。**

11. 2026-10-06 冷启动观察：已通过 ADB 对设备 `1b3a7d8` 执行重启并清空后抓取日志。启动完成约 48 秒后，SystemUI 才挂入锁屏根视图；在此之前 Launcher/个人助理窗口已开始附着，所以会短暂出现无状态栏的桌面。SystemUI 随后报告主线程约 2.5 秒的延迟，堆栈落在系统自己的 `MiuiIslandMediaViewHolder` / 通知栈初始化；同时 Qualcomm 电话与 IMS 服务因 telephony service 尚未就绪反复重启。模块日志显示锁屏根钩子安装、根视图附着且 `enabled=true`，crash 缓冲区没有 SystemUI 或模块进程崩溃。结论是“桌面 → 黑屏 → 仅壁纸 → 黑屏 → 锁屏”由系统开机阶段的窗口和服务排序造成；LSPosed 模块只有 SystemUI 创建后才能运行，不能提前接管这段过程。后续应以模块开/关的两次冷启动时序对照评估其额外影响，避免为遮挡启动闪屏而在更早的系统层强行加覆盖层。

12. 2026-10-06 桌面通知栏与切歌过渡修复：`KeyguardManager.isKeyguardLocked()` 在本机已解锁后下拉通知栏的短窗口内仍可能返回 true，导致锁屏根视图中的模块时间和背景进入桌面通知栏。现以 `ACTION_USER_PRESENT` 作为解锁周期边界，在下一次 `SCREEN_OFF` 前强制隐藏/暂停场景，媒体回调只预建不可见视图，不能再把锁屏组件恢复到桌面通知栏。通知入口从 HyperOS 锁屏根移动到模块自己的 `FrameLayout` 并固定在底部 10 dp，避免根布局在动画中忽略 gravity 后落到左上角；位置低于充电文案。切歌数据层不再以 `MediaController` 对象是否相同决定是否保留缓存，新的 MediaSession 或 URI 封面均会继续显示上一首完整沉浸页，待下一张封面解码成功才原子切换；5 秒超时或解码失败才回退原生。Debug APK（`0.2.0` / versionCode 9）已构建并安装，待重启 SystemUI 后验收桌面下拉、入口位置和跨会话切歌。

13. 2026-10-06 通知页空白与时间消失修复：`hideNativeClockLayers(root)` 的递归范围意外包含模块的 `foreground`，把自绘 `TextClock` 当成系统时钟隐藏，因此播放器页会只剩封面和卡片。现递归遇到模块自己的前景/背景即停止，并且每次进入播放器页或通知页都会明确恢复自绘时钟可见。通知页此前仅按进入沉浸前的原始 visibility 恢复通知栈；该 ROM 有时原始状态就是 `INVISIBLE`，于是出现只剩模糊背景的空白页。现通知页及其 pre-draw 守卫都会明确设通知栈为 `VISIBLE`，最终 `restore()` 仍会回到初始状态。Debug 构建通过后已安装到 `1b3a7d8` 并重启；日志确认锁屏根已附着、模块 `enabled=true`。启动检查时无活跃媒体会话，故原生锁屏保持显示，待播放带封面曲目后进行真机验收。

12. 2026-10-06 闪屏诊断日志（第四轮，**本轮只加日志、不改行为**）。用户真机复现确认第 7 条的「熄屏唤醒防闪」与第 9 条的「解锁滑动背景裂缝」**都没消除**对应现象：① 上滑解锁时仍有一秒露出壁纸；② 快速熄屏再亮屏时先显示原生锁屏约一秒。为精确定位触发路径，补了以下日志：

    - `LockScreenOverlay.restore()` 改成 `restore(String reason)`，所有调用点标注原因（`predraw-keyguard-unlocked` / `user-present` / `switch-off` / `destroy` / `render-*` / `artwork-timeout`）；撤层前打印一行状态快照 `fg/bg/shown/expanded/scene/attached/interactive/keyguard`。
    - pre-draw 守卫检测到「前景仍存活但 `isKeyguardLocked()` 已翻 false」时打一次 `Pre-draw: keyguard unlocked while overlay alive`（有一次性标志，不会每帧刷屏）。
    - `render()` 的每个早退分支走 `skip(reason)` 去重日志，可在日志里直接看到「为什么没渲染」。
    - `MediaSource.refresh()` 打印「从查询会话到真正出图」的耗时（毫秒），并区分三条路径：bitmap 直出 / 缓存帧命中 / URI 异步解码，用于判断亮屏后的一秒是花在异步取封面还是别处。
    - `HookEntry` 打印锁屏根视图的 attach/detach 时间点与可见性，用于判断场景是否为 `destroy()` 销毁。

    **抓取方式**：`adb -s 1b3a7d8 logcat -c` 后复现问题，再 `adb -s 1b3a7d8 logcat -v time | grep MeloLock`。注意覆盖层跑在 `com.android.systemui` 进程，**配置端 App 进程的日志里不会有这些行**——按包名 `io.github.melolock` 过滤只会看到配置界面自己的日志。

    同时订正上文「改完需要灭屏再亮屏一次」：自熄屏唤醒防闪改动后 `ACTION_SCREEN_OFF` 不再撤层，场景存活时灭屏再亮屏不会重建。

13. 2026-10-06 解锁闪屏修复：**淡出 + 保留实例（已构建、已安装，待真机验收）**。第 12 条的诊断日志抓到了 5 轮完整「熄屏 → 亮屏 → 解锁」，把两个现象定为同一根因：

    - **上滑解锁露壁纸一秒**：解锁瞬间 `keyguardGuard` 检测到 `isKeyguardLocked()` 翻 false，立即 `restore()`。日志显示此时 `interactive=true`（屏幕亮着）、`attached=true`（根视图没分离）、`scene=true`（场景完整）——是**守卫主动撤层**，不是根视图分离。而 `restore()` 里的 `restoreChangedViews()` 会把原生壁纸层恢复 VISIBLE，系统解锁动画却还要跑 80~190ms（`USER_PRESENT` 才到），空档期就是壁纸。
    - **亮屏先见原生锁屏约一秒**：是上一条的衍生——每次解锁都销毁场景，下次亮屏只能 `create()` 重建，实测 126~145ms，叠加屏幕物理亮起到广播送达的系统延迟，观感即「先原生锁屏再变音乐版」。**反证**：日志里有一轮没经过解锁，`SCREEN_OFF kept=fg=true` → `SCREEN_ON fg=true` 直接复用，完全不闪。

    改法：新增 `suspended` 状态。解锁时 `suspend()` 把覆盖层淡出 180ms 到 `GONE` 并保留全部视图实例，随后 `restoreChangedViews()` 把原生层交还系统；判定为 suspended 时 pre-draw 守卫完全不介入（不隐藏原生层、不 bringToFront），媒体回调也不接管界面。锁屏重新出现时 `resume()` 恢复可见性并直接 `showMusic()`，跳过 `create()`。仅在模块关闭、媒体不可用（`snapshot == null`）、锁屏根视图分离时才真正 `restore()`。

    日志新增 `suspend reason=...` / `suspend done; scene kept for reuse` / `resume reused scene ...`，`state()` 增加 `suspended=` 字段，验收时看这几行即可判断是否走的复用路径。

14. 2026-10-06 暂停保留 + 熄屏预建（第 13 条的补完）。真机验证第 13 条时发现两条遗漏的路径：

    - **暂停时不该销毁**：原实现里暂停会让 `snapshot == null` → `restore()` 撤层，于是「暂停后锁屏」被原生界面顶替，下次亮屏又要重建。现在 `MediaSource` 区分「会话还在但暂停」与「会话真的消失」：前者交出最后一帧（`Snapshot.playing = false`，`speed` 归零让进度条停在暂停处），后者才回退原生锁屏。同时 `current` 在暂停时仍指向该会话，否则会注销 controller 回调、用户点播放后锁屏不更新。中间键改为播放/暂停切换，图标跟随 `playing`。
    - **「桌面播歌 → 熄屏 → 亮屏」从来没有场景可复用**：在桌面上 `render()` 一直 `skip("keyguard-unlocked")`，场景根本没建过，`suspend/resume` 救不了这条路径。现在 `ACTION_SCREEN_OFF` 会主动触发一次媒体刷新，`render()` 在 `!isInteractive()` 时调 `preCreate()`——屏幕已灭、用户看不见，正好把场景建好并置 `GONE` + `suspended`，亮屏时由 pre-draw 第一帧 `resume()` 秒显。

    新增日志：`Scene pre-created while display off; will resume on wake`、`Session paused in Xms; keeping last frame`。`render()` 抽出 `applySnapshot()` 供正常渲染与预建共用。

15. 2026-10-06 暂停误判 + 解锁残留。真机日志暴露第 14 条的两个漏洞：

    - **暂停被误判成「无会话」，整个场景被销毁**：暂停识别原先依赖 `lastReady`，而切歌失败（日志里的 `Artwork decode failed in 81ms`）会把 `lastReady` 清空，此后暂停就认不出来 —— `refresh()` 走 `onMedia(null)`，`render()` 落到 `render-no-session` 把覆盖层撤掉，锁屏直接掉回「壁纸 + 原生时钟 + 原生媒体通知卡」。改为遍历会话时记住第一个「允许的包 + 有 metadata + 非播放」的会话，不依赖 `lastReady` / `current`。
    - **解锁后前景组件残留桌面**：`suspend()` 原先把隐藏动作挂在 `ViewPropertyAnimator.withEndAction` 上，但解锁时窗口正在切换，动画回调可能根本不推进，前景层就永远保持可见。改为「淡出只是视觉效果，真正的隐藏由 `main.postDelayed(finishSuspend, 150)` 兜底」——`finishSuspend` 走 Handler，不依赖动画回调；`resume()` / `restore()` 会取消这个待执行回调。

    另外诊断日志的 tag 已随整体改名变为 `MeloLock`。

16. 2026-10-06 解锁残留治本：**前景层改挂锁屏根视图** + **播放即预建**。用户截图显示解锁瞬间「壁纸已出、前景组件（大时钟/大封面/播放器卡片/通知按钮）完整残留」——根因是背景层挂在锁屏根视图（`HyperOSKeyguardRootView`），被系统解锁动画直接带走；而前景层挂在窗口根视图，不跟系统动画走，只能等我们自己的淡出，过程完全不同步。改为把 `foreground` 与 `notificationButton` 也挂到锁屏根视图：系统退场动画把整个锁屏根（背景+前景+按钮）一起带走，各层消失时机完全同步。通知栈仍在窗口根，展开通知时自然盖在锁屏根之上。`restore()` 的 removeView 判据同步改为锁屏根。

    同时实现「播放即预建」：`render()` 在解锁态（桌面）收到有效媒体快照且场景不存在时，直接 `preCreate()` 把场景建好并置 GONE + `suspended`——**一点播放歌曲，锁屏场景就绪**，之后锁屏/熄屏/亮屏都不再走 `create()`。`pre-draw` 守卫在 suspended 且未锁屏时完全不介入，桌面上不会误显示。

17. 2026-10-06 **左侧下拉改为直接堵手势** + `thisObject` 事故修复。状态检测路线两次被真机证伪（通知栈可见性、`notification_panel` 可见性），改为在通知面板（`id/notification_panel`）的触摸入口挂 Xposed 钩子，沉浸场景显示期间把左半屏 `ACTION_DOWN` 返回 false。第一版漏了 `hook.thisObject != leftShadePanel` 判定：面板类没覆写 `dispatchTouchEvent`，`getMethod` 拿到的是框架 `View` 的实现 → 等于给 SystemUI **所有 View** 装钩，把播放器上曲/播放暂停与「展开通知」按钮一起吃掉。修复后只在面板实例上生效。开关 `Config.BLOCK_LEFT_SHADE`（默认开），每次手势现读。

18. 2026-10-06 **切歌不再退回原生锁屏**。真机日志显示换歌时 App 会先摘掉 metadata 里的封面 bitmap、只留 URI（且是 `https://`，`ContentResolver` 读不了 → `FileNotFoundException`），约 300ms 后才补齐；期间会话也可能短暂不合格。旧代码三条路径一起撤层：解码失败清空 `lastReady`、会话一不合格立刻下发 null（→ `restore("render-no-session")`）、置空 `current` 注销回调。改为**失败/超时保留上一帧** + **1500ms 宽限期** + **空窗期按包名重新挂回会话**。

21. 2026-10-07 **上滑解锁不再露原生壁纸**。用户反馈上滑解锁时会看到原生壁纸。加探针（`unlockWatch`，suspend 后每 30ms 采样一次锁屏根视图，持续 1500ms）后实测三次复现，得到决定性数据：

    ```
    t=+36ms   root=[INVISIBLE attached=true alpha=0.00 ty=0.00] fg=[VISIBLE alpha=1.00] bg=[VISIBLE]
    t=+131ms  root=[INVISIBLE attached=true alpha=0.00 ty=0.00] fg=[VISIBLE alpha=0.07]  bg=[VISIBLE]
    t=+221ms  root=[INVISIBLE attached=true alpha=0.00 ty=0.00] fg=[GONE]                bg=[GONE]
    ```

    **根因不是原先猜的那样**：① 系统**没有**给整棵树做「跟随的退场动画」——它在 `suspend()` 触发**之前**就把 `HyperOSKeyguardRootView` 直接置 `INVISIBLE + alpha 0`，且全程 `ty=0.00`（无位移）；挂在它内部的任何层都没有过渡窗口，父控件 alpha 归零即一同消失，因此「让系统动画带走我们的层」这条思路**不成立**。② 用户看到的也**不是** `restoreChangedViews()` 交还的那个锁屏壁纸层（它还在 `alpha=0` 的容器里，根本不可见），而是**桌面底下那张系统壁纸**——keyguard 一撤就露出来了。③ 我们自己的 120ms 淡出（`fg alpha 1.00 → 0.07`）是**白淡**：期间父控件已不可见。④ 本次 `USER_PRESENT` 在 suspend 后仅 16ms 到达（旧记录的 80~190ms 来自另一批采样），不是关键变量。

    **改法**：把 `background`（模糊封面底图 + 遮罩）从 `root.addView(background, 0, ...)` 挪到 **`windowRoot.addView(background, 0, ...)`** —— 挂窗口根最底层、位于 keyguard 根**之外**。keyguard 被系统撤走时这一层留在原地，下面露出的就是沉浸背景而不是桌面壁纸；收尾仍由 `suspend` / `finishSuspend` 置 `GONE`，不会残留到桌面。`restore()` 改为按实际父容器移除。**前景层（`foreground`）必须留在锁屏根**跟着系统走，否则会退回「壁纸已出、组件还在」的旧问题。

    **排查教训**：真机 logcat 行格式是 `I/MeloLock(6507):`（**标签后跟 PID，不是冒号**），`grep "MeloLock:"` 会漏掉全部有效行，据此误判过「模块没运行」，并连带让用户白勾 Vector 作用域、白重启一次设备。正确过滤是 `grep -E "/MeloLock\("`。另外 `adb logcat > file` 走块缓冲、不实时落盘，抓现场要用 `adb logcat -d -v time -t 'MM-DD HH:MM:SS.mmm'` 一次性 dump。

19. 2026-10-06 **播放器页 ↔ 通知页共享元素切换**，并修掉三个真机反馈：① 展开通知后左下拉仍能拉出通知（`sceneShowing()` 不该带 `playerSceneVisible`）；② 桌面下拉露出原屏保时间（suspended 期间要继续按住原生时钟层，但不碰壁纸层）；③ 切回播放器时间闪一下（真凶是返回时把含时钟的 `foreground` 从 alpha 0 淡入）。附带：位移上限收到 96dp 且展开/返回复用同一值、`bringToFront()` 换成 `ensureOnTop()`（避免每帧 `requestLayout`）。

20. 2026-10-07 **首个 Release**：仓库推送到 <https://github.com/zuige66/Hyper-MeloLock>，v0.2.0 正式签名 APK（v1+v2+v3）发布到 [Releases](https://github.com/zuige66/Hyper-MeloLock/releases/tag/v0.2.0)。补 `.gitignore`（签名密钥、`.workbuddy/`、临时截图）。发布用 token 只活在临时文件里，用完即删（**并且应当在 GitHub 上吊销**）。

22. 2026-10-07 **重启作用域列表改为本模块自己的作用域，并在点开前先探 root**。首页右上角刷新图标（`MiuixIcons.Refresh`，`actionDescription` 用 `restart_scope`）原本直接弹出 `RestartScopeDialog`，存在两个问题：① 列表是迁入的 HyperIsland 硬编码 5 项（`com.android.systemui` / `com.milink.service` / `com.android.settings` / `com.xiaomi.xmsf` / `com.android.providers.downloads`），**后四项本模块根本没有 hook**；② root 判定发生在「点了确定之后」——`RestartScopeService.restart()` 走 `Runtime.exec("su")`，失败才显示 `restart_root_required`，没 root 时列表照样弹出来，用户只能白点一次。

    改为：`RestartScopeService` 新增 `hasRoot()`（开一个 `su` 跑 `id`，以 `waitFor() == 0` 判定），`OverviewPage` 的 `onAction` 在协程里先调用，**有权限才 `showRestartDialog = true`，否则 Toast `restart_root_required`**。同时 `RestartScopeDialog` 的 `targets` 用 `stringArrayResource(R.array.xposed_scope)` 与内置 `RestartScopeTargets` 求交集，**只列 Manifest 里真正声明过的作用域**——当前 `xposed_scope.xml` 只有 `com.android.systemui` 一项，将来往该文件加一项列表会自动跟上。注意 `hasRoot()` 会触发 root 管理器（SukiSU）的授权弹窗。

23. 2026-10-07 **未勾选音乐应用则不启用沉浸锁屏**。`Config.packageAllowed()` 原本是 `raw.isEmpty() || (!NONE.equals(raw) && allowedPackages(context).contains(packageName))`，即**未设置＝允许全部**：用户刚装好、还没在「音乐应用」页做任何选择时，任何 App 的 MediaSession 都能拉起覆盖层。现在空值与哨兵 `NONE` 一律 `return false`，**必须显式勾选某个播放器包名才放行**。配套把 `enabledAppCount()` 的 `-1`（原「允许全部」返回值）去掉，改为直接返回 `selectedPackages(context).size()`；首页「已开启应用」数据卡因此不再需要区分「全部 / 0」。
