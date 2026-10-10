# MeloLock R8 规则（2026-10-10 启用混淆，原「不开混淆」约定已被覆盖，见 AGENTS.md）
#
# 原则：Xposed 框架只按类名字符串实例化 xposed_init 里登记的入口类，
#       Manifest 组件（Activity/Service/Provider/Application）由 AGP 自动生成的
#       aapt 规则保留，无需手写。模块内的反射全部指向 ROM 类（SystemUI /
#       KeyguardViewMediator 等），不受本模块混淆影响。

# xposed_init 登记的三个入口：框架按 FQN 反射构造并回调接口方法，
# 类名与方法名都必须原样保留。
-keep class io.github.melolock.HookEntry { *; }
-keep class io.github.melolock.ShortcutAnimBackdrop { *; }
-keep class io.github.melolock.WallpaperCover { *; }

# 内容提供器被 SystemUI 跨进程按 authority 访问；Manifest 会保留类本身，
# 这里把成员一起钉住，防止后续有人在 Provider 里加反射点后踩坑。
-keep class io.github.melolock.ConfigProvider { *; }

# libxposed service 由管理器（Vector）跨进程绑定回调，接口实现挂在
# Application（Manifest 保留）上；库自带 consumer 规则，这里只兜底不警告。
-dontwarn io.github.libxposed.**
