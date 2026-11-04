# 模块目录结构

可安装单元是一个 KernelSU/Magisk 模块目录，由
`module/` 暂存并打包为 `build/okcarlink-1.0.0.zip`。该目录包含四类文件：
root 管理器自己执行的启动钩子与元数据、管理器叠加
模块之后才可达的少量 `system/bin` 助手、作为唯一用
户界面的 webroot，以及承载 dex 与 lockdown 身份的
payload 目录。

> **命名。** 模块 id 现在是 `okcarlink`，但设备侧运行时名
> 称—— `okcar-port-ctl`、`okcar-linkd`、
> `/data/local/tmp` 下的 `okcar-port-*` 文件——都
> 是故意与内部工具保持一致、不做改动的。WebUI 仍
> 旧探测旧安装 id `okcar-port` 与
> `okcar-port-lite`，以便既有安装继续可用。

## 1. 逐文件说明

| 路径 | 用途 | 何时运行 |
| --- | --- | --- |
| `module/module.prop` | 模块身份与描述 | 安装与列表时由管理器读取 |
| `module/customize.sh` | 安装钩子：打印此构建是什么 | 刷入 zip 时执行一次 |
| `module/post-fs-data.sh` | 早期启动钩子：准备运行时目录、设置 SDP Device ID | 每次启动，在 `late_start` 之前 |
| `module/service.sh` | 晚期启动钩子：蓝牙准备、守护进程启动、面板启动 | 每次启动，在 `late_start` 时 |
| `module/uninstall.sh` | 交还 USB 并清除模块的运行时状态 | 管理器移除模块时 |
| `module/sepolicy.rule` | 守护进程需要的三项 SELinux 授权 | 管理器安装策略时执行一次 |
| `module/action.sh` | 模块的动作按钮 | 用户在 Magisk 中点按它时 |
| `module/system/bin/okcar-linkd` | 守护进程启动器与监督进程 | 由 `service.sh` 启动；也响应来自 WebUI 的 `start`/`stop`/`status`/`send` |
| `module/system/bin/okcar-port-ctl` | 用于开关、蓝牙与 USB 操作的特权 CLI | 由 `post-fs-data.sh`、`service.sh`、CGI、WebUI 与运维者调用 |
| `module/system/bin/okcar-port-check` | 诊断入口：打印模块位置及其状态 | 按需 |
| `module/system/bin/okcar-port-panel` | `127.0.0.1:8088` 上的本地 busybox `httpd` 面板 | 由 `service.sh` 与 `action.sh` 启动 |
| `module/system/bin/okcar-carkit` | 有线链路助手：gadget 重建、lockdown 身份、FunctionFS 服务 | 由 wired 开关调用 |
| `module/system/bin/okcar-role` | 以 1 Hz 保持 Type-C/USB 角色固定 | 由 wired 开关启动 |
| `module/system/bin/okcar-carwith-svc` | 给 CarWith 桥接服务单独的 SELinux type | 由 `post-fs-data.sh` 执行 `setup`；之后是分离的看门狗 |
| `module/system/usr/idc/okcar-uinput-touch.idc` | 把虚拟触摸设备归类为触摸屏 | 由 `service.sh` 安装进 `/data/system/devices/idc` |
| `module/webroot/` | WebUI（`index.html`、`app.js`、`ksu-bridge.js`、`style.css`、`variant`、`cgi-bin/ctl`） | 由管理器或面板提供 |
| `module/panel/` | 面向 `-h module-dir` 面板布局的重定向页与 CGI 转发器 | 由面板提供 |
| `module/payload/` | dex 与 lockdown 密钥对，由打包脚本加入 | 由 `okcar-linkd` / `okcar-carkit` 读取 |

`module/README.md` 是一份中英双语短说明：本模块是什么、文档在哪里。
打包脚本在它存在时把它一并打进 zip。

## 2. 启动序列

### 2.1 `post-fs-data.sh`——最早的钩子

这个钩子同步运行并阻塞启动的其余部分，因此它几乎不
做重活：它创建 `/data/local/tmp` 并把它重新
`chmod` 为 `755`（内核每次启动都会以
`0771 shell:shell` 重建该目录，而 conf、FIFO 与状
态文件都放在那里），记录它解析到的
`okcar-port-ctl` 是哪一份、模块的 `system` 分区是
否已被叠加，安装 CarWith 桥接服务的 SELinux type，并运行
`okcar-port-ctl apply early`——它只设置
`bluetooth.device_id.vendor_id=0x004C`（Apple），让蓝牙栈在初始化时正确构建
SDP Device ID 记录。这里不能放别的东西：一次裸
`apply` 还会在 `boot_completed` 之前执行 wired 开
关的 gadget 重绑，代价是八次 `EBUSY` 绑定尝试、USB
端口留下一个脏的 Android 身份，然后让一次重试卡死
在 configfs 的 `D` 状态——症状就是"插上没反应，插很
多次也还是连不上"。

### 2.2 `service.sh`——晚期启动

在 `late_start`，按顺序：

1. 只在特殊的 `a13` 构建上安装
   `payload/bt-proxy.name` 中指名的蓝牙代理配套应用。
   主线发布不带这个文件，所以这段代码是惰性的。
2. 把 `.idc` 文件安装进 `/data/system/devices/idc`，因为
   `/system/usr/idc` 只在管理器真正叠加了模块时才存在——
   而没有这个归类，框架就不把 uinput 虚拟触摸设备当成
   触摸屏。
3. 等待 `sys.boot_completed`（最长 180 s），然后等待蓝牙栈
   （`bluetooth_address` 出现加上一个存活的蓝牙进程，最
   长 120 s）。更早启动会让 RFCOMM 监听连续几分钟不发
   布。
4. 运行 `okcar-port-ctl apply boot`：`did`、`oui` 与
   `trim`，只在确实有变化时才重新加载蓝牙射频。它绝不是一次裸
   `apply`——裸 apply 会连 wired 开关一起应用，并在启
   动时重绑 USB gadget，其后果是 2026 年 10 月的重启
   循环（重启 → USB 重绑 → adb 与网络都没了 → 重启）。
5. 读取退出标记 `disable-linkd` 与 `linkd` 开关，任一
   表示本次启动守护进程应保持静默就退出；然后如果
   payload dex 缺失也退出。
6. 分离地启动守护进程：`setsid "$LINKD" supervise &`。模式不从命令行传入；它由
   `okcar-linkd` 从 `/data/local/tmp/okcar-port-mode`
   读取，文件缺失时由 `okcar-linkd` 写入默认值。此前
   硬编码这个值会覆盖用户自己选择的投屏应用。
7. 启动本地面板（`127.0.0.1:8088`）。如果没有提供
   `httpd` 的 busybox，它会报错，而启动继续。
8. 如果 session 开关是关的，就给守护进程发 `stop`，让它不监听，并在
   3 s 后记录守护进程状态的开头几行。
9. 最后分离地 spawn 有线启动自动项
   （`setsid sh -c "'okcar-port-ctl' wired-boot" &`）——
   刻意永不放在启动关键路径上，并自带一道保险：连续两
   次自动启动之后再次重启就永久禁用它，这是在 2026 年
   10 月的重启循环与相反故障（启动从不触碰 USB，插很多次线也连不上）之间的平衡。

### 2.3 守护进程的启动

`okcar-linkd supervise` 从自身位置解析模块目录（在
确认 payload 存在之后，对四种安装布局做回退），填
充 ART 环境——在 Android 13+ 上 bootstrap 与
dexeoat 的 classpath 都从一个存活的 zygote 采样，
因为 ART 在 classpath 为空时会 abort——把守护进程钉
在某个 system cgroup 上，让冻结器无法在熄屏时杀掉
它，创建命令 FIFO，并以 root 身份在 `app_process`
内 exec `com.okcar.link.daemon.LinkDaemon`。然后它每
2 s 检查一次守护进程：守护进程缺席约 12 s 就重启，
重新钉 cgroup，并消解 `okcar-port-mode` 与守护进程
自身 `--mode` 之间的模式漂移（连续两次漂移读数就重
启守护进程）。同一时刻只有一个监督进程存在，靠一个
原子 `mkdir` 锁保证，其 pid 文件是唯一的活动性来源。

### 2.4 `uninstall.sh`

卸载模块不会清空 `/data/local/tmp`，所以重装会继承上一个用户的选
择——实测故障是一台 `okcar-port-mode` 仍然写着 `app`、而默认值已经改成
`mirror` 的机器。顺序很重要，它就是整个脚本的全部
要点：

1. **先交还 USB**：`okcar-carkit stop`（解绑我们的
   gadget，恢复框架的 USB 身份，让 init 重建 adb），然后
   `okcar-port-ctl switch wired off` 与
   `switch linkd off`。
2. **再杀进程**：守护进程、FunctionFS 服务与 carkit
   guard，每一个都包在 `timeout` 里。USB 栈里的一个异
   常会让 configfs 读取永远阻塞——实测是 guard 的
   `cat` 卡在 `D` 状态、管理器的转圈永不结束——所以一
   次没有边界的 `pkill` 会挂住卸载。
3. **再清除模块状态**，按前缀限定为 `okcar-port-*` 与
   `okcar-*`，这样 `/data/local/tmp` 里别的模块的文件
   永不被碰：开关文件、模式文件、carkit 状态与 guard
   pid、USB 身份归档、日志、蓝牙原始值、动画归档，以
   及一次对 `okcar-port-*.conf` 的清扫——包括
   `okcar-port-video.applied`，它必须被清掉，否则重装后面板会显示上一个使用者留下的已应用值。

### 2.5 `customize.sh`

安装钩子只设置 `SKIPUNZIP=0` 并打印此构建是什么：
一个可能卡顿、丢帧或崩溃的无线 CarPlay 测试构建；
没有 APK，只有 payload dex 加启动器；它只做一件事——
把手机画面镜像到头机，车机侧由其自带的互联 App 负责
——不含任何调试通道；控制与日志都在管理器 WebUI 里；
蓝牙变更是带显示恢复功能的手动开关；bug 报告应给出机型、ROM
与 Android 版本，外加 `/data/local/tmp` 下的两个日志。

### 2.6 `sepolicy.rule`

三项互不相关的授权，用空格分隔的语句语法书写：文件
先按逗号切分，再剥离注释，所以注释里的一个逗号会让
整个文件失败。这些授权是：(1) 一条从
`wpa_supplicant` 到模块的 dgram/stream 套接字通道，
让框架自己的 supplicant 能应答一个位于框架域之外的
`p2p0` 客户端；(2) 普通应用对 `/data/local/tmp` 的
文件与 FIFO 访问，让车机端应用能把自己的 `app`/
`app-ui` 命令追加进守护进程的 FIFO 而不吃 `EACCES`；
(3) 一个 CarWith 桥接服务的私有 service type，让
`getService('okcar-carwith')` 能解析，而不是回落到
默认 service type。声明的取舍：这些规则让任何应用都能读写
`/data/local/tmp` 下的内容——这就是一个 root 模块通
过 `/data/local/tmp` 上的文件与普通应用对话所要付
的代价。

## 3. 助手参考

| 助手 | 行数（约） | 职责 |
| --- | --- | --- |
| `okcar-linkd` | 780 | 以 root 在 `app_process` 下运行 dex；`start`/`stop`/`status`/`send`/`supervise`/`restart`；cgroup 钉定、FIFO 传输、模式消解、动画处理 |
| `okcar-port-ctl` | 870 | 每个特权步骤：开关状态、分阶段 apply、蓝牙原语、有线 gadget 命令、能力报告、授权授予 |
| `okcar-port-check` | 82 | 出问题时该跑的那一条命令：解析模块目录（含 `modules_update` 情形）、模块从未启动过时告警、tail 启动钩子日志、打印守护进程与开关状态 |
| `okcar-port-panel` | 64 | 停止与启动回环 busybox `httpd`，用 token 保护它，打印 URL 并发一条通知 |
| `okcar-carkit` | 1700 | 有线助手：归档框架 USB 身份、构建 Apple gadget、把 lockdown 密钥对装进 `/data/local/tmp`、运行 FunctionFS 服务，并报告哪个 gadget 自称是 Apple |
| `okcar-role` | 92 | 通过写 Type-C data role，以 1 Hz 把这台手机的 USB 角色钉住，让 C-to-C 线无法来回翻转角色 |
| `okcar-carwith-svc` | 212 | 把一份打了标签的实时 `plat_service_contexts` 副本挂载到原件之上并重新加载策略，让桥接服务 type 能解析；servicemanager 被替换时看门狗卸载它 |
| `okcar-uinputd`（`native/uinputd`） | — | 静态 Bionic 助手，持有虚拟输入设备并注入触摸与按键事件 |
| `okcar-ffs-usbmux`（`native/ffs-usbmux`） | — | 静态助手，为有线链路运行 FunctionFS gadget、usbmux 服务与 lockdown 握手 |

## 4. 开关与安全

七个开关全部是特权开关：每一个都需要 root，因为写
`/data/local/tmp` 状态不是动作—— 动作是一次
`setprop`、一次 USB gadget 重绑、一次 cgroup 写入
或一次进程重启。状态存在
`/data/local/tmp/okcar-port-switches.conf` 并且在
重启后存活；带着关闭的开关重启，只是不再重新应用该
开关的效果。

| 开关 | 默认 | 需要 root，因为它…… | 会碰 USB？ | 重启时 |
| --- | --- | --- | --- | --- |
| `did` | 开 | 写一个系统属性 | 否 | 由 `service.sh` 重新应用（`apply boot`） |
| `oui` | 关 | 改蓝牙地址并重载射频 | 否 | 由 `service.sh` 重新应用，因为控制器文件在蓝牙初始化时由 NV 重建 |
| `trim` | 关 | 关掉若干蓝牙 profile 并重载射频 | 否 | 由 `service.sh` 重新应用；配对完成后可以关掉 |
| `linkd` | 开 | 启动或停止守护进程 | 否 | 关会压制启动时的启动动作；WebUI 的开关无需重启即可启动它 |
| `session` | 开 | 在运行中的守护进程内部停掉 RFCOMM 监听 | 否 | 在守护进程启动时读取 |
| `anim` | 关 | 在投屏期间强制动画缩放，之后恢复 | 否 | 关是惰性的；开会在下一次投屏会话期间应用 |
| `wired` | 关 | 把整个 USB gadget 重绑成 Apple 设备 | **是** | 绝不在启动时应用；只有分离的 wired-boot 自动项可能，且它在两次"自动启动后又重启"的循环后自行禁用 |

有两个安全行为属于开关层，而不是某一个开关：守护进
程与会话被绑在一起（`linkd` 与 `session` 开关同起
同落），并且 WebUI 里的总开关在把其他开关全部关掉
之前，会记住此前哪些开关是开的，写进一个文件，好让
它之后或重启之后能恢复同一组。

## 5. 本发布的范围

`webroot/variant` 标记（`lite`）是打包脚本写入的印记，WebUI 读取它来隐藏那些
配套应用在此不存在的行；同一个标记还在模式文件缺失时决定守护进程的默认模式：`mirror`。
模块目录里没有其他任何东西以它为条件。
