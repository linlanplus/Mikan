# 小橘分身 · Mikan

无广告、不限数量的安卓应用多开。不用 root。

An ad-free Android app cloner with no clone limit. No root needed.

> 当前版本 / Current version · **1.0.2**

## 功能 · Features

- **想开几个就开几个：** 同一个 App 可以开 3 个、5 个，甚至更多。每个分身各自登录、各自存数据，互不影响。
- **每个分身都像另一台手机：** 自动分配不同的机型、设备名、Android ID 和序列号；也可以自己改，内置 25 款常见机型。
- **用户和文件夹：** 按「用户」分组，用文件夹整理，长按拖动，跟桌面差不多。
- **链式代理：** 在「设置 → 设置链式代理」导入订阅或节点。第一次保存后，入口会变成「我的链式代理」。长按分身可选节点、测延迟。内置 Xray，支持 VMess、VLESS、Trojan、Shadowsocks，只代理该分身的 TCP；UDP 和 DNS 仍走本机网络。
- **重命名：** 只改分身在列表里的名字，不改应用本身。
- **相册管理：** 在「设置 → 相册管理」里按用户名选照片或撤销授权，不用开完整系统相册权限。
- **GMS 管理：** 按首页用户名配置 Google 服务；之后新建或移进来的分身会跟着这个设置走。
- **虚拟定位、快捷方式、冻结：** 从上游项目继承下来的能力。
- **主题和语言：** 跟随系统 / 浅色 / 深色，简体中文 / English。

---

- **As many clones as you want:** run three, five, or more copies of the same app. Each clone keeps its own data and logins.
- **A different phone per clone:** every clone gets its own model, device name, Android ID, and serial number. You can edit those, or pick from 25 built-in phone models.
- **Users and folders:** group clones by user, tidy them into folders, and rearrange with a long-press drag — like a home screen.
- **Proxy chains:** import a subscription or node link in **Settings → Set up proxy subscriptions**. After the first save, that entry becomes **My proxy subscriptions**. Long-press a clone to pick a node or test latency. The bundled Xray core can route that clone's TCP over VMess, VLESS, Trojan, or Shadowsocks. UDP and DNS still use the device network.
- **Rename:** change only the name shown in the list, not the app itself.
- **Album management:** under **Settings → Album management**, pick photos for a named user or revoke access — no need for full gallery permission.
- **GMS Manager:** set Google services per home-screen user. New and moved clones follow that user's setting.
- **Fake location, shortcuts, freezing:** carried over from the upstream project.
- **Theme and language:** system / light / dark, 简体中文 / English.

## 下载 · Download

从 [Releases](https://github.com/linlanplus/Mikan/releases) 下载：

- `arm64-v8a`：近几年的手机一般选这个
- `armeabi-v7a`：老一点的 32 位手机

Download from [Releases](https://github.com/linlanplus/Mikan/releases): pick `arm64-v8a` for most recent phones, or `armeabi-v7a` for older 32-bit devices.

## 已知问题 · Known issues

- Google 官方 Android 12L x86 模拟器跑不了内置的 ARM 版 Xray。代理功能已在 MuMu Android 15 上测过。
- Telegram 的后台保活偶尔会报错。
- 繁体中文翻译还不完整。
- 有些 App 会检测多开环境，进了分身可能用不了。

---

- The Google Android 12L x86 emulator cannot run the bundled ARM Xray binary. Proxy features have been tested on MuMu Android 15.
- Telegram's background keep-alive job can fail.
- The Traditional Chinese translation is incomplete.
- Some apps detect cloning and may not work inside a clone.

## 使用须知 · Notice

这个项目只适合学习和个人合法使用。请遵守当地法律和各 App 的用户协议，别拿去刷量、欺诈或干别的违规事。

This project is for learning and lawful personal use. Follow the law where you live and each app's terms. Don't use it for fraud, fake engagement, or other abuse.

## 编译 · Building

需要 JDK 21、Android SDK、NDK 29，以及 Go 1.26 工具链。Gradle 会给两个 ARM 架构编内置 Xray。项目路径里别带空格，否则 ndk-build 可能出事。

Builds need JDK 21, the Android SDK, NDK 29, and a Go 1.26 toolchain. Gradle builds the embedded Xray binary for both ARM architectures. Keep spaces out of the project path so ndk-build doesn't break.

```bash
./gradlew assembleDebug
```

正式签名：准备一个 properties 文件（含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`），在 `~/.gradle/gradle.properties` 里写上 `mikan.keystore=<文件路径>`。没有的话，release 会用调试签名。

For a release signature, create a properties file with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`, then set `mikan.keystore=<path>` in `~/.gradle/gradle.properties`. Without that, release builds use the debug key.

## 作者 · Authors

本版本作者：Claude Code (Opus 5.5)、Codex (GPT-6 Astra)、LinLan

## 致谢 · Credits

小橘分身基于以下开源项目：

Mikan is built on these open source projects:

- [Black's BlackBox](https://github.com/Black00Z/Blacks-BlackBox) by Black00Z：本项目的直接上游
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

[Apache License 2.0](LICENSE)。改动说明见 [NOTICE](NOTICE)，第三方许可见 [THIRD_PARTY_NOTICES.txt](app/src/main/assets/THIRD_PARTY_NOTICES.txt)。

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for what changed, and [THIRD_PARTY_NOTICES.txt](app/src/main/assets/THIRD_PARTY_NOTICES.txt) for third-party licenses.

版权问题与 bug 反馈 · Copyright and bug reports: support@lqstudio.uk
