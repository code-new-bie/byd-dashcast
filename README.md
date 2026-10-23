# Dashcast 仪表导航

BYD Android 车机 → QNX 仪表的 USB 应用投屏。当前源码 **1.4.0 / code40**，Kotlin产品入口及有效内容区合成：全屏1728×432、大中心窗720×384内框，原厂深浅ColorBrush四边过渡。保留原图标同任务交接和停止归还导航；没有人为累计时长、帧数或流量上限。

- 首页一键开始/停止；原应用图标打开时车机可操作同一任务，切其他应用后由副屏承接；不是两份独立导航，停止不强停地图。
- 应用搜索、收藏、默认选择；高德分身优先，不兼容时不偷偷回退主屏镜像。
- 顶部状态、中央地图、底部原生功率/车速/电量/续航；原生数据绑定不由 APK 重画。支持比例保持/中心裁剪、兼容小窗、日间/夜间主题。
- 前台服务持续拥有同一条会话，直至停止、实际断开或异常；不定时重连，通知栏可停止。
- 模式切换通过原厂导航文字中继；停止并确认清理、ALT0/接口释放后再切换，正常流程不要求手动拔线。重复 NCM 保留已有连接。
- 诊断页提供 ADB 连接、一键日志导出到下载目录；历史工程工具不再作为默认页面。
- 视频不使用激活码、token 或设备绑定；正常 ADB 调试和 USB 访问许可保留。

[1.4.0 升级/内容区/融合](docs/PRODUCT_1.4.0.md) · [1.3.0 同任务交接](docs/PRODUCT_1.3.0.md) · [构建](docs/BUILD.md) · [验证边界](docs/VALIDATION.md) · [架构](docs/ARCHITECTURE.md)

## 产品与安全边界

正常导航不再要求每次勾选停车验证；应用选择/设置应在不分心的安全场景完成，驾驶中只使用导航，不观看娱乐视频。软件没有可信车速/挡位输入，不假装知道车辆状态。

全屏和中心窗视频均在原生信息下，所属五秒滚动租约在失联、代次变化或停止时恢复，不永久改主题。1.2.0实车照片显示旧融合不达标，本版用明确原厂颜色画刷替代新增材质链，真正绘制/回读了桌面像素，不以编译链接代替效果。**本版QNX GPU/Kanzi主题动态切换、Android EGL、全部告警和实车视觉仍需目标机确认。**地图昼夜由导航设置管理；原生警告仍可能覆盖地图，不能为美观隐藏车门/安全带/ADAS分支。

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

本版须匹配升级APK与仪表App：相对1.2/1.3仅HMI/KZB/receiver三个App成员变化，其余50及MCU、参考OS、MLO/IFS/Boot、USB角色脚本不改。receiver只改一条配套能力比较，媒体与NCM链保留。公开源码不含固件原件、APK、SDK、密钥、ADB身份或原始实车日志；配套另行分发。组件工具需要精确原始HMI/KZB/TOC及1.2 receiver，输出不是可刷FULL，[详见构建](docs/BUILD.md)。

[使用](docs/USAGE.md) · [发布边界](docs/PUBLISHING.md) · [安全](SECURITY.md) · [依赖来源](THIRD_PARTY_NOTICES.md) · [许可证待选](LICENSE_STATUS.md) · [贡献](CONTRIBUTING.md)

本项目不是 BYD、Desay SV 或 QNX 官方项目，名称仅说明测试目标。
