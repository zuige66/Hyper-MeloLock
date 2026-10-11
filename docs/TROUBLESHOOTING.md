# 常见问题与排查手册（TROUBLESHOOTING）

> 维护者向：问题 → 现象 → 定位方法 → 解决/结论。只放**验证过的**结论；推测性内容写明「未定位」。
> 使用者向的安装/使用问题看 `README.md`；实现细节看 `docs/DEVELOPMENT.md`。新问题按此格式追加。

## 目录

1. [装了模块没效果 / 锁屏还是原生的](#1-装了模块没效果--锁屏还是原生的)
2. [装完新包，跑的还是旧代码](#2-装完新包跑的还是旧代码)
3. [App 显示「Xposed 框架：未知」](#3-app-显示xposed-框架未知)
4. [第一次 `am crash` 后模块没注入](#4-第一次-am-crash-后模块没注入)
5. [debug 包和 release 包来回切换后配置丢失 / 不生效](#5-debug-包和-release-包来回切换后配置丢失--不生效)
6. [检查更新不提示新版本](#6-检查更新不提示新版本)
7. [解锁/亮屏时闪一下「原生壁纸/原生锁屏」](#7-解锁亮屏时闪一下原生壁纸原生锁屏)
8. [锁屏上滑露原生壁纸（未解锁时）](#8-锁屏上滑露原生壁纸未解锁时)
9. [怀疑卡顿——怎么量化而不是靠眼睛](#9-怀疑卡顿怎么量化而不是靠眼睛)
10. [改了配置但锁屏没反应](#10-改了配置但锁屏没反应)
11. [想改设备上的配置文件，写不进去](#11-想改设备上的配置文件写不进去)
12. [通知页染色没生效 / 颜色不对](#12-通知页染色没生效--颜色不对)
13. [「永不息屏」开关不生效](#13-永不息屏开关不生效)
14. [构建偶发失败 / 卡住很久](#14-构建偶发失败--卡住很久)
15. [R8 混淆后功能失效（NoSuchFieldError / NoSuchMethodError）](#15-r8-混淆后功能失效nosuchfielderror--nosuchmethoderror)

---

## 1. 装了模块没效果 / 锁屏还是原生的

**排查顺序**（缺一步都可能白忙）：
1. 模块开关是否打开（App 首页顶部）；
2. Vector 管理器里模块是否勾选，**作用域是否勾了 `SystemUI`**（新增作用域 ≠ 生效，见 #2 的 rev 判别法）;
3. 重启 SystemUI：`adb shell am crash com.android.systemui`（`am force-stop` 无效）；
4. 日志自证：`adb logcat -d | grep -E "/MeloLock\("`（注意标签后是 PID，`grep "MeloLock:"` 会漏光全部）。
   看有没有 `Keyguard root attached` / `Elements: clock=`——有 = 模块在跑；一行都没有 = 没注入，回到第 2 步。

## 2. 装完新包，跑的还是旧代码

**先自证再复现**：每个入口都会打 `rev=<修订串>`（如 `HookEntry rev=HE4`）。**先在日志里看到新 rev，再让人复现**；
看不到 rev = 新代码没被加载（装包失败 / SystemUI 没重启 / 作用域没勾）。
离线确认设备上是哪一版：`adb shell pm path io.github.melolock` → pull base.apk → 逐 dex 搜特征字符串。
注意：`ProtectionDomain.getCodeSource()` 在 SystemUI 进程里返回 null（dex 由 InMemoryDexClassLoader 加载），只能靠手工修订串。

## 3. App 显示「Xposed 框架：未知」

框架是 Vector（binder 只在**开机时**推送），adb 侧拿不到版本号属预期。**重启设备**即可显示精确版本；
未重启时显示「Vector（模块运行中，API v102）」是正常回退，不影响功能。

## 4. 第一次 `am crash` 后模块没注入

已知现象：装包后第一次 `am crash com.android.systemui`，新 SystemUI 进程里可能一条 MeloLock 日志都没有
（作用域扫描时机）。**再 crash 一次**（或重开重勾作用域）即恢复。判别：crash 后比对新 PID + 找 `rev=` 行。

## 5. debug 包和 release 包来回切换后配置丢失 / 不生效

两者**签名不同，不能覆盖安装**，必须先 `adb uninstall`（会清配置），然后：
重开模块开关 + 在 Vector 里**重新勾选 SystemUI 作用域**。判别当前是哪个包：
`adb shell dumpsys package io.github.melolock | grep pkgFlags`，含 `DEBUGGABLE` 即 debug。
调试装 debug、发布用 release（zuige 约定）；两者都不开混淆不行——Xposed 靠类名定位（见 #15）。

## 6. 检查更新不提示新版本

更新源有**两份**，缺一即可能查到旧版本：
- 主源：GitHub Releases latest（public 仓库无需鉴权）；
- 备用源：`blog.zuiges.com/downloads/melolock/latest.json` 静态文件，**发版必须手动同步**
  （versionCode / versionName / apkUrl / changelog 四个字段缺一即判无效源）。
判定逻辑：versionCode 整数比较**或** versionName 三段比较，任一更新即提示。降级测试：`adb install -r -d 旧.apk`。

## 7. 解锁/亮屏时闪一下「原生壁纸/原生锁屏」

**先分清闪的是什么**：
- 只有壁纸（无时钟图标）= 解锁交接时机问题，看 `suspend reason=` 与 `native layers handed back at t=+Nms`；
- 连时钟都出来 = 另有路径在放原生内容，抓 `nativeCensus()`（`keyguardRoot=` 那行）看父容器还活不活——
  解锁时系统会把整棵锁屏根置 `INVISIBLE + alpha 0`，挂在里面的层连绘制都不参与，自己改可见性没用，
  必须把不透明封面 `liftCoverToWindowRoot()`，重新锁屏时 `ensureBackgroundOrder()` 挂回。

**参数红线**：`UNLOCK_COVER_HOLD_MS` 下限 ≈150ms（桌面窗口 +127ms 才合成，早了必露原生壁纸）；
想更看得到桌面动效只能减 `UNLOCK_COVER_FADE_MS`，不能减 HOLD；淡出必须 `LinearInterpolator`。

## 8. 锁屏上滑露原生壁纸（未解锁时）

背景层 z 序只有唯一正确位置：锁屏根里、紧跟原生壁纸层 `keyguard_background_layer` 之后
（同时满足「壁纸之上、底部快捷栏之下」）。锚点用「壁纸层的父容器 + 紧跟其后」，不要写死下标。
排查顺序：先看父容器还活不活（`nativeCensus()`），再看自己的可见性/alpha
（`state()` 里 `cover=V1.00` 只说明层在，不说明它被画出来）。
**2026-10-11 未定位项**：沉浸式下上下滑动偶发原生壁纸闪烁，疑似壁纸窗口（miwallpaper 进程，
WallpaperCover 的 GL 纹理）在面板滑动时露边，非 SystemUI 视图层问题——待 screenrecord + 日志对帧确认。

## 9. 怀疑卡顿——怎么量化而不是靠眼睛

把 logcat 存成文件，用 python 按每行时间戳算**相邻采样间隔**：>60ms 的就是主线程被占住的时刻，
再对照那个时刻在跑什么逻辑。比人眼可靠（本项目靠它定位过「诊断探针自己打 50 行 logcat 造成卡顿」）。
已踩的两个坑：① 临时探针用完必须删（logcat 写入是同步的，「只打日志」不是零成本）；
② 给 ImageView 重设同一张 bitmap 也会触发重绘（媒体层每 2 秒交一次快照，必须引用比对去重）。
**debug 包天然比 release 慢**（debuggable 无 AOT 优化），对比性能必须用 release 包。

## 10. 改了配置但锁屏没反应

- **外观类参数**：灭屏再亮屏一次生效（`resume()` 会比对 appearanceSignature，变了就重建场景），无需重启 SystemUI；
- **行为类开关**（隐藏快捷栏、通知染色、永不息屏）：同样亮灭屏一次生效（`create()` 重读）；
- App 进程不在时 Provider 查不到 → SystemUI 侧按 `enabled=false` 处理，别误判成配置被清空；
- 诊断读值：`adb shell content query --uri content://io.github.melolock.config/state`（或 `/elements`）。

## 11. 想改设备上的配置文件，写不进去

SELinux 拦 shell 写 app data（`run-as … cat >` 被拒），Provider 只实现了 query。
**只能走 App UI 改**；adb 侧只读诊断（见 #10）。

## 12. 通知页染色没生效 / 颜色不对

机制与真机实证（2026-10-11，五轮迭代结论，详见 DEVELOPMENT.md #54）：

| 目标 | 真身 | 可行手段 |
|---|---|---|
| 通知卡 | `NotificationBackgroundView`（id **全小写** `backgroundnormal`/`backgrounddimmed`） | 公开 `setTint(int)`；原始色反射 `mTintColor` 保存复原 |
| 媒体卡 | `MiuiMediaHeaderView`（直接挂通知栈，非通知行），底为 onDraw 手绘 | 卡内 index 0 插静止圆角色层（**必须显式 MATCH_PARENT**，否则量成 0x0）+ media_bg 的 View background 一并染 |

- `resourceName()` 返回**全小写**，按驼峰匹配永不命中（踩过）；
- ImageView 排查时要查 `getBackground()` **和** `getDrawable()` 两个（media_bg 靠 background 藏底，漏查多绕三轮）；
- 系统滚动/换主题/新通知会 rebind 行视图冲掉滤镜 → 展开期间 500ms 节流浅扫（深度 ≤6）重挂；
- **已证伪别再试**：染 `scrim_notifications`（ScrimController 每帧写回）；通知栈后插整页色层（zuige 否决——
  要的是卡片变色不是背景变色）；按类名找媒体头（从 media_bg 向上爬到栈的直接子级才可靠）。

## 13. 「永不息屏」开关不生效

HyperOS 锁屏的息屏走 **AOD 倒计时路径**，View 级 `setKeepScreenOn`（挂锁屏根或前景层都试过）管不住。
终版方案：**SCREEN_BRIGHT_WAKE_LOCK**（SystemUI uid=1000 自带 WAKE_LOCK 权限），场景创建时按配置取、
restore 必还。**红线：锁屏根（系统视图）上挂 keepScreenOn 会导致亮屏慢 + 滑动壁纸闪烁**（2026-10-11 踩过）。

## 14. 构建偶发失败 / 卡住很久

先重跑一次（1~2 分钟就过）再怀疑代码；真错误看 `^e:` / `.java:N` 行。
Windows 特有：`:app:dexBuilderDebug` 可能因杀毒锁 `.dex` 挂住几小时才 FAIL → 构建加 `timeout 300`，
超时清 `app/build/intermediates/{project_dex_archive,desugar_graph}` 再跑。
Gradle 被 VS Code 扩展 JRE 劫持（报 jlink 不存在）→ `export JAVA_HOME=<完整 JDK>` + `./gradlew --stop`。

## 15. R8 混淆后功能失效（NoSuchFieldError / NoSuchMethodError）

Release 已开 R8 + 资源收缩（APK 3.6MB）。keep 规则（`app/proguard-rules.pro`）钉住 xposed_init 三入口 +
ConfigProvider + LockScreenOverlay。**红线：新增按类名/字段名反射本模块类的代码必须同步 keep**；
盘点反射点时别只查 `Class.forName`——`XposedHelpers.findAndHookMethod("io.github.melolock.Xxx")` 和
`getObjectField(obj, "field")` 同样会中招（2026-10-11 真机踩过 `NoSuchFieldError: lm0#shownArtwork`）。
装机后必查 `--pid` 里有没有 MeloLock 日志。
