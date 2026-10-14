# 0.7.7 高清传输节奏可观测性与剩余门禁

## 本版完成

- 保留0.7.6的1920×720实际输出尺寸门控、PTS选帧、固定时间轴、Surface静态 cadence和迟到重锚定。
- `FRAME_PACER`新增`min_transport_interval_us`、`max_transport_interval_us`和`burst_intervals`，直接暴露实际同步写入间隔及低于目标间隔的次数。
- 发送之间检测到单调时钟倒退立即失败，并有JVM回归测试覆盖；APK升为0.7.7/code24。
- 仪表 OS、MCU、启动镜像、USB角色脚本、NCM和接收器协议不改；仪表配套包沿用已审计高清接收端二进制重新封装。

## 离线门禁

必须通过Android单元测试、assemble、lint、真实Baseline H.264/Java DCV1/高清ARM解析、成品包装和独立审计。`burst_intervals=0`的单元测试只证明节奏器逻辑，不证明设备端实际显示帧率。

## 现场门禁

1. 停稳或可恢复台架刷入匹配的0.7.7 APK和仪表包。
2. 短时确认`SHELL_CAPTURE_FORMAT`为1920×720，`SHELL_CAPTURE_COMPLETE`接近30 FPS。
3. 检查`FRAME_PACER`的最小/最大间隔和`burst_intervals=0`；若有迟到，确认后续发送没有追赶突发。
4. 观察仪表动态画面并独立测量显示FPS；不能用ACK或车机日志替代。
5. 短测通过后再做重复投屏、停止清理、视频后Host、600秒、1800秒和异常回归。

动态告警隔离仍未完成，只允许停车或可恢复台架验证。
