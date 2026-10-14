# Dashcast

BYD Android 车机 → QNX 仪表 USB 投屏工程。当前开发源码 **0.7.2 1920×720/30 FPS 高清 cadence 修正版**，匹配 **0.7.2 高清仪表接收端**。0.5.0已有两轮动态投屏、后台、停止及拔线Host/U盘现场通过；0.7.2 的硬编、QNX解码和真实30 FPS仍待实车验证，公开 Release 仍为0.4.4。

此项目用于可恢复台架或车辆停稳时的验证，不是官方固件、通用车型适配包或行驶中使用的生产产品。仪表 MCU 不修改，视频不使用激活码/token/设备绑定；正常 ADB 和 USB 访问授权仍然必需。

## 当前成果

- APK 经已授权的本机 ADB 和原厂导航文字中继，请求仪表 Host/NCM 模式。
- 车机画面经 shell SurfaceControl 镜像、硬件 H.264 Baseline 编码，通过用户态 USB NCM/TCP/DCV1 发送。
- 复用仪表已有媒体/窗口接口，验证中央 640×240 小窗口；原仪表 HMI 保留。
- 0.4.4 修复操作锁残留、跨会话 TCP 源端口复用和重复 NCM 重连。
- 实车已有首次动态画面与新版9次重复连接记录；**传输通过、画面观察和Host实际恢复是不同验收项**。
- 0.5.0增加前台Service拥有的最长30分钟会话、通知停止、有界日志；切导航不再由Activity取消长会话。采集传输真实编码PTS，不保存持续视频。
- 长模式先协商DCV1/2；0.4.x仪表接收端会在START前拒绝。0.6.0继续使用0.5.0仪表App，已安装该版本无需重刷仪表，只需同签名覆盖APK。
- 0.6.0增加完整画面保持比例、中心裁剪、自定义地图区域及原拉伸对照；配置按会话冻结，保留为历史几何候选。
- 0.7.0将持续投屏的车机硬件 AVC/DCV1 几何提升为1920×720/30 FPS请求，并在DCV1发送前按编码PTS限流；仪表App只改高清几何槽和720行AVC边界，MCU、参考OS、USB角色脚本和停止机制保持不变。
- 0.7.1在发送端保留PTS选帧并将接受帧重定时到固定33,333微秒传输节奏；APK升为code18，仪表二进制逻辑沿用0.7.0高清适配。
- 0.7.2将Surface输入编码器无新画面时的上一帧重复 cadence 对齐到33,333微秒，并保留0.7.1的PTS选帧/重定时；APK升为code19，必须与匹配的0.7.2高清仪表App成对使用。该机制维持静态画面节奏，不制造运动画面。
- 固件KZB已确认存在中央动态告警节点的本地几何；不能宣布中央小窗是全状态安全区。**本版未实现告警隔离，只允许停稳/台架验证**，勾选确认不等于安全认证。
- 0.6.0新几何仍需停稳实显验收；0.5.0首轮176.890秒主动停止、次轮10.687秒，两轮清理通过，但10/30分钟、自然到时及异常场景未覆盖。

详细边界见 [验证状态](docs/VALIDATION.md)，不要把历史“待验证”或当前一次通过推广到所有车型。

## 仓库结构

```text
phase1/android/              APK源码、Gradle Wrapper、当前协议测试
phase1/qnx/ref2-noauth/      QNX脚本、ARM适配源、测试替身
analysis/                   哈希固定的原生适配、ELF读取、SV容器工具
tools/                      公开内容检查、离线App包装、工具测试
docs/                       架构、构建、操作、验证和发布清单
.github/                    源码检查/Android构建CI、问题模板
SOURCE_MANIFEST.json        当前审核源码的逐文件哈希、来源及机械变换清单
```

不包含原厂/参考固件、SDK、编译APK、原始实车日志、签名私钥、ADB身份或历史视频凭据。固件构建需要操作者自行提供匹配且有权使用的输入，**克隆仓库不等于可以直接刷仪表**。

## 构建与使用

APK需要JDK21、Android SDK35、build-tools35.0.1；Java语言目标11，目标车机Android10/API29。依赖版本承接0.4.4，没有同时升级工具链或重写Kotlin。

```bash
cd phase1/android
bash gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Windows使用`gradlew.bat`。首次需要网络下载依赖；`--offline`只适合已准备好缓存的环境。完整说明：[构建](docs/BUILD.md)、[停稳验证顺序](docs/USAGE.md)。

**新环境产生的debug签名可能不同，不能保证覆盖安装原实测APK。不要为覆盖安装而公开旧签名密钥，也不要盲目卸载清数据丢失ADB身份。**

## 文档入口

- [架构及端口说明](docs/ARCHITECTURE.md)
- [0.7.2高清 cadence 任务与剩余门禁](docs/TASKS_0.7.2.md)
- [0.6.0任务、几何与固件显示边界](docs/TASKS_0.6.0.md)
- [0.5.0任务、状态机和验收](docs/TASKS_0.5.0.md)
- [0.7.2及历史改动](CHANGELOG.md)
- [验证状态与剩余现场确认](docs/VALIDATION.md)
- [上传前检查与Release边界](docs/PUBLISHING.md)
- [贡献说明](CONTRIBUTING.md)、[安全说明](SECURITY.md)
- [源码来源/依赖](THIRD_PARTY_NOTICES.md)、[许可证待选状态](LICENSE_STATUS.md)

本项目不是BYD、Desay SV或QNX官方项目，名称仅用于说明测试目标。
