# 0.7.4 高清运行时 FPS 证据与剩余门禁

## 本版完成

- 保持硬件 AVC Baseline `1920×720 / 6 Mbps / 30 FPS` 请求和高清仪表接收适配。
- 保留0.7.3实际输出尺寸门控、0.7.2的Surface `33,333 µs` 上一帧重复 cadence、PTS选帧和DCV1固定时间轴重定时。
- 采集结束时记录编码PTS FPS、访问单元墙钟 FPS和平均PTS间隔，日志为 `SHELL_CAPTURE_COMPLETE`。
- APK为 `0.7.4/code21`，必须与匹配的高清仪表App成对使用；仪表 OS、MCU、USB角色脚本和接收器协议不改。

## 已通过的离线门禁

Android 280 JVM测试/40套、assemble、lint；真实Baseline H.264、Java DCV1、PTS门控、高清ARM解析、ACK/STOP清理、OEM DEX入口和0.7.4完整两文件成品审计均通过。

这些检查不等于 Android MediaCodec真机、QNX IVA-HD/Screen、USB NCM吞吐或仪表真实显示通过。

## 实车门禁

1. 停稳或可恢复台架安装匹配的0.7.4 APK和仪表App。
2. 短时确认 `SHELL_CAPTURE_FORMAT` 实际尺寸为1920×720。
3. 读取 `SHELL_CAPTURE_COMPLETE`：PTS FPS、墙钟 FPS接近30，平均PTS间隔接近33,333µs；再对照 `FRAME_RATE_GATE`。
4. 观察仪表动态画面并独立测量显示端帧率，不能用发送端统计或ACK代替。
5. 逐步回归20～30秒、600秒、1800秒、后台、自然结束、异常退出、重复使用和视频后Host/U盘恢复。

动态车速/挡位/告警/导航层级未完成隔离前禁止道路使用。
