# 当前架构（0.5.0开发 / 0.4.4实车基线）

控制与视频分离。阶段一已经采用用户态NCM/TCP/DCV1；早期方案的RTP/UDP/video-service路线不是当前验证包的实现。

```text
控制：APK → 正常授权本机ADB(uid2000) → 原厂导航文字中继 → 原MCU/SPI → QNX USB服务
视频：ADB shell镜像 → 硬件H.264 Baseline → APK用户态NCM/TCP → 仪表DCV1接收器 → 媒体/Screen
```

## 控制边界

使用两条既有文本命令：`SCMD_USBMODE1`为Host，`SCMD_USBMODE2`为NCM。原厂服务负责文字传递，不在APK猜测/注入原始CAN帧，不修改MCU或关闭ADB认证。

APK能记录广播提交、导航处理尝试和USB枚举，但这些不是CAN送达/仪表模式ACK。Host实际结果需普通U盘识别验证。原厂导航中继可能改变导航页面，验证时须停稳并退出原导航。

## 视频与网络

固定私有链路：车机`192.168.77.2/30` → 仪表`192.168.77.1:31707`，不回退Wi-Fi/移动网络，不修改系统IP/路由。目标USB身份`1234:fff1`仍需结合连接来源核实，不接管其他USB设备。

USB最小协商：ALT0 → GET_NTB_PARAMETERS → 合法4字节SET_NTB_INPUT_SIZE → ALT1；结束时ALT0，再释放两个接口。无能力的format/CRC/filter请求不发送。

DCV1/1使用HELLO、START、FRAME、HEARTBEAT、STOP及接收端状态回复。源尺寸/显示窗口640×240、Baseline/progressive门禁保留；Screen visible/提交帧数不能当成真实解码帧计数。视频无激活授权，不代表ADB/USB访问可以绕过许可。

## 0.5.0持续会话

Activity仅发起/停止并展示状态；非导出的ProjectionService持有前台通知、有限时WakeLock和单个有界worker。Activity切后台或重建不取消Service会话；正常进程死亡不进行START_STICKY或自动恢复。USB断开只中止所属会话，不自动发Host或重连。

授权shell的连续模式输出DCS1：24字节头（magic、AU长度、编码器PTS-us、flags、reserved=0）+AnnexB AU；flags=0/1为普通/关键帧，length=0且flags=2为显式EOS，PTS必须等于最后一帧。APK增量解析，每帧最多2MiB、总字节512MiB、65000帧、最长1800秒；没有持续TS录像或无界待发送列表。逐帧同步ACK提供背压；统计ACK往返，不伪称端到端显示延迟。

长会话HELLO version=2，必须先由匹配仪表接受；拒绝时不降级成短模式或打开媒体窗口。短探针仍用version=1，APK保留原短时长/1800帧限制；新仪表接收端两版本共用65000帧/31分钟硬界。仪表保留15秒心跳、原视频空闲和清理保护，允许30分钟发送及排空。只有受控长采集按有效数据续期ADB空闲截止，原诊断/控制/短探针保留绝对超时。

保留仪表原1.5秒视频空闲保护：用户停止时先在同一TCP上STOP并记录清理结果，再发所属ADB shell-v2 stdin固定STOP行；自然结束时DCS1显式EOS在Android显示/编码器拆除之前触发接收端STOP。之后仍须确认shell EXIT=0，再ALT0/释放两接口。采集停止排空最多10秒，从发stdin STOP开始计时，不被QNX媒体清理预算占用。任一步不确认则隔离本进程后续操作；日志保留，不删仪表guard、不按进程名kill共享服务。进程互斥在结果/隔离状态公布之后释放。

Service代码与这些上限通过本地构建/契约测试；目标Android10后台运行及QNX长时间出图仍须实车验证，见[任务与验收](TASKS_0.5.0.md)。

## 为什么车机端口会变

仪表监听端口始终31707。车机每次新TCP连接从49152～65535分配临时源端口，进程级随机起点后循环扫描；同一连接内不改变。活跃端口不复用，关闭后隔离240秒再允许复用，新连接可立即使用其他端口。

这样避免刚关闭的同一TCP四元组被立刻重用。旧现场日志不足以唯一命名对端关闭状态，不宣称已证明某一种TIME_WAIT子情况。

## 仪表适配范围

原国内App46成员及元数据保留，加单一dip-instrument模块；原参考OS不修改，MLO/QNX-IFS/R-IFS及MCU不打入新App-only包。

0.4.4相对已出图0.4.3只变三个仪表模块：instrument-usb、usb-mode.ksh、run-cast.ksh。用已有libc rmdir释放仅两个精确自有空guard；真实超时/清理失败继续隔离，不递归删锁、不强杀共享HMI/媒体服务。receiver、startup、cast-mode保持0.4.3字节。

0.5.0相对0.4.4只改仪表receiver的四个已审计区域，共122字节（版本/心跳状态、帧上限、总时限）；USB模块、四脚本与原媒体/AVC解析/清理保持原字节。App SHA256为bf8446491f0277a66e25fbb96a6355b403735ae5ad8dfbe079cd34958a2b5641。本地FULL首测ZIP复制参考OS和原国内MCU原件；“内容不改”不等于“不刷写”。

ARM适配是对匹配输入的哈希固定、范围审计补丁，不是假称从头QNX编译或通用固件支持。
