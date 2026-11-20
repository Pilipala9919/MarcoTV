# Marco TV 资源与署名

作者：Pilipala · 2026-10-24

## 原创生成壁纸

以下静态图像由 Pilipala 为 Marco TV 使用图像生成工具制作，作为项目资源按 GPL-3.0-only 发布。它们是生成图像，不是真实地点拍摄或相机实拍。

| 名称 | 文件 | 画面 |
| --- | --- | --- |
| 雾蓝海岸 | `app/src/main/assets/wallpapers/coast.jpg` | 深蓝海岸、薄雾和安静的海面 |
| 暖沙丘 | `app/src/main/assets/wallpapers/dunes.jpg` | 蓝调时刻的暖色沙丘 |
| 极夜山脊 | `app/src/main/assets/wallpapers/aurora.jpg` | 云海、山脊及微弱极光 |
| 峡谷 | `app/src/main/assets/canyon.jpg` | 暗色砂岩峡谷 |

图像中不含时钟、应用图标或桌面组件，运行时按画幅居中覆盖。桌面、时间及动画由应用实时绘制。

## 第三方字体与图标

`MaterialIcons-Regular.ttf` 和应用图标中的 TV 图形来自 Google Material Design Icons，遵循 Apache License 2.0。

- [官方仓库](https://github.com/google/material-design-icons)
- [字体](https://github.com/google/material-design-icons/blob/master/font/MaterialIcons-Regular.ttf)
- [TV 图标](https://github.com/google/material-design-icons/blob/master/src/hardware/tv/materialicons/24px.svg)
- [原始许可](licenses/Material-Icons-Apache-2.0.txt)

保留 Google / Material Design Authors 的原署名和许可，不将第三方资源改署为项目原创。Android 系统字体由电视系统提供，不随项目分发。

卡片名称、图标和横幅在运行时读取用户设备已安装的应用，不预置进项目。相关品牌和权利归各自所有者。完整声明见 [NOTICE](NOTICE)。

## 4K 航拍动态壁纸缩略图

`app/src/main/assets/wallpapers/aerials/` 中的 10 张 16:9 静态预览缩略图提取自开源社区项目 [AerialScreensaver/AerialCommunity](https://github.com/AerialScreensaver/AerialCommunity) 发布的 4K 航拍视频（DJI Inspire 2 实拍），仅用于在「我的壁纸」中快速展示预览图；完整 4K HEVC 视频在用户确认后按需通过 HTTPS 下载并经 SHA-256 校验后缓存到本地，不打包进 APK。原摄影与社区版权归原作者所有。
