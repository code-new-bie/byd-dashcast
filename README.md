# Dashcast 仪表导航

BYD Android 车机 → QNX 仪表的 USB 独立应用投屏。当前源码 **1.0.1 / code34**，Kotlin 四页产品入口；健康会话不再有人为累计时长、帧数或流量上限。

- 首页一键开始/停止导航投屏；车机切其他应用时，仪表保持所选独立副屏应用。
- 应用搜索、收藏、默认选择；高德分身优先，不兼容时不偷偷回退主屏镜像。
- 比例保持/中心裁剪、全尺寸原生前景下层/兼容小窗、日间/夜间主题。
- 前台服务持续拥有同一条会话，直至停止、实际断开或异常；不定时重连，通知栏可停止。
- 模式切换通过原厂导航文字中继；停止并确认清理、ALT0/接口释放后再切换，正常流程不要求手动拔线。重复 NCM 保留已有连接。
- 诊断页提供 ADB 连接、一键日志导出到下载目录；历史工程工具不再作为默认页面。
- 视频不使用激活码、token 或设备绑定；正常 ADB 调试和 USB 访问许可保留。

[1.0.1 产品与升级](docs/PRODUCT_1.0.1.md) · [构建](docs/BUILD.md) · [验证边界](docs/VALIDATION.md) · [架构](docs/ARCHITECTURE.md)

## 产品与安全边界

正常导航不再要求每次勾选停车验证；应用选择/设置应在不分心的安全场景完成，驾驶中只使用导航，不观看娱乐视频。软件没有可信车速/挡位输入，不假装知道车辆状态。

全尺寸视频在原生窗口下方，背景由同所属可续期的五秒租约管理；失联、主题代次变化、停止会恢复原生背景，不永久透明。取消使用总时限不会取消失联、单帧和资源保护。原生前景路径保留不等于全部告警/硬件合成已经认证。**1.0.1 尚无新实车全尺寸合成、全部动态告警和带线切换电气兼容结果，不宣称道路安全认证或通用车型适配。**

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

MCU、参考 OS、MLO/IFS/Boot、KZB/前景业务不改。公开源码不含原厂/参考固件原件、APK、SDK、密钥、ADB 身份或原始实车日志；可刷配套另行审核分发，克隆源码不等于拥有可刷仪表包。1.0.1 组件生成仅支持精确1.0.0 receiver与原始已检查 HMI，两端必须匹配DCV1/3与RWN2能力，[详见构建](docs/BUILD.md)。

[使用](docs/USAGE.md) · [发布边界](docs/PUBLISHING.md) · [安全](SECURITY.md) · [依赖来源](THIRD_PARTY_NOTICES.md) · [许可证待选](LICENSE_STATUS.md) · [贡献](CONTRIBUTING.md)

本项目不是 BYD、Desay SV 或 QNX 官方项目，名称仅说明测试目标。
