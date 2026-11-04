# 代码架构

本文说明 OkCarLink 模块的整体结构：设备侧（KernelSU/Magisk 模块、root 助手脚本、原生二进制）、负责 CarPlay/AirPlay 协议的 headless Java 守护进程，以及它们各自对接的手机侧协议。面向想读源码、想重新构建模块、或想扩展模块的开发者。

## 0. 公开发布版的变更

本产品由内部工具更名而来。公开身份为：

| 项目 | 值 |
| --- | --- |
| 产品名 | OkCarLink |
| 模块 id | `okcarlink`（`module/module.prop`） |
| 版本 | `1.0.0`（`versionCode=100`） |
| 作者 | `okcarlink` |

内部的**运行时文件名是故意保持不变的**：`okcar-port-ctl`、`okcar-linkd`、`okcar-carkit`、
`okcar-port-*` 状态文件，以及 `okcar-carkit` / `okcar-cast` 套接字名都沿用原名。新产品名下没有任何东西被改名，本文档也不做这样的声明。

没有配套 APK；整个 iPhone 侧协议栈就是一个 dex。

## 1. 为什么没有 APK

这套栈原本跑在一个 Android 应用里：Activity 驱动它（`LinkActivity`），Service 托管它（`OkCarLinkService`）。以普通应用身份运行，在本模块瞄准的 OEM ROM 上会真实损失能力：后台 activity 启动、按网络随机化 MAC、`MediaProjection` 授权弹窗、应用域抓屏、安装签名检查，都会限制一个端口栈能做什么。守护进程头部注释（`daemon/src/com/okcar/link/daemon/LinkDaemon.java` 顶部）把当前形态的原因记录下来：同一批编译好的类以 `root` 身份的 `app_process` 运行，于是没有 manifest、没有 Activity、没有权限弹窗，特权部分（虚拟显示、编码器、输入注入、Wi-Fi/蓝牙控制）都在进程自己手里。

这个决定带来的一切后果：

- **一个 dex。** `tools/build-daemon.sh` 把整个 `daemon/src` 编译成单个 `build/okcar-link.dex`，没有 manifest，也没有 APK 打包步骤——manifest 正是这套设计刻意避开的应用侧契约。
- **一个监督进程，而不是 Service。** `module/system/bin/okcar-linkd supervise` 就是进程管理器：它构造正确的 `app_process` 环境、把守护进程移出可被冻结的 cgroup、每 2 秒检查一次、在超时缺席后替换它。
- **一份源码根目录。** 应用侧类与守护进程侧类在同一份源码树里，两者不可能各自漂移（见第 8 节）。

## 2. 分层结构图（文本）

````text
┌──────────────────── 手机 / USB + 射频侧 ────────────────────┐
│  Wi-Fi（加入 SoftAP 或 Wi-Fi Direct 组）  蓝牙 BR/EDR   USB │
│        │                                          │        │
│  ┌─────▼────── root 模块层（KernelSU/Magisk）─────▼──────┐    │
│  │ service.sh · post-fs-data.sh · uninstall.sh · action.sh│   │
│  │ module.prop · sepolicy.rule                          │    │
│  │ system/bin 助手：okcar-port-ctl（开关）、okcar-linkd   │    │
│  │   （监督进程）、okcar-port-panel（本地回环界面）、      │    │
│  │   okcar-carkit（USB 拉起）、okcar-carwith-svc、         │    │
│  │   okcar-port-check / okcar-port-role                  │    │
│  │ 原生二进制：okcar-uinputd        okcar-ffs-usbmux      │    │
│  │ webroot/（WebUI） panel/         system/usr/idc/*.idc  │    │
│  └───────┬──────────────────────────────┬─────────────────┘    │
│          │ CLASSPATH=payload/…dex        │ 助手协议              │
│          ▼                              ▼                      │
│  ┌──────── headless Java 守护进程（root app_process）──────┐    │
│  │ com.okcar.link.daemon.LinkDaemon   入口 + 命令通道       │    │
│  │ com.okcar.link.port.*      后端：显示/抓屏/输入/Wi-Fi  │    │
│  │ com.okcar.link.wireless.*  RFCOMM 监听、iAP2、Wi-Fi 交接│    │
│  │ com.okcar.link.airplay.*   AirPlay-2 配对 + RTSP + 发送│    │
│  │ com.okcar.link.iap2.*      iAP2 协议（设备角色）        │    │
│  │ com.okcar.link.media.*     抓屏 + H.264/HEVC + 音频     │    │
│  │ com.okcar.link.carkit.*    有线 Java 半侧（套接字宿主） │    │
│  │ com.okcar.link.stream2.*   display-2 投屏管线           │    │
│  │ com.okcar.link.cast.*      共享线格式编解码器            │    │
│  └───────┬──────────────────────────────┬─────────────────┘    │
│          │ stdin 命令（FIFO）            │ 原生 stdio 协议       │
│          ▼                              ▼                      │
│  okcar-linkd.in ──► LinkDaemon   UinputTouchBackend ──►        │
│                                      okcar-uinputd            │
│                                      /dev/uinput 虚拟触摸屏    │
│  okcar-ffs-usbmux ──► 抽象套接字 "okcar-carkit" ──► CarkitHost │
└───────────────────────────────────────────────────────────────┘
            ▲                                          │
            └─────────── 手机侧协议（CarPlay/AirPlay） ◀┘
```

手机始终处在链路**iPhone 侧**：它在所有模式下都扮演设备角色，车机（或任何主机）是配件。无线模式下它发布 iAP2 服务记录、等待主机拨入；有线模式下 FunctionFS gadget 向主机呈现兼容 iPhone 的 USB 设备，由主机把它枚举成 Apple 设备并讲 usbmux。手机真实的平台身份从不对外声明——身份按协议逐层协商（第 7 节，以及有线 lockdown 身份见 `docs/zh/crypto.md`）。

## 3. 模块目录

`module/` 就是打包进发布 zip 的 KernelSU/Magisk 模块（`tools/package-module.sh` 列出了必须随zip发布的文件；`sepolicy.rule` 与 `uninstall.sh` 不是可选项）。

| 路径 | 作用 |
| --- | --- |
| `module/module.prop` | 模块身份（`id=okcarlink`、`version=1.0.0`、中英文描述） |
| `module/post-fs-data.sh` | 开机早期钩子：在蓝牙栈初始化前声明 SDP Device ID 厂商字段（Apple），并运行 `okcar-carwith-svc setup` |
| `module/service.sh` | 启动编排：对 `/data/local/tmp` 执行 `chmod 755`、等待开机完成与蓝牙就绪、安装 `.idc`、`okcar-port-ctl apply boot`、启动 `okcar-linkd supervise` 与本地面板 |
| `module/customize.sh` | 安装钩子；打印 lite 变体说明。安装时不做任何编译 |
| `module/uninstall.sh` | 先把 USB 交还框架，再删除模块自己的 `/data/local/tmp` 状态。卸载**不会**清空 `/data/local/tmp`，这正是该脚本存在的原因 |
| `module/sepolicy.rule` | 两条授权：框架 supplicant 可以响应我们的 p2p0 控制套接字；不可信应用可以追加写入 `/data/local/tmp` 下的守护进程 FIFO |
| `module/action.sh` | 模块按钮（Magisk）：启动本地面板、打开其 URL、发一条通知 |
| `module/system/bin/okcar-linkd` | 监督进程（第 4、5 节） |
| `module/system/bin/okcar-port-ctl` | 唯一的 root 入口：开关、蓝牙身份动作、自检（第 9 节） |
| `module/system/bin/okcar-carkit` | 有线拉起与 USB 交还（第 7 节） |
| `module/system/bin/okcar-port-panel` | 仅监听 127.0.0.1 的迷你面板，文档根目录就是 `webroot/` |
| `module/system/bin/okcar-carwith-svc`、`okcar-port-check`、`okcar-port-role` | SELinux 服务助手、能力/平台自检、角色节点助手 |
| `module/payload/okcar-link.dex` | 整个端口栈（一个 dex）；由 `tools/package-module.sh` 暂存到此 |
| `module/system/usr/idc/okcar-uinput-touch.idc` | 注入用虚拟触摸屏的输入设备类别；`service.sh` 还会把它复制到 `/data/system/devices/idc`，因为在管理器并不真正挂载模块的设备上 `/system/usr` 覆盖不存在 |
| `module/webroot/` | WebUI（`index.html`、`app.js`、`ksu-bridge.js`、`style.css`、`cgi-bin/ctl`） |
| `module/panel/` | 跳转到 `webroot/` 并带上面板 token 的前门 |

## 4. 启动链：谁启动什么

1. **`post-fs-data`（同步、早期）。** 只做一件事：在蓝牙栈建立 SDP 记录前声明 SDP Device ID 厂商字段——栈初始化之后的 `setprop` 要等射频重启才生效。它同时运行 `okcar-carwith-svc setup`（`okcar-carwith` binder 名的 SELinux 类型；未登记的服务名会退回 `default_android_service`，任何应用都找不到它）。
2. **`service.sh`。** `/data/local/tmp` 每次开机都会被以严格权限重建，因此脚本先放宽权限再往里写任何东西。它按模块目录解析助手（带回退 `/system/bin`）、安装 `.idc`、等待 `sys.boot_completed` 与真实蓝牙适配器，然后运行 `okcar-port-ctl apply boot`。
3. **`okcar-port-ctl apply boot`** 只应用默认开启的开关（`did` 那一条 `setprop`）。它刻意**不**跑裸 `apply`——那会连带应用有线开关、在开机早期重绑 USB gadget；这个组合在现场设备上造成过开机循环，所以每个早期钩子都用自己的受限 apply。
4. **`okcar-linkd supervise`** 启动 dex（第 5 节）。`service.sh` 同时启动回环面板，并在会话开关关闭时通知守护进程停止。有线的开机自启跑在一个分离的后台任务里，刻意不占开机关键路径，并带有自己的重复自启保护。
5. 守护进程写出状态文件，并提供一行一条命令的 FIFO（`/data/local/tmp/okcar-linkd.in`）——WebUI、`okcar-port-ctl`、车机应用都通过它与守护进程对话。

## 5. 守护进程如何启动、如何找到模块

`module/system/bin/okcar-linkd` 是一个 shell 监督进程。它的任务是让下面这行每次都成立：

````sh
CLASSPATH=$MODDIR/payload/okcar-link.dex app_process com.okcar.link.daemon.LinkDaemon
```

- **模块目录。** 由 `$0` 推导（裸名调用时有候选列表），并以 payload 文件确实存在来验证——裸名调用曾把 dex 解析到一个不存在的路径，守护进程随后静默退出。
- **环境补齐。** `app_process` 在环境不良的 shell 里会以 "ANDROID_DATA environment variable unset" 中止，因此缺少时补齐 `ANDROID_DATA` / `ANDROID_ROOT`。Android 13+ 上还会补齐 ART 变量（`BOOTCLASSPATH`、`DEX2OATBOOTCLASSPATH`、`ANDROID_ART_ROOT`、`ANDROID_I18N_ROOT`、`ANDROID_TZDATA_ROOT`、`ANDROID_ASSETS`、`ANDROID_STORAGE`），做法是**采样一个活着的系统进程**——手写的 classpath 跟不上每个 ROM。只补缺失的变量：调用方环境已经完整的（正常开机路径）完全不被改动。
- **从 dex 内部看**，`PortBootstrap.ownModuleDir()` 解析 `java.class.path` 中 `/data/adb/modules` 下的 `/payload/` 前缀——守护进程的 classpath 精确指出它自己的模块目录，不管安装 id 是什么。接着 `PortBootstrap.locate(name)`（`daemon/src/com/okcar/link/port/PortBootstrap.java:348`）按顺序搜索：`<module>/system/bin/<name>`、`/system/bin/<name>`、`/data/local/tmp/<name>`，以及回退 id 列表 `{"okcar-port", "okcar-port-lite", "okcarlink"}` 下同一条路径（`/data/adb/modules` 与 `/data/adb/modules_update` 两处都查）。
- **运行时文件**都在 `/data/local/tmp`（监督进程支持主机自检用的 `OKCAR_TMP` 覆盖）。守护进程从写进自己 stdin 的 FIFO 读命令；它写出 `okcar-linkd.status` 和供人阅读的日志。

## 6. 守护进程包结构

一个 dex，涵盖整个 `daemon/src`。各包职责与值得先读的类：

| 包 | 文件数 | 职责 | 代表类 |
| --- | --- | --- | --- |
| `com.okcar.cast` | 2 | 共享线格式编解码器，纯 Java、不依赖 Android（第 8 节） | `LinkCodec`、`LinkStreamReader` |
| `com.okcar.link` | 4 | 顶层状态、Service 门面、应用侧宿主 | `LinkState`、`NativeBridge`、`OkCarLinkService`、`LinkActivity` |
| `com.okcar.link.airplay` | 26 | AirPlay-2 发送侧：SRP + X25519/Ed25519 配对、密封通道、RTSP 会话机、发送器 | `AirPlayPairing`、`AirPlaySession`、`WirelessAirPlayClient`、`ScreenStreamSender`、`VideoPacer`、`Ed25519Pure`、`X25519Pure` |
| `com.okcar.link.carkit` | 11 | CarWith/carwith 侧桥接与有线 Java 宿主 | `CarkitServiceHost`、`CarwithBridge`、`CarwithCastClient`、`CarwithInput` |
| `com.okcar.link.castlink` | 1 | LocalServerSocket `okcar-cast`：接收 APK 推来的编码帧并转发（第 8 节） | `CastLinkServer` |
| `com.okcar.link.daemon` | 1 | headless 入口与命令通道 | `LinkDaemon` |
| `com.okcar.link.hid` | 1 | HID 报文解码（车机侧触摸/按键） | `HidReportDecoder` |
| `com.okcar.link.iap2` | 10 | iAP2 协议，设备角色 | `Iap2Link`、`Iap2SessionHandler`、`Iap2Frame`、`UsbIap2Transport`、`Iap2SelfTest` |
| `com.okcar.link.input` | 2 | 向手机注入触摸/按键 | `InputInjector`、`TouchGeometry` |
| `com.okcar.link.media` | 10 | 抓屏、H.264/HEVC 编码、音频、QoS | `MirrorVideoSource`、`VideoQos`、`OpusAudioEncoder`、`FrameWatch` |
| `com.okcar.link.net` | 3 | mDNS 发现与仅 IPv4 响应器 | `MdnsDiscovery`、`MdnsV4Responder`、`LinkNetworkMonitor` |
| `com.okcar.link.plist` | 1 | 二进制 plist 编解码（可在主机验证） | `BinaryPlist` |
| `com.okcar.link.port` | 19 | 面向 root/设备的后端与策略 | `PortBootstrap`、`RootAppDisplayFactory`、`UinputTouchBackend`、`AppPriorityPolicy`、`MiuiSynergyMode`、`CastCooldown` |
| `com.okcar.link.rtsp` | 1 | RTSP 成帧原语 | `RtspMessage` |
| `com.okcar.link.stream2` | 11 | display-2 投屏管线：显示 2 上的 App → 编码 → RTP/RTSP | `Cast2Controller`、`Rtsp2Server`、`RtpPacketizer`、`SdpBuilder`、`FrameHub` |
| `com.okcar.link.usb` | 3 | USB 模式/拓扑助手 | `UsbModeController`、`UsbTopology`、`PcIntents` |
| `com.okcar.link.util` | 1 | 日志安全的十六进制 | `Hex` |
| `com.okcar.link.wireless` | 13 | 无线会话：RFCOMM 监听、入网、会话客户端 | `WirelessCarPlaySession`、`WifiGroupJoiner`、`WifiP2pGroupJoiner`、`AccessoryMemory`、`RfcommIap2Transport`、`WirelessSelfTest` |

最大的几个源文件，可作为阅读顺序：`WirelessAirPlayClient.java`（约 151 KB）、`WirelessCarPlaySession.java`（约 129 KB）、`LinkDaemon.java`（约 115 KB）、`AirPlayPairingSelfTest.java`（约 102 KB）、`RootAppDisplayFactory.java`（约 87 KB）、`WifiGroupJoiner.java`（约 82 KB）、`OkCarLinkService.java`（约 80 KB）。

包结构里有两条设计准则值得照抄：

- **纯 Java 内核。** 承载协议语义的类（`com.okcar.cast`、`AirPlayPairing`、`CastCooldown`、`BinaryPlist`、以及 `*Pure` 加密助手）刻意不用任何 `android.` 导入，因此同一份源码可以在主机 JVM 上编译，并被 `*SelfTest` 类断言——不需要设备。
- **接口之后的实现。** `com.okcar.link.port` 定义面向设备的契约（显示/抓屏/输入/Wi-Fi）及其 root 实现（`RootAppDisplayFactory`、`UinputTouchBackend`、`RootWmSizeBackend`、`ShellWifiOps`），因此上层会话代码从不需要判断"我现在是不是 root"。

### 6.1 原生二进制如何被调用

- **`okcar-uinputd`**（源码 `native/uinputd/okcar-uinputd.c`，静态链接的 arm64 bionic 程序）由 `UinputTouchBackend` 作为子进程启动，使用一行一条命令的 stdin 协议：`D <slot> <x> <y>`、`M <slot> <x> <y>`、`U <slot>`、`K <linux-key> <0|1>`、`Q`。它打开 `/dev/uinput`，注册一个直接输入的多点触摸屏（5 个 slot）加一段按键范围，ABS 范围来自 `--width`/`--height`。`PortBootstrap` 用面板**未旋转**的尺寸启动它——输入栈会自己旋转触摸屏的 raw 范围；`TouchGeometry` 把每个注入点换算进这个空间。
- **`okcar-ffs-usbmux`**（源码 `native/ffs-usbmux/`）由 `okcar-carkit` 启动，写出 Apple USBMUX 接口的 FunctionFS 描述符，然后提供 usbmux + lockdown TLS，并把 carkit 字节管道桥接到抽象套接字 `okcar-carkit`（Java 侧 `CarkitServiceHost` 在此监听，见第 7 节）。

## 7. 三种模式概览

### 7.1 无线 CarPlay（默认）

1. `post-fs-data` 已声明 SDP Device ID；`service.sh` 已应用默认开启的开关并启动监督进程。
2. `LinkDaemon` 安装 root 后端（`PortBootstrap.installDefaults`）并进入 `WirelessCarPlaySession`，后者发布 `00000000-deca-fade-deca-deafdecacafe` 的 RFCOMM 服务记录。**配件来拨我们**；这一通拨号就是"来连线吧"的通知。
3. accept 循环把每个套接字交给 `RfcommIap2Transport` + `Iap2Link` + `WirelessCarPlayDriver`；设备角色的链路开关（由配件先发 SYN、确认刚收到的序号）在这里设置，停止时恢复。
4. 配件在 iAP2（`0x5703` / `0x4301`）里交出它的网络：SSID、密码、安全类型、信道。`WifiP2pGroupJoiner`（真实 Wi-Fi Direct 组）或 `WifiGroupJoiner`（站点 + network suggestion）负责加入，并**验证**关联——"请求被接受"不等于成功。清理规则：连上时记录、断开时移除该网络。
5. 在加入的链路上，`WirelessAirPlayClient` 向配件发起 AirPlay 会话：`GET /info` → `POST /pair-setup`（M1–M6，SRP）→ `POST /pair-verify`（M1–M4）→ 密封控制通道，全部经由 `AirPlayPairing`（纯 Java）。然后是真实 iPhone 的行为：密封的 `POST /command` `{"type":"disableBluetooth"}`、一次 type-130 流 SETUP，以及到配件数据端口的第二条 TCP 连接，用 DataStream 输出密钥密封，承载 32 字节 APTransportPackage 记录里的原始 iAP2。iAP2 由此从蓝牙搬到 Wi-Fi。
6. 视频：`MirrorVideoSource` 把手机画面镜像进一个 Surface，用 `MediaCodec` 编码 H.264（默认）或 HEVC；`WirelessVideoSender`/`VideoPacer` 把流速整形成配件可用的节奏。音频：`OpusAudioEncoder` → `WirelessAudioSender`。`MdnsV4Responder` 在链路上发布仅 IPv4 的 mDNS 记录——`NsdManager` 的双栈应答会解析出这些车机无法路由的 IPv6 地址。
7. 车机侧的触摸从加密事件/HID 通道回来（`HidReportDecoder` → `InputInjector`，或 `UinputTouchBackend` → `okcar-uinputd`），注入到手机上。
8. 停止时恢复链路开关；把系统饿死的会话会让新投屏进入冷却窗口（`com.okcar.link.port.CastCooldown`），同时保持监听——车机永远看不到手机消失。

### 7.2 无线 AirPlay 镜像

AirPlay 发送侧原语是无线栈的共享半边。一个只是 AirPlay 接收器（没有 iAP2 凭据交接）的车机，由同一套配对与会话机制指向一个已发现的接收器来服务：

1. `MdnsDiscovery.browseForAirPlay` 在对应网络接口上解析接收器。
2. `AirPlaySession` 以显式步骤机走过 RTSP 控制通道——`/info` → `/auth-setup` → `/pair-setup` → `/pair-verify` → 流建立（`streams` 字典及其端口键）→ 录制——并如实报告对方答了什么，而不是假定顺序（端点是已知的，确切次序不是）。
3. `ScreenStreamSender` 把编码后的画面推到协商好的数据端口，由 `VideoPacer` 整形；音频走自己的流。
4. 这条路径没有 iAP2、没有 lockdown；配对状态就是 AirPlay 身份（Ed25519 长期密钥）——与有线 lockdown 密钥对是两回事，见 `docs/zh/crypto.md`。
5. 坦白一处不确定：哪个入口最终启用这条路径因分发形态而异。在本源码树里，"发现接收器"这一入口（`MdnsDiscovery` + `AirPlaySession` + `ScreenStreamSender`）由 `com.okcar.link.OkCarLinkService` 驱动——同一份共享源码的应用侧宿主；headless 守护进程则只通过无线 CarPlay 会话到达同一批配对与发送原语。

### 7.3 USB 有线 CarPlay（开关默认关闭）

1. 用户打开有线开关（WebUI 或 `okcar-port-ctl switch wired on`）→ `okcar-carkit start [full|ncm]`，先 setsid 脱离，再触碰 `sys.usb.config`。
2. `okcar-carkit` 创建 FunctionFS function、挂载它、启动 `okcar-ffs-usbmux` 让描述符先存在，先 link `ffs` 再 link NCM，最后才绑定 UDC。同时把 lockdown 身份装进 `/data/local/tmp`（证书 `0644`、私钥 `0600`）。
3. 车机把 gadget 枚举为兼容 iPhone 的设备并向它说 usbmux：16 字节大端帧、版本握手、经 usbmux 的 TCP 到 lockdown 端口。
4. `okcar-ffs-usbmux` 先应答明文 `GetValue`/`SetValue`，再用 `EnableSessionSSL=true` 应答 `StartSession` 并升级为经 BIO 对驱动的 BoringSSL TLS 服务端，然后用端口 + **不带** `EnableServiceSSL` 应答 `StartService com.apple.carkit.service`——那是一条原始 iAP2 字节管道。
5. 每条 mux 连接被桥接到抽象套接字 `okcar-carkit`；Java 侧 `CarkitServiceHost` 接受连接，`UsbIap2Transport` + `Iap2Link` 以现场验证过的有线链路参数掌管设备角色。
6. 媒体与触摸复用 7.1 第 6–7 步完全相同的平面；差别只在传输层。
7. 拆除按固定顺序交还端口（先解绑我们的 → `sys.usb.config=adb` → 再恢复 configfs），并有一个区分"端口被占 / USB 卡死 / 需要重启"三种状态的断路器，加有限的自我修复。

## 8. 共享线格式编解码器（`com.okcar.cast`）

`daemon/src/com/okcar/cast/` 是帧格式唯一定义的地方。

| 方向 | 帧 |
| --- | --- |
| 出：视频 | `V pts flags len\n` + Annex-B H.264 负载 |
| 出：编解码器配置 | `C spsLen ppsLen\n` + 负载 |
| 出：音频 | `A pts rate ch len\n` + 负载 |
| 入：触摸 | `T action x y displayId\n` |

`LinkStreamReader` 是接收状态机：LF 结尾的头行（超长头部被丢弃并重置读取器，而不是无限增长）、按声明长度精确累积的负载、以及超限的帧按字节跳过——流永不失同步。帧有上限（`MAX_PAYLOAD` 4 MiB、`MAX_HEADER` 512）。

为什么它必须是唯一事实来源：

- 应用侧和守护进程侧是同一个协议。格式的第二份拷贝——哪怕看起来兼容——就是现场设备上"画面正常、触摸全死、两边都不报错"的成因。
- `CastLinkServer`（包 `com.okcar.link.castlink`）接收由 APK 经本地套接字 `okcar-cast` 推来的这些帧，直接交给 AirPlay 画面发送器，自己不做抓屏也不做编码；触摸行沿同一条连接返回。整条路径是可选开启的（`/data/local/tmp/okcar-cast.enable`），因此模块自己的镜像编码路径保持默认、不被触碰。
- `tools/build-daemon.sh` 刻意把编解码器留在单一源码根里（"这样应用和守护进程就不可能漂移"），且它的类是纯 Java，可以在主机 JVM 上跑测试。

## 9. 运行时状态与扩展方式

### 9.1 运行时状态

所有运行时状态都在设备的 `/data/local/tmp`。文件名集合是助手脚本、守护进程、WebUI 与车机应用之间契约的一部分，因此名字保持稳定：

| 文件 | 写入方 / 作用 |
| --- | --- |
| `okcar-port-switches.conf` | `okcar-port-ctl`：七个开关（`did`、`oui`、`trim`、`linkd`、`session`、`anim`、`wired`）及其持久化状态；首次运行写入默认值 |
| `okcar-port-mode` | `okcar-port-ctl` / WebUI：投屏模式（镜像 / App / CarWith / carplaypp） |
| `okcar-port-app.conf` | 车机画面的共享 App 包名（守护进程必须记住它——重启后它不带参数启动） |
| `okcar-port-app-display.conf`、`okcar-port-app-host.conf` | App UI Activity 选择；App 自己的实例是否允许留在手机上 |
| `okcar-port-video.conf`、`okcar-port-video.applied` | 码率/帧率期望（有线/无线的范围刻意分开：Wi-Fi 链路承受的码率远低于 USB）与实际生效的钳制值——常量见 `daemon/src/com/okcar/link/media/VideoQos.java:32` 与 `:44` |
| `okcar-port-priority.conf` | 投屏 App 的按包 nice 优先级——常量见 `daemon/src/com/okcar/link/port/AppPriorityPolicy.java:24` |
| `okcar-port-keeper.conf` | keeper 保护哪些 App |
| `okcar-port-net.log`、`okcar-port.log` | 网络拉起日志、模块日志 |
| `okcar-wifi-selftest.conf` | 可选的 `ssid=`/`passphrase=` 键值对，Wi-Fi 加入自检读取（主机自检会把它指向别处） |
| `bt.mac.orig` | OUI 改写前的控制器 MAC 备份；`restore-bt` 写回 |
| `okcar-carkit.state`、`okcar-carkit.wanted` | 有线意图与状态（已启动/未启动），含守护进程跨重启保留的 wanted 位 |
| `okcar-g1-orig.conf`、`okcar-usb-quiet` | 原始 gadget 身份存档；init 的 USB 动作已被静默的标记 |
| `okcar-cast.enable` | 本地 cast link 的开启标记（第 8 节） |
| `okcar-apps.tsv`、`okcar-activities.tsv` | 供 WebUI 选择器使用的包/Activity 清单 |
| `okcar-port-lang`、`okcar-port-theme` | WebUI 语言与主题选择 |
| `lockdown-cert.pem`、`lockdown-key.pem` | 有线 lockdown 身份——见 `docs/zh/crypto.md` |
| `okcar-linkd.*` | 监督进程簿记：日志、pid、命令 FIFO `okcar-linkd.in`、supervise/pid/lock 标记 |

`module/uninstall.sh` 只删模块自己写的这些文件（按名字），一个不多删——`/data/local/tmp` 与设备上其他一切共享。

### 9.2 新增一个协议处理器

把协议语义放进一个不使用 `android.` 导入的新包（照抄 `com.okcar.cast` 或 `AirPlayPairing`），传输层由 `com.okcar.link.port` 的某个后端或 `wireless`/`airplay` 的会话类驱动，并通过守护进程的命令通道（`LinkDaemon.handle()`，也就是 FIFO 协议）暴露，而不是随手读文件。在它旁边加一个 `*SelfTest` 类——这是既有协议代码的模式，也是让一次改动不依赖汽车就能被 review 的原因。

### 9.3 新增一个配置开关

把它加进 `module/system/bin/okcar-port-ctl`：`sw_default` 里一个默认值、`sw_set` 里一段处理、`apply_all` 里一个应用动作、分发里一个 verb。状态持久化在 `okcar-port-switches.conf`。默认值要刻意决定：只有 `did` 那一行 `setprop` 默认开启，因为开机路径只允许跑不会破坏早期开机的动作。更长的设置（码率/帧率、优先级）是带 `CONF_PATH` 风格常量加"已应用"影子文件的 conf 文件，这样 UI 能同时显示期望值与实际值（见 `VideoQos.java`）。

### 9.4 新增一个原生助手

照现有静态 bionic 配方（第 10 节）加一个 `native/<name>/build.sh`，在 `tools/package-module.sh` 里把二进制收进 `stage/system/bin/`，运行时一律通过 `PortBootstrap.locate(name)` 找它——绝不直接写 `/system/bin`。保持 `okcar-uinputd` 那样的一行一条 stdin 协议，并遵守打包脚本强制的 `0755`/`0644` 纪律：`0600` 的助手不可执行，`0600` 的 `.idc` 会让被注入的触摸屏被误判为普通指针。

## 10. 构建与打包

| 步骤 | 需要什么 / 做什么 |
| --- | --- |
| `tools/build-daemon.sh` | 把整个 `daemon/src` 编译成 `build/okcar-link.dex`。需要 AOSP 树（`TREE`）提供平台 framework jar（含隐藏 API jar），以及 JDK 17：`javac --release 11`，然后 `d8 --min-api 29`。发布目标刻意保守：更新的 class file 版本曾在现场设备上让 ART 崩溃 |
| `native/uinputd/build.sh`、`native/ffs-usbmux/build.sh` | 用同一棵 AOSP 树（本仓库不带 NDK）装配静态 bionic arm64 程序：AOSP clang、bionic 头文件、`crtbegin_static`/`libc.a`/`crtend`、`-target aarch64-linux-android21 -static -nostdinc -nostdlib`。`okcar-ffs-usbmux` 额外链接树里的静态 BoringSSL——lockdown 握手需要 TLS |
| `tools/package-module.sh` | 跑三个构建、打包 `module/`、拷入 dex 与两个原生二进制、把构建者提供的 `lockdown/lockdown-{cert,key}.pem` 复制进 `payload/`（见 `docs/zh/crypto.md`）、写入 `webroot/variant`、执行权限纪律、对每个暂存文件跑隐私泄漏闸门，最后产出发布 zip |
| `module/sepolicy.rule` | 必须随zip发布：没有它框架 supplicant 无法应答我们的 p2p0 控制套接字，App→守护进程 FIFO 命令会静默失败 |

为什么静态链接：模块必须在任何 Android 11+ arm64 车机手机上运行，而不必自带或安装运行时；动态依赖 vendor 库，恰恰是另一个 ROM 没有的东西。

## 11. 说明与坦白的空白

- Wi-Fi 自检 conf 与开关状态机可以通过 `OKCAR_TMP` / `OKCAR_BT_MAC` 风格的环境变量覆盖在主机上驱动；理解生产流程不需要它们。
- 第 7.2 节的 AirPlay 接收器路径是按共享源码描述的；在某个具体部署中由哪个入口启用它，这里换成了直说而非测量。
- 第 7.2 节的 AirPlay 接收器路径是按共享源码描述的；逐变体的精确接线是本文唯一以"不确定"而非"实测"表述的地方。
