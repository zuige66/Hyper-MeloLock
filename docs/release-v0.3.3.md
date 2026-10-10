# v0.3.3 发布物料（2026-10-11）

- Release：https://github.com/zuige66/Hyper-MeloLock/releases/tag/v0.3.3
- 资产：`Hyper-MeloLock-v0.3.3.apk`（3,667,278 字节，正式签名 SHA-1 `2b73265bf50dba6576a7758c552340cdc54ec1f7`）
- 版本号：`versionCode 13` / `versionName 0.3.3`，tag `v0.3.3`

## Release notes（已发布）

见 Release 页面正文，与下方「对外文案」同源，只是格式更完整。

## 国内备用源 latest.json（blog.zuiges.com/downloads/melolock/latest.json）

主源 `api.github.com` 拉不到时，App 会回退到这个静态文件。**发版后必须同步更新**，
字段缺失会被判为无效源（校验：`versionCode` > 0、`versionName` 非空、`apkUrl` 非空）：

```json
{
  "versionCode": 13,
  "versionName": "0.3.3",
  "apkUrl": "https://github.com/zuige66/Hyper-MeloLock/releases/download/v0.3.3/Hyper-MeloLock-v0.3.3.apk",
  "changelog": "- 安装包 39MB → 3.6MB\n- 修复：手电筒/相机转场背景偶尔退回纯色\n- 不再向外部发送任何统计数据\n- 清理遗留代码，功能与设置不变"
}
```

> 判定逻辑：versionCode 整数比较 **或** versionName 三段比较，任一更新即提示升级
> （两条源口径一致，避免 GitHub 源说有更新、blog 源说已是最新）。

## 酷安推文（可直接粘贴）

Hyper MeloLock v0.3.3 更新：安装包从 39MB 瘦到 3.6MB

澎湃 OS 3（Android 16）的锁屏沉浸式音乐模块，这版主要做瘦身和修 bug。

【更新内容】
1. 安装包 39MB → 3.6MB，下载和安装都快了一大截
2. 修复：手电筒 / 相机转场时，背景偶尔变成纯色、拿不到封面的问题
3. 不再向外部发送任何统计数据（旧版每次打开应用都会上传一份设备环境快照）
4. 清理了大量与本模块无关的遗留代码，界面和功能都不变

【怎么装】
- 旧版本直接覆盖安装即可，不用卸载（卸载会清空你的配置）
- 装完重启一次 SystemUI 生效
- 在模块管理器里确认已勾选 com.android.systemui、com.miui.miwallpaper 两个作用域
- 模块默认关闭，装完记得在 App 里打开总开关、勾选你的音乐应用

【适配】
只适配澎湃 OS 3（Android 16），换机型或换 ROM 版本不会生效。
另有：锁屏播放时左侧下拉不受影响、封面更新跟随切歌。

【下载】
https://github.com/zuige66/Hyper-MeloLock/releases/tag/v0.3.3

【交流】
QQ 群：1129363923
加群链接：https://qm.qq.com/q/mEJT74MJa0
（应用「关于」页里也有入口，装了 QQ 会直接拉起群资料卡）

有问题群里或评论区反馈，带上机型和系统版本。

> 群号与短链取自 `LockScreenPages.kt` 的 `QQ_GROUP_NUMBER` / `QQ_GROUP_JOIN_URL`，
> 与应用内「QQ 交流群」入口完全一致，改群号时两处要同步。

## 备选短版（酷安动态/评论区用）

Hyper MeloLock v0.3.3：安装包 39MB → 3.6MB，顺手修了转场背景偶尔变纯色的问题，
不再上报任何统计数据。旧版直接覆盖安装，不用卸载。
下载：https://github.com/zuige66/Hyper-MeloLock/releases/tag/v0.3.3
交流 QQ 群：1129363923（https://qm.qq.com/q/mEJT74MJa0）
