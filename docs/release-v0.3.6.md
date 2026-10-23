# v0.3.6 发布物料（2026-10-24）

- 版本号：`versionCode 16` / `versionName 0.3.6`，tag `v0.3.6`
- 资产：`Hyper-MeloLock-v0.3.6.apk`（3,716,574 字节，正式签名 SHA-1 `2b73265bf50dba6576a7758c552340cdc54ec1f7`，apksigner v3 scheme 校验通过）
- 本轮提交（`git log v0.3.5..HEAD`）：15 个，其中功能向 5 个
  - `586f0d2` feat: 歌词不顶掉歌名（lyric_row 开关，默认开）
  - `07ba602` feat: 隐藏播放器小封面 + 文字两行居中（hide_card_art，默认开）
  - `277a35d` 密码界面专辑背景沉浸（远端合入）
  - `0210026` feat: 歌词逐句显示 + 播放图标镜像 + 跑马灯修复 + 原生播放器只留媒体卡
  - `8a95a91` / `d88aa2c` / `b2edf5b` fix: 图标 v2、原生栈每帧被打回、充电文案闪烁、LRC 广告句跳过、通知染色残留

## Release notes（GitHub，已发）

```markdown
### 新增 / 改进

- **歌词、歌名、歌手同屏显示**：播放器卡片副行新增「歌手 / 歌名」。有歌词时主行是当前歌词句、
  副行是歌手与歌名，三者一次看全，不再一放歌词就找不到歌名；不想要的可在「播放器」分组关掉，
  回到原来的两行
- **播放器卡片可隐藏专辑封面**：新增「隐藏专辑封面」开关。打开后卡片左侧的小封面消失，
  歌词与歌名歌手两行水平居中，锁屏更干净。注意：点小封面直接跳音乐 App 的入口会随封面一起消失
- **密码界面封面沉浸**：输解锁密码时背景同样用当前专辑封面，不再闪回原生壁纸

### 安装说明

- 从旧版本**直接覆盖安装即可，不需要卸载**（卸载会清空配置）
- 装完后重启一次 SystemUI 生效；若状态异常，在模块管理器里确认已勾选
  `com.android.systemui` 与 `com.miui.miwallpaper` 作用域
- 适配：澎湃 OS 3（Android 16）；多机型测试期，遇到显示异常请用「调试」分组的诊断报告反馈
```

Release：<https://github.com/zuige66/Hyper-MeloLock/releases/tag/v0.3.6>

## 国内备用源 latest.json（blog.zuiges.com/downloads/melolock/latest.json）

已同步并**线上验证通过**（2026-10-24 01:20：返回 `versionCode 16`，APK HEAD 200 / 3,716,574 字节）：

```json
{
  "versionCode": 16,
  "versionName": "0.3.6",
  "apkUrl": "https://github.com/zuige66/Hyper-MeloLock/releases/download/v0.3.6/Hyper-MeloLock-v0.3.6.apk",
  "changelog": "- 歌词、歌名、歌手三者同屏：播放器副行显示「歌手 / 歌名」，放歌词时不再看不到歌名\n- 播放器卡片可隐藏专辑封面，歌词与歌名歌手两行水平居中\n- 逐句歌词：主行跟着播放进度一句一句走，前奏与广告句自动跳过\n- 密码界面同样用专辑封面做背景，不再闪回原生壁纸\n- 长歌名 / 歌手名自动滚动，速度与停顿可调\n- 修复媒体卡染色残留、时钟小时偶发消失、解锁闪原生壁纸"
}
```

博客侧动作：APK 复制到 `D:\Workplace\hexo\source\downloads\melolock\`，`hexo generate` + `hexo deploy`（gh-pages `2681b30`）。

## 博客文章

`source/_posts/hyper-melolock-lockscreen-cover.md`：国内直链改指 v0.3.6，文末新增「四、v0.3.6 更新说明」一节。

## 对外文案

- 酷安：`docs/COOLAPK-v0.3.6.md`（含发布前检查项与备选短版）
- README 下载直链与文案链接已同步到 v0.3.6

## 待办

- `docs/downloads/melolock/` 里积了 11 个包（含 8 个测试包），下次发版可清理，只留 latest + 上一版。
