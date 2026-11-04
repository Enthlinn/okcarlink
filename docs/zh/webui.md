# WebUI 与控制界面

三个控制界面坐落在一对模块侧入口之上：
`okcar-port-ctl` 掌管配置与特权副作用（蓝牙开关、
USB gadget 重绑、cgroup 钉定、授权授予），
`okcar-linkd` 掌管守护进程及其 `start` / `stop` /
`status` / `send` / `supervise` / `restart` 动词。
反引号里的路径是仓库相对路径；以斜杠开头的路径在设
备上，位于 `/data/local/tmp` 之下。WebUI 是 CLI 的
一次呈现，而不是第二份实现。

> **命名。** 内部运行时名称（`okcar-port-ctl`、
> `okcar-linkd`、`okcar-port-*` 文件）故意与内部
> 工具保持一致、不做改动；只有模块 id 现在是
> `okcarlink`。

## 1. 三个控制界面

| 界面 | 由谁提供 | 传输方式 | 面向用户 |
| --- | --- | --- | --- |
| 管理器 WebUI | `module/webroot/`（`index.html`、`app.js`） | KernelSU/Magisk 管理器的 WebView 桥 | KernelSU 用户（推荐） |
| 本地面板 | `okcar-port-panel`（busybox `httpd` 监听 `127.0.0.1:8088`） | `module/webroot/cgi-bin/ctl` + `ksu-bridge.js` | Magisk 用户，或任何不带管理器 WebUI 的安装 |
| CLI | `okcar-port-ctl`、`okcar-linkd` | 普通 root shell | 运维者、脚本、bug 报告 |

**管理器 WebUI。** root 管理器在一个 WebView 里加
载 `module/webroot/index.html`，其 origin 是模块目
录，并注入一个名为 `kernelsu` 的桥，暴露 `exec`——
这是页面运行特权命令的唯一途径，因此在没有本地面板
运行的纯浏览器里它是惰性的。骨架承载状态行、类别列
表与详情页、模式标签页、选择器与日志面板。

**本地面板。** `okcar-port-panel start` 运行
`busybox httpd -f -p 127.0.0.1:8088 -h <module>/webroot`
（`PORT` / `OKCAR_PANEL_PORT` 可覆盖端口，地址始终
是回环）。URL 是
`http://127.0.0.1:8088/?token=<token>`——16 个随机
十六进制字节，存放在
`/data/local/tmp/okcar-port-panel.token`，由
`okcar-port-panel url` 打印。提供的是同一个
`webroot`，所以两个界面只在传输方式上不同，而
`module/action.sh` 会打开该 URL 并把它作为一条通知
发出来，免得 Magisk 用户根本看不到它。

**`ksu-bridge.js`** 通过 CGI 让页面在纯浏览器里对
着面板工作：它用三参数的
`kernelsu.exec(command, optionsJson, callbackName)`
实现这条路径，命令变成
`fetch('cgi-bin/ctl?do=exec&token=...&b64=<base64url(cmd)>')`，结果抵达
`window[callbackName](errno, stdout, stderr)`。
base64url 让 `+`、`/`、`=` 不被破坏，载荷是 UTF-8，所以中文命令也能存活；而这个垫片只在没有原生桥时才安装自己：覆盖原生桥会把命令导向一条在
WebView 里并不存在的 HTTP 路径。

## 2. `okcar-port-ctl`——特权 CLI

一个脚本，每个特权步骤的唯一 root 入口，位于
`module/system/bin/okcar-port-ctl` （叠加安装下也
是 `/system/bin/okcar-port-ctl`）。

### 2.1 动词

| 动词 | 作用 |
| --- | --- |
| `switches` / `switch-state <key>` / `switch <key> on｜off` | 以 `<key> <state> default=<state> - description` 打印全部开关，或设置七个开关之一并应用它 |
| `apply [boot｜early｜manual]`、`wired-boot` | 在一个阶段（2.3）应用这些开关；分离的有线启动自动项 |
| `caps` / `status` / `where` / `platform` | USB 与能力事实（管理器、configfs、UDC、Type-C 节点、uinput、functionfs、内核模块）、状态头部、模块路径、叠加状态、Android 版本、root 提供方 |
| `oui` / `spoof-bt` / `restore-bt` / `bt-did` / `bt-name` / `bt-sdp` | WebUI 仍在使用的历史蓝牙动词：MAC OUI、SDP Device ID、设备名、SDP 精简与恢复 |
| `wm <WxH>` / `grant` / `net-recover` / `stall-clear` | 显示尺寸提示、授予守护进程的运行时权限、重启 Wi-Fi（始终分离）、清理一次长时间的停滞窗口 |

未知动词会打印脚本的用法块——在手机上就是一份可用的
参考。

### 2.2 七个开关

状态位于
`/data/local/tmp/okcar-port-switches.conf`，每个开关一行
`key=value`，首次读取时写入，好让没有任何默认值是隐藏的内存值，并以固定键序在一个
`mkdir` 锁之下原子重写——启动钩子、WebUI 与手动命令
都会并发写入。

| 开关 | 默认 | 效果与安全性 |
| --- | --- | --- |
| `did` | 开 | 把 SDP Device ID 厂商通告为 Apple（`bluetooth.device_id.vendor_id=0x004C`）：一次 `setprop`，不走射频周期，是唯一默认开启的蓝牙动作 |
| `oui` | 关 | 把蓝牙 MAC OUI 段改写为 Apple。**会改 MAC**——每次配对都失效，控制器文件在蓝牙初始化时由 NV 重建，所以每次启动都必须重新应用 |
| `trim` | 关 | 关掉五条未用的蓝牙 profile 记录，让 SDP 响应塞得进车机的发现数据库（约 1600 B）。只在配对阶段有效 |
| `linkd` | 开 | 守护进程；关会停止它并压制启动时的启动动作 |
| `session` | 开 | 无线会话；关让进程继续跑但不再监听 |
| `anim` | 关 | 镜像会话是否强制动画缩放为 0；关则立即恢复保存下来的缩放值 |
| `wired` | 关 | 这台手机通过 USB 重新枚举为 Apple 设备（`05ac:12a8`，USBMUX + NCM）。**占掉整个 USB 端口**——USB adb 停止，5555 上的 TCP adb 保留。唯一重新配置 USB 的开关 |

默认值遵循一条逻辑：`did` 是车机用来挑选手机的一个
可逆属性，所以它开着；`oui` 与 `wired` 各自都要用
户付出沉重代价，所以它们关着并受确认保护；`anim`
在某次默认开启的值于一个面板没有对应行的构建上静默
生效之后，改到了关。

### 2.3 应用阶段

| 阶段 | 应用 | 调用方 |
| --- | --- | --- |
| `early` | 只有 `did` | `module/post-fs-data.sh`，在蓝牙栈初始化之前 |
| `boot` | `did`、`oui`、`trim`，只在有变化时才重新加载射频 | `module/service.sh` |
| `manual` | 全部，包括 `wired` | WebUI、`action.sh`、运维者 |

裸 `apply` 意味着 `manual`，包含 wired 开关：启动
钩子总是点名自己的阶段。

## 3. `okcar-linkd`——守护进程接口

启动器与监督进程以 root 在 `app_process` 下运行唯
一的 dex `payload/okcar-link.dex`，把守护进程钉在一个
system cgroup 上让冻结器杀不掉它，创建命令 FIFO，并消解模式漂移。
`send` 先持久化再说话：每个选择在命令通过 FIFO 之
前先写进它自己的 conf 文件（启动钩子以无参数方式启
动守护进程，所以下一次启动必须重新挂上同一个选择），
而 FIFO 以读写方式打开，因为普通打开会在没有读者时
永远阻塞。

| `send` 命令 | 含义 |
| --- | --- |
| `mode mirror` / `desktop` | 模式与归途（WebUI 写模式文件并重启——见 5.7） |
| `app <pkg>` / `app-ui <pkg>/<activity>` | 要投屏的应用，或它的 activity（`-` 清除） |
| `keeper on｜off` / `app-host on｜off` | 保活与 app-host 标志 |
| `size auto｜WxH` / `codec auto｜h264｜hevc` | 钉住触摸空间（修掉 `MISMATCH` / `touch=-1`）/ 编码器偏好 |
| `place` / `display up｜down` | 重试放置投屏 surface；预建或释放虚拟显示 |
| `start` / `stop` / `status` / `apps` / `stall-clear` | 会话控制、快照刷新、应用列表导出、清理停滞窗口 |

## 4. `webroot/cgi-bin/ctl`——面板 CGI

CGI 助手由 busybox `httpd` 作为 `/cgi-bin/ctl` 执
行，它是面板唯一的特权路径：每个请求都重新对着
`/data/local/tmp/okcar-port-panel.token` 校验
token，否则回答 `bad token`，显式设置 `PATH`，并从自身位置解析模块目录。

| 查询 | 行为 |
| --- | --- |
| `do=exec&b64=...` | 解码 base64url 命令，用 `sh -c` 运行并捕获 stdout/stderr，返回 base64url 编码的 `{"errno","stdout","stderr"}` |
| `do=status` | CGI 以谁运行、守护进程状态头部、开关、模式、QoS conf、优先级 conf |
| `do=switch&key=...&value=...` | 运行 `okcar-port-ctl switch <key> <value>` |
| `do=mode` | 写模式文件、重新启用 session 开关、重启守护进程 |
| `do=qos` | 重写 `okcar-port-video.conf`（每种传输的比特率/fps、延迟、渲染缩放） |
| `do=apps` / `do=app` | 读取导出的应用列表 / 设置要投屏的应用 |

`exec` 解码器手工修补 base64url 的填充（先用 `tr`
把 `-_` 换成 `+/`，再补齐），并且从不把 `+` 替换成
空格：`+` 是合法 base64 字符，那个替换会悄悄地毁掉
命令。

## 5. 管理器 WebUI（`webroot/app.js`）

`app.js`（约 2360 行）是一个单文件页面应用。修改它
时要保留的是它开头的那几条规则：读取字段之前先把每
一种桥结果形状归一化（管理器返回
`{errno, stdout, stderr}`、一个纯字符串或
`{code, out, err}`）；绝不用裸名调用模块助手，因为
`system` 叠加并非在每个安装上都挂载；每次状态 tick
只保留一次廉价的 shell 探测；在 `document` 上委托
点击并在 `finally` 里重新启用每个按钮；把 variant
当数据，而不是第二个页面。

**5.1 解析模块。** `MODULE_CANDIDATES` 枚举
`/data/adb/modules`、 `/data/adb/modules_update`
与旧的 `okcar-port` / `okcar-port-lite` id；
`resolvePaths()` 跑一次探测，挑出那个含有可执行
`okcar-linkd` 的候选，把 `webroot/variant` 读进
`lite` 标志，并返回 `dir` / `linkd` / `ctl` /
`carkit`——叠加挂载时优先 `/system/bin/okcar-linkd`。

**5.2 传输。** 页面探测一次 exec 协议然后坚持它：
KernelSU 3.x 把回调*名字*当字符串传，老管理器传一
个函数，混用两者会卡住桥。`run()` 让每条命令与一个
截止时间赛跑（探测 4 s，动作 40 s）；连续两次探测超时会升起
"桥静默"横幅，点名三种补救：从管理器打开页面、点守
护进程那一行的 test-exec-bridge 动作、或在终端里跑
`su -c 'id'`。

**5.3 开关/状态模型。** `CATS` 每个子系统一条
（daemon、session、display、target、 did、oui、
anim、trim、wired、carplaypp），带名称、开关键、面向操作员的警告、健康度与原始值，以及可展开成具体
shell 命令的动作按钮。一行渲染一个开关与一个彩色值；
点它打开二级页面——一个子页面，不是弹窗。危险开关
（`oui`）先确认，未知的开关状态保持可用并带
tooltip，而不是看起来坏了。

**5.4 状态探测。** `probeCommand()` 构造一整个
shell 行：先发一条新的 `status` （好让快照永不陈旧
），然后状态文件、日志尾部、开关、蓝牙与 USB/wired
事实、caps、 platform、模式文件、守护进程自己的
`--mode`、主题文件、已应用的 QoS 值、
`persist.okcar.max_fps` 上限与一个 `probe_end=1`
哨兵。`mode_running` 是守护进程自己的 `--mode`；
`mode_file` 是意图——文件是意图，进程是事实。
"Accessory connected"需要配件字段*并且*一条就绪的
AirPlay 通道，因为配件字段在一次掉线之后仍然存活。
缺少哨兵标记探测被截断；整体失败不会伪造默认开关状
态。

**5.5 运行时 i18n。**
`/data/local/tmp/okcar-port-lang`（默认 `zh`）驱动一个头部
`<select>`，在任何桥检查之前读取，好让选择器在纯浏览器里也能用。静态文本住在
`data-en` / `data-zh` 属性里（placeholder 在
`data-en-placeholder` / `data-zh-placeholder`），
`setLang` 写文件、设置
`document.documentElement.lang` 并立即重渲染一切，
而不是等下一个 5 s tick。中文表是权威——英文表由它
反转生成，所以两者不会漂移；长的动态值（日志、包名、
属性 dump）原样穿过、不翻译。

**5.6 总开关、同生共死绑定、看门狗。** 总开关
（`#sw-master`）在把它们全部关掉之前，先把哪些开关开着记进
`/data/local/tmp/okcar-port-master-memory.conf`，并在打开时从该文件恢复（回落到
`linkd` + `session` + `did`）——是一个文件，不是内
存，因为页面会关闭、手机会重启。`linkd` 与
`session` 双向绑定，同起同落。看门狗每 5 s 跑一次，
只做恰好两件纠正，每键每 60 s 限一次尝试：`linkd`
开关开着而进程不见了就启动守护进程；属性不再是
`0x4c` 时重新应用 `did`。它绝不在监听消失时重启守护进程：一次活的投屏会把状态文件改写成一段不再以
`listening as` 开头的描述，而这个误读曾经杀掉过活
的会话。

**5.7 模式。** 四个模式标签页：`mirror`（默认）、
`app`、`carplaypp`（仅在该模块已安装时）与
`carwith`（仅在该应用存在时）。因为模式是守护进程
的*启动*参数，在启动时从
`/data/local/tmp/okcar-port-mode` 读取，所以每次改
模式都要先写模式文件**并且** 重启守护进程——只写文
件，就是一个面板声称在投应用而车机显示镜像的原因。
一个确认对话框说明投屏会断，`mirror` 还会清掉记住
的应用，应用投屏通过选择器挑应用与 activity，这些内容在启动时从
conf 文件重新填充（默认导航匹配：百度、高德、腾讯
）。

**5.8 QoS。** 滑块与数字框行，双向绑定并持久化到
`/data/local/tmp/okcar-port-video.conf`：
`wireless_bitrate` 1000..30000、 `wired_bitrate`
2000..100000 kbps、`wireless_fps` / `wired_fps`
15..60、 `video_delay_ms` 0..2000 步长 10（默认 0——
0 意味着"无延迟"，不是"不覆盖"）与
`app_render_scale` 25..100 步长 5（默认 100）。钳制与守护进程完全一致：
0 保持 0，其他值在范围内吸附到步长。行下方的注释显示来自
`/data/local/tmp/okcar-port-video.applied` 的*已应
用*值，所以"保存了但会话仍用旧值"可以与"被钳制"和"
被上限压住"区分开；已应用的 fps 很低，意味着
`persist.okcar.max_fps` 上限、车机的
`/info maxFPS`，或编码器。

**5.9 优先级。**
`/data/local/tmp/okcar-port-priority.conf` 每行一
个包名；守护进程每 3 s 读一次，并把这些进程 renice
进 top-app cpuset。

## 6. 开关背后的运行时文件

| 文件 | 控制什么（写入者） |
| --- | --- |
| `okcar-port-mode` | 守护进程启动模式；文件缺失时由守护进程写默认值（CGI、`app.js`、`okcar-linkd start`） |
| `okcar-port-switches.conf` | 七个开关（`okcar-port-ctl`） |
| `okcar-port-video.conf` / `okcar-port-video.applied` | 请求的 QoS / 当前会话实际应用的（CGI、守护进程） |
| `okcar-port-app.conf`、`okcar-port-app-display.conf`、`okcar-port-app-host.conf` | 要投屏的应用、要放置的 activity、app-host 标志（`okcar-linkd send`、CGI、`app.js`） |
| `okcar-port-priority.conf` | 优先级包名列表（WebUI） |
| `okcar-linkd.status` / `okcar-linkd.in` | WebUI 解析的状态快照 / 命令 FIFO，`0666` 好让普通应用也能写（守护进程） |
| `okcar-linkd.log` / `okcar-port.log` | 守护进程启动器与监督进程日志 / 启动钩子日志（`okcar-port-check` tail 后者） |
| `okcar-port-lang` / `okcar-port-theme` / `okcar-port-master-memory.conf` | UI 语言 / 主题 / 总开关恢复哪些开关（`app.js`） |
| `okcar-port-panel.token` / `okcar-port-panel.url` | 面板 token / URL（`okcar-port-panel`） |
| `okcar-port-apps.tsv` / `okcar-port-activities.tsv` | 导出的应用与 activity 列表（守护进程、`send apps`） |
| `bt.mac.orig` / `okcar-animations.saved` | OUI 变更前的原始蓝牙 MAC / 镜像会话后要恢复的动画缩放 |
| `okcar-carkit.wanted` | 请求的有线链路状态；守护进程每 2 s 轮询一次（`okcar-port-ctl wired`） |

WebUI 各行受支持的手动等价写法：

```
# 模式按钮做什么
echo mirror > /data/local/tmp/okcar-port-mode
okcar-port-ctl switch session on && okcar-linkd restart

# 投一个应用，然后压住无线比特率
echo com.example.app > /data/local/tmp/okcar-port-app.conf
okcar-linkd send app com.example.app
printf 'wireless_bitrate=8000' >> /data/local/tmp/okcar-port-video.conf
```

不要直接写 `okcar-linkd.in`：普通的 shell 重定向会
阻塞到读者出现，可能挂住调用方——请用
`okcar-linkd send`。

## 7. 从头加一个新开关

1. **决定值住在哪里。** 如果守护进程每个会话都必须对
   它行动，给它一个启动时读取的 conf 文件（或一个
   `send` 动词）并带一个站得住脚的默认值——启动钩子以
   无参数方式启动守护进程，所以任何要记住的东西都必须
   在磁盘上。纯属性开关也仍然住在
   `okcar-port-switches.conf` 里。
2. **`okcar-port-ctl`**：把键加进默认迁移列表、加进原子写入的固定重写顺序、加进
   `show_switches` 描述；决定哪个 `apply` 阶段应用它
   （或都不应用）；加上它的警告行。
3. **`webroot/cgi-bin/ctl`**：普通开关没什么要做的——
   `do=switch` 转发给 CLI， `do=status` 打印整个
   switches 文件，所以新键会自动出现在两个界面里。只
   有开关需要额外参数时才加一个动作。
4. **`app.js`**：把键加进探测的开关默认值、加一条
   `CATS` 条目（或设置它的 `switchKey`）并带警告与动作、加上它的标签行，并
   且——如果开关需要*实时*回读—— 扩展 `probeCommand`。总开关通过把键加进它的键列表加入。
5. **变体门控**：如果 lite 构建应该隐藏这一行，把类别
   条目标记成在 `webroot/variant` 为 `lite` 时被丢掉。
6. **两个界面都测**——从管理器（桥路径）和从面板 URL
   （CGI 路径）：一条命令在一个里行、另一个里不行，通
   常是 base64url 或回调名字的细节。
7. **值得照抄的先例**：`anim` 开关（每个开关都需要一
   个面板行，否则它的默认值必须是惰性的）；`oui` 的确
   认；`did` 的看门狗纠正。

两个诚实的限制：守护进程的自检类如何运行在这棵树里
没有文档（见 `docs/en/building.md`），而有线那一行报告的是
USB 栈，不是车机屏幕。
