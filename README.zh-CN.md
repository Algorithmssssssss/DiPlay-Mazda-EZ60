# DiPlay for Mazda EZ-60

**为国内版马自达 EZ-60（长安马自达 J90K，CAMZD OS / Android 14，联发科 MT8676）提供有线及无线 CarPlay**，适配车机 5K 分屏和挡风玻璃 HUD。本项目是 [DiPlay](https://github.com/shihabal3amri/DiPlay) 的分支，应用包名 `com.reachkim.diplay.ez60`，可与原版 DiPlay 同时安装。

> **预发布版。** 仅在一台 EZ-60 和一部 iOS 27 的 iPhone 上测试过。本应用不是 Apple 认证产品，与 Apple、马自达、长安或梧桐（Tinnove）均无关联，也未获其认可。

[下载](https://github.com/Algorithmssssssss/DiPlay-Mazda-EZ60/releases) · [安装与连接（英文）](docs/INSTALL.md) · [报告问题](https://github.com/Algorithmssssssss/DiPlay-Mazda-EZ60/issues/new/choose) · [English](README.md)

## 0.2.17-ez60.1 可用功能

- **无线 CarPlay**：使用车载热点最流畅（使用中 43–59 fps，每帧解码 4–5 ms，触摸到画面 21–60 ms），也可使用 5 GHz Wi-Fi Direct。
- **有线 CarPlay**：通过 USB 连接，需先执行一次 ADB 命令（车机没有 VPN 授权界面）。
- **主副驾两侧屏幕**：CarPlay 在启动它的一侧打开；车机双指滑动可把它移到另一侧，无需重新连接；车机显示车辆面板或语音助手时会自动收窄。
- **挡风玻璃 HUD**：CarPlay 导航时，HUD 由车机自己绘制转向箭头、距离和剩余路程，并在地图区域显示 CarPlay 地图。已用 Google 地图验证。
- **针对 MT8676 优化画面**：默认 60 fps，并使用联发科低延迟 H.264 解码器。
- 方向盘上一曲、下一曲和播放／暂停键。
- **可与车机蓝牙音乐并存**：DiPlay 把音频焦点留给车机蓝牙播放器，车机不再在 CarPlay 播放时暂停 iPhone。
- 隐藏仅适用于比亚迪的设置；其余 DiPlay 0.2.17 功能均保留。

## 已知问题

- **HUD 音乐行**显示“暂无歌词”：这是车机的歌词行，而 CarPlay 不提供歌词。
- 副驾一侧的 CarPlay 区域按主驾一侧镜像设定，尚未实测。

## 安装与连接

1. 从[发布页](https://github.com/Algorithmssssssss/DiPlay-Mazda-EZ60/releases)下载 APK，安装到**车机**上（不要装到 iPhone）。请查看发布说明：不含 CarPlay 配件身份的版本无法连接 iPhone。
2. 在想显示 CarPlay 的一侧从应用列表打开 **DiPlay EZ-60**，按提示授予附近设备（蓝牙）、麦克风和通知权限。
3. **无线**：先用蓝牙配对 iPhone 与车机。在“设置 → 连接 → 连接设置”中选择“车载热点（推荐）”并填写车载热点的名称和密码，或选择 Wi-Fi Direct。关闭 iPhone 的个人热点，然后点按“连接”。
4. **有线**：在能用 ADB 连接车机的电脑上执行一次下面的命令，然后用数据线连接 USB 数据口并点按“通过 USB 连接”：

   ```sh
   adb shell appops set com.reachkim.diplay.ez60 ACTIVATE_VPN allow
   ```

5. **HUD**：“设置 → 导航 → 位置 → HUD 逐向导航（实验性）”默认开启，更改在下次连接时生效。

如遇问题，请在停车状态下复现，然后在“设置 → 诊断 → 保存诊断报告”导出报告，删除个人信息后附在 [Issue](https://github.com/Algorithmssssssss/DiPlay-Mazda-EZ60/issues/new/choose) 中。切勿公开热点密码。

## CarPlay 配件身份

源代码和源码构建不包含 CarPlay 配件身份，因此无法连接 iPhone。要连接 iPhone，构建时需要自行提供身份文件，见[构建说明](docs/BUILD.md)。每个发布版本的说明都会写明其 APK 是否包含身份；任何拿到这类 APK 的人都能提取其中的身份，见 [SECURITY.md](SECURITY.md)。

## 来源与致谢

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)，DiPlay 又基于 [xcertplay](https://github.com/shilapi/xcertplay)（GPL-3.0）。主页和设置界面改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0），许可证见 `docs/licenses`。最初的 EZ-60 座舱适配（5K 屏幕上的显示区域切换）来自 [JulyStar-Lv 的 DiPlay 分支](https://github.com/JulyStar-Lv/DiPlay)，其尺寸依据 [TidePlayer](https://github.com/JulyStar-Lv/TidePlayer) 公布的测量结果。分发修改版本时请保留这些声明。CarPlay 及其图标归 Apple Inc. 所有。
