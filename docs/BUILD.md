# 构建、打包与产出物

仓库里有两部分可以构建：车机 APK，以及仪表 App 里需要修改的文件（下称“仪表组件”）。APK 只依赖本仓库源码。仪表组件要用你自己持有的固件原件作输入，仓库不包含任何固件。

当前版本：APK **1.20.2 / code72**，配套仪表 **1.19.3**。

## 一、环境配置

| 用途 | 需要 | 说明 |
|---|---|---|
| APK | JDK 21 | CI 用 Temurin 21。`JAVA_HOME` 指向它。 |
| APK | Android SDK：`platforms;android-35`、`build-tools;35.0.1` | 用 `ANDROID_HOME` 或 `android/local.properties` 里的 `sdk.dir` 指定 SDK 位置（`local.properties` 不要提交）。 |
| APK | Gradle 9.5.0 | 由 `gradlew` / `gradlew.bat` 自动下载，wrapper 已校验分发包 SHA256。AGP 9.3.0，Kotlin 用 AGP 内置的，不另加 Kotlin 插件。 |
| APK | 网络（仅首次） | 首次构建要下载依赖；缓存好之后可以加 `--offline`。 |
| 仪表组件 | Python 3.12 或更新版本 | 依赖见 `requirements-native.txt`：`pip install -r requirements-native.txt`。 |
| 便携控制器测试 | C 编译器（gcc 或 clang） | 只用于 `tools/test_native_background.py`，Windows 上可在 WSL 里运行。 |

目标车机为 Android 10（API 29）：`minSdk`/`targetSdk` 29，`compileSdk` 35，Java/Kotlin 目标 11。

## 二、编译 APK

```bash
cd android
bash gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Windows 用 `gradlew.bat`。三个任务分别是单元测试、构建 debug APK、lint，和 CI 一致。

产出：

| 文件 | 内容 |
|---|---|
| `android/app/build/outputs/apk/debug/app-debug.apk` | debug APK，包名 `com.byd.dashcast.phase1.debug`，版本 `1.20.2-debug` / code72 |
| `android/app/build/test-results/testDebugUnitTest/` | 单元测试结果（XML） |
| `android/app/build/reports/lint-results-debug.html` | lint 报告 |

**签名**：debug APK 用的是构建机上的 debug 密钥。只有签名与车上已装的 APK 相同，才能覆盖安装；签名不同时只能先卸载，应用数据和 ADB 身份会一并丢失。仓库不包含任何签名密钥。

## 三、构建仪表组件

### 需要准备的原件

| 参数 | 文件 | 来源 | SHA256 |
|---|---|---|---|
| `--hmi` | `rc_hmi.exe` | 基础包 app.sv 的 `base/app/rc_hmi/` | `5fe489555ac7e3cc3cc4f73cfd47fa3cca580d881e570d22e4ac93a6fad07b10` |
| `--kzb` | `bic_byd_stha.kzb` | 同上 | `4a1938ec7eb6714b8bc116c57ad44447cfb1ec7371931827b886e288c319dc3d` |
| `--toc` | `bic_byd_stha.kzb.txt` | 同上 | `402c609076bc539bd552652f00fd63440f59d28e8cc908ca494f249060ad106e` |
| `--scene` | `3dscreen.kzb` | 同上 | `18d817c89f07e7171a26e67ec4a1f567d76e8ae7b61c6c3383ee92882be8323c` |
| `--config` | `application.cfg` | 同上 | `f672ed14e92ee2345aaefc5c4192f5d37bb10baf368f9edcca08bc5c7c662538` |
| `--receiver` | `instrument-receiver-1.2.0` | 本仓库 [Release `receiver-1.2.0-2021-song-plus-dmi`](https://github.com/code-new-bie/byd-dashcast/releases/tag/receiver-1.2.0-2021-song-plus-dmi) 的附件（即 1.2.0 配套包 app.sv 里的 `base/app/dip-instrument/instrument-receiver`） | `8b490f2fd8b6b5b8f97f93e2f085122838e12c67670e60fd7770cb455ac63aa6` |

基础包指本项目所基于的宋 Plus DM-i 仪表升级包（使用原厂 MCU）。每一步都按哈希检查输入，原件不对会直接拒绝，不会产生输出。`--receiver` 不是原厂文件，是本项目 1.2.0 版产出的接收端。它和参考包原始接收端之间有一段早期修改步骤没有公开工具，所以单独作为 Release 附件提供，直接下载即可。

把原件从 app.sv 里取出来，可以用 `analysis/sv_container.py` 的 `unpack_sv`。

### 一条命令构建

```bash
python tools/build_instrument_components.py \
  --hmi ORIG/rc_hmi.exe --kzb ORIG/bic_byd_stha.kzb --toc ORIG/bic_byd_stha.kzb.txt \
  --scene ORIG/3dscreen.kzb --config ORIG/application.cfg \
  --receiver instrument-receiver-1.2.0 --output OUT
```

`OUT` 必须是新目录（它的上级目录必须已经存在）。工具在内存里依次做三步，再加上 application.cfg 和四个 QNX 脚本。每个产物都要和审核过的 1.19.3 文件逐字节相同，否则报错，一个文件也不写。

产出（按 app.sv 内的安装路径摆放）：

| 产出文件 | 来自 |
|---|---|
| `OUT/base/app/rc_hmi/rc_hmi.exe` | 原 HMI + 各版补丁 + B2 混合模式（共 3 步） |
| `OUT/base/app/rc_hmi/bic_byd_stha.kzb` | 原 KZB + 布局/背景 + B2 全屏遮罩 + 1.19.3 小窗遮罩 |
| `OUT/base/app/rc_hmi/3dscreen.kzb` | 原 3D 场景，投屏时隐藏道路底板和车模 |
| `OUT/base/app/rc_hmi/application.cfg` | 原文件末尾加一行 `SurfaceBitsAlpha = 8` |
| `OUT/base/app/dip-instrument/instrument-receiver` | 1.2.0 接收端 + 1.17 各项改动 |
| `OUT/base/app/dip-instrument/startup.ksh` 等四个脚本 | 原样复制 `instrument/ref2-noauth/` |
| `OUT/REPORT.json` | 每个产物的 SHA256 和每一步的检查报告 |

### 分步构建（排查用）

一条命令的结果和下面三步依次执行相同，三步的中间结果可以单独检查：

```bash
python tools/build_projection_fit_components.py --hmi … --kzb … --scene … --toc … --receiver … --output STEP1   # 1.17 基线四组件
python tools/build_b2_matte_components.py --hmi STEP1/rc_hmi.exe --kzb STEP1/bic_byd_stha.kzb --output STEP2       # 1.19.2：B2 混合模式和全屏遮罩
python tools/build_center_mask_components.py --hmi STEP2/rc_hmi.exe --kzb STEP2/bic_byd_stha.kzb --output STEP3    # 1.19.3：小窗遮罩
```

第 1 步还会输出 `3dscreen.kzb` 和 `receiver`，后两步不再改动它们。遮罩 PNG（`instrument/assets/`）的 RGB 是乘数，不是普通图片：不要再预乘，也不要用图片软件重新保存。HMI 和 KZB 必须成对使用，不能和其他版本混用。

## 四、打包成刷机包

仓库不提供生成刷机包的工具。完整仪表包由维护者用自己的原件组装，组成如下，供核对用。

刷机包是一个 ZIP，里面是 `release/` 目录下的六个文件，放到 U 盘或 SD 卡的 `release/` 后由仪表的升级程序读取：

| 文件 | 内容 |
|---|---|
| `release/app.sv` | 基础包的 app.sv，替换和新增下表中的成员，其余成员原样保留 |
| `release/os.sv` | 参考仪表包 2 的 os.sv，原样使用 |
| `release/mcu.sv` | 基础包的 mcu.sv（原厂 MCU），原样使用 |
| `release/RcAppVersion`、`kzbVersion`、`version_svp` | 基础包原样 |

app.sv 中的变化：

| 成员 | 变化 | 属主 / 权限 |
|---|---|---|
| `./base/app/rc_hmi/rc_hmi.exe` | 替换 | 1000:1000，0775 |
| `./base/app/rc_hmi/bic_byd_stha.kzb`、`3dscreen.kzb`、`application.cfg` | 替换 | 1000:1000，0664 |
| `base/app/dip-instrument/`（目录） | 新增 | 0:0，0700 |
| `base/app/dip-instrument/instrument-receiver`、四个 `.ksh` | 新增，来自上一节产出 | 0:0，0700 |
| `base/app/dip-instrument/instrument-usb` | 新增，USB 模式辅助程序 | 0:0，0700 |

注意两组成员的路径写法不同：基础包原有成员带 `./` 前缀，新增成员不带，重打包时要保持原样。`instrument-usb` 是参考包里同名程序的修改版（SHA256 `734cfe1796ee040a57237e21bbf0563514d81a6d8c4114cc8d958586183d0e4b`），从 0.4.4 起一直没变，修改工具不在本仓库，只能沿用已有配套包里的这个文件。

`.sv` 不是普通的 tar.gz：gzip 流后面还有 512 字节尾部，记录 gzip 流的 MD5 和解压后 tar 的长度，仪表升级时会校验。`analysis/sv_container.py` 的 `unpack_sv` / `pack_sv` 负责解包和重打包：保留每个成员的属主、权限、时间和顺序，并重新计算尾部。不要在 Windows 上解压后再压缩，那样会丢失属主和权限。

刷机前先备份原包，在停稳的车或台架上进行，并准备好能恢复的原包。

## 五、维护者交付的产出物

每个版本交付严格两个文件：

| 文件 | 说明 |
|---|---|
| `dashcast-<版本>-debug.apk` | 用维护者固定的 debug 密钥签名，可以覆盖安装 |
| `dashcast-2021-song-plus-dmi-instrument-<版本>-<标记>-FULL.zip` | 上一节所述的完整仪表包，例如 `…-1.19.3-RWVF-B2-CENTER-FULL.zip` |

只改 APK 时，仪表包沿用上一版，两个文件仍成对交付。两份文件都不上传到本仓库。

## 六、提交前检查

和 CI 相同：

```bash
python tools/check_public_tree.py --verify-snapshot
python -m unittest discover -s tools/tests -p 'test_*.py' -v
python tools/test_native_background.py --output build/native-background-ci
cd android && bash gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

`check_public_tree.py` 检查文件清单与 `SOURCE_MANIFEST.json` 是否一致，并拒绝固件、APK、密钥、日志、个人路径等内容。`test_native_background.py` 的输出目录必须是仓库 `build/` 下的新目录。
