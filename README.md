# Dashcast 仪表导航

BYD Android 车机 → QNX 仪表的 USB 独立应用投屏。当前源码 **1.1.2 / code37**，Kotlin 四页产品入口；优化顶底布局坐标、柔和边缘及续租待确认判定，保留地图准备与断线恢复，健康会话没有人为累计时长、帧数或流量上限。

- 首页一键开始/停止导航投屏；车机切其他应用时，仪表保持所选独立副屏应用。
- 应用搜索、收藏、默认选择；高德分身优先，不兼容时不偷偷回退主屏镜像。
- 顶部状态、中央地图、底部原生功率/车速/电量/续航；原生数据绑定不由 APK 重画。支持比例保持/中心裁剪、兼容小窗、日间/夜间主题。
- 前台服务持续拥有同一条会话，直至停止、实际断开或异常；不定时重连，通知栏可停止。
- 模式切换通过原厂导航文字中继；停止并确认清理、ALT0/接口释放后再切换，正常流程不要求手动拔线。重复 NCM 保留已有连接。
- 诊断页提供 ADB 连接、一键日志导出到下载目录；历史工程工具不再作为默认页面。
- 视频不使用激活码、token 或设备绑定；正常 ADB 调试和 USB 访问许可保留。

[1.1.2 布局优化与升级](docs/PRODUCT_1.1.2.md) · [构建](docs/BUILD.md) · [验证边界](docs/VALIDATION.md) · [架构](docs/ARCHITECTURE.md)

## 产品与安全边界

正常导航不再要求每次勾选停车验证；应用选择/设置应在不分心的安全场景完成，驾驶中只使用导航，不观看娱乐视频。软件没有可信车速/挡位输入，不假装知道车辆状态。

全尺寸视频在原生窗口下方，背景和导航布局由同所属可续期的五秒租约管理；失联、主题代次变化、停止会恢复，不永久改主题。1.0.1 已获用户确认全尺寸有画面、原生信息正常及本次 Host 恢复成功，五轮同连接停止清理链正常。**1.1.1 照片已确认布局重排生效，但有裁切/对齐和硬边；1.1.2针对这些优化，新的实际观感仍待现场。素材合成预览不是渲染结果，冷启动/物理重连不由此自动验收；全部告警/道路未认证，既有基础通过项保持。**

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
