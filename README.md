# Dashcast

BYD Android 车机 → QNX 仪表的阶段一 USB 投屏验证工程，当前源码基线 **0.4.4**。

此项目用于可恢复台架或车辆停稳时的验证，不是官方固件、通用车型适配包或行驶中使用的生产产品。仪表 MCU 不修改，视频不使用激活码/token/设备绑定；正常 ADB 和 USB 访问授权仍然必需。

## 当前成果

- APK 经已授权的本机 ADB 和原厂导航文字中继，请求仪表 Host/NCM 模式。
- 车机画面经 shell SurfaceControl 镜像、硬件 H.264 Baseline 编码，通过用户态 USB NCM/TCP/DCV1 发送。
- 复用仪表已有媒体/窗口接口，验证中央 640×240 小窗口；原仪表 HMI 保留。
- 0.4.4 修复操作锁残留、跨会话 TCP 源端口复用和重复 NCM 重连。
- 实车已有首次动态画面与新版9次重复连接记录；**传输通过、画面观察和Host实际恢复是不同验收项**。

详细边界见 [验证状态](docs/VALIDATION.md)，不要把历史“待验证”或当前一次通过推广到所有车型。

## 仓库结构

```text
phase1/android/              APK源码、Gradle Wrapper、当前协议测试
phase1/qnx/ref2-noauth/      QNX脚本、ARM适配源、测试替身
analysis/                   哈希固定的原生适配、ELF读取、SV容器工具
tools/                      公开内容检查、离线App包装、工具测试
docs/                       架构、构建、操作、验证和发布清单
.github/                    源码检查/Android构建CI、问题模板
SOURCE_MANIFEST.json        首次整理的逐文件哈希及机械变换清单
```

不包含原厂/参考固件、SDK、编译APK、原始实车日志、签名私钥、ADB身份或历史视频凭据。固件构建需要操作者自行提供匹配且有权使用的输入，**克隆仓库不等于可以直接刷仪表**。

## 构建与使用

APK需要JDK21、Android SDK35、build-tools35.0.1；Java语言目标11，目标车机Android10/API29。依赖版本保持0.4.4基线，没有借整理升级运行逻辑。

```bash
cd phase1/android
bash gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Windows使用`gradlew.bat`。首次需要网络下载依赖；`--offline`只适合已准备好缓存的环境。完整说明：[构建](docs/BUILD.md)、[停稳验证顺序](docs/USAGE.md)。

**新环境产生的debug签名可能不同，不能保证覆盖安装原实测APK。不要为覆盖安装而公开旧签名密钥，也不要盲目卸载清数据丢失ADB身份。**

## 文档入口

- [架构及端口说明](docs/ARCHITECTURE.md)
- [0.4.4改动与历史摘要](CHANGELOG.md)
- [验证状态与剩余现场确认](docs/VALIDATION.md)
- [上传前检查与Release边界](docs/PUBLISHING.md)
- [贡献说明](CONTRIBUTING.md)、[安全说明](SECURITY.md)
- [源码来源/依赖](THIRD_PARTY_NOTICES.md)、[许可证待选状态](LICENSE_STATUS.md)

本项目不是BYD、Desay SV或QNX官方项目，名称仅用于说明测试目标。
