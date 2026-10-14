# 0.8.0 远程副屏任务与剩余门禁

## 已实现

- 应用选择：`ACTION_MAIN + CATEGORY_LAUNCHER`列出可启动组件，默认目标仍为车机主屏。
- 远程显示：授权 ADB shell uid=2000 使用 Android 10 framework 的 `DisplayManagerGlobal.createVirtualDisplay` 创建 1920×720 public/presentation 显示。
- 跨屏启动：使用固定 allowlist 组件调用 `/system/bin/am start --display <id> --user current -n <package/activity>`；不拼接任意用户 shell 文本。
- 视频复用：虚拟显示输出直接连接现有 Surface AVC 1920×720/30，后续仍使用既有 H.264、DCV1、NCM/TCP 和 QNX 接收端。
- 独立探针：不连接仪表即可验证虚拟显示创建、应用启动、H.264 实际尺寸、SPS/PPS/IDR和完整退出证据。

## 必须现场完成

1. 车机能否由 uid=2000 创建 virtual display。
2. 车机 ROM 是否允许 shell 在该 display 上启动普通 launcher activity。
3. 至少一个地图/普通应用是否实际在虚拟显示上产生可编码画面。
4. 远程副屏探针通过后，NCM/DCV1/QNX 实际解码与仪表窗口显示。
5. 同一应用持续投屏、停止清理、后台/导航切换、600/1800秒和视频后Host恢复。
6. KZB对应动态告警/车速/挡位区域无遮挡；未完成前只允许停稳/台架，不得道路使用。

## 失败处理

- Context、VirtualDisplay或`am`失败：保存APK日志，换普通可启动应用复测；不改仪表固件，不连续重试。
- 车机探针通过但仪表无画面：按既有NCM、TCP、DCV1、QNX media/Screen分层诊断，不能把车机门控写成仪表通过。
- 任何停止/清理未确认或安全信息被遮挡：停止本轮，正常恢复，不强杀共享服务、不自动切Host。

0.8.0只改 APK。配套交付中的仪表完整包是原0.7.9高清包的字节不变副本，用于明确验证基线，不代表仪表端产生新改动。
