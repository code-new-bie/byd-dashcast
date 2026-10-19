# 当前架构（0.9.0 F1只读显示检查候选）

F1在既有有序DCV1流增加固定type9读取资源页；单独诊断不创建视频窗口，视频诊断每FRAME ACK后最多读取一页。它不改窗口/管线/背景，不创建第二个窗口管理器。普通投屏默认不发送扩展，旧HELLO1/2及FRAME ACK/STOP保持。没有激活/设备绑定。实际EGL alpha和物理混合仍不能由只读文本证明，HMI受控接入尚未实现，全屏未开放。

0.8.2分别核对SPS编码尺寸1920×720、mm-renderer输入URI的video.width/height=1920/720、push配置1920×720，以及Screen窗口640×240。shell helper将Java stdout重定向到stderr，DCS1/AVC仍直写原始FD1；消费者异常会触发所属stdin STOP和有限退出排空，不发送更多视频，不强杀共享服务。收到完整EXIT及释放标记才确认采集清理，原始失败不会被改成成功。

本项目用于受控台架或停车验证。控制面和视频面分离：控制面通过设备已有的授权通道请求 USB 角色变化；视频面通过本机 ADB 获取屏幕、硬件编码，再经 USB 网络链路发送到仪表侧接收器。

```text
控制：APK → 已授权的车机服务 → 仪表 USB 角色
视频主屏：授权 ADB SurfaceControl 镜像 → 硬件 H.264 → USB 网络 → DCV1 接收器 → 仪表媒体/显示
视频远程副屏：授权 ADB shell → Android VirtualDisplay → 硬件 H.264 → USB 网络 → DCV1 接收器 → 仪表媒体/显示
```

远程副屏只在 Android 车机侧创建。它不是把 QNX 仪表注册成 Android `Display`：APK选择一个可启动组件后，shell侧创建1920×720 public/presentation `VirtualDisplay`，把编码器输入Surface作为输出，并调用`am start --display`；主屏SurfaceControl镜像仍作为默认回退路径。VirtualDisplay权限、跨屏启动、具体应用兼容性和QNX实际显示必须单独现场验证。

## 控制边界

APK只调用目标设备已有的、经过授权的系统服务，不注入原始总线帧，不修改 MCU，不关闭 ADB 认证，也不自动在后台切换 USB 角色。模式切换的“请求已提交”和“目标设备已完成切换”是两个不同结果；后者必须由设备枚举或实际 Host 设备验证。

## 视频与时间轴

0.8.1请求硬件AVC Baseline、1920×720和30FPS，校验实际编码尺寸。START为窗口x/y/宽/高/FPS/flags，固定(640,240,640,240,30,0)；SPS尺寸独立验证1920×720。远程context创建前准备Looper，虚拟屏使用PUBLIC/PRESENTATION/OWN_CONTENT_ONLY。编码前Surface输入限速，编码后保留全部访问单元；FrameRateGate重定时，FramePacer按单调时钟排程写入，同步ACK背压和迟到重锚定保持。OutputFrameRateEvidence只报告实际偏差，不中断有效视频，真实30FPS仍需测量。完整非零EXIT与命令失败分开，资源释放标记或EXIT缺失仍隔离。

固定 PTS 只证明发送端时间轴，不证明远端解码或显示帧率。真实硬件编码、USB吞吐、远端显示和安全层级必须单独现场验证。

## 会话与清理

连续模式由前台 Service 持有，使用有界采集缓冲、逐帧 ACK 和有限时长。正常结束、用户停止、USB断开和错误清理分别记录状态；如果清理未确认，后续会话保持隔离，避免重复发送模式命令或留下共享资源残留。

## 公开范围

公开仓库只包含可复现的 Android/QNX 接口源码、测试和通用说明，不包含车辆标识、固件原件、固件哈希、设备密钥、原始日志或照片。使用者必须自行提供有权使用的设备和匹配输入，克隆仓库不等于可以直接刷写设备。
