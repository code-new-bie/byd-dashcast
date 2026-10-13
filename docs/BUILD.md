# 构建与复现

## Android APK

需要JDK21（设置JAVA_HOME）、Android SDK（设置ANDROID_HOME或本地local.properties）、platforms;android-35和build-tools;35.0.1。SDK/许可证应自行按官方流程配置；仓库不携带SDK，不自动替操作者接受许可证。

Gradle9.5.0、AGP9.3.0、Java语言目标11、min/target SDK29；JUnit4.13.2和org.json:20260814仅在桌面单测使用。Gradle分发包和Wrapper已增加/核对官方SHA256。

```bash
cd phase1/android
bash gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

Windows对应`gradlew.bat`。APK位于`app/build/outputs/apk/debug/app-debug.apk`，测试/ lint在`app/build`。新机器首次下载依赖，不能直接假设`--offline`可用。

当前开发版本0.5.0/code15，发布实车基线0.4.4/code14保留。文本行尾统一LF。历史参考包1的10个旧夹具/辅助类不公开（其中3个测试套共32项），AnnexB测试调用当前无凭据DCV1短契约；其余及新长会话测试保留。

0.5.0工作区259测试/37套；公开版独立227测试/34套通过（只排除32个历史凭据测试）。公开版与工作区分别构建/验证，不以一方通过替代另一方；历史0.4.4为231/199。最新结果见[验证状态](VALIDATION.md)。

## 自备输入的仪表App离线包装

这一步需要匹配的国内原版包、参考包2（并已由操作者在目标仪表安装参考OS），不是远程/自动刷机。原件放入忽略的`private-inputs/`，不要提交到Git。

```bash
python -m pip install -r requirements-native.txt
python tools/build_instrument_app.py --original private-inputs/original.zip --reference private-inputs/reference2.zip --output build/instrument-check
```

工具只读原版App/MCU、参考OS和原生模块，核对固定哈希、sidecar、46→53成员和全部原成员元数据；适配后的6模块与已审计0.5.0首测App固定哈希相同，不能用0.4.4 App假称支持长会话。输出目录必须不存在，不覆盖输入/旧交付。

输出App-only ZIP仅有app.sv及3个版本辅助文件，不含OS/MCU。报告位于输出目录旁的子目录，不混入ZIP。它**不是完整交付验收**：需成对选定APK、检查签名/匹配条件、当前回归及恢复手段，才可由操作者决定停稳验证。此工具不能代替实车验收或旧私有工作区的独立审计。

Keystone只汇编、Capstone/pyelftools用于检查；没有把QNX SDK或参考ELF拷进仓库。必要输入缺失/哈希不符时直接失败，不搜索/下载未知固件。

## 仓库工具测试

```bash
python -m unittest discover -s tools/tests -p 'test_*.py' -v
python tools/check_public_tree.py
python tools/check_public_tree.py --verify-snapshot
```

最后一项用于核对首次整理的逐文件哈希，之后正常开发改源码会改变快照，应重新审核/更新清单。日常检查默认检查待公开文件、危险扩展名/凭据模式/个人路径和文档链接，不当作完整秘密扫描器或法律审查。

GitHub CI只检查公开源码/工具并构建测试APK；不连接车辆、不制作或上传参考固件、不发布Release，不使用签名私钥/设备ADB身份。0.4.4源码首次CI已通过；0.5.0 CI以本次提交后的实际运行状态为准，不等于实车验证。
