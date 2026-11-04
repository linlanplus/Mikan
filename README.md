# 小橘分身 · Mikan

无广告、不限数量的安卓应用多开工具。不需要 root。

An ad-free Android app cloner with no limit on the number of clones. No root needed.

> 当前版本 / Current version · **1.0.2**

## 功能 · Features

- **不限数量的分身：** 同一个应用可以开 3 个、5 个甚至更多。每个分身的数据和登录状态互相独立。
- **每个分身一台"不同的手机"：** 自动给每个分身分配不同的机型、设备名、Android ID 和序列号，也可以手动修改，内置 25 款常见机型。
- **用户和文件夹：** 按"用户"分组管理分身，用文件夹整理，和桌面一样长按拖动。
- 在 **设置 → 设置链式代理** 中导入订阅或节点链接。首次保存后，这个入口变为 **我的链式代理**。长按分身可选择节点或测延迟。内置 Xray 支持 VMess、VLESS、Trojan 和 Shadowsocks，代理该分身的 TCP 连接。UDP 和 DNS 使用设备网络。
- **重命名：** 给分身改显示名称，不影响应用本身。
- 在 **设置 → 相册管理** 为指定用户名选择照片或撤销授权。按用户选图无需开启完整系统相册访问。
- 在 **GMS 管理** 按首页用户名设置 Google 服务，之后新建或移入的分身会跟随该设置。
- **虚拟定位、快捷方式、冻结：** 继承自上游项目。
- **主题和语言：** 跟随系统 / 浅色 / 深色，简体中文 / English。

---

- **Unlimited clones:** run three, five or more copies of the same app, each with its own data and login.
- **A different phone per clone:** every clone gets its own model, device name, Android ID and serial number automatically. You can also pick one of 25 built-in phone models or edit them by hand.
- **Users and folders:** group clones into "users" and organise them in folders with launcher-style drag and drop.
- Import subscriptions or node links in **Settings → Set up proxy subscriptions**. After the first save, this entry becomes **My proxy subscriptions**. Long-press a clone to choose a node or test latency. The embedded Xray core routes the clone’s TCP connections over VMess, VLESS, Trojan or Shadowsocks. UDP and DNS use the device network.
- **Rename:** change the name a clone is shown with, without touching the app itself.
- In **Settings → Album management**, choose photos for a named user or revoke access to individual photos. This works without full system gallery access.
- **GMS Manager** controls Google services per home-screen user. New and moved clones inherit that user’s setting.
- **Fake location, shortcuts, freezing:** inherited from the upstream project.
- **Theme and language:** system / light / dark, 简体中文 / English.

## 下载 · Download

从 [Releases](https://github.com/linlanplus/Mikan/releases) 下载：

- `arm64-v8a`：近几年的手机都选这个
- `armeabi-v7a`：较老的 32 位手机

Download from [Releases](https://github.com/linlanplus/Mikan/releases): `arm64-v8a` for most phones from recent years, `armeabi-v7a` for older 32-bit phones.

## 已知问题 · Known issues

- Google 官方 Android 12L x86 模拟器无法运行内置的 ARM 版 Xray。代理功能已在 MuMu Android 15 上验证。
- Telegram 的后台保活任务可能报错。
- 繁体中文翻译不完整。
- 部分应用会检测多开环境，可能无法在分身里正常使用。

---

- The Google Android 12L x86 emulator cannot run the bundled ARM Xray binary. Proxy features have been tested on MuMu Android 15.
- Telegram's background keep-alive job can fail.
- The Traditional Chinese translation is incomplete.
- Some apps detect app cloning and may not work inside a clone.

## 使用须知 · Notice

本项目仅供学习和个人合法使用。请遵守你所在地区的法律以及各应用的用户协议，不要用于欺诈、刷量或其他违规用途。

This project is for learning and lawful personal use. Follow the law where you live and the terms of the apps you use. Do not use it for fraud, fake engagement or other abuse.

## 编译 · Building

需要 JDK 21、Android SDK、NDK 29 和 Go 1.26 工具链。Gradle 会为两个 ARM 架构编译内置 Xray。请使用不含空格的项目路径运行 ndk-build。

Builds require JDK 21, the Android SDK, NDK 29 and a Go 1.26 toolchain. Gradle builds the embedded Xray executable for both ARM architectures. Use a project path without spaces for ndk-build.

```bash
./gradlew assembleDebug
```

正式签名：创建一个 properties 文件（包含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`），在 `~/.gradle/gradle.properties` 里用 `mikan.keystore=<文件路径>` 指向它。没有它时，release 版本使用调试签名。

Release signing: create a properties file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, and point `mikan.keystore=<path>` in `~/.gradle/gradle.properties` at it. Without it, release builds use the debug key.

## 作者 · Authors

本版本作者：Claude Code (Opus 5.5)、Codex (GPT-6 Astra)、LinLan

## 致谢 · Credits

小橘分身基于以下开源项目：

Mikan is built on these open source projects:

- [Black's BlackBox](https://github.com/Black00Z/Blacks-BlackBox) by Black00Z: the direct base of this project
- [NewBlackbox](https://github.com/ALEX5402/NewBlackbox) by ALEX5402
- [BlackBox](https://github.com/FBlackBox/BlackBox)
- [VirtualApp](https://github.com/asLody/VirtualApp) by asLody
- [VirtualAPK](https://github.com/didi/VirtualAPK) by didi
- [Xray-core](https://github.com/XTLS/Xray-core) by Project X
- [Dobby](https://github.com/jmpews/Dobby) by jmpews
- [xDL](https://github.com/hexhacking/xDL) by HexHacking
- [BlackReflection](https://github.com/CodingGay/BlackReflection) by CodingGay
- [FreeReflection](https://github.com/tiann/FreeReflection) by tiann

## 许可 · License

[Apache License 2.0](LICENSE)。修改说明见 [NOTICE](NOTICE)，第三方代码的许可见 [THIRD_PARTY_NOTICES.txt](app/src/main/assets/THIRD_PARTY_NOTICES.txt)。

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for what was changed, and [THIRD_PARTY_NOTICES.txt](app/src/main/assets/THIRD_PARTY_NOTICES.txt) for the licenses of third-party code.

版权问题与bug反馈 · Copyright and bug reports: support@lqstudio.uk
