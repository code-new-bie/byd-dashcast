# 仪表端源码

| 路径 | 内容 |
|---|---|
| `ref2-noauth/startup.ksh`、`run-cast.ksh`、`cast-mode.ksh`、`usb-mode.ksh` | 安装到仪表 `base/app/dip-instrument/` 的四个脚本，与当前刷机包中的文件逐字节相同 |
| `ref2-noauth/receiver-*.arm` | 接收端的 ARM 补丁汇编源，被 `analysis/` 中的模块读取 |
| `ref2-noauth/tests/` | 测试脚本时使用的替身程序 |
| `native-hmi/*.thumb` | HMI 的 Thumb 补丁汇编源；`background-b2.thumb` 是当前的混合模式补丁 |
| `native-hmi/projection_background_controller.{c,h}`、`native-hmi/tests/` | 便携背景控制器及其测试，只依赖 C99，用于在主机上验证逻辑，不是可刷的 HMI 部件 |
| `assets/b2/` | 1.19.2 的两张遮罩乘数图（全屏用 `DXFullDark.png`） |
| `assets/b3-center/` | 1.19.3 的小窗遮罩乘数图 `DXCenterDark.png`（RGB 等于 Alpha） |

遮罩图的 RGB 是混合时的乘数，不是普通图片：不要预乘，也不要用图片软件重新保存，否则重建结果会和审核过的文件不一致。

这些源码和图片如何组合成仪表组件，见[构建文档](../docs/BUILD.md)；整体结构见[架构](../docs/ARCHITECTURE.md)。

便携控制器测试：

```bash
python tools/test_native_background.py --output build/native-background-test
```

输出目录必须是仓库 `build/` 下的新目录，需要 C 编译器。测试用模拟的渲染接口，不需要 QNX 或 Kanzi SDK。
