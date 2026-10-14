# 当前架构（0.7.1高清时间轴候选 / 0.5.0实车基线）

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

DCV1/1使用HELLO、START、FRAME、HEARTBEAT、STOP及接收端状态回复。0.7.1持续模式请求1920×720/30 FPS Baseline，发送端用编码PTS选帧并在DCV1前重定时到固定33,333微秒；Screen visible/提交帧数不能当成真实解码帧计数。视频无激活授权，不代表ADB/USB访问可以绕过许可。

## 0.5.0持续会话

Activity仅发起/停止并展示状态；非导出的ProjectionService持有前台通知、有限时WakeLock和单个有界worker。Activity切后台或重建不取消Service会话；正常进程死亡不进行START_STICKY或自动恢复。USB断开只中止所属会话，不自动发Host或重连。

授权shell的连续模式输出DCS1：24字节头（magic、AU长度、编码器PTS-us、flags、reserved=0）+AnnexB AU；flags=0/1为普通/关键帧，length=0且flags=2为显式EOS，PTS必须等于最后一帧。APK增量解析，每帧最多2MiB、总字节512MiB、65000帧、最长1800秒；没有持续TS录像或无界待发送列表。逐帧同步ACK提供背压；统计ACK往返，不伪称端到端显示延迟。

长会话HELLO version=2，必须先由匹配仪表接受；拒绝时不降级成短模式或打开媒体窗口。短探针仍用version=1，APK保留原短时长/1800帧限制；新仪表接收端两版本共用65000帧/31分钟硬界。仪表保留15秒心跳、原视频空闲和清理保护，允许30分钟发送及排空。只有受控长采集按有效数据续期ADB空闲截止，原诊断/控制/短探针保留绝对超时。

保留仪表原1.5秒视频空闲保护：用户停止时先在同一TCP上STOP并记录清理结果，再发所属ADB shell-v2 stdin固定STOP行；自然结束时DCS1显式EOS在Android显示/编码器拆除之前触发接收端STOP。之后仍须确认shell EXIT=0，再ALT0/释放两接口。采集停止排空最多10秒，从发stdin STOP开始计时，不被QNX媒体清理预算占用。任一步不确认则隔离本进程后续操作；日志保留，不删仪表guard、不按进程名kill共享服务。进程互斥在结果/隔离状态公布之后释放。

Service代码与这些上限通过本地构建/契约测试；0.5.0两次动态画面、后台、停止/窗口退出及拔线Host/U盘已有现场通过。完整10/30分钟、自然结束/异常仍待回归，见[验证状态](VALIDATION.md)。

## 0.7.1高清采集时间轴

持续采集的 MediaCodec 参数为 1920×720、6 Mbps、Baseline、Level 4、30 FPS 请求。`FrameRateGate` 按编码器PTS选帧，接受后按固定33,333微秒递增重定时，并记录输入/输出PTS统计；低于30 FPS的硬件编码器不会被软件补帧。仪表接收器的两个高清范围修改必须与APK成对使用。

0.6.0 的 fit/crop/region/stretched 取景实现保留为历史配置能力，不能改变仪表窗口或宣称告警安全区。

## 0.6.0采集几何

```text
配置界面 → CaptureOptions快照 → ProjectionService → 固定shell参数 → CaptureGeometry
                                                        ↓
实际主屏尺寸/层栈 → SurfaceControl源矩形/目标矩形 → 原640×240编码与接收流程
```

fit显示完整源并保持比例；crop从中心裁成8:3填满；region按实际主屏尺寸选取用户百分比矩形，再保持比例；stretch保留旧版拉伸供对照。1920×1080源的fit为426×240、居中x107，crop去掉上下各180像素。像素整数舍入有至多一个目标像素的比例误差。

配置不改变QNX窗口大小/位置或codec尺寸，不自动寻找某个地图应用窗口。非敏感模式保存到私有偏好，停稳确认不保存；会话启动后冻结，不因界面重建或偏好变更改动正在运行的流。新增检查仅依赖原有OEM十个方法及三个DisplayInfo字段，没有新增MediaProjection或关闭ADB/USB许可。

原固件KZB目录txt只是资源目录；只读二进制解析确认1920×720 Screen、主题/状态节点、中央WarnText本地矩形。FW节点局部位置约(687,368)、549×70，与当前投屏窗口坐标数值相交，但完整父变换/运行状态/Screen层级未认证。不能由一个主题或初始Opacity=0宣布空白安全区，亦不能把局部坐标直接冒充所有状态的全局坐标。此版没有告警隔离、不扩大原窗口，只用于停稳/台架比较几何。未来安全嵌入/告警让位需单独完成动态HMI与Screen验证后再扩大分辨率。

## 为什么车机端口会变

仪表监听端口始终31707。车机每次新TCP连接从49152～65535分配临时源端口，进程级随机起点后循环扫描；同一连接内不改变。活跃端口不复用，关闭后隔离240秒再允许复用，新连接可立即使用其他端口。

这样避免刚关闭的同一TCP四元组被立刻重用。旧现场日志不足以唯一命名对端关闭状态，不宣称已证明某一种TIME_WAIT子情况。

## 仪表适配范围

原国内App46成员及元数据保留，加单一dip-instrument模块；原参考OS不修改，MLO/QNX-IFS/R-IFS及MCU不打入新App-only包。

0.4.4相对已出图0.4.3只变三个仪表模块：instrument-usb、usb-mode.ksh、run-cast.ksh。用已有libc rmdir释放仅两个精确自有空guard；真实超时/清理失败继续隔离，不递归删锁、不强杀共享HMI/媒体服务。receiver、startup、cast-mode保持0.4.3字节。

0.5.0相对0.4.4只改仪表receiver的四个已审计区域，共122字节（版本/心跳状态、帧上限、总时限）；USB模块、四脚本与原媒体/AVC解析/清理保持原字节。App SHA256为bf8446491f0277a66e25fbb96a6355b403735ae5ad8dfbe079cd34958a2b5641。本地FULL首测ZIP复制参考OS和原国内MCU原件；“内容不改”不等于“不刷写”。

0.6.0相对0.5.0仪表端零变更，以上App/OS/MCU/Boot及模块hash全部保留。

0.7.0相对0.5.0只在接收器的高清几何和720行AVC边界增加适配，USB/角色模块、媒体/清理分发、参考2 OS和国内MCU不改。0.7.0 APK不能与0.5/0.6仪表App混用；公开源码不提供固件原件。

ARM适配是对匹配输入的哈希固定、范围审计补丁，不是假称从头QNX编译或通用固件支持。
