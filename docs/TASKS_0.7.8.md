# 0.7.8 高清编码器 30 FPS 门控与剩余门禁

## 本版实现

- `OutputFrameRateEvidence`统计`MediaCodec`完整输出访问单元。
- 自然结束且请求时长至少3秒时，至少60帧、PTS/墙钟28.5～31.5 FPS、PTS间隔30,000～36,667微秒才允许门控通过。
- `SHELL_CAPTURE_FPS_GATE`输出编码端门控结果；手动停止只保留诊断，不把主动停止当作失败。
- 不改仪表OS、MCU、USB角色脚本和DCV1协议；必须与匹配0.7.8仪表App成对使用。

## 本轮离线证据

- JVM、assemble、lint、高清H.264/Java DCV1/ARM合同、OEM入口、完整包装和独立审计均需以0.7.8报告为准。
- 这些证据不代表Android真机硬编、QNX IVA-HD/Screen解码、NCM吞吐或仪表显示30 FPS。

## 现场顺序

1. 停稳或可恢复台架刷入匹配的0.7.8 APK和仪表包。
2. 运行至少3秒自然结束的短会话，确认`SHELL_CAPTURE_FORMAT`为1920×720，`SHELL_CAPTURE_FPS_GATE status=PASS`。
3. 确认动态画面、`FRAME_PACER`的`burst_intervals=0`和仪表清理/窗口退出。
4. 短时通过后再做600秒、1800秒、后台、异常退出、视频后Host恢复及普通U盘回归。

动态告警隔离和安全HMI仍未完成，禁止道路测试；日志/ACK只证明发送端或协议状态，不能代替实际显示观察。
