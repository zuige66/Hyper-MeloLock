# v0.3.9 发布物料（2026-11-15）

- 版本号：`versionCode 19` / `versionName 0.3.9`，tag `v0.3.9`，入口 rev `HE20`
- 资产：`Hyper-MeloLock-v0.3.9.apk`，**3,733,006 字节**，SHA-256 `3495670d097ad6f78c0fc68201f3dc6026e496954019530b7c5c10d002b3eec6`
- 签名：`apksigner verify --print-certs -v` 通过（v3 scheme，CN=zuige，SHA-1 `2b73265bf50dba6576a7758c552340cdc54ec1f7`，与历版一致 → 可直接覆盖安装）
- 改动确实进包：解包逐个 dex 搜到 `hide_entry` / `HE20` / `syncEntryVisibility` / 「隐藏通知入口」/「隐藏底部胶囊」；`aapt dump badging` 返回 `versionCode='19' versionName='0.3.9'`

## 发版范围（zuige 2026-11-15 拍板「一起发 v0.3.9」）

**发版前两个源都停在 v0.3.6 / code 16，本来就是对齐的**，不存在版本号不一致：

| 源 | 发版前 |
| --- | --- |
| GitHub `releases/latest` | `v0.3.6` |
| blog `downloads/melolock/latest.json` | `0.3.6` / code 16，`apkUrl` 指向 GitHub v0.3.6 |

但代码领先两版、且有两个提交**未推 `origin/main`**（远端 main 停在 `3ba331b`）。本版把这些累积改动一次带出：

| 提交 | 内容 | 用户可感知 |
| --- | --- | --- |
| `3ba331b` | 歌词职员表行过滤 + 跑马灯两处抽动修复 | 编曲/录音等制作信息不再占主行；长文本起滚不跳 |
| `3dfca12`（HE18 / 0.3.7） | 拦掉网易云锁屏歌词页 + 锁屏结构普查全树 dump | 网易云的右划锁屏页不再盖住沉浸锁屏 |
| `4a3495b`（HE19 / 0.3.8） | 媒体现场取证 + 会话监听自愈重试 | 诊断报告可一键抓现场；监听注册失败自动重试 |
| 本轮（HE20 / 0.3.9） | `hide_entry` 隐藏通知入口 | 屏下指纹机型可去掉底部胶囊 |

**本轮改动文件**：`Config.java`、`LockScreenOverlay.java`、`HookEntry.java`、`LockScreenPages.kt`、`app/build.gradle.kts`；文档 `README.md`、`AGENTS.md`、`docs/DEVELOPMENT.md`。
实现细节与已知限制见 `docs/DEVELOPMENT.md`「最近改动补充」2026-11-15 条。

## Release notes（GitHub）

```markdown
### 新增 / 改进

- **可以隐藏底部的「展开通知」了**：在「设置 → 通知入口」打开「隐藏通知入口」，底部那颗胶囊连同它的触摸区域一起消失。屏下指纹机型如果觉得它挡手，打开即可。**默认关闭**，不影响现有用户。
  ⚠️ 注意：这颗胶囊同时也是「播放器 ↔ 通知页」来回切换的入口，隐藏后就不能再这样切页了，通知仍从系统下拉查看。
- **拦掉网易云音乐自己的锁屏歌词页**：锁屏上不会再被网易云那个右划页面盖住。需要你在框架管理器里给本模块**补勾「网易云音乐」作用域并重启一次网易云**，否则该页面照旧。
- **诊断报告更好用了**：点「复制报告」时会顺带抓一份当前播放的现场信息，能区分「当时确实没在放歌」和「读取失败」，反馈问题更快。
- 歌词里的制作信息（编曲、录音工程师等）不再占掉主行；长歌名开始滚动时不再先跳一下。

### 修复

- 媒体监听偶尔注册不上时会自动重试，部分机型息屏后再开播也能正常接管锁屏。

### 安装说明

- 从旧版本**直接覆盖安装即可，不需要卸载**（卸载会清空配置）
- 装完后重启一次 SystemUI 生效；若状态异常，在模块管理器里确认已勾选
  `com.android.systemui` 与 `com.miui.miwallpaper`（要用网易云拦截再勾 `com.netease.cloudmusic`）
- 适配：澎湃 OS 3（Android 16）；多机型测试期，遇到显示异常请用「调试」分组的诊断报告反馈
```

## 国内备用源 latest.json（blog.zuiges.com/downloads/melolock/latest.json）

四个字段都要有值，缺一即被判无效源：

```json
{
  "versionCode": 19,
  "versionName": "0.3.9",
  "apkUrl": "https://github.com/zuige66/Hyper-MeloLock/releases/download/v0.3.9/Hyper-MeloLock-v0.3.9.apk",
  "changelog": "- 新增「隐藏通知入口」：屏下指纹机型可去掉底部胶囊，让开指纹区域（默认关；隐藏后无法在播放器与通知页之间切换）\n- 拦截网易云音乐的锁屏歌词页（需在框架管理器补勾「网易云音乐」并重启网易云）\n- 诊断报告增强：复制时抓取当前播放现场，能区分「没在放歌」与「读取失败」\n- 媒体监听注册失败后自动重试，息屏后再开播也能正常接管\n- 歌词里的制作信息不再占主行；长文本起滚不再跳动"
}
```

> 判定逻辑：`versionCode` 整数比较 **或** `versionName` 三段比较，任一更新即提示升级。
> `apkUrl` 仍指向 GitHub 直链（备用源只负责「告诉 App 有新版本 + 提供备用下载」，下载时主链失败会自动换 blog 直链重试，因此 APK 也必须同步进 blog 目录）。

## 博客文章

`D:\Workplace\hexo\source\_posts\hyper-melolock-lockscreen-cover.md`：国内直链改指 v0.3.9，文末追加「五、v0.3.9 更新说明」一节（措辞与酷安文案一致，只写用户可感知的效果）。

## 对外文案

- 酷安：`docs/COOLAPK-v0.3.9.md`（含发布前检查项与备选短版）
- README 下载直链已同步到 v0.3.9；适配表与安装说明已补上第三个可选作用域 `com.netease.cloudmusic`

## 发布四步清单（逐条打勾后才动线上）

1. **代码侧**：bump ✓ / `assembleRelease` ✓ / `apksigner` ✓ / dex 特征串 ✓ / **装机 ✓（2026-11-15 22:06，覆盖装；签名一致未卸载，配置保留）**：
   - `dumpsys package io.github.melolock` → `versionCode=19 versionName=0.3.9` ✓
   - `am crash com.android.systemui` 后 SystemUI 新 PID `29436`，logcat `HookEntry rev=HE20` ✓、`Elements: … hideEntry=false` ✓、`[scrim]` / `WallpaperCoverPush` 入口 rev 行齐全 ✓
   - `content query …/elements` → `Row: 29 key=hide_entry, value=0` ✓；`/state` → `enabled=1`、已勾选的三个音乐应用保留 ✓
   - **待人工**：在 App 里打开「隐藏通知入口」，灭屏亮屏一轮确认胶囊不复现（本机拒绝 ADB 输入事件，UI 只能人工验）
   → 提交 + `git push origin main` → `git tag -a v0.3.9` + `git push origin v0.3.9`
2. **GitHub Release**：PAT + REST API 建 release、上传 APK；验证 `releases/latest` 返回 `v0.3.9`、直链 200 且字节数一致。curl 必须带 `--noproxy '*' --ssl-no-revoke`
3. **blog 备用源**：APK 复制进 `D:\Workplace\hexo\source\downloads\melolock\` + 更新 `latest.json` + 改常青文 → **commit + push `main`，由 GitHub Actions `Circle Feed Update & Deploy` 自动部署**（本次：`d2461a5` 因远端 `1d3ee65` 抢先推了友链数据被拒 → `pull --rebase --autostash` 后 `9a66e8c` 推送成功）。
   - ⚠️ **本次踩坑**：先跑了一遍本机 `hexo deploy`（gh-pages `ee1a57c`），它对 gh-pages 是 force push，会回退 CI 刷新的朋友圈数据；已由后续 push main 触发的 Actions 重跑纠正。**以后只推 main，不要本地 deploy**（已写进 AGENTS.md / DEVELOPMENT.md）。
   - 推 main 前若远端有新提交，用 `git pull --rebase --autostash origin main` 保护本地未提交改动，别 force。
   - **验收**：Cloudflare CDN 600s + GitHub Pages 构建有延迟，push 后立刻查 `blog.zuiges.com` 可能仍是旧内容；源站真相用 GitHub API 查 `zuige66/hexo` 的 `gh-pages` 分支 `downloads/melolock/latest.json`（本次已确认 19 / 0.3.9），等缓存过期后再验域名返回 `versionCode 19` 与 APK HEAD 200。
4. **文章与文案**：常青文追加 v0.3.9 一节；`docs/COOLAPK-v0.3.9.md` 发布到酷安

**两个源发布后都要回到同一版本 `0.3.9` / code 19**，这是本轮「对齐发布」的验收标准。

## 待办（非本轮）

- `D:\Workplace\hexo\source\downloads\melolock\` 里积了 11 个包（含 8 个测试包），下次发版可清理，只留 latest + 上一版。
