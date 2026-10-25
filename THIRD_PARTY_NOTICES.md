# 源码来源与依赖

- NCM六个原协议类Usb、UsbDesc、Ncm、Net、TcpConn、Wire来自项目所有者提供的NCM demo；所有者2026-10-13明确确认本人编写。项目调整命名空间并补协商/有界收发/清理/端口租约等，不公开来源工作区路径。
- 其余项目源码、适配脚本与测试由此工程维护。QNX工具是自写的输入哈希固定适配与容器处理，不包含原厂/参考固件或完整参考ELF源。
- Gradle Wrapper9.5.0来自Gradle，已有文件版权/SPDX头保留；[Apache-2.0文本](third_party/gradle/LICENSE)单独随附。Wrapper及分发校验和来自官方services.gradle.org。
- 构建/测试依赖：AGP9.3.0、JUnit4.13.2、org.json:20260814；原生适配依赖Keystone0.9.2、Capstone5.0.9、pyelftools0.33。它们通过各自包仓库获取，其许可由各自项目管理，项目所有者的LICENSE不替代依赖许可。
- Android SDK/JDK/QNX/车机和仪表软件不随仓库分发。厂商名称仅为兼容性目标说明，不构成官方背书。
- RWV6本地适配工具使用Pillow12.3.0（包元数据许可MIT-CMU）生成代码定义的轮廓alpha；不随APK/仓库捆绑Pillow二进制。依赖自身版权与许可仍适用。

选择整体项目许可证前需保留第三方既有声明，并独立核对未来二进制/固件发布范围。当前选择状态见[LICENSE_STATUS](LICENSE_STATUS.md)。
