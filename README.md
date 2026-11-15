# 佳能蓝牙遥控 (CanonRemote)

> 本软件完全由 AI 生成

一个 Android 应用，通过蓝牙低功耗 (BLE) 模拟佳能 **BR-E1** 遥控器，遥控佳能相机对焦、拍照、延时拍摄和间隔拍摄（延时摄影）。

协议参考了以下逆向工程成果：

- [pklaus/canoremote](https://github.com/pklaus/canoremote)（Python，基于 bleak，实测 EOS 200D）
- [ids1024/cannon-bluetooth-remote](https://github.com/ids1024/cannon-bluetooth-remote) 与
  [Ian Douglas Scott 的协议逆向文章](https://iandouglasscott.com/2018/07/04/canon-dslr-bluetooth-remote-protocol/)
- [iebyt/cbremote](https://github.com/iebyt/cbremote)（Android 实现参考）

## 支持相机

所有支持 BR-E1 蓝牙遥控的佳能机型，例如：
EOS R / RP / R5 / R6 / Ra、6D Mark II、77D、800D、850D、200D、M50、M200、
PowerShot G7 X Mark III、G5 X Mark II 等（原作者在 EOS 200D 上实测通过）。

## 蓝牙协议速览

| 项目 | 值 |
| --- | --- |
| 遥控服务 | `00050000-0000-1000-0000-d8492fffa821` |
| 配对特征 | `00050002-0000-1000-0000-d8492fffa821`，写入 `[0x03] + 遥控器名(ASCII)` |
| 事件特征 | `00050003-0000-1000-0000-d8492fffa821`，写入 1 字节指令（Write Without Response） |
| 指令字节 | 模式位(bit2-3) \| 按键位(bit4-7) |
| 按下快门 | `0x8C`（立即模式 + 快门键） |
| 松开快门 | `0x0C`（立即模式，即松开所有按键） |
| 按下对焦 | `0x4C`，松开 `0x0C` |
| 视频开始/停止 | 按下 `0x88`、松开 `0x08` |

详见 `app/src/main/java/com/cannonremote/app/ble/CanonProtocol.kt`。

## 功能

- **连接相机**：主界面左上角相机图标进入连接界面；扫描到的相机广播中带佳能服务
  UUID 的会标记“佳能”。配对成功后自动记住设备，下次启动自动连接；支持“断开连接”
  与“忘记设备”。
- **对焦 / 快门按钮**：按住发送“按下指令”，松开发送“松开指令”（同真实遥控器）。
- **延时拍摄**：设定 N 秒后自动拍一张，带倒计时显示。
- **间隔拍摄**：立即拍第一张，之后每 N 秒拍一张，最多 M 张；显示进度与下一张倒计时。
- **后台/锁屏运行**：延时与间隔拍摄通过前台服务（Foreground Service）运行，通知栏显示
  常驻进度通知（第 x/M 张、下一张倒计时、进度条），并提供“停止拍摄”快捷按钮；配合
  WakeLock 保证锁屏期间正常触发拍摄。
- **拍一张**的实现：发送“按下快门指令”→ 等待可配置间隔（默认 150ms，见设置）→ 发送
  “松开快门指令”。

## 使用前的相机设置

1. 相机菜单：选择 **连接至无线遥控器** → **添加要连接的设备**，即可进入配对模式
2. 在应用连接界面点击 **扫描设备**，选择列表中的佳能相机即可配对连接
3. 相机驱动模式设为 **自拍/遥控**（Q 键 → Drive mode）；视频模式请在菜单开启“遥控”
4. 相机息屏后 BLE 会休眠：如果连接失败或中断，先唤醒相机（半按快门/点亮屏幕）再重试

## 构建与安装

要求：Android Studio（或 JDK 17 + Android SDK 35）、Gradle wrapper（已生成）。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Android Studio 中直接 Open 项目即可。最低支持 Android 8.0 (API 26)。

## 项目结构

```
app/src/main/java/com/cannonremote/app/
├── CanonRemoteApp.kt            # Application：持有 BLE 管理器、偏好、拍摄调度器
├── MainActivity.kt              # 主界面：连接状态、AF/快门、延时、间隔拍摄
├── DeviceActivity.kt            # 连接界面：扫描、配对、记住/忘记设备
├── SettingsActivity.kt          # 设置：快门按下→松开间隔
├── ble/
│   ├── CanonProtocol.kt         # BR-E1 协议常量与指令字节
│   └── CanonBleManager.kt       # 扫描/连接/配对/串行写队列/自动重连
├── data/AppPrefs.kt             # SharedPreferences：记住的设备与拍摄参数
├── shooting/
│   ├── ShootingController.kt    # 延时单拍与间隔拍摄调度
│   └── IntervalShootingService.kt # 拍摄前台服务：后台保活 + 进度通知
└── ui/                          # 权限工具、主题
```

## 后台运行说明

延时/间隔拍摄开始时会自动启动前台服务（`connectedDevice` 类型）并显示常驻通知，
锁屏或切换到其他应用后拍摄继续。注意：

- Android 13+ 首次开始拍摄会请求通知权限；拒绝只影响通知显示，不影响拍摄本身。
- 部分国产 ROM（MIUI/EMUI/ColorOS 等）需要在系统设置中允许应用“后台运行/自启动”
  或锁屏不清理，否则前台服务也可能被提前回收。
- 应用被用户手动划掉（部分 ROM 的“清理”动作）会连带取消拍摄，属系统行为。

## License

本项目基于 [Apache License 2.0](LICENSE) 发布。协议中参考的第三方项目：
[ids1024/cannon-bluetooth-remote](https://github.com/ids1024/cannon-bluetooth-remote)（MIT）、
[iebyt/cbremote](https://github.com/iebyt/cbremote)（Apache-2.0）。
