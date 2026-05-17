# 光影破壁者 Light Breaker

原生 Android MVP：拳击手套蓝牙计拳、击碎瓷砖揭开 Mock 云端画作、本地画廊与等级成长。

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

## 已实现范围

- 首页：Mock 云端画作生成、扫描/连接手套、开始训练。
- 蓝牙调试：设备列表、左右手状态、原始通知包日志。
- 游戏页：16 x 10 瓷砖、连击、力度破砖、左右手贡献、模拟出拳。
- 结算页：拳数、完成率、最高连击、卡路里、XP。
- 我的画廊：完成 100% 的作品保存到 Room。
