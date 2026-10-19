# 构建与复现

## Android源码

需要 JDK 21、Android SDK 35、Android Gradle Plugin 9.3.0 和仓库声明的 Gradle Wrapper。目标运行环境为 Android 10/API 29，Java语言级别为11。

```bash
cd phase1/android
bash gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Windows 使用 `gradlew.bat`。APK位于 `app/build/outputs/apk/debug/app-debug.apk`，测试和 lint 报告位于 `app/build`。首次构建需要按操作者环境准备依赖；`--offline`只适用于缓存已经就绪的机器。

当前源码0.9.0/code30；F1需匹配0.9.0仪表App，窗口仍640×240，视频输入1920×720。原stdout隔离/有限退出/USB路线保留。私有321项/46套、公开副本独立289项/43套及assemble/lint通过，32项历史凭据测试不进公开源。构建成功不代表0.9.0目标机显示检查、全屏合成或实际30FPS通过。

## F1接收器纯覆盖层

`analysis/f1_display_probe_native.py`公开纯`apply_probe_overlay`入口，输入必须是其固定哈希对应的0.8.2接收器ELF；原件由操作者自行提供。安装已审核`requirements-native.txt`后，可运行`python tools/apply_f1_display_overlay.py --input <已有匹配ELF> --output <新ELF>`。输入不匹配或输出已存在会拒绝。它只生成接收器及审计JSON，不制作升级包、不更改OS/MCU/HMI、不连接设备。旧App包装器仍只支持历史0.5.0，不宣称它能直接制作0.9.0配套固件。

## 测试范围

单元测试覆盖协议封装、PTS门控、采集边界、USB会话状态和清理路径。公开CI只构建源码和运行离线检查，不连接车辆、不制作固件、不上传APK，也不使用设备密钥。

## 签名和部署

新环境产生的 debug 签名可能不同，不能保证覆盖安装已有设备版本。不要把签名私钥或 ADB 身份放入仓库；部署前应由操作者确认签名、版本匹配和可恢复回退路径。
