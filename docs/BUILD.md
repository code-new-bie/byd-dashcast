# 构建与复现

## Android源码

需要 JDK 21、Android SDK 35、Android Gradle Plugin 9.3.0 和仓库声明的 Gradle Wrapper。目标运行环境为 Android 10/API 29，Java语言级别为11。

```bash
cd phase1/android
bash gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Windows 使用 `gradlew.bat`。APK位于 `app/build/outputs/apk/debug/app-debug.apk`，测试和 lint 报告位于 `app/build`。首次构建需要按操作者环境准备依赖；`--offline`只适用于缓存已经就绪的机器。

当前源码版本为 0.7.8/code25：持续视频目标是1920×720/30 FPS，发送端采用PTS选帧、固定时间轴重定时，将Surface无新缓冲时的上一帧重复 cadence 对齐为33,333微秒，在转发前硬性校验实际输出尺寸；自然结束样本还必须通过60帧编码器PTS/墙钟30FPS门控。每个同步DCV1写入前按单调时钟排程33,333,333纳秒；迟到后从当前实际发送起点重新锚定下一 slot，避免慢ACK追赶突发，同时记录编码间隔、传输迟到、最小/最大间隔和突发次数。构建成功不等于设备硬件编码、远端解码或实际显示成功。

## 测试范围

单元测试覆盖协议封装、PTS门控、采集边界、USB会话状态和清理路径。公开CI只构建源码和运行离线检查，不连接车辆、不制作固件、不上传APK，也不使用设备密钥。

## 签名和部署

新环境产生的 debug 签名可能不同，不能保证覆盖安装已有设备版本。不要把签名私钥或 ADB 身份放入仓库；部署前应由操作者确认签名、版本匹配和可恢复回退路径。
