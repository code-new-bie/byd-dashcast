# 0.7.2 高清 Surface cadence 修正版与剩余门禁

## 本版完成

- 保持硬件 AVC Baseline `1920×720 / 6 Mbps / 30 FPS` 请求和高清仪表接收适配。
- `FrameRateGate` 按编码器 PTS 选帧，将接受帧重定时为固定 `33,333 µs` 传输间隔。
- 将Surface输入编码器无新缓冲时的上一帧重复 cadence 对齐为 `33,333 µs`。它只维持静态画面节奏，不生成运动内容。
- 保留同步 ACK、USB NCM/TCP/DCV1、STOP清理和Host恢复边界；APK为 `0.7.2/code19`，必须与匹配的高清仪表App成对使用。

## 已通过的离线门禁

Android 280 JVM测试/40套、assemble、lint；真实Baseline H.264、Java DCV1、PTS门控、高清ARM解析、ACK/STOP清理、OEM DEX入口和0.7.2完整两文件成品审计均通过。

这些检查不等于 Android MediaCodec真机、QNX IVA-HD/Screen、USB NCM吞吐或仪表真实显示通过。

## 实车门禁

1. 停稳或可恢复台架安装匹配的0.7.2 APK和仪表App。
2. 短时确认硬件编码器实际输出1920×720，并观察仪表有连续动态画面；静态画面重复 cadence 不能代替动态画面测试。
3. 读取 `FRAME_RATE_GATE`：输入/输出/丢帧、输入PTS FPS、输出PTS FPS、固定间隔和Surface cadence配置；另外测量仪表实际显示帧率，不能只用ACK代替。
4. 逐步回归20～30秒、600秒、1800秒、后台、自然结束、异常退出、重复使用和视频后Host/U盘恢复。
5. 动态车速/挡位/告警/导航层级未完成隔离前禁止道路使用。
