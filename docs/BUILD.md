# 构建、打包与产出物

仓库里有车机 APK 和仪表端两部分源码。APK 只依赖本仓库源码；仪表端在一个**基础固件包**上构建：取出其中要改的原厂文件，打补丁、加入本项目的两个程序，再和基础包的其余文件一起打成刷机包。仓库不包含任何固件。

当前版本：APK **1.40.0 / code97**，配套仪表 **1.40.0/RWVI**。

## 一、基础固件包

基础包是维护者为 2021 款宋 Plus DM-i 整理的仪表升级包，文件名 `release_完美复刻四驱仪表_使用原厂mcu.zip`：app.sv 是整理过界面图片和字体的仪表应用，os.sv 是原厂系统，mcu.sv 是原车出厂的 MCU，原样未改。维护者把它存放在自己的私有仓库里，不随本仓库公开。它的六个文件按 SHA256 固定在 `tools/base_firmware.py` 里；ZIP 本身可以重新压缩，文件放在根目录或 `release/` 下都可以：

| 文件 | SHA256 |
|---|---|
| `app.sv` | `d4969ab876f27d3a40cd04658a447d46da636f98f153889a5b520c34207062f5` |
| `os.sv` | `845cba196c82b1565872cc3728fb75002777b5b09d9eedb89dfa3e87c5cfd832` |
| `mcu.sv` | `1ac756c66b26e16c2d1f4b2a93a6bd4ea0722fdfe7df3a0f51ab572c7179f337` |
| `RcAppVersion` | `5b604cdb6ce90ff0004bd81463e5028a2bfd2e0557dc2c015df4a3868a38de8a` |
| `kzbVersion` | `a103c8fa49730744fc004b031688e6ae346c08d7baff5d370073612b58e77646` |
| `version_svp` | `fc6eaabe209eab86893d43cfee167d7ca3a48211cca52805f9263ba1eeda3236` |

构建只读取其中七个原厂文件，每个也按哈希核对（见 `tools/base_firmware.py` 的 `INPUTS`）：app.sv 里的 `rc_hmi.exe`、`bic_byd_stha.kzb` 及其目录 `bic_byd_stha.kzb.txt`、`3dscreen.kzb`、`application.cfg`、`Resource_Font/msyhbd.ttf`，以及 os.sv 里的 `base/etc/slm-config-modules.xml`。文件不对时工具直接拒绝，不产生输出。

## 二、环境

| 用途 | 需要 | 说明 |
|---|---|---|
| APK | JDK 21 | CI 用 Temurin 21。`JAVA_HOME` 指向它。 |
| APK | Android SDK：`platforms;android-35`、`build-tools;35.0.1` | 用 `ANDROID_HOME` 或 `android/local.properties` 里的 `sdk.dir` 指定（`local.properties` 不要提交）。 |
| APK | Gradle 9.5.0 | 由 `gradlew` / `gradlew.bat` 自动下载，wrapper 已校验分发包 SHA256。AGP 9.3.0，Kotlin 用 AGP 内置的。 |
| 仪表程序 | clang + lld（带 ARM 目标），bash | `instrument/native/build.sh` 交叉编译 QNX 6.5 ARM 程序，不需要 QNX SDK。Windows 上在 WSL 里运行。 |
| 仪表补丁与打包 | Python 3.12 或更新 | 依赖见 `requirements-native.txt`：`pip install -r requirements-native.txt`（Keystone、Capstone、pyelftools、NumPy、Pillow）。两个离线指令检查另需 Unicorn。 |
| 便携控制器测试 | C 编译器 | 只用于 `tools/test_native_background.py`。 |

目标车机为 Android 10（API 29）：`minSdk`/`targetSdk` 29，`compileSdk` 35，Java/Kotlin 目标 11。

## 三、编译 APK

```bash
cd android
bash gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Windows 用 `gradlew.bat`。产出 `android/app/build/outputs/apk/debug/app-debug.apk`（包名 `com.byd.dashcast.phase1.debug`，版本 `1.40.0-debug` / code97），单测结果在 `android/app/build/test-results/`，lint 报告在 `android/app/build/reports/lint-results-debug.html`。

**签名**：debug APK 用构建机上的 debug 密钥签名。只有签名与车上已装的 APK 相同才能覆盖安装；不同时只能先卸载，应用数据和 ADB 身份会一并丢失。仓库不包含任何签名密钥。

## 四、从基础包构建仪表刷机包

`BASE` 是你的基础包 ZIP，`W` 是一个新的工作目录：

```bash
# 1. 取出构建要读的原厂文件（这里只用到字体）
python tools/base_firmware.py --base BASE --output W/originals

# 2. 用固件自带的粗体字生成仪表提示文字的点阵头文件（需要 Pillow）
python instrument/native/notice_glyphs.py W/originals/msyhbd.ttf W/notice_glyphs.h

# 3. 交叉编译仪表上的两个程序
bash instrument/native/build.sh CLANG_USR W/native W/notice_glyphs.h

# 4. 构建 app.sv / os.sv 里要换的文件
python tools/build_instrument_components.py --base BASE \
  --native-usb W/native/dashcast-usb --native-receiver W/native/dashcast-receiver --output W/components

# 5. 和基础包的其余文件一起打成刷机包
python tools/package_full.py --base BASE --components W/components \
  --output W/dashcast-2021-song-plus-dmi-instrument-1.40.0-RWVI-FULL.zip --report W/PACKAGE.json
```

`CLANG_USR` 是 clang/lld 的安装前缀（`$CLANG_USR/bin/clang`、`$CLANG_USR/bin/ld.lld`）。每个输出目录都必须是新目录。

第 4 步在内存里依次完成：布局与背景（`tools/build_projection_fit_components.py`）→ B2 混合模式和全屏遮罩 → 小窗遮罩 → RWVG 淡入 → RWVI 仪表几何 → 投屏告警位置（含全屏时隐藏驾驶辅助场景图），再给 `application.cfg` 加一行 `SurfaceBitsAlpha = 8`，给启动模块表加上本模块的启动项（`analysis/startup_entry.py`）。七个产物都要和审核过的文件逐字节相同（`EXPECTED`），否则一个文件也不写。产物按安装路径放在 `W/components/app/` 和 `W/components/os/`，`REPORT.json` 记录每一步的检查结果。

排查时前三步可以单独运行：`build_projection_fit_components.py --base BASE --output STEP1`，`build_b2_matte_components.py --hmi STEP1/rc_hmi.exe --kzb STEP1/bic_byd_stha.kzb --output STEP2`，`build_center_mask_components.py --hmi STEP2/rc_hmi.exe --kzb STEP2/bic_byd_stha.kzb --output STEP3`。主题几何和告警位置的指令级离线检查是 `tools/test_theme_geometry.py`、`tools/test_warning_perimeter.py`（需要 Unicorn，`--toc` 用第 1 步取出的 `bic_byd_stha.kzb.txt`）。

遮罩 PNG（`instrument/assets/`）的 RGB 是混合时的乘数，不是普通图片：不要预乘，也不要用图片软件重新保存。HMI 和 KZB 必须成对使用。

## 五、刷机包组成

刷机包是一个 ZIP，里面是 `release/` 下的六个文件，放到 U 盘的 `release/` 后由仪表升级程序读取：

| 文件 | 内容 |
|---|---|
| `release/app.sv` | 基础包 app.sv，替换、新增下表成员，其余成员的内容、属主、权限、时间和顺序不变 |
| `release/os.sv` | 基础包 os.sv，只替换 `base/etc/slm-config-modules.xml`；tar 里其余字节与基础包完全相同 |
| `release/mcu.sv` | 基础包原样（原厂 MCU） |
| `release/RcAppVersion`、`kzbVersion`、`version_svp` | 基础包原样 |

| 成员 | 变化 | 属主 / 权限 |
|---|---|---|
| `./base/app/rc_hmi/rc_hmi.exe` | 替换 | 1000:1000，0775 |
| `./base/app/rc_hmi/bic_byd_stha.kzb`、`3dscreen.kzb`、`application.cfg` | 替换 | 1000:1000，0664 |
| `base/app/dashcast/`（目录）及 `dashcast-receiver`、`dashcast-usb` | 新增在最后 | 0:0，0700 |
| os.sv 的 `base/etc/slm-config-modules.xml` | 末尾加一个启动项：开机运行 `/base/app/dashcast/dashcast-usb --boot` | 不变 |

基础包原有成员带 `./` 前缀，新增成员不带，重打包时保持原样。ZIP 不压缩，文件名、顺序和时间固定，所以同样的输入总是得到同样的字节。

`.sv` 不是普通的 tar.gz：gzip 流后面还有 512 字节尾部，记录 gzip 流的 MD5 和解压后 tar 的长度，仪表升级时会校验。`analysis/sv_container.py` 负责解包和重打包并重新计算尾部；不要在 Windows 上解压后再压缩，那样会丢失属主和权限。`tools/package_full.py` 写出前会重新读取成品，逐个成员和基础包、组件核对。

刷机前先备份原包，在停稳的车或台架上进行，并准备好能恢复的原包。

## 六、维护者交付的产出物

每个版本交付严格两个文件，都不上传到本仓库：

| 文件 | 说明 |
|---|---|
| `dashcast-<版本>-debug.apk` | 用维护者固定的 debug 密钥签名，可以覆盖安装 |
| `dashcast-2021-song-plus-dmi-instrument-<版本>-<标记>-FULL.zip` | 上一节所述的刷机包，例如 `…-1.40.0-RWVI-FULL.zip` |

## 七、提交前检查

和 CI 相同：

```bash
python tools/check_public_tree.py --verify-snapshot
python -m unittest discover -s tools/tests -p 'test_*.py' -v
python tools/test_native_background.py --output build/native-background-ci
cd android && bash gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

`check_public_tree.py` 检查文件清单与 `SOURCE_MANIFEST.json` 是否一致，并拒绝固件、APK、密钥、日志、个人路径等内容。`test_native_background.py` 的输出目录必须是仓库 `build/` 下的新目录。`tools/tests` 里的仪表程序测试在 Linux 上用 gcc 把两个程序编成本机版运行（Windows 或没有 gcc 时跳过）。
