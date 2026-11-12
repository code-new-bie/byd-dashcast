# 架构

完整运行顺序见[实现时序图](SEQUENCE.md)，按当前 APK/仪表 1.41.0 分开说明各条入口、共同传输/合成和异常恢复，包括样式切换的几何控制与读回、投屏告警位置。仪表端全部改动都建立在一个固定的基础固件包上，见[构建文档](BUILD.md)。

```
车机（Android 10）                                      仪表（QNX，Kanzi HMI）
┌────────────────────────────────┐   USB 数据线    ┌──────────────────────────────────┐
│ ProductActivity  投屏/应用/设置 │   NCM 网络     │ dashcast/                         │
│ ProjectionService 前台会话      │ ─────────────▶ │   dashcast-receiver  解码 H.264    │
│ shell 宿主（经本机 adbd）       │  DCV1 视频流   │   dashcast-usb USB 模式切换       │
│   虚拟屏 + 编码器               │                │ rc_hmi.exe + bic_byd_stha.kzb     │
│ 用户态 USB NCM（ncm/）          │                │   视频窗口在下，原生信息在上       │
└────────────────────────────────┘                └──────────────────────────────────┘
```

## 车机端（`android`）

- **界面与状态**：`ProductActivity` 有投屏、应用、设置三页，投屏页内嵌操作窗口（`EmbeddedCastView`，布局由 `CastWorkspaceLayout` 计算）。`ProductController` 协调开始、停止、更换应用和连接；`ProductSettings` 保存用户选择。
- **会话**：`ProjectionService` 是前台服务，从开始到停止、断开或出错一直持有同一个会话，不定时重连，通知栏可以停止。
- **shell 宿主**：应用通过本机 adbd（`AdbShellClient`，RSA 身份存在 Android Keystore）以 shell 身份运行 `app_process` 宿主，由它创建虚拟屏、迁移应用任务和采集画面（`ShellMirrorHost`、`ShellScreenCapture`、`ShellExternalDisplay`）。普通应用的任务放在本应用拥有的虚拟屏上；主屏上的投屏页和仪表看到的是同一帧画面。
- **视频**：画面经编码器编成 H.264，按 DCV1 协议（`NoAuthVideoProtocol`）通过 TCP 发出。
- **USB 连接**：`ncm/` 是用户态 USB NCM 实现，车机作为 USB 主机，经数据线和仪表组成网络。仪表 USB 角色（平时做 USB 主机读 U 盘，投屏时做 NCM 网络设备）的切换请求经原厂导航文字通道发给仪表（`NavigationUsbRelay`），由仪表上的 `dashcast-usb` 执行。
- **外部接入**：`ExternalDisplayService` 提供名为 `fission_dashcast_meter` 的屏幕，供支持外部屏幕的应用（如定制版高德地图）直接发送画面。定制地图自带的仪表按钮只会向车辆请求切换导航类型、并等待原车仪表回报，我们的仪表不回报，所以由无障碍服务 `MirrorIconService` 识别这个按钮的点击，再由 App 开关地图投屏；开启接入时，shell 宿主会顺带打开这个无障碍服务。
- **授权与启动**：仪表每次切换到投屏模式都会重新枚举 USB 设备，`ShellUsbGrant` 在 shell 里（车机 shell 有 `MANAGE_USB`）给本应用授予该设备的访问权限，避免系统弹窗；`BootReceiver` 在“开机启动”开启时，恢复保存的连接开关。
- **熄火与上电**：比亚迪车机熄火是一次快速关机，约 10 秒后杀掉所有第三方应用并挂起，上电时重发开机广播（带 `from_quickboot`）。投屏模式打开时，`VehiclePowerWatch` 经已授权的本机 adbd 运行 shell 助手 `ShellVehiclePower`，每 0.3 秒读一次整车电源档位（`BYDAutoBodyworkDevice.getPowerLevel()`）和挡位；电源由开变为熄火并持续 1 秒、且不在行驶挡时判定熄火（约熄火后 1.5 秒），`ProductController` 随即停止投屏，没开“开机启动”时再把仪表切回 U 盘模式。系统关机广播是兜底。熄火后 10 秒内又上电时按开关重新连接；“上电后恢复上次投屏”开启时，上电连上仪表后重新开始熄火时正在投的应用。

## 仪表端（`instrument`）

- 开机时仪表的启动管理直接运行 `dashcast-usb --boot`：它把本模块复制到 tmpfs、建好日志和背景共享页，再切换到内存里的副本。开机不切换 USB 角色。
- `dashcast-usb` 收到指令后自己完成 USB 主机模式和 NCM 设备模式之间的切换（仪表地址 192.168.77.1，车机 192.168.77.2）。`mount`、`umount`、`io-usb-dcd`、`io-pkt-v6-hc`、`ifconfig`、`ulink_ctrl`、`pidin` 仍调用系统自带的程序，每步最多 4 秒，整次切换最多 45 秒。
- `dashcast-usb` 也是接收端的父进程：启动时通过管道得知端口已监听，停止时发 TERM 并等待退出。只有退出码为 0 才算清理完成；清理没有确认时，回到 Host 和再次启动都会被拒绝，避免在窗口或媒体没释放时继续。
- `dashcast-receiver` 接收 DCV1 视频并解码，显示在 HMI 下层的窗口里。
- `rc_hmi.exe` 和 `bic_byd_stha.kzb` 加了投屏布局：空闲、全屏、小窗三种状态。投屏时用自有背景和曲边遮罩，原生的车速、功率、电量、续航和告警仍在最上层；全屏时告警灯、ACC 灯与设定速度、限速牌排在视频四周的边框里。投屏期间（全屏和小窗）HMI 的运行时绘制过滤会按节点名跳过整个 ACCSys 组，ACC 跟车距离图、前车标识和 ACC 系统图不再出现；车道线、自车图片和道路装饰也由同一层绘制过滤隐藏。ACC 灯、设定速度、告警灯、车道偏离提示和接管提示文字照常显示。停止或失联后恢复原厂显示。遮罩用自定义混合模式：帧缓冲乘以遮罩的 RGB，所以遮罩图的 RGB 是乘数。
- 两种布局的视频窗口都是整屏 1:1。App 画的清晰画面比遮罩的清晰区大出一圈淡出带，由遮罩把它淡入外框。HMI、接收端和 App 之间用配对标记 RWVI 互相核对，版本不匹配就拒绝投屏。
- `3dscreen.kzb` 在投屏时隐藏道路底板和自车模型，安全告警仍优先显示。

两个仪表程序是本项目用 C 写的（`instrument/native/`）；HMI 和 KZB 的改动是对基础包里按哈希固定的原厂文件打补丁（Thumb 汇编源在 `instrument/native-hmi/`，Python 构建模块在 `analysis/`），不重新编译 HMI。构建方法见[构建文档](BUILD.md)。

## 边界

- 车机系统软键盘、禁止副屏显示的应用和部分安全窗口仍有限制。
- 目标仪表的 GPU、显示控制器和视频硬解只能在实车上验证；离线测试覆盖的范围见[验证状态](VALIDATION.md)。
