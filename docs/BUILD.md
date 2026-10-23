# 构建与复现

## 1.4.0 RWV3配套组件（当前）

APK code40，Android构建命令/原工具链不变。公开独立构建使用自己的签名，不复制维护者签名或ADB身份。需要同时匹配仪表App；MCU/参考OS/Boot/角色保持。

组件复现需要操作者自行提供：精确原始HMI/KZB/TOC与1.2.0 receiver（SHA8b490f2fd8b6b5b8f97f93e2f085122838e12c67670e60fd7770cb455ac63aa6）。`python tools/build_viewport_components.py --hmi ORIGINAL_HMI --kzb ORIGINAL_KZB --toc ORIGINAL_TOC --receiver RECEIVER_120 --output NEW_DIRECTORY`。仅生成三个组件，不是FULL升级包；输入hash/路径/已有输出不匹配时拒绝，不在现有修改成品上重复套补丁。ColorBrush叶已有初始刷，原state2直接切深浅；没有新增仪表GL shader。历史CLI保留用于复现旧版。下文各“当前”仅指历史版本。

## 1.3.0 APK任务交接（当前）

APK code39，JDK21/AGP9.3/内置Kotlin工具链与命令不变。本版没有新仪表组件，继续使用下节1.2.0的HMI/KZB/receiver；不要重复对已修改组件打补丁或为了版本号重打MCU/OS。公开源码独立构建使用本机签名，不等于维护者原签名覆盖包。[升级和边界](PRODUCT_1.3.0.md)。

## 1.2.0 原厂主题与中心窗（当前）

APK code38，构建工具链及签名规则不变。自行提供精确原始HMI/KZB/TOC及1.1.2接收器（SHA256 `7c5e36bfaedafb963dba9ff42619c50254fb0c4a45da378402a560dc4bad08fe`），安装现有requirements-native.txt依赖，输出父目录须存在：

```bash
python3 tools/build_display_fusion_components.py --hmi /private/original-rc_hmi.exe --kzb /private/original-bic_byd_stha.kzb --toc /private/original-bic_byd_stha.kzb.txt --receiver /private/receiver-1.1.2 --output build/display-fusion
```

输出HMI/KZB/receiver三组件和审计JSON，不是可刷升级包；无私有固件路径依赖，已有输出/输入不符拒绝。正常升级仍需匹配APK＋App，cfg/USB角色/原MCU/参考OS/Boot保持。[本版范围](PRODUCT_1.2.0.md)。下面旧工具保留历史复现，不用它们打本版配套。

## 1.1.2 导航布局优化（当前）

APK code37，Gradle命令和原签名规则不变。HMI输出与1.1.1相同，仪表相对1.1.1仅KZB布局变化，receiver/角色/OS/MCU保持；公开工具不含原件也不生成升级ZIP。安装requirements-native.txt，父目录build须已存在，自备精确原始HMI、KZB及其TOC：

```bash
python3 tools/build_navigation_components.py --hmi /private/original-rc_hmi.exe --kzb /private/original-bic_byd_stha.kzb --toc /private/original-bic_byd_stha.kzb.txt --output build/navigation
```

输出两个组件及范围JSON，输入哈希/已有输出/符号链接不匹配拒绝。当前KZB使用navigation_layout_refine.py，HMI保持navigation_layout_fix.py与navigation-layout-local.thumb，不给已修改HMI重复打补丁。[1.1.2范围与操作](PRODUCT_1.1.2.md)。以下各“当前”保留为历史写作状态，不是当前打包入口。

## 1.0.1 持续会话与组件（历史）

APK code34，普通Gradle构建/测试/lint命令不变。新协议DCV1/3和RWN2需要匹配接收器/HMI，不允许只改UI或只更新APK。

自行提供精确1.0.0 receiver（SHA256 974ee40f19643370b58e0846ea211ce0da5b6f841b845c03a72b7912253997d7）和原始未打桥HMI，依赖沿用requirements-native.txt：

```bash
python3 tools/build_unlimited_components.py --receiver /private/receiver-100 --hmi /private/original-rc_hmi.exe --output build/unlimited
```

仅输出receiver/HMI和范围JSON，不是可刷FULL；输入精确哈希/已有输出不匹配即拒绝。1.0 USB角色脚本、cfg/启动桥/媒体库不改，原工具继续用于历史版本复现。[当前产品说明](PRODUCT_1.0.1.md)。以下保留历史构建。

## 1.0 Kotlin产品与组件（历史）

APK code33，AGP9.3内置Kotlin，Java/Kotlin目标11，无需另加kotlin-android插件；普通Gradle构建/测试/lint命令不变。原签名与ADB身份不公开，不保证新环境debug签名可覆盖旧APK。

自行提供精确0.10.0 receiver和未打桥的原始已检查HMI，安装requirements-native.txt后运行：

```bash
python3 tools/build_product_components.py --receiver /private/receiver-010 --hmi /private/original-rc_hmi.exe --output build/product
```

输出receiver/HMI/USB角色脚本和范围JSON，严格校验输入哈希和新输出边界，**不是可刷FULL**。应用cfg沿用0.10的alpha配置，原启动桥/媒体库保持；不得拿旧包装器生成产品包。[产品操作及限制](PRODUCT_1.0.md)。下面保留历史构建。

## 当前0.10.0合成组件

APK code32，Android构建步骤不变。自行提供精确0.9.1 receiver与原始application.cfg，安装requirements-native.txt依赖：

```bash
python3 tools/build_composition_components.py --receiver /private/instrument-receiver --config /private/application.cfg --output build/composition
```

输出receiver/cfg/审计JSON，仅组件，不是可刷FULL或QNX SDK编译。输入/已有输出不匹配拒绝；需原0.9.1 HMI/启动桥及匹配0.10.0 APK。旧包装器不能生成本版；不含固件、签名或原始日志。[操作与边界](TASKS_0.10.0.md)。以下是历史组件构建。

## 0.9.1限时背景组件

需要requirements-native.txt中的依赖及自行提供的精确0.8.2 HMI/receiver。新输出的父目录必须已存在：

```bash
python3 tools/build_background_poc_components.py --hmi /private/rc_hmi.exe --receiver /private/instrument-receiver --output build/background-poc
```

两份输入哈希都通过后才创建输出，产生HMI/receiver及范围报告；**不是升级ZIP构建器或QNX SDK编译**。新启动源为startup-background-poc.ksh，App内安装路径仍为startup.ksh。旧build_instrument_app仅支持0.5.0，不能生成本候选。APK为code31/0.9.1-debug，操作和未验证项见[TASKS](TASKS_0.9.1.md)。

## 原生背景控制器（便携源码，不是仪表插件）

新增源码位于`phase1/qnx/native-hmi/`，只依赖C99标准库。已有C编译器/Python 3的主机可运行：

```bash
python3 tools/test_native_background.py --output build/native-background
```

输出目录必须新建；默认启用地址/未定义行为检查。当前35项测试用假渲染端口，不依赖QNX/Kanzi SDK。**这不产生可刷HMI、不是QNX交叉编译，也不表示已在仪表内工作**；缺失的真实主线程/背景/显示桥接见[接口边界](../phase1/qnx/native-hmi/README.md)。没有因此提升APK版本或制作新配套包。

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
