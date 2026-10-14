# v0.3.5 发布物料（2026-10-14）

- 版本号：`versionCode 15` / `versionName 0.3.5`，tag `v0.3.5`
- 资产：`Hyper-MeloLock-v0.3.5.apk`（3,700,044 字节，正式签名 SHA-1 `2b73265bf50dba6576a7758c552340cdc54ec1f7` ✓ 已 apksigner 校验）
- 本轮提交（`git log v0.3.4..HEAD`）：`ec7dbde` 远程诊断报告 + `c2adb10` 移除版本门禁

## Release notes

```markdown
### 新增 / 改进

- **远程诊断报告**：「调试」分组新增「诊断报告」卡片，展开可看模块在锁屏里的关键事件
  （版本采集、Hook 安装结果、渲染跳过原因、媒体会话包名等），一键复制后发给开发者即可，
  不再需要数据线、root 或 adb——远程适配排障零门槛
- **移除版本门禁**：不再按系统界面 / 壁纸插件的构建号硬性拦截，其他机型、其他澎湃 OS 3
  小版本也会尝试启用（找不到目标界面时自动恢复原生锁屏）。完整验证机型仍是 Redmi
  Note 9 Pro（澎湃 OS 3，Android 16），其他机型遇到显示异常请附诊断报告反馈

### 安装说明

- 从旧版本**直接覆盖安装即可，不需要卸载**（卸载会清空配置）
- 装完后重启一次 SystemUI 生效；若状态异常，在模块管理器里确认已勾选
  `com.android.systemui` 与 `com.miui.miwallpaper` 作用域
- 适配：澎湃 OS 3（Android 16）；多机型测试期，欢迎带诊断报告反馈
```

## 国内备用源 latest.json（blog.zuiges.com/downloads/melolock/latest.json）

主源 `api.github.com` 拉不到时，App 会回退到这个静态文件。**发版后必须同步更新**，
字段缺失会被判为无效源（校验：`versionCode` > 0、`versionName` 非空、`apkUrl` 非空）：

```json
{
  "versionCode": 15,
  "versionName": "0.3.5",
  "apkUrl": "https://github.com/zuige66/Hyper-MeloLock/releases/download/v0.3.5/Hyper-MeloLock-v0.3.5.apk",
  "changelog": "- 「调试」页新增诊断报告：一键复制关键事件发给开发者，远程排障零门槛\n- 移除版本门禁：其他机型 / 澎湃 OS 3 小版本也尝试启用，显示异常自动恢复原生锁屏"
}
```

> 判定逻辑：versionCode 整数比较 **或** versionName 三段比较，任一更新即提示升级
> （两条源口径一致，避免 GitHub 源说有更新、blog 源说已是最新）。

## 上传清单

1. GitHub Release（tag `v0.3.5`，资产 `Hyper-MeloLock-v0.3.5.apk`）——可用 PAT 经 API 创建
2. blog 侧 `downloads/melolock/latest.json` 按上面 JSON 更新（zuige 手动上传；若 APK 也放
   blog 直链，把 `apkUrl` 换成 `https://blog.zuiges.com/downloads/melolock/Hyper-MeloLock-v0.3.5.apk`）
3. 发布前让 zuige 过目 release notes
