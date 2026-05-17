# LightBreaker

原生 Android MVP：拳击手套蓝牙计拳、击碎瓷砖揭开 Mock 云端画作、本地画廊与等级成长。

## 服务器

- 服务器地址：`http://152.136.62.157/`
- 数据库：`LightBreaker`
- 瓷砖底图目录：`http://152.136.62.157/lightbreaker/images/`
- 图片清单：`http://152.136.62.157/lightbreaker/images/manifest.json`
- 图片审核页：`http://152.136.62.157/lightbreaker/images/review.html`
- 图片类型：自然风光、名画再现、城市建筑、抽象艺术，每类 100 张。
- APP 端只保留非敏感服务器元数据；数据库账号密码不写入源码或 APK。
- 当前服务器已建立 `LightBreaker` 数据库及 `app_profiles`、`glove_devices`、`gallery_items`、`session_records`、`achievement_states`、`glove_packet_logs`、`sync_events` 表。

## 蓝牙协议

协议来源：`D:/2026/202605/瓷砖方案/击打瓷砖APP蓝牙协议.docx`

- 左手设备名：`BOXING#PL...`
- 右手设备名：`BOXING#PR...`
- 开启陀螺仪：`C5 5C 04 01`
- 关闭陀螺仪：`C5 5C 04 00`
- 通知包：`D5 5D 03 包数 电量 陀螺仪拳击次数 压力拳击次数 陀螺仪拳击力度 压力拳击力度 空 空`
- GATT UUID 未在协议中声明，APP 会自动选择 notify/write 特征，并优先兼容 `FFE4` notify 与 `FFE1` write。

## 构建

```powershell
cd D:\2026\202605\0518
.\gradlew.bat assembleDebug
```

调试包输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 测试

当前工作区路径为 ASCII 目录，可以直接运行协议单元测试：

```powershell
cd D:\2026\202605\0518
.\gradlew.bat testDebugUnitTest
```

## 图片素材采集

服务器图片素材通过 `tools/fetch_lightbreaker_commons_images.py` 从 Wikimedia Commons API 采集，并在 `manifest.json` / `sources.csv` 中保留来源、作者和许可证信息。

## 已实现范围

- 首页：Mock 云端画作生成、扫描/连接手套、开始训练。
- 蓝牙调试：设备列表、左右手状态、原始通知包日志。
- 游戏页：16 x 10 瓷砖、连击、力度破砖、左右手贡献、模拟出拳。
- 结算页：拳数、完成率、最高连击、卡路里、XP。
- 我的画廊：完成 100% 的作品保存到 Room。
