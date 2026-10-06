# AGENTS.md

## 项目约定

- 完成代码改动后同步更新相关 Markdown 文档，至少维护 `README.md` 的当前实现、构建或验收说明。
- 本项目是 Java Android + Vector/LSPosed 模块，配置端已迁入 HyperIsland 的 Kotlin/Compose/Miuix `app` 源码；修改配置字段时必须同时检查 `Config.java`、`ConfigProvider.java`、Compose 页面和 SystemUI 侧读取逻辑。
- 锁屏覆盖层默认失败关闭：找不到目标 SystemUI 视图或媒体数据无效时恢复原生界面。
- 界面只做「复用 HyperIsland 原版组件 + 换数据源」，不新写样式；同名卡片直接提升 `OverviewPage.kt` 里的实现为 `internal` 共享，禁止复制第二份。
- `LockScreenOverlay.java` 由 Hook 注入 SystemUI 进程：任何改动都必须失败关闭（异常退回原生锁屏），并且**不要与其他会话/人工编辑并行改这个文件**。
- 新增锁屏可调参数时：键名与默认值加到 `Config.java` 的 `ELEMENT_DEFAULTS`，Provider 走 `/elements` 的 key/value 通道，**不要**再去改 `ConfigProvider` 的列投影。

## 最近完成

- 直接迁入 HyperIsland（MIT）的配置端主题、组件、资源和导航，入口替换为：首页、音乐应用、外观、开发者。
- 四个根页面对齐 HyperIsland 版式：首页改成「状态卡 + 两张数据卡 + 系统信息卡 + 链接卡」；音乐应用页改成「搜索栏 + 图标行 + 开关」；作者署名与外链留空显示「待填写」并置灰，位置在 `LockScreenPages.kt` 末尾的 `TODO(作者信息)`。
- 把 `OverviewPage.kt` 私有的 `StatusGrid` / `StatusCard` / `StatCard` / `InfoCard` / 告警卡提升为 `internal` 并参数化标题，HyperIsland 首页与模块首页共用同一批组件。状态卡新增 `clickableWhenInactive`，因为本模块把它当总开关用（HyperIsland 原版未激活时不可点，会导致关掉后点不回来）。
- 外观页加入锁屏三元素编辑器：时间（字号/宽高/粗细/字体圆润/颜色/间距）、专辑封面（缩放或宽高/圆角/间距）、播放器（缩放或宽高/圆角/间距），另保留背景设置。尺寸用 dp，锁定比例时用缩放百分比，间距统一为「距上一个元素」。
- `Config.java` 新增 `ELEMENT_DEFAULTS` 与 `elementValues()/elementInt()/elementString()/setElementInt()`；`ConfigProvider` 新增 `/elements` 的 key/value 查询；`LockScreenOverlay.measureElements()` 负责算最终尺寸，新增 `roundTypeface()/applyClockTypeface()` 处理内置圆体字体与 `wght` 粗细。
- 内置两个 OFL 开源可变圆体数字字体（Quicksand、Baloo 2）到 `assets/fonts/`，因为系统字体没有圆角轴也没有圆体族。对外发布前需补 OFL 全文。
- 应用名改为 `Hyper Music Scape Lock`（`app_name` 资源），图标改为 `res/drawable-nodpi/ic_hmsc.png`，开发者 `zuige` / GitHub `zuige66`。
- `Config.java` 新增 `selectedPackages()`、`allPackagesDisabled()`、`enabledAppCount()`、`deviceSupported()` 只读辅助，用于区分「未设置（允许全部）」与「已全部取消」；未改动任何配置键。
- 保留媒体播放器白名单和锁屏背景外观配置，并通过 ContentProvider 同步。
- 构建已升级至 Gradle 9.5 / AGP 9.3.1，并已用 `./gradlew.bat --no-daemon :app:assembleDebug` 验证 Debug 构建通过、安装到真机并启动验证。
- 已禁用迁入代码的启动统计，并用 Manifest merger 移除最终 APK 的 `INTERNET` 权限，避免访问 HyperIsland 更新/下载服务。
