# Dashcast 仪表导航

BYD Android 车机 → QNX 仪表的 USB 应用投屏。当前源码 **1.12.2 / code53 / RWVA**：清晰视频覆盖整个内圈开口，仅轮廓渐变，两侧装饰圈继续隐藏；1632×440源与1920×720传输分开，安全参考框不裁视频。承接1.12实际2D模式传播、普通车模/路面让位、主屏可用区重排、地图/USB开关及按名接入。

[1.12.2 使用及边界](docs/PRODUCT_1.12.2.md) · [1.12.0 功能](docs/PRODUCT_1.12.0.md) · [同实例结构](docs/MIRROR_PROJECTION.md)。本次需升级 APK 和 RWVA 仪表 App，不能只装 APK；MCU/匹配参考 OS 不变。1.12.1候选因内部矩形模糊问题撤回。没有将模拟器结果当作目标车机或所有应用实车通过。

- 首页一键开始/停止；普通应用任务位于自有VirtualDisplay，同帧GL分发到编码器和可挂卸主屏镜像，不采集其他主屏应用。停止精确归还业务任务，不强停/清数据。
- 应用搜索、收藏、默认选择；普通单任务入口也走托管，不限制应用类别。原图标在投屏期间进入镜像；切走不主动停止仪表。原车定制导航按可配置表保留独立Meter入口，不接管其主屏图标。
- 顶部状态、中央地图、底部原生功率/车速/电量/续航；原生数据绑定不由 APK 重画。支持比例保持/中心裁剪、兼容小窗、日间/夜间主题。
- 前台服务持续拥有同一条会话，直至停止、实际断开或异常；不定时重连，通知栏可停止。
- 模式切换通过原厂导航文字中继；停止并确认清理、ALT0/接口释放后再切换，正常流程不要求手动拔线。重复 NCM 保留已有连接。
- 诊断页提供 ADB 连接、一键日志导出到下载目录；历史工程工具不再作为默认页面。
- 视频不使用激活码、token 或设备绑定；正常 ADB 调试和 USB 访问许可保留。

[1.7.2 清理恢复/配套升级](docs/PRODUCT_1.7.2.md) · [1.7.1 右下布局](docs/PRODUCT_1.7.1.md) · [构建](docs/BUILD.md) · [验证边界](docs/VALIDATION.md) · [架构](docs/ARCHITECTURE.md)

## 产品与安全边界

正常导航不再要求每次勾选停车验证；应用选择/设置应在不分心的安全场景完成，驾驶中只使用导航，不观看娱乐视频。软件没有可信车速/挡位输入，不假装知道车辆状态。

全屏和中心窗视频均在原生信息下，所属五秒滚动租约在失联、代次变化或停止时恢复，不永久改主题。旧版本实显成果继续承接，不能把旧照片当1.9.0实车验收。系统软键盘、应用自身禁止副屏/安全窗口、真实图标闪现、目标硬编和全部动态告警仍有兼容边界；**不是所有应用无条件可投或道路认证。**原厂深浅控制融合，地图昼夜仍由导航自己管理。告警始终优先。

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

本版需配套RWVA仪表App，组件生成器提供HMI/主KZB/3dscreen KZB/receiver；MCU、参考OS/MLO/IFS/Boot及USB角色不改。公开源码不含固件原件、APK、SDK、密钥、ADB身份或原始实车日志；配套另行分发。tools/build_sharp_inner_components.py可复现组件，输出不是可刷FULL，[使用细节](docs/PRODUCT_1.12.2.md)。

[使用](docs/USAGE.md) · [发布边界](docs/PUBLISHING.md) · [安全](SECURITY.md) · [依赖来源](THIRD_PARTY_NOTICES.md) · [许可证待选](LICENSE_STATUS.md) · [贡献](CONTRIBUTING.md)

本项目不是 BYD、Desay SV 或 QNX 官方项目，名称仅说明测试目标。
