# 仪表投屏实现时序

本文对应 **APK 1.39.0/code96＋仪表 1.39.0/RWVI**（初版于 2026-11-06 整理）。源码以本文所在提交为准；最后单列主题几何控制、告警位置和离线验证边界。

## 阅读索引

| 图 | 要回答的问题 |
|---|---|
| [1. 端到端总览](#1-端到端总览) | 从点击开始到仪表显示，整体经过哪些环节？ |
| [2. 开机与连接](#2-开机与连接) | 两个开关恢复后，哪些动作自动发生？ |
| [3. 切换 NCM](#3-切换-ncm) | APK 如何通过原厂通道切换仪表 USB？ |
| [4. 恢复 U 盘模式](#4-恢复-u-盘模式) | 如何在线结束投屏并恢复 Host？ |
| [5. 普通应用启动](#5-普通应用启动与任务接管) | 冷启动、已有任务和更换应用如何处理？ |
| [6. 主屏显示与操作](#6-主屏显示触摸原图标与切换其他应用) | 同一任务如何在两端显示，原图标和触摸如何工作？ |
| [7. 定制地图专用入口](#7-定制地图专用入口) | 地图独立仪表页面与普通应用有何区别？ |
| [8. 按屏幕名接入](#8-按屏幕名接入) | 外部应用如何向待机屏送画面？ |
| [9. 地图原生按钮](#9-定制地图原生按钮) | 地图自带按钮如何触发我们的会话？ |
| [10. 视频共同链路](#10-编码传输与显示的共同链路) | 编码、握手、发送和首次打开背景的顺序是什么？ |
| [11. 仪表合成](#11-仪表合成与租约) | 视频、背景蒙版和原生信息怎样叠加与恢复？ |
| [12. 两档分辨率](#12-全屏与小窗的分辨率和双端输出) | 1920×720 与应用实际布局分辨率有什么区别？ |
| [13. 停止与换应用](#13-正常停止与更换应用) | 怎样归还任务，避免导航中断和资源残留？ |
| [14. 断线与隔离恢复](#14-拔线失败与自动清理) | 无法收到清理确认时如何恢复？ |
| [15. 样式切换修复](#15-整套仪表样式切换几何控制) | 样式变化时如何保持全屏几何、恢复最新主题？ |

### 参与者和通道

| 参与者/通道 | 职责 |
|---|---|
| `ProductActivity / ProductController` | 三个标签页、操作窗口、连接开关、开始/停止/换应用的协调 |
| `ProjectionService / ProjectionSessionController` | 一个前台投屏会话和清理状态；不随页面离开而停止 |
| 本机 ADB `127.0.0.1:5555` | 使用已授权身份，以 shell 身份启动本 APK 的宿主；也调用原厂导航服务、授予本 APK 的 USB 权限 |
| `ShellScreenCapture` 等宿主 | 创建/持有虚拟屏、接管或启动应用、编码、处理操作窗口和输入 |
| Binder | APP 与自己 shell 宿主之间的 Surface、输入和接入屏控制；带会话票据/调用方校验 |
| 用户态 USB NCM | APK 直接访问 USB bulk 接口，在用户态实现 Ethernet/IP/TCP；不依赖车机出现系统网卡 |
| DCV1/TCP | 车机 `192.168.77.2` → 仪表 `192.168.77.1:17475`；视频、心跳和背景控制共用有序连接 |
| `dashcast-usb` | 接收模式指令，串行切换仪表 Host/NCM，管理自己的 USB/网络进程 |
| `dashcast-receiver` | 协议接收、向原生媒体链提交 H.264、管理视频窗口、转交 HMI 请求 |
| `rc_hmi.exe / KZB / QNX Screen` | 渲染线程应用布局/蒙版和租约；视频在下，原生信息与告警在上 |

**三条通道应分开看**：USB 角色命令走原厂导航文字通道；车机到仪表的视频走 NCM 上的 DCV1/TCP；主屏操作窗口与 shell 宿主之间走 Binder。本机 ADB 视频 stdout 只把编码数据送回 APK，不是另一条跨仪表的视频线路。

## 1. 端到端总览

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant A as APK 页面/控制器
    participant S as 前台投屏服务
    participant H as 本机 ADB / shell 宿主
    participant Q as 仪表 USB 管理
    participant R as 仪表视频接收端
    participant K as Kanzi / Screen
    U->>A: 选择应用和全屏/小窗，开始
    A->>S: 检查上次清理；需要时先停止旧会话
    A->>H: 已授权 ADB，确认 NCM 或发模式指令
    H->>Q: 原厂导航文字通道 SCMD_USBMODE2
    Q-->>A: USB NCM 枚举（已是目标模式则复用）
    A->>H: 给本 APK 授予该 USB 设备权限
    A->>S: 创建唯一前台会话
    S->>H: shell-v2 启动采集；选择应用/原生地图/接入屏路径
    H->>H: 虚拟屏 → GL → H.264 硬件编码
    H-->>S: DCS1 视频帧＋真实编码 PTS
    S->>R: 首个有效帧后连接 TCP，OFFER/QUERY/HELLO/START
    loop 当前会话持续发送
        S->>R: DCV1 FRAME / HEARTBEAT
        R-->>S: 提交和窗口状态 ACK
    end
    S->>R: 帧进展达标后请求 HMI 投屏布局
    R->>K: 共享内存请求，渲染线程应用蒙版和布局
    K-->>R: nonce/序号/布局/绘制进展回执
    R-->>S: QUERY 回读；持续续租
    Note over H,K: 同一应用源，主屏操作窗口与仪表分别映射；主屏物理分辨率不变
    U->>A: 停止 / 更换应用
    A->>S: 先完整结束当前会话
    S->>R: RESTORE 并确认 → STOP 并确认
    S->>H: STOP stdin → 归还任务/释放采集 → shell-v2 EXIT
    S->>S: 释放 USB 接口；登记清理结果
    Note over A,Q: 普通停止保留 NCM；只有选择 U 盘模式才请求 Host
```

本图概括成功路径；若已存在可用 NCM，不会重复发送切换命令。首次 ADB RSA 授权仍须用户允许；“视频无激活码”不表示跳过 Android 的 ADB/USB 权限。

## 2. 开机与连接

```mermaid
sequenceDiagram
    autonumber
    participant OS as Android / QNX 启动
    participant B as BootReceiver / 控制器
    participant P as 保存的设置
    participant E as 接入屏服务
    participant H as ADB shell
    participant Q as 仪表 startup / USB 管理
    OS->>Q: 仪表 SLM 运行 dashcast-usb --boot
    Q->>Q: 复制模块到 tmpfs；启动模式指令服务
    Note over Q: 共享内存仅在不存在时创建；开机不主动改 USB 角色
    OS->>B: Android BOOT_COMPLETED
    B->>P: 读取“开机启动”及两个连接开关
    alt 已开启开机启动
        opt 已保存“应用内投屏接入”开启
            B->>E: 启动前台接入服务
            E->>H: 创建 fission_dashcast_meter 待机屏
        end
        opt 已保存“投屏模式”开启
            B->>B: ensureLink，检查 NCM/授权
            B->>H: 缺设备时请求 NCM；有设备则提前授权
        end
    else 未开启开机启动
        Note over B: 不自动恢复；打开 APK 后按设置恢复连接
    end
    Note over B,E: 开机恢复本身不打开界面、不自动开始媒体会话；外部应用随后送画面可触发会话
    OS->>B: USB 接入广播
    B->>B: 延迟约 300 ms 后 ensureLink
    OS->>B: USB 拔出广播
    B->>B: 若会话活动则先退出/清理
    opt 投屏模式仍开启、20 秒后仍缺设备且无活动会话
        B->>H: 补发一次 NCM 连接请求
    end
```

车机原厂自启动管理是否放行，会影响开机广播后的运行。1.23.0 起，连接失败保留用户选择，显示失败状态；不会因为约 16 秒没发现 NCM 就自动切 Host。

源码：[BootStart](../android/app/src/main/java/com/byd/dashcast/phase1/BootStart.kt)、[ProductController](../android/app/src/main/java/com/byd/dashcast/phase1/ProductController.kt)、[dashcast_usb.c](../instrument/native/dashcast_usb.c)。

## 3. 切换 NCM

```mermaid
sequenceDiagram
    autonumber
    participant A as ProductController
    participant T as HotRoleTransition / USB 闸门
    participant H as 本机 ADB shell
    participant N as 原厂 AmapService / 车辆通道
    participant Q as dashcast-usb
    participant USB as Android USB 枚举
    A->>T: 请求投屏模式 NCM
    T->>USB: 观察唯一目标设备
    alt 已有匹配 NCM 且无需切换
        T-->>A: 复用；不重置 USB、不破坏当前视频
    else 需要切换
        T->>T: 停止当前会话并等待清理，取得 USB 操作闸门
        T->>H: 核对原厂导航配置、shell 身份和服务/接收器
        H->>N: 启动 com.example.amapservice.AmapService
        H->>N: 广播 NEXT_ROAD_NAME=SCMD_USBMODE2
        Note over H,N: action=AUTONAVI_STANDARD_BROADCAST_SEND，KEY_TYPE=10001，TYPE=0
        N->>Q: 原厂文字数据通道传到仪表
        Q->>Q: 串行执行，检查前一次操作与清理
        Q->>Q: 卸载目标 Host 驱动，启动私有 DCD/网络
        Q->>Q: ncm0=192.168.77.1/30，连接 USB Device
        Q->>Q: 启动接收端，监听 17475
        Q-->>USB: NCM 设备重新枚举
        T->>USB: 有界等待目标设备出现
        USB-->>A: VID:PID 1234:fff1，设备路径
    end
    A->>H: ShellUsbGrant（仅本 APK UID＋该设备）
    H->>USB: IUsbManager.grantDevicePermission
    A->>USB: 重新读取 hasPermission
    opt shell 授权失败
        A->>USB: 标准 requestPermission，核对回调代次/设备
    end
```

- 原厂导航功能号 `1140461576` 是框架功能号，不能当成原始 CAN 仲裁 ID。本图不虚构底层 CAN ID，也不把“广播发出”视为切换成功。
- Android 侧成功条件是观察到唯一匹配的 NCM 并获得访问权限；仪表提示和物理 Host/U 盘能力属于另一类证据。
- APK 的连接失败策略与仪表脚本内部的失败恢复不同：脚本在 NCM 后端初始化失败时可以尝试恢复自己管理的 Host。

源码：[NavigationUsbProtocol](../android/app/src/main/java/com/byd/dashcast/phase1/NavigationUsbProtocol.kt)、[NavigationUsbRelay](../android/app/src/main/java/com/byd/dashcast/phase1/NavigationUsbRelay.kt)、[HotRoleTransition](../android/app/src/main/java/com/byd/dashcast/phase1/HotRoleTransition.kt)、[ShellUsbGrant](../android/app/src/main/java/com/byd/dashcast/phase1/ShellUsbGrant.kt)、[dashcast_usb.c](../instrument/native/dashcast_usb.c)。

## 4. 恢复 U 盘模式

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant A as 控制器 / HotRoleTransition
    participant S as 当前媒体会话
    participant USB as APK 的 USB NCM
    participant H as ADB / 原厂导航通道
    participant Q as 仪表模式脚本
    U->>A: 关闭“投屏模式”，选择 U 盘模式
    A->>S: stopAndAwaitClean
    S->>S: RESTORE/STOP、采集助手退出、会话退出
    A->>USB: 检查 ALT0＋控制/数据接口释放证据
    alt 本地清理已确认
        A->>H: 发送一次 SCMD_USBMODE1
        H->>Q: 原厂文字通道 HOST 请求
        Q->>Q: 确认接收端结束和清理
        Q->>Q: 软断开 DCD；关闭 ncm0 与私有网络/DCD
        Q->>Q: 重新挂目标 Host 驱动并探测
        Q-->>USB: NCM 从 Android 设备列表消失
        USB-->>A: 仅证明设备已离开，不是仪表 Host 正常的完整回执
        Q-->>U: 仪表模式结果；普通 U 盘识别可确认 Host 能力
    else 清理不明
        A->>A: 隔离并按范围恢复，禁止叠加切换操作
    end
```

逻辑上支持保持数据线连接进行切换。关闭 TCP 不等于释放 USB 接口；基础 Host↔NCM 切换与视频失败后的恢复，要分别判断。

## 5. 普通应用启动与任务接管

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant A as 投屏页 / ProductController
    participant S as ProjectionService
    participant H as ShellScreenCapture / MirrorAppSession
    participant D as Android 任务/显示系统
    participant X as 被投应用
    participant B as MirrorBridge / EmbeddedCastView
    U->>A: 选择普通应用，开始
    A->>A: 打开投屏标签，显示准备中；更新启动代次
    opt 上一个应用正在投屏
        A->>S: 停止旧会话并等待服务退出、清理确认
    end
    A->>A: 恢复检查、ADB、NCM、USB 权限、入口检查
    A->>S: startProduct（只有一个活动会话）
    S->>S: 先进入前台，再检查启动条件
    S->>B: 生成本次镜像票据
    S->>H: ADB shell-v2 / app_process，传目标和票据
    H->>D: 创建自有虚拟屏＋采集 Surface
    alt 主屏已有唯一匹配任务
        H->>D: 记录原栈，将原任务迁到自有虚拟屏
    else 应用未运行
        H->>D: am start --display 自有屏
        D->>X: 创建应用任务
        opt Launcher 把同应用后继任务转到主屏
            H->>D: 唯一身份核对后接管稳定的后继任务
        end
    end
    H->>A: 迁移后重新显示投屏标签，避免 Home 盖住页面
    H->>B: Binder 宣告宿主，校验票据/UID/屏幕/源尺寸
    B->>H: 绑定操作窗口 Surface
    X->>H: 同一任务在虚拟屏渲染
    par 主屏输出
        H-->>B: GL 输出同一源画面，等比显示
    and 仪表输出
        H-->>S: GL 编码输出，经共同视频链路传输
    end
```

这条路径是**同一用户、同一应用任务、同一状态的两端显示**，不复制账号或应用数据。任务留在自有虚拟屏时，主屏投屏标签承载它的可操作画面。启动代次可以取消过时请求，避免较慢的旧异步操作在新选择之后又开始。

源码：[ProductController](../android/app/src/main/java/com/byd/dashcast/phase1/ProductController.kt)、[ProjectionService](../android/app/src/main/java/com/byd/dashcast/phase1/ProjectionService.kt)、[MirrorAppSession](../android/app/src/main/java/com/byd/dashcast/phase1/MirrorAppSession.kt)、[MirrorBridge](../android/app/src/main/java/com/byd/dashcast/phase1/MirrorBridge.kt)。

## 6. 主屏显示、触摸、原图标与切换其他应用

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant V as 主屏投屏标签 / EmbeddedCastView
    participant B as MirrorBridge
    participant H as ShellMirrorHost / GL
    participant D as Android 输入/任务系统
    participant X as 虚拟屏上的同一应用
    participant R as 仪表视频链路
    U->>V: 点击或滑动操作窗口
    V->>V: 去掉窗口留白，将触点逆映射到源像素
    V->>B: MotionEvent＋当前 Surface 租约/源尺寸
    B->>H: Binder 定向输入
    H->>H: 校验调用者、票据、已绘制租约和源几何
    H->>D: 设置目标 displayId，injectInputEvent
    D->>X: 在自有虚拟屏处理输入
    X-->>H: 更新后的同一帧
    H-->>V: 更新主屏操作窗口
    H-->>R: 同步编码输出
    U->>D: 回桌面，打开其他应用
    V->>H: 主屏 Surface 可解绑；不结束源任务和编码
    H-->>R: 仪表继续显示被投应用
    U->>D: 再点被投应用原图标
    D-->>H: 无障碍提示＋宿主任务观察
    H->>D: 核对并托管自己的目标任务；需要时迁回自有屏
    H->>V: 打开已有投屏标签
    V->>B: 重建 Surface 后重新绑定
    H-->>V: 恢复同一应用的操作画面
    Note over H,D: 只处理本次所属任务；未使用全局 ActivityController 槽
```

返回键也定向到所属虚拟屏。系统输入法可在车机上工作，但仍受系统及应用的多屏支持限制；投屏页使用 `ADJUST_NOTHING`，键盘不会通过挤压主屏操作窗口改变应用虚拟屏的布局。切其他应用不结束投屏，不等于保证所有第三方应用都支持副屏生命周期。

源码：[EmbeddedCastView](../android/app/src/main/java/com/byd/dashcast/phase1/EmbeddedCastView.kt)、[ShellMirrorHost](../android/app/src/main/java/com/byd/dashcast/phase1/ShellMirrorHost.kt)、[MirrorAppSession](../android/app/src/main/java/com/byd/dashcast/phase1/MirrorAppSession.kt)、[MirrorLaunchObserver](../android/app/src/main/java/com/byd/dashcast/phase1/MirrorLaunchObserver.kt)。

## 7. 定制地图专用入口

```mermaid
sequenceDiagram
    autonumber
    participant A as 控制器 / 入口路由
    participant E as 待机接入屏
    participant H as RemoteDisplaySession
    participant D as Android 显示系统
    participant M as 定制地图
    participant V as 共同视频链路
    A->>A: 查询专用入口表、组件属性和副屏能力
    Note over A,M: 当前表项为 com.byd.automap 的 extra.MeterActivity
    alt 此地图已唯一占用待机接入屏
        A->>E: 复用该屏实际模式/尺寸
        E->>V: 转“按屏幕名接入”采集路径
    else 需要新建原生副屏
        A->>H: 启动原生地图投屏目标
        H->>D: 创建自有 fission 前缀显示屏，使用独立别名
        H->>D: am start MeterActivity --display 自有屏
        D->>M: 独立仪表界面；主屏地图任务保留
        M-->>H: 仪表专用画面 Surface
        H->>V: 采集并进入共同视频链路
        Note over H,M: 退出仅移除本次新建的所属副屏任务；不清掉主屏地图任务
    end
```

专用入口与普通应用同任务镜像是两种机制。当前表不代表所有高德版本都有原生副屏入口；没有匹配入口的应用走普通路径，或由其外部屏幕能力走接入路径。地图自身怎样共享导航状态由地图实现决定。

源码：[入口表](../android/app/src/main/assets/native_projection_entries.json)、[ProjectionEntryRouter](../android/app/src/main/java/com/byd/dashcast/phase1/ProjectionEntryRouter.kt)、[RemoteDisplaySession](../android/app/src/main/java/com/byd/dashcast/phase1/RemoteDisplaySession.kt)。

## 8. 按屏幕名接入

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant A as 控制器 / 单会话协调
    participant E as ExternalDisplayService / Access
    participant H as ShellExternalDisplay
    participant X as 支持外部屏幕的应用
    participant C as ExternalCaptureSession
    participant V as 共同视频链路
    U->>A: 打开“应用内投屏接入”
    A->>E: 启动前台接入服务
    E->>H: 经 ADB 创建 fission_dashcast_meter
    H-->>E: Binder 端点/票据/实际尺寸；注册回调
    Note over A,H: 这里只准备待机屏；不因打开此开关就改变仪表或开始视频
    U->>X: 选择屏幕名，发送外部画面
    X->>H: 在待机屏放置 Presentation / Activity
    loop 约 500 ms 检查
        H-->>E: 屏幕健康、唯一所属 UID、实际模式等快照
    end
    E->>A: 唯一发送者请求接入
    alt 已有别的投屏会话
        A->>A: 保持单会话；等待前一会话结束
    else 可以开始
        A->>A: NCM/授权/清理检查，按接入屏实际模式开始
        A->>C: 生成采集票据，绑定已有待机屏
        C->>H: 附着编码采集 Surface
        H-->>C: 待机屏输出，不再创建第二块同名屏
        C->>V: 进入共同视频链路
    end
    X->>H: 发送者关闭画面或所属关系变化
    H-->>C: 活动采集的状态检查发现变化
    C->>A: 结束媒体会话
    A->>E: endCapture / DETACH
    E->>H: 解除采集；接入开关仍开则保留待机屏
```

接入屏空闲时才能按全屏/小窗偏好重建尺寸；活动画面使用实际屏幕尺寸，不在应用运行中随意改屏。关闭接入开关时，现有采集可先完成，所属画面与采集都离开后再释放待机屏。

**当前边界**：接入服务还在启动、Binder 端点尚未建立时，模式请求可能没有被保存重放；高德车机版普通投屏与它主动向接入屏发画面的冲突也仍是已知项，不能把这两种情况画成全部兼容。

源码：[ExternalDisplayService](../android/app/src/main/java/com/byd/dashcast/phase1/ExternalDisplayService.kt)、[ExternalDisplayAccess](../android/app/src/main/java/com/byd/dashcast/phase1/ExternalDisplayAccess.kt)、[ShellExternalDisplay](../android/app/src/main/java/com/byd/dashcast/phase1/ShellExternalDisplay.kt)、[ExternalCaptureSession](../android/app/src/main/java/com/byd/dashcast/phase1/ExternalCaptureSession.kt)。

## 9. 定制地图原生按钮

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant M as 定制地图原生按钮
    participant OEM as 原车导航类型接口
    participant I as MirrorIconService 无障碍
    participant A as ProductController
    participant E as 接入屏 / 视频会话
    Note over M,OEM: 按钮可见受地图初始化、fission 屏和车辆导航类型条件影响
    U->>M: 点击原生仪表投屏按钮
    par 地图自己的请求
        M->>OEM: setEventValue(0x4C10A018)
        Note over M,OEM: 地图等待导航类型回报；当前仪表没有提供其期望的回报
    and 我方点击识别
        M-->>I: 精确按钮 ID 的点击事件
        I->>I: 两个已适配 ID，约 1 秒去抖
        I->>A: toggleMap
        alt 当前正在按名投屏
            A->>E: 正常停止当前会话
        else 当前可开始地图投屏
            opt 接入屏空闲，给地图原路径机会
                A->>A: 等约 1500 ms，观察接入
            end
            A->>E: 优先匹配/复用接入，必要时走地图入口
        end
    end
    Note over I,E: 开启接入时，shell 仅追加我方无障碍服务；不替换已有服务
```

因此“显示了按钮”“识别了点击”“建立媒体会话”是三个不同环节。1.21.0 后已删除自绘悬浮按钮，当前走原按钮识别和正常会话协调。

源码：[MirrorIconService](../android/app/src/main/java/com/byd/dashcast/phase1/MirrorIconService.kt)、[ProductController](../android/app/src/main/java/com/byd/dashcast/phase1/ProductController.kt)、[ShellExternalDisplay](../android/app/src/main/java/com/byd/dashcast/phase1/ShellExternalDisplay.kt)。

## 10. 编码、传输与显示的共同链路

```mermaid
sequenceDiagram
    autonumber
    participant T as ContinuousProjectionTask
    participant N as UsbNcmSession
    participant H as ShellScreenCapture / GL / MediaCodec
    participant F as AdbScreenVideoSource
    participant C as NoAuthVideoClient
    participant R as dashcast-receiver
    participant K as Kanzi HMI
    T->>N: 打开唯一 NCM，声明控制/数据接口
    N->>N: ALT0，读取 NTB 能力，设置支持的参数，ALT1
    Note over N: 用户态 Ethernet/ARP/IP/TCP；固定本机 .2、仪表 .1
    T->>C: 创建单次客户端＋随机 nonce，配置无限会话
    T->>H: ADB shell-v2 启动本 APK 的采集类
    H->>H: 创建 1920×720 硬件 H.264 Baseline 编码器
    H->>H: 目标 30 FPS、约 1 秒关键帧；接应用/地图/接入屏 Surface
    H-->>F: DCS1 分帧 stdout＋原始编码 PTS；stderr 为诊断
    F->>F: 校验完整 AU、SPS/PPS/IDR 与时间戳，控制发送节奏
    F->>C: 第一个有效 AU 触发 begin
    C->>N: TCP connect 192.168.77.1:17475
    N->>R: 建立 TCP
    R-->>C: DCV1 OFFER（无视频激活认证）
    C->>R: QUERY HMI 能力和空闲状态
    R-->>C: RWVI、布局状态、请求/确认序号
    C->>R: HELLO v3
    R-->>C: ACK
    C->>R: START，1920×720，全屏或小窗标志
    R-->>C: 媒体上下文就绪 ACK
    loop 每个有效 AU
        F->>C: Annex-B H.264＋真实 PTS
        C->>C: 先维护心跳/背景租约，再发送
        C->>R: FRAME（序号、相对 PTS、完整 AU）
        R->>R: 原生媒体提交、解码与视频窗口
        R-->>C: 该帧的提交 ACK
        opt 首次达到 30 次 ACK 且源时间推进至少 1000 ms（仅一次）
            C->>R: HMI START（本会话 nonce，5 秒租约）
            R->>K: 发布共享内存请求
            K-->>R: 渲染线程应用并回执
        end
        C->>R: 按间隔 HEARTBEAT / QUERY / RENEW
        R-->>C: 有序回执
    end
    Note over C,K: HMI START 排队后服务即可进入 STREAMING；后续继续核验布局/绘制和续租
```

- 首个编码帧出现后才建立 TCP 视频会话；HMI 开洞在足够源进展之后。`START` 后 12 秒仍未达到源进展门槛会失败，避免把短暂空动画当成可用视频。
- 当前 `readyForStreaming()` 在投屏布局 START 已排队后即可返回真，并没有等待所有 HMI 绘制回读；后续循环继续确认。`STREAMING` 状态本身不等于布局/实车画面已经验收。
- H.264、背景控制和心跳由同一有序连接的所有者处理；一批 ADB stdout 含多帧时，逐帧发送前也维护租约，避免同步 ACK 等待把续租饿死。
- TCP 目的端口固定 `17475`（1.35.0 起；所有端口见 `DashcastPorts.kt`）；新连接的本地源端口可以变化。帧序号/线上的 PTS 按 v3 处理回绕，未设置两小时总时长限制。
- ACK 证明接收端报告的提交/状态，不证明解码帧数、屏幕动态画面或实际 30 FPS；编码目标帧率也不是实测帧率。

源码：[ContinuousProjectionTask](../android/app/src/main/java/com/byd/dashcast/phase1/ContinuousProjectionTask.kt)、[ShellScreenCapture](../android/app/src/main/java/com/byd/dashcast/phase1/ShellScreenCapture.kt)、[AdbScreenVideoSource](../android/app/src/main/java/com/byd/dashcast/phase1/AdbScreenVideoSource.kt)、[NoAuthVideoClient](../android/app/src/main/java/com/byd/dashcast/phase1/NoAuthVideoClient.kt)、[NoAuthVideoProtocol](../android/app/src/main/java/com/byd/dashcast/phase1/NoAuthVideoProtocol.kt)、[UsbNcmSession](../android/app/src/main/java/com/byd/dashcast/phase1/UsbNcmSession.kt)、[接收端](../instrument/native/dashcast_receiver.c)。

## 11. 仪表合成与租约

```mermaid
sequenceDiagram
    autonumber
    participant C as ProductCompositionSession
    participant R as 接收端 DCV1 背景控制
    participant M as dashcast-bg1 共享内存
    participant K as Kanzi 渲染线程 / 布局控制器
    participant S as QNX Screen 合成
    C->>R: QUERY，要求 RWVI＋空闲且无待确认请求
    R-->>C: HMI 快照、epoch/PID、状态和序号
    C->>R: START，nonce＋全屏/小窗租约标志
    R->>M: 发布请求序号/操作/所属 nonce
    R-->>C: 已排队；尚非渲染完成
    K->>M: 每帧读取请求，独立检查租约
    K->>K: Content＋已注册预制件写投屏 DCLayout
    K->>K: 主题色 Backing＋曲线 Contour；抑制已适配车模/道路装饰
    K->>S: HMI RGBA 层输出
    Note over K,S: 底层视频 → 背景/曲边蒙版 → 原生数字/图标/告警
    K->>M: 确认序号/nonce、布局读回、绘制进展
    loop 会话存活
        C->>R: 约 250 ms QUERY
        R->>M: 读取新鲜且一致的快照
        M-->>C: 经接收端返回
        opt 约 1 秒到期且上次请求已确认
            C->>R: RENEW，nonce＋5 秒租约
            R->>M: 排队，等待渲染线程确认
        end
    end
    alt 正常停止
        C->>R: RESTORE
        R->>M: 排队恢复请求
        K->>M: 撤销投屏布局/透明覆盖，回执空闲
        R-->>C: 回读确认后才继续媒体 STOP
    else 断线或不再续租
        K->>K: 5 秒租约失效，独立恢复原厂显示
        Note over C: 4 秒未获有效确认先判故障并进入清理
    end
```

请求使用 DCV1 背景控制扩展（操作号 10）：QUERY/START/RESTORE/RENEW 分别为 0/1/2/3。START 线值 `5001` 是小窗选择哨兵，不能解释成实际比全屏多 1 ms 的租约；两档健康租约均按 5 秒维护。激活/待确认请求有约 1.5 秒边界；绘制计数用于证明原生遍历进展。

深浅模式通过原厂主题色参与背景合成。当前全屏采用 B2 配套遮罩/混合实现，不能把早期建议的“只改窗口预乘声明”当成现行方案。RWVG 小窗改为整屏接收后沿用直通 Alpha 模型，是当前实现的假设，尚缺该版实车 Alpha 读回证据。

1.25.1 在自有蒙版检查后增加全屏四个实际局部变换及速度对齐的读回；APK 给合法短暂待确认最多 1000 ms，不续租掩盖几何错误。原厂/小窗的几何动画不按全屏固定值校验。它仍不是所有元素或物理显示验收，见图 15。

源码：[ProductCompositionSession](../android/app/src/main/java/com/byd/dashcast/phase1/ProductCompositionSession.kt)、[product-runtime.thumb](../instrument/native-hmi/product-runtime.thumb)、[当前布局构建](../analysis/product_scene_layout.py)、[淡入配套构建](../analysis/fade_in_viewport.py)。

## 12. 全屏与小窗的分辨率和双端输出

| 层次 | 全屏 | 小窗 |
|---|---|---|
| 普通应用虚拟屏/超采样源 | 3824×996，320 dpi | 3366×1458，480 dpi |
| 原生地图/按名接入的源画布 | 1912×498，160 dpi | 1870×810，160 dpi |
| 1920×720 编码帧内的源画面载体 | `(x=4,y=89,w=1912,h=498)` | `(399,101,1122,486)`，源画布缩放 0.6 |
| 仪表视频窗口 | 1920×720，1:1 | 1920×720，1:1 |
| 最终清晰区 | 曲线遮罩透明区域 | 小窗遮罩透明区域 |
| 主屏操作窗口 | 保持源宽高比，按可用页面尺寸等比显示 | 保持源宽高比，按可用页面尺寸等比显示 |

上述源画布**包含外围淡入带**。清晰区是蒙版定义的曲线，不是把整块 1920×720 或表中的载体矩形都算作清晰区；源画布与清晰区不能混为一个分辨率。全屏和小窗也不是把同一块 1920×720 应用界面任意裁成两种窗口。

```mermaid
sequenceDiagram
    autonumber
    participant P as 模式/源尺寸配置
    participant X as 应用虚拟屏
    participant G as CenterFrameRenderer
    participant M as 主屏操作窗口
    participant E as 1920×720 编码 Surface
    participant K as 仪表视频＋曲线蒙版
    P->>X: 创建本模式对应尺寸与 DPI
    X->>X: 应用在该虚拟屏尺寸下布局
    X->>G: 单一源 Surface 帧
    par 主屏消费者
        M->>G: 提供当前页面 Surface 尺寸
        G->>M: 源画面等比缩放，触摸使用相反变换
    and 仪表消费者
        P->>G: 本模式编码帧内的载体矩形
        G->>E: 超采样缩小/映射；填满指定载体
        E->>K: 编码/传输/解码，整屏视频 1:1
        K->>K: 蒙版只在设计清晰区外围淡入背景
    end
    Note over X,M: 主屏窗口大小改变只重建输出映射；不改变应用源屏与车机物理分辨率
```

同一普通应用任务只执行一份源布局，两端输出尺寸可以不同，不能同时承诺该任务在两端各做一份独立自适应布局。主屏宽高比不同的剩余空间不应靠拉伸消除。定制地图有独立仪表界面时，才属于另一个原生页面。当前两档配置和淡入带是代码事实，不是对每个第三方应用在异形清晰区内完整可见的保证。

源码：[WorkspaceDisplayProfile](../android/app/src/main/java/com/byd/dashcast/phase1/WorkspaceDisplayProfile.kt)、[ProjectionDisplayProfile](../android/app/src/main/java/com/byd/dashcast/phase1/ProjectionDisplayProfile.kt)、[ContentViewport](../android/app/src/main/java/com/byd/dashcast/phase1/ContentViewport.kt)、[CenterFrameRenderer](../android/app/src/main/java/com/byd/dashcast/phase1/CenterFrameRenderer.kt)、[fade_in_masks.py](../analysis/fade_in_masks.py)。

## 13. 正常停止与更换应用

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant A as 控制器/会话状态
    participant T as 采集任务 / ADB 控制
    participant R as 仪表接收端 / HMI
    participant H as shell 采集宿主
    participant D as Android 任务/显示系统
    participant USB as 本地 USB NCM
    U->>A: 停止或选择下一个应用
    A->>A: 标记 STOPPING，取消旧启动代次
    A->>T: 设置 stop 标志
    T->>R: beforeStop：RESTORE HMI
    R->>R: 渲染线程确认恢复原厂显示
    R-->>T: 恢复序号、nonce、空闲布局回执
    T->>R: DCV1 STOP
    R->>R: 关闭本次媒体/视频窗口
    R-->>T: 清理回执
    T->>H: shell-v2 stdin 发送 STOP 换行
    alt 普通应用同任务镜像
        H->>D: 把业务任务归还主屏/原栈，确认不再滞留自有屏
        H->>D: 释放自有虚拟屏
    else 新建原生地图副屏
        H->>D: 仅移除本次新建的副屏任务并释放屏
    else 按名接入
        H->>D: 解除采集 Surface，保留仍开启的待机屏
    end
    H->>H: 释放 Binder、观察器、GL、编码器及 Surface
    H-->>T: SHELL_CAPTURE_RESOURCES_RELEASED＋shell-v2 EXIT
    T->>USB: 关闭用户态网络、ALT0、释放控制/数据接口
    USB-->>A: 本地资源释放证据
    A->>A: 全部已确认则 STOPPED；服务退出
    opt 用户选择了下一个应用
        A->>A: 旧服务退出后才准备新会话
    end
    Note over A,USB: 此流程不发送 Host；仍保留用户选择的投屏模式
```

这里的顺序是实际 `AdbShellClient.ShellControl` 的 `beforeStop` → `STOP\n`。不能在宿主还未归还任务时先粗暴杀掉业务应用；也不能仅凭 TCP 关闭或 ADB 通道断开就记作清理成功。

正常清理至少区分：①采集宿主资源标记和真实 EXIT；②仪表背景/媒体清理；③本地 USB 资源；④是否存在打开一半而状态不明的资源。过程出错但这些全部已释放，可进入 FAILED；缺少确认则进入相应恢复状态。

源码：[AdbShellClient](../android/app/src/main/java/com/byd/dashcast/phase1/AdbShellClient.kt)、[ContinuousProjectionTask](../android/app/src/main/java/com/byd/dashcast/phase1/ContinuousProjectionTask.kt)、[NoAuthVideoClient](../android/app/src/main/java/com/byd/dashcast/phase1/NoAuthVideoClient.kt)、[CleanupRecoveryPolicy](../android/app/src/main/java/com/byd/dashcast/phase1/CleanupRecoveryPolicy.kt)、[ProjectionSessionController](../android/app/src/main/java/com/byd/dashcast/phase1/ProjectionSessionController.kt)。

## 14. 拔线、失败与自动清理

```mermaid
sequenceDiagram
    autonumber
    participant USB as USB 设备观察
    participant A as 控制器 / 会话状态
    participant H as ADB shell / 采集宿主
    participant R as 仪表接收端 / HMI
    participant P as AppRestart / RestartReceiver
    USB-->>A: 本次所属 USB 物理消失
    A->>H: 保留独立本机 ADB，停止并清理自有采集
    A->>USB: 关闭本地 NCM 资源
    R->>R: 无续租后，HMI 5 秒租约到期恢复
    Note over A,R: 物理拔线不能伪造远端 RESTORE/STOP 回执
    alt 本地采集/USB 已释放，仅远端未确认
        A->>A: DISCONNECTED 或 RECOVERY_REQUIRED
        USB-->>A: 后续重新连接并获授权
        A->>R: 只读 QUERY，等待同一恢复检查内的新鲜空闲快照
        R-->>A: 能力/代次/空闲/无待确认请求
        A->>A: 允许下一会话；恢复检查不发 CAN 或媒体 START
    else 仅采集助手退出未确认，本地 USB 已释放
        A->>A: QUARANTINED，范围 CAPTURE 或 CAPTURE_AND_PEER
        A->>H: ps 精确匹配自有 ShellScreenCapture 与 shell 子进程
        H->>H: 结束匹配残留，最多约 3 秒复查消失
        H-->>A: 清理结果
        A->>A: CAPTURE 成功 → STOPPED；还缺远端 → RECOVERY_REQUIRED
    else 本地 USB/线程或未知资源未确认
        A->>A: QUARANTINED，范围 LOCAL
        A->>P: 安排闹钟，再结束本 APK 自身进程
        P->>P: Android 10 约 5 秒后唤起接收器
        P->>A: 新进程标记 RECOVERY_REQUIRED，恢复开关
        opt 之前界面在前台
            P->>H: 经 shell 重开 APK 页面
        end
    end
    opt 自动清理中用户又点开始
        A->>A: 排队；清理成功后再执行
    end
    opt 自动清理失败或触发自动重启间隔限制
        A->>A: 显示“重启应用”操作
    end
```

1.24.0 的自动结束目标是我方采集助手及匹配的 shell 子进程，不是被投业务应用；自重启最多每两分钟自动执行一次。若清理后仍缺仪表确认，必须继续空闲回读，不能直接当成恢复完成。

当前会话状态包括 `IDLE / STARTING / STREAMING / STOPPING / STOPPED / FAILED / DISCONNECTED / RECOVERY_REQUIRED / QUARANTINED`。“用户选择的 USB 模式”“是否枚举/授权”“媒体会话状态”是三个独立维度，不应压成一个开关真假值。

源码：[DetachedPeerRecovery](../android/app/src/main/java/com/byd/dashcast/phase1/DetachedPeerRecovery.kt)、[LeftoverCapture](../android/app/src/main/java/com/byd/dashcast/phase1/LeftoverCapture.kt)、[AppRestart](../android/app/src/main/java/com/byd/dashcast/phase1/AppRestart.kt)、[ProjectionSessionController](../android/app/src/main/java/com/byd/dashcast/phase1/ProjectionSessionController.kt)、[ProductController](../android/app/src/main/java/com/byd/dashcast/phase1/ProductController.kt)。

## 15. 整套仪表样式切换：几何控制

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant O as 原厂主题状态
    participant G as 专用 DCGeometry 组
    participant N as 数字及单位节点
    participant H as 渲染线程读回
    participant A as APK
    H->>H: 逐级解析 Screen/RootPage/Content/Cluster/content/Prefab_Cluster_Power_Speed/content
    H->>H: 定位 Power/PowerShow、Speed/SpeedShow 的直接子节点
    H->>G: 全屏模式选择 Full，本地私有键
    G->>N: 设置四项变换和速度对齐
    U->>O: 切换整套仪表样式
    O->>O: 更新当前样式、其他原生颜色与动画
    Note over O,N: 五项冲突属性及十条相关动画已经迁到专用组
    H->>G: 仍为 Full；同状态不需要重施加
    H->>N: 读取全屏实际变换与对齐
    H-->>A: 同一快照内发布匹配或待确认
    opt 暂时不匹配且其他归属条件正常
        A->>A: 最多 1000 ms 同步窗口，保持媒体、不无条件续租
    end
    U->>A: 停止投屏
    A->>H: RESTORE
    H->>G: 选择节点当前的原厂样式
    G->>N: 恢复最后所选样式的几何
```

冲突的五项属性和十条主题转场规则集中到一个组；原厂主题组的其余规则、后续组及绑定保留。全屏固定几何；小窗/空闲使用当前原厂样式，并保留其原有几何动画。不存在先恢复旧样式快照的步骤。

实际 Thumb 与受控属性引擎替身的 92 个用例（包含原生直接子节点查找、实际 TOC 层级及1.25.0失败重现）覆盖字段错误、无效节点、同状态跳过、主题变化及最新样式恢复。尚未运行完整 Kanzi 或实车，本版效果仍待车辆确认。[构建模块](../analysis/theme_geometry.py)、[原生检查函数](../instrument/native-hmi/theme-geometry.thumb)。

## 证据范围

本文是按现行源代码、固定构建链和成品核对得到的实现文档，不是一次新的实车验收报告。基础 USB 切换、视频和部分接入已有历史实车证据；1.21.0 的自动 USB 授权/无障碍与按名接入也有实车日志。1.22.0 起的淡入/小窗 Alpha、1.24.0 真实采集助手的故障恢复等新增点仍应按各自的验证范围判断。

特别要区分：原生属性回执、提交计数、绘制遍历、模拟器行为，与目标仪表真实硬解、Screen 合成、动态画面及安全信息可见性。整套样式切换修复已实现并通过离线检查，实车验收保持待确认。其他边界见[验证状态](VALIDATION.md)、[架构](ARCHITECTURE.md)和[使用说明](USAGE.md)。

## 16. 投屏告警位置覆盖（1.26.1）

```mermaid
sequenceDiagram
    participant H as 既有HMI投屏控制器
    participant P as 当前Indicator预制件
    participant M as 原厂告警管理器＋位置组
    participant D as 原厂告警数据
    H->>P: 全屏时每帧找到当前实例的content，值不同时写DCWarningLayout=1（小窗写0，留在原厂位置）
    P->>M: 激活唯一位置组
    M->>P: 49个叶节点的局部布局/原点/对齐归一，覆盖外围位置
    D->>P: 保持原显隐、颜色、闪烁和消警更新
    Note over P,M: 原厂三组与动画完整保留；图标在视频/蒙版之上
    H->>P: 停止或租约撤销：DCWarningLayout=0
    M->>P: 撤销几何覆盖，使用当前原厂布局
```

全屏、小窗位置相同。状态机读控制值时不向父节点查找，所以必须写在content本地；换整套样式后下一帧给新实例写值。写入不参与投屏布局确认，也没有新增协议或APK开关。资源、原生矩阵和属性替身检查不等于完整Kanzi或实车通过。
