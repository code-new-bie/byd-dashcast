# 架构

```
车机（Android 10）                                      仪表（QNX，Kanzi HMI）
┌────────────────────────────────┐   USB 数据线    ┌──────────────────────────────────┐
│ ProductActivity  投屏/应用/设置 │   NCM 网络     │ dip-instrument/                   │
│ ProjectionService 前台会话      │ ─────────────▶ │   instrument-receiver  解码 H.264  │
│ shell 宿主（经本机 adbd）       │  DCV1 视频流   │   usb-mode.ksh 等  USB 模式切换   │
│   虚拟屏 + 编码器               │                │ rc_hmi.exe + bic_byd_stha.kzb     │
│ 用户态 USB NCM（ncm/）          │                │   视频窗口在下，原生信息在上       │
└────────────────────────────────┘                └──────────────────────────────────┘
```

## 车机端（`android`）

- **界面与状态**：`ProductActivity` 有投屏、应用、设置三页，投屏页内嵌操作窗口（`EmbeddedCastView`，布局由 `CastWorkspaceLayout` 计算）。`ProductController` 协调开始、停止、更换应用和连接；`ProductSettings` 保存用户选择。
- **会话**：`ProjectionService` 是前台服务，从开始到停止、断开或出错一直持有同一个会话，不定时重连，通知栏可以停止。
- **shell 宿主**：应用通过本机 adbd（`AdbShellClient`，RSA 身份存在 Android Keystore）以 shell 身份运行 `app_process` 宿主，由它创建虚拟屏、迁移应用任务和采集画面（`ShellMirrorHost`、`ShellScreenCapture`、`ShellExternalDisplay`）。普通应用的任务放在本应用拥有的虚拟屏上；主屏上的投屏页和仪表看到的是同一帧画面。
- **视频**：画面经编码器编成 H.264，按 DCV1 协议（`NoAuthVideoProtocol`）通过 TCP 发出。
- **USB 连接**：`ncm/` 是用户态 USB NCM 实现，车机作为 USB 主机，经数据线和仪表组成网络。仪表 USB 角色（平时做 USB 主机读 U 盘，投屏时做 NCM 网络设备）的切换请求经原厂导航文字通道发给仪表（`NavigationUsbRelay`），由仪表脚本执行。
- **外部接入**：`ExternalDisplayService` 提供名为 `fission_dashcast_meter` 的屏幕，供支持外部屏幕的应用（如定制版高德地图）直接发送画面。定制地图自带的仪表按钮只会向车辆请求切换导航类型、并等待原车仪表回报，我们的仪表不回报，所以由无障碍服务 `MirrorIconService` 识别这个按钮的点击，再由 App 开关地图投屏；开启接入时，shell 宿主会顺带打开这个无障碍服务。
- **授权与启动**：仪表每次切换到投屏模式都会重新枚举 USB 设备，`ShellUsbGrant` 在 shell 里（车机 shell 有 `MANAGE_USB`）给本应用授予该设备的访问权限，避免系统弹窗；`BootReceiver` 在“开机启动”开启时，恢复保存的连接开关。

## 仪表端（`instrument`）

- `startup.ksh` 在开机时由仪表的启动管理调用，只在 tmpfs 里准备本模块，开机不切换 USB 角色。
- `usb-mode.ksh` 在 USB 主机模式和 NCM 设备模式之间切换（仪表地址 192.168.77.1，车机 192.168.77.2），`instrument-usb` 是它使用的辅助程序。
- `cast-mode.ksh` 启动和停止接收端；`run-cast.ksh` 是接收端的守护包装，记录退出和清理结果，清理没有确认时不允许再次开始。
- `instrument-receiver` 接收 DCV1 视频并解码，显示在 HMI 下层的窗口里。
- `rc_hmi.exe` 和 `bic_byd_stha.kzb` 加了投屏布局：空闲、全屏、小窗三种状态。投屏时用自有背景和曲边遮罩，原生的车速、功率、电量、续航和告警仍在最上层；停止或失联后恢复原厂背景。遮罩的混合方式见[仪表 1.19.3](PRODUCT_1.19.3.md)。
- 1.22.0 起两种布局的视频窗口都是整屏 1:1。App 画的清晰画面比遮罩的清晰区大出一圈淡出带，由遮罩把它淡入外框（见[1.22.0](PRODUCT_1.22.0.md)）。HMI、接收端和 App 之间用配对标记 RWVG 互相核对，版本不匹配就拒绝投屏。
- `3dscreen.kzb` 在投屏时隐藏道路底板和自车模型，安全告警仍优先显示。

上述改动都是对按哈希固定的原件打补丁（ARM/Thumb 汇编源在 `instrument/`，Python 构建模块在 `analysis/`），不重新编译 HMI。构建方法见[构建文档](BUILD.md)。

## 边界

- 车机系统软键盘、禁止副屏显示的应用和部分安全窗口仍有限制。
- 目标仪表的 GPU、显示控制器和视频硬解只能在实车上验证；离线测试覆盖的范围见[验证状态](VALIDATION.md)。
