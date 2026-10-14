# 0.7.9 G3 高清硬编运行时探针与剩余门禁

## 本轮已实现

- G3按钮改用APK自带的 `ShellScreenCapture`，5秒自然结束验证实际硬件 AVC、1920×720输出格式、H.264 SPS/PPS/IDR和完整退出。
- `OutputFrameRateEvidence` 每60个编码器输出图像做一次滚动检查，PTS/墙钟帧率必须为28.5～31.5 FPS，相邻PTS间隔必须为30,000～36,667微秒；自然结束时再次检查最终样本。
- 0.7.9/code26 与匹配的0.7.9仪表App成对使用；仪表OS、MCU、Boot、USB角色脚本和DCV1协议不改。

## 现场顺序

1. 车辆停稳或使用可恢复台架，刷入匹配的APK/FULL包并确认旧会话已清理。
2. 授权本APK的车机本机ADB，点击“G3 高清硬编门控（ADB / 1920×720 / 30 FPS）”。
3. 先确认 `G3_RUNTIME_HARDWARE_AVC_PASS`，再切NCM、接USB和允许系统USB访问。
4. 做短时真实投屏，检查 `SHELL_CAPTURE_FORMAT`、`SHELL_CAPTURE_FPS_GATE`、`FRAME_RATE_GATE`、`FRAME_PACER`、仪表动态画面和窗口退出。
5. 短时通过后再做600秒、1800秒、后台切换、重复使用、异常退出和视频后Host恢复。

## 未关闭门禁

- Android车机实机硬编、QNX IVA-HD实际解码和Screen实际显示仍需现场证据。
- NCM吞吐、真实仪表显示FPS、Glass-to-Glass延迟及动态告警隔离仍未完成。
- G3通过、ACK、visible属性或发送端30 FPS日志都不能单独证明仪表画面达到30 FPS。
