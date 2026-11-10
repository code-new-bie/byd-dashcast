# 仪表端源码

| 路径 | 内容 |
|---|---|
| `native/` | 仪表上运行的两个程序，C 编写：`dashcast_usb.c`（开机准备、USB 主机/NCM 设备切换、提示文字、接收端的父进程）和 `dashcast_receiver.c`（接收 DCV1 视频并交给系统解码显示）；`build.sh` 用 clang/lld 交叉编译，`notice_glyphs.py` 从固件字体生成提示文字点阵，`tests/` 是测试替身 |
| `native-hmi/*.thumb` | HMI 的 Thumb 补丁汇编源，被 `analysis/` 中的模块读取；`background-b2.thumb` 是当前的混合模式补丁 |
| `native-hmi/projection_background_controller.{c,h}`、`native-hmi/tests/` | 便携背景控制器及其测试，只依赖 C99，用于在主机上验证逻辑，不是可刷的 HMI 部件 |
| `assets/b2/` | B2 混合模式的两张遮罩乘数图：全屏 `DXFullDark.png`、小窗 `DXCenterDark.png` |
| `assets/b3-center/` | 小窗遮罩乘数图 `DXCenterDark.png`（RGB 等于 Alpha） |
| `assets/rwvg/` | 当前刷机包用的两张淡入遮罩（直通乘数），由 `analysis/fade_in_masks.py` 从前两组生成 |

遮罩图的 RGB 是混合时的乘数，不是普通图片：不要预乘，也不要用图片软件重新保存，否则重建结果会和审核过的文件不一致。

所有改动都在基础固件包上构建，HMI/KZB 补丁的输入按哈希固定为基础包里的原厂文件。如何组合成刷机包见[构建文档](../docs/BUILD.md)；整体结构见[架构](../docs/ARCHITECTURE.md)。

便携控制器测试：

```bash
python tools/test_native_background.py --output build/native-background-test
```

输出目录必须是仓库 `build/` 下的新目录，需要 C 编译器。测试用模拟的渲染接口，不需要 QNX 或 Kanzi SDK。
