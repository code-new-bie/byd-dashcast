# Dashcast 仪表导航

BYD Android 车机 → QNX 仪表的 USB 应用投屏。当前源码 **1.8.0 / code47 / RWV5**：修单包批量发送时HMI维护饥饿、外填充延展文字；全幅左右原生渐变从80加宽至176px，源与有效内框同步1536×384，中心720×384及紧凑里程保持，传输仍1920×720。真正无独立入口的单任务应用仍不支持，不回退主屏镜像或改用户数据。

[1.8.0 根因、配套升级、应用边界与使用](docs/PRODUCT_1.8.0.md)。必须配套APK与新仪表App，保留原MCU/参考OS；本轮没有新日志，不将代码反例称为唯一现场回溯或全部应用通过。

- 首页一键开始/停止；应用专用VirtualDisplay直接输出，不再采用同任务搬屏或主屏镜像兜底。停止只回收可证明归属的副屏任务，保留原应用任务/账号。
- 应用搜索、收藏、默认选择；已分析版本的原车导航用独立Meter入口，其他应用须支持多实例，能力不足明确拒绝。不是任意应用均支持双界面，也不假称同账号就保证业务状态同步。
- 顶部状态、中央地图、底部原生功率/车速/电量/续航；原生数据绑定不由 APK 重画。支持比例保持/中心裁剪、兼容小窗、日间/夜间主题。
- 前台服务持续拥有同一条会话，直至停止、实际断开或异常；不定时重连，通知栏可停止。
- 模式切换通过原厂导航文字中继；停止并确认清理、ALT0/接口释放后再切换，正常流程不要求手动拔线。重复 NCM 保留已有连接。
- 诊断页提供 ADB 连接、一键日志导出到下载目录；历史工程工具不再作为默认页面。
- 视频不使用激活码、token 或设备绑定；正常 ADB 调试和 USB 访问许可保留。

[1.7.2 清理恢复/配套升级](docs/PRODUCT_1.7.2.md) · [1.7.1 右下布局](docs/PRODUCT_1.7.1.md) · [构建](docs/BUILD.md) · [验证边界](docs/VALIDATION.md) · [架构](docs/ARCHITECTURE.md)

## 产品与安全边界

正常导航不再要求每次勾选停车验证；应用选择/设置应在不分心的安全场景完成，驾驶中只使用导航，不观看娱乐视频。软件没有可信车速/挡位输入，不假装知道车辆状态。

全屏和中心窗视频均在原生信息下，所属五秒滚动租约在失联、代次变化或停止时恢复，不永久改主题。1.4.0已有四种静态实显、1.7.3用户反馈地图和里程可见；不能把旧照片当新布局验收。**1.8.0原厂Kanzi/目标GPU、稳定帧率和全部动态告警尚未本版实车。**原厂深浅控制融合，地图昼夜仍由导航自己管理。真实接管/车门/故障警告保持优先，不保证所有警告出现时地图仍完全无遮挡。

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

本版必须配套更新APK和RWV5仪表App，不能混用旧几何。仪表仅HMI/KZB/receiver三成员变化；MCU、参考OS/MLO/IFS/Boot、USB角色及其余50 App成员保持原字节。公开源码不含固件原件、APK、SDK、密钥、ADB身份或原始实车日志；配套另行分发。tools/build_soft_edge_components.py可复现RWV5三个组件，输出不是可刷FULL，[详见构建](docs/BUILD.md)。

[使用](docs/USAGE.md) · [发布边界](docs/PUBLISHING.md) · [安全](SECURITY.md) · [依赖来源](THIRD_PARTY_NOTICES.md) · [许可证待选](LICENSE_STATUS.md) · [贡献](CONTRIBUTING.md)

本项目不是 BYD、Desay SV 或 QNX 官方项目，名称仅说明测试目标。
