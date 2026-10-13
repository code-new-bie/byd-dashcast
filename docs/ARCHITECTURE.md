# 当前架构（0.4.4）

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

## 为什么车机端口会变

仪表监听端口始终31707。车机每次新TCP连接从49152～65535分配临时源端口，进程级随机起点后循环扫描；同一连接内不改变。活跃端口不复用，关闭后隔离240秒再允许复用，新连接可立即使用其他端口。

这样避免刚关闭的同一TCP四元组被立刻重用。旧现场日志不足以唯一命名对端关闭状态，不宣称已证明某一种TIME_WAIT子情况。

## 仪表适配范围

原国内App46成员及元数据保留，加单一dip-instrument模块；原参考OS不修改，MLO/QNX-IFS/R-IFS及MCU不打入新App-only包。

0.4.4相对已出图0.4.3只变三个仪表模块：instrument-usb、usb-mode.ksh、run-cast.ksh。用已有libc rmdir释放仅两个精确自有空guard；真实超时/清理失败继续隔离，不递归删锁、不强杀共享HMI/媒体服务。receiver、startup、cast-mode保持0.4.3字节。

ARM适配是对匹配输入的哈希固定、范围审计补丁，不是假称从头QNX编译或通用固件支持。
