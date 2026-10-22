# Dashcast 仪表导航

BYD Android 车机 → QNX 仪表的 USB 独立应用投屏。当前源码 **1.2.0 / code38**，Kotlin 四页产品入口；恢复右下里程位置、原厂深浅主题融合和四边渐变，新增16:9大中心窗。健康会话没有人为累计时长、帧数或流量上限。

- 首页一键开始/停止导航投屏；车机切其他应用时，仪表保持所选独立副屏应用。
- 应用搜索、收藏、默认选择；高德分身优先，不兼容时不偷偷回退主屏镜像。
- 顶部状态、中央地图、底部原生功率/车速/电量/续航；原生数据绑定不由 APK 重画。支持比例保持/中心裁剪、兼容小窗、日间/夜间主题。
- 前台服务持续拥有同一条会话，直至停止、实际断开或异常；不定时重连，通知栏可停止。
- 模式切换通过原厂导航文字中继；停止并确认清理、ALT0/接口释放后再切换，正常流程不要求手动拔线。重复 NCM 保留已有连接。
- 诊断页提供 ADB 连接、一键日志导出到下载目录；历史工程工具不再作为默认页面。
- 视频不使用激活码、token 或设备绑定；正常 ADB 调试和 USB 访问许可保留。

[1.2.0 主题融合与大中心窗](docs/PRODUCT_1.2.0.md) · [构建](docs/BUILD.md) · [验证边界](docs/VALIDATION.md) · [架构](docs/ARCHITECTURE.md)

## 产品与安全边界

正常导航不再要求每次勾选停车验证；应用选择/设置应在不分心的安全场景完成，驾驶中只使用导航，不观看娱乐视频。软件没有可信车速/挡位输入，不假装知道车辆状态。

全屏和大中心窗视频都在原生信息下方，背景/布局仍由所属五秒租约管理；失联、代次变化或停止会恢复，不永久改主题。1.1.2实车约9分45秒正常投屏/停止，用户确认总体排布规整，但里程缺失与主题融合需要优化。本版已实现这些改动及新中心窗，新增着色器通过桌面实际 GLES 编译链接；**新仪表GPU/Android EGL运行、视觉、主题动态切换、全部告警与道路范围仍待实车，不把桌面或模拟当验收。**地图昼夜仍由导航设置管理，APK主题与仪表主题分开。

既有小窗、应用独立副屏、Host/NCM 与两轮五秒背景恢复现场成果继续保留；开发离线回归由项目维护者完成，不要求用户重复跑所有旧探针。[历史状态与改动](CHANGELOG.md)单独保留各版本范围。

## 构建

JDK21、Android SDK35、build-tools35.0.1，目标车机 Android10/API29；Java/Kotlin JVM 目标11。沿用 AGP9.3 内置 Kotlin，不叠加旧 Kotlin Android 插件或更换工具链。

```bash
cd phase1/android
bash gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Windows 用 gradlew.bat。首次依赖需要网络；准备完缓存才能 --offline。**自行构建的 debug 签名可能不同，不保证覆盖安装实测 APK；不要公开旧签名私钥或盲目卸载清数据丢失 ADB 身份。**

## 仓库与固件

Kotlin 产品界面、状态协调、应用目录、配置、角色工作流和持续合成控制位于 phase1/android；已打通的 Java 编码/DCV1/NCM/shell ABI 保留。phase1/qnx 提供脚本、精确 ARM/Thumb 适配源及便携控制器；analysis 与 tools 提供哈希固定组件生成和公开内容检查。

MCU、参考 OS、MLO/IFS/Boot、接收器和 USB 角色脚本不改；1.1.2 相对1.1.1仪表只改 App 内 KZB，HMI原字节保持，保留原厂数据源和告警资源。公开源码不含原厂/参考固件原件、APK、SDK、密钥、ADB 身份或原始实车日志；配套另行分发。组件工具需要精确原始 HMI/KZB/TOC，输出不是可刷 FULL；两端匹配 RWN2+NLC 状态，[详见构建](docs/BUILD.md)。

[使用](docs/USAGE.md) · [发布边界](docs/PUBLISHING.md) · [安全](SECURITY.md) · [依赖来源](THIRD_PARTY_NOTICES.md) · [许可证待选](LICENSE_STATUS.md) · [贡献](CONTRIBUTING.md)

本项目不是 BYD、Desay SV 或 QNX 官方项目，名称仅说明测试目标。
