# 0.7.3 高清输出格式门控与剩余门禁

## 本版完成

- 保持硬件 AVC Baseline `1920×720 / 6 Mbps / 30 FPS` 请求和高清仪表接收适配。
- 保留0.7.2的Surface无新缓冲时 `33,333 µs` 上一帧重复 cadence、PTS选帧和DCV1固定时间轴重定时。
- 连续采集收到实际 `MediaCodec` 输出格式时，硬性校验 `KEY_WIDTH`/`KEY_HEIGHT` 为 `1920×720`；缺失或不匹配会在转发访问单元前失败。
- APK为 `0.7.3/code20`，必须与匹配的高清仪表App成对使用；仪表 OS、MCU、USB角色脚本和接收器协议不改。

## 已通过的离线门禁

Android 280 JVM测试/40套、assemble、lint；真实Baseline H.264、Java DCV1、PTS门控、高清ARM解析、ACK/STOP清理、OEM DEX入口和0.7.3完整两文件成品审计均通过。

这些检查不等于 Android MediaCodec真机、QNX IVA-HD/Screen、USB NCM吞吐或仪表真实显示通过。

## 实车门禁

1. 停稳或可恢复台架安装匹配的0.7.3 APK和仪表App。
2. 短时确认日志出现 `SHELL_CAPTURE_FORMAT` 且实际尺寸为1920×720，并观察仪表有连续动态画面。
3. 读取 `FRAME_RATE_GATE`：输入/输出/丢帧、PTS FPS和固定间隔；另外测量仪表实际显示帧率，不能只用ACK代替。
4. 逐步回归20～30秒、600秒、1800秒、后台、自然结束、异常退出、重复使用和视频后Host/U盘恢复。
5. 动态车速/挡位/告警/导航层级未完成隔离前禁止道路使用。
