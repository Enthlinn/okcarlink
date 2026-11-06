# 从源码构建

OkCarLink 1.0.0 用三个 shell 脚本编译成一个可刷入的 zip：`build/okcarlink-1.0.0.zip`。模块 id 是
`okcarlink`，`module/module.prop` 里是 `version=1.0.0` / `versionCode=100`。没有 Gradle 工程，也没有
CMake 工程：payload 由 `javac` 加 `d8` 产出（`tools/build-daemon.sh`），两个原生助手是用 AOSP 树手写的
配方（`native/uinputd/build.sh`、`native/ffs-usbmux/build.sh`）；`tools/package-module.sh` 把三者跑一遍，
再把模块目录压成 zip。本文所有结论都来自第 6 节的来源；第 5 节说明哪些步骤真正执行过、哪些**未经验证**。

## 0. 构建产物

| 产物 | 由谁产出 | 进入 zip 的位置 |
| --- | --- | --- |
| `build/okcar-uinputd` | `native/uinputd/build.sh` | `system/bin/okcar-uinputd` |
| `build/okcar-ffs-usbmux` | `native/ffs-usbmux/build.sh` | `system/bin/okcar-ffs-usbmux` |
| `build/okcar-link.dex` | `tools/build-daemon.sh` | `payload/okcar-link.dex` |
| `build/okcarlink-1.0.0.zip` | `tools/package-module.sh` | ——（你刷入的那个文件） |

zip 是你唯一要安装的东西；另外三个是打包器复制到位的中间产物。中间产物缺失不是致命错误——打包器打印
一行 `note: ... missing`，仍然产出 zip，只是对应能力在运行期被关掉（第 3 节）。

运行期名称沿用内部工具包，未改：`okcar-linkd`（以 root 在 `app_process` 下 exec
`com.okcar.link.daemon.LinkDaemon` 的监督进程）、`okcar-port-ctl`、`okcar-port-check`、
`okcar-port-panel`、`okcar-carkit`、`okcar-port-role`、`okcar-carwith-svc`、`okcar-uinputd`、
`okcar-ffs-usbmux`。

## 1. 前置条件

| 条件 | 原因 | 如何找到 |
| --- | --- | --- |
| Linux x86_64 主机 | 配方要用 `prebuilts/clang/host/linux-x86/...` 以及在那里构建的 soong 中间产物 | —— |
| `bash` + coreutils（`find`、`cp`、`chmod`、`mkdir`、`printf`、`rm`） | 每一步都是 bash 脚本 | 在 `PATH` 上 |
| `zip`、`unzip` | 打包器构建发布包并打印 `unzip -l` 清单 | 在 `PATH` 上 |
| 一个 JDK——任何现代的都可以；脚本自己定位 | `javac` 编译 `daemon/src`，树里的 `d8.jar` 转换它。`tools/build-daemon.sh` 的报错文字写着 JDK 17，但唯一真实要求是接受 `--release 11` 的 `javac` | `$JH` -> `$JDK` -> `$JAVA_HOME` -> `PATH` 上的 `javac`（取其上两级目录） -> `$HOME/*/tools/jdk-*` -> `/usr/lib/jvm/*` |
| 一棵 AOSP 源码树 | 平台 framework jar（含隐藏 API）、AOSP clang、静态 bionic 部件与静态 BoringSSL 都出自同一棵树 | `$TREE`，否则取 `$HOME/aosp/*` / `$HOME/android/*` 中第一个含 `out/target/common/obj/JAVA_LIBRARIES` 的检出 |
| 已 root、装有 KernelSU 或 Magisk 管理器的 Android 11+ 设备 | 只用于安装与验证真实构建，不用于构建 | —— |

**完整构建离不开 AOSP 源码树。** 没有它，两个原生构建各打印自己的 `SKIP` 行并以 0 退出，dex 构建打印
自己的 `SKIP` 行并以 0 退出——dex 与两个原生二进制都不进包。因此一行构建是：

    TREE=/path/to/aosp tools/package-module.sh

配方预期 Android 13 / API 33 家族的树——`prebuilts/sdk/33/public/android.jar`、来自
`prebuilts/clang/host/linux-x86/clang-r450784d/bin/clang` 的 clang、为 platform 30/31 构建的 soong
中间产物（`..._apex30`、`..._apex31`）——而且要构建到产出 `out/target/common/obj/JAVA_LIBRARIES`，那正是
自动探测寻找的标记。不下载 NDK，本仓库也不附带 sysroot。

## 2. 构建步骤

### 2.1 第 1 步——克隆

    git clone <repository-url> okcarlink
    cd okcarlink

下文路径都相对这个根；脚本会 `cd` 到自身目录并自行推导根，所以从任何工作目录都能跑。

### 2.2 第 2 步——lockdown 身份（可选；模块会自己生成）

有线 CarPlay 必须向车机的 lockdown 握手出示证书。仓库不附带任何密钥材料，因为一个私钥在每个 fork 里都公开的
root 模块，在每个安装里都会一模一样——所以密钥对改为首次使用时现场生成：`/data/local/tmp/lockdown-{cert,key}.pem`
不存在时，`okcar-carkit` 调用 `okcar-ffs-usbmux --gen-identity`，由原生用它已经静态链接的 BoringSSL 写出自签名
RSA-2048 证书（`CN=iPhone`、v3、sha256WithRSA、十年）。因此全新安装不需要任何宿主机步骤，恢复出厂设置清空
`/data/local/tmp` 也只会让下一次有线启动重新生成一对。日后要更换，用 WebUI 有线页面的*重新生成证书*按钮，或
`okcar-port-ctl identity regen`；只跑 `okcar-port-ctl identity` 会报告当前装的是什么。原理与失败模式见
`docs/zh/crypto.md`。

如果你希望身份由自己指定——例如给一批设备分发同一对，或希望它从第一次开机就在 `payload/` 里——就在宿主机上
生成。以下命令只写进 gitignore 的 `lockdown/`（在仓库根执行）：

    mkdir -p lockdown
    openssl req -x509 -newkey rsa:2048 -keyout lockdown/lockdown-key.pem \
      -out lockdown/lockdown-cert.pem -days 3650 -nodes -subj "/CN=OkCarLink"

确认两半属于同一对——第一组摘要必须彼此相等，第二组也必须彼此相等：

    openssl x509 -in lockdown/lockdown-cert.pem -noout -modulus | openssl md5
    openssl rsa  -in lockdown/lockdown-key.pem  -noout -modulus | openssl md5

    openssl x509 -in lockdown/lockdown-cert.pem -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum
    openssl rsa  -in lockdown/lockdown-key.pem  -pubout      | openssl pkey -pubin -outform DER | sha256sum

对外宣告的公钥**不编译进二进制**。`okcar-ffs-usbmux` 回答 `GetValue DevicePublicKey` 时，从它自己安装的证书
（`/data/local/tmp/lockdown-cert.pem`）里提取 PKCS#1 公钥并原样返回，所以宣告的密钥与同一二进制在 TLS 层
出示的证书不可能不一致——也就是本页过去警告的那种经典有线故障。现场生成的一对和你自己提供的一对都如此。

如果你更愿意保留编译期常量：`native/ffs-usbmux/okcar-ffs-usbmux.c` 里的 `DEVICE_PUBKEY_PKCS1_PEM_B64` 非空时
仍优先生效，且形式必须是 PKCS#1（普通 `-pubout` 产出 SubjectPublicKeyInfo，车机会拒绝）：

    openssl rsa -in lockdown/lockdown-key.pem -RSAPublicKey_out | base64 -w0   # RSA-2048 约 568 个字符

`lockdown/lockdown-{cert,key}.pem` 存在时打包器会把它们复制进 `payload/`，不存在时静默跳过，因为模块能自己
生成；它打印的 `note: lockdown/<file> absent` 只是提示，不是缺陷。把密钥对放进 `payload/` 只是省掉首次生成。
### 2.3 第 3 步——构建原生助手

    TREE=/path/to/aosp native/uinputd/build.sh      # -> build/okcar-uinputd
    TREE=/path/to/aosp native/ffs-usbmux/build.sh   # -> build/okcar-ffs-usbmux

两者都用同一棵树构建面向 Android 11+ 的完全静态 bionic arm64 二进制：模块必须在任何 arm64 车机手机上
运行，而不必自带或安装运行时，而动态依赖 vendor 库，恰恰是另一个 ROM 没有的东西。共享配方：

| 部件 | 来源 |
| --- | --- |
| 编译器 | `$T/prebuilts/clang/host/linux-x86/clang-r450784d/bin/clang` |
| 标志 | `-target aarch64-linux-android21 -static -O2 -nostdinc -nostdlib -fuse-ld=lld` |
| 头文件 | clang resource dir、`$T/bionic/libc/include`、`bionic/libc/arch-arm64/include`、`kernel/uapi`、`kernel/uapi/asm-arm64`、`kernel/android/uapi` |
| crt / libc | 来自 `out/soong/.intermediates/bionic/libc` 的 `crtbegin_static.o`、`libc.a`、`crtend.o` |
| builtins | clang resource dir 里的 `libclang_rt.builtins-aarch64-android.a` |

`okcar-ffs-usbmux` 额外的 FunctionFS gadget、usbmux 侧与 lockdown 服务端，因此它也链接树里的静态
`libssl.a` 与 `libcrypto_static.a` 及 BoringSSL 头文件——lockdown 握手需要 TLS。`okcar-uinputd` 完全不需要
这些：它是 `system/usr/idc/okcar-uinput-touch.idc` 所声明虚拟设备的触摸注入后端。

**覆盖项。** `TREE=/path/to/aosp` 选择树；否则套用与 dex 构建相同的 `$HOME/aosp/*` 与 `$HOME/android/*`
自动探测。`OUT=/some/directory` 重定向输出；默认 `<repo>/build`，用 `mkdir -p` 创建。没有树时每个脚本在
stderr 打印 `SKIP: needs an AOSP source tree (set TREE=/path/to/aosp); skipping this build` 并以 0 退出，
随后打包器把该二进制报告为缺失（第 3 节）。

**确认结果是静态的**——这些预期来自上面的链接标志，且**未**在作者主机上执行（主机没有 AOSP 树，第 5 节）：

    file build/okcar-uinputd       # expect: ELF 64-bit LSB executable, ARM aarch64, statically linked
    readelf -d build/okcar-uinputd # expect: "There is no dynamic section in this file"
    readelf -l build/okcar-uinputd | grep -c INTERP    # expect 0
    # repeat all three for build/okcar-ffs-usbmux

### 2.4 第 4 步——构建 payload dex

    TREE=/path/to/aosp tools/build-daemon.sh           # -> build/okcar-link.dex

这就是整套投射栈，一个 dex，由 `module/system/bin/okcar-linkd` 以 root 在 `app_process` 下运行。没有
APK，没有 manifest——见第 4 节。

*JDK 解析。* 依次 `$JH`、`$JDK`、`$JAVA_HOME`、`PATH` 上的 `javac`（JDK 根是其上两级目录），然后
`$HOME/*/tools/jdk-*` 与 `/usr/lib/jvm/*`。都不产出 `javac` 时脚本打印
`needs JDK 17: set JH=/path/to/jdk-17 (or JDK/JAVA_HOME), or put javac on PATH` 并以 **2** 退出——硬停，
因为没有 dex，`okcar-linkd` 没有任何东西可启动。

*SKIP 行为。* 没有树时打印 `SKIP: needs an AOSP source tree for the framework classes (set
TREE=/path/to/aosp); skipping this build` 并以 0 退出。

*它做什么。* `find` 收集 `daemon/src` 下每个 `*.java`（包括共享线路编解码 `com.okcar.cast`，它与
`com.okcar.link` 放在一起，使应用侧与守护进程侧不会各自漂移）成一份源码清单；`javac` 编译、`jar`
打包、树里的 d8 转换：

    "$JH/bin/javac" --release 11 -nowarn -encoding UTF-8 -d "$OUT/classes" -cp "$CP" "@$OUT/sources.list"
    jar cf classes.jar
    java -cp "$TOOLS/../lib/d8.jar" com.android.tools.r8.D8 --min-api 29 --lib <android.jar> --output "$OUT/dex" classes.jar
    cp "$OUT/dex/classes.dex" build/okcar-link.dex

*Classpath。* `$T/out/target/common/obj/JAVA_LIBRARIES/framework-minus-apex_intermediates/classes.jar`，加
`$T/prebuilts/sdk/33/public/android.jar`，并在树里有时加上隐藏的 wifi、connectivity、tethering、
graphics、media framework jar。移植要用到 `android.jar` 不暴露的平台内部实现，单靠公开 SDK 编不过。

*为什么 `--release 11` 与 `--min-api 29`。* d8 警告 class file 版本 56 及以上不受官方支持，脚本记录了
早先一次构建发过这类 class，守护进程两次以 ART 内部 SIGSEGV 收场（无线线程上
`ClassLinker::FindResolvedMethod` 的空指针解引用，以及同一栈上 `StackVisitor` 的 `throw_dex_pc` 检查）；
因此构建只发 ART 真正支持的 class。`--min-api 29` 是 Android 10 成为下限的原因。中间输出在
`build/linkd-build/`；最终输出是一个 `build/okcar-link.dex`，脚本用 `ls -l` 打印。

### 2.5 第 5 步——打包模块

    tools/package-module.sh                       # -> build/okcarlink-1.0.0.zip

打包器以静音 stdout 跑第 2.3–2.4 节的三个构建脚本（无法构建时各自仍在 stderr 打印 `SKIP` 行）；把
`module/` 暂存到 `build/stage`（先 `rm -rf` 再 `cp -r`，所以陈旧的暂存绝不会漏进发布）；复制构建产物，
缺失时打印一行提示：

| 提示 | 含义 |
| --- | --- |
| `note: build/okcar-uinputd missing -- the matching feature reports its daemon as unavailable at runtime` | 镜像能工作，触摸到不了车机屏幕；`REQUIRE_ARTIFACTS=1` 时改为直接中止 |
| `note: build/okcar-ffs-usbmux missing -- ...`（同样的措辞） | 有线开关绑不了 gadget |
| `note: build/okcar-link.dex missing -- ...`（同样的措辞） | 模块能装，但守护进程永远起不来 |
| `note: lockdown/<file> absent -- the module will generate a self-signed identity on first wired start` | 没问题：`payload/` 里没有固定的密钥对，首次有线启动会现场生成一对（§2.2） |

然后：把分发变体标记 `lite` 写进 `webroot/variant`；设置显式权限，因为工作区常以 `0600` 存放一切，而
`0600` 的助手不可执行、`0600` 的 `.idc` 会把注入的触摸屏误判成普通指针——`post-fs-data.sh`、
`service.sh`、`customize.sh`、`uninstall.sh`、`system/bin` 助手、CGI 目录及其 `ctl` 脚本、两个暂存的原生
二进制为 `0755`；`module.prop`、`sepolicy.rule`、`action.sh`、`system/usr/idc/okcar-uinput-touch.idc` 以及
payload/webroot 文件为 `0644`；跑泄漏门禁；然后把 `module.prop post-fs-data.sh service.sh customize.sh
uninstall.sh sepolicy.rule system webroot payload panel action.sh`（存在时加 `README.md`）压成
`build/okcarlink-1.0.0.zip` 并打印 `unzip -l` 清单。

两个打包细节是承重的，且写在脚本自身里：`sepolicy.rule` 必须随包，因为没有它，拥有 Wi-Fi Direct 接口的
supplicant 会拒绝应答框架域之外的客户端；`uninstall.sh` 必须随包，因为管理器在删除模块目录前会运行它，
以交还 USB 并清理运行期状态。

**泄漏门禁。** 它扫描 `build/stage` 下每个文件——暂存模块树、dex、原生二进制与构建者的 lockdown 密钥对
——对照两组内置模式：私钥材料标记（`BEGIN ... PRIVATE KEY`、`ssh-rsa`、`ssh-ed25519`、`authorized_keys`）
与凭据形状（隧道令牌、上传令牌与 URL、`api_key` 风格名、`Bearer <token>` 字面量）。第三组实验室专有模式
——私有主机名、内部 CIDR 段、员工 id，以及车机配件热点的 SSID 与口令——刻意不写进脚本：它在构建时从不受
版本管理的 `tools/lab-patterns.local` 读取（每行一个扩展正则；可用 `LEAK_EXTRA_FILE` 覆盖路径），因此仓库
永远不会公开门禁本要拦下的那些字符串。没有这个文件时，门禁仍按两组内置模式运行。唯一豁免是刻意且狭
窄的：`stage/payload/lockdown-cert.pem` 与 `stage/payload/lockdown-key.pem` **不带**私钥组扫描，因为每次
安装都必须出示真实密钥对，那里命中私钥会让每个发布都失败。任何其他命中会打印 `LEAK: <file>` 加最多五个
匹配字面量，并以 `release package contains lab information/credentials; aborted.` 与退出码 1 中止。

*已知缺口（通过复刻其模式验证）：* 扫描用 `grep -I`，会跳过工具视为二进制的文件，所以 dex 与两个原生二进
制在注释里被点名、实际并未作为文本搜索。所有暂存文本文件都被覆盖，门禁自身的模式位于
`tools/package-module.sh`（以及可选的 `tools/lab-patterns.local`），在被扫描树之外；不要依赖门禁保护二进制负载。

*陈旧产物陷阱。* 打包器复用 `build/` 里已有的一切，所以若本次跳过某构建而 `build/` 里仍留有上次产物，
缺失提示不会出现，包会带着陈旧二进制成立；干净构建请删掉整个 `build/` 目录。`REQUIRE_ARTIFACTS=1` 补上另
一半陷阱——产物完全缺失——它在打包前直接失败，而不是发一个残缺的 zip，CI 用的就是它。

### 2.6 第 6 步——刷入

在 KernelSU 或 Magisk 管理器里安装 `build/okcarlink-1.0.0.zip`（模块 → 从本地安装）；管理器以模块 id
`okcarlink` 报告它。`post-fs-data.sh` 在早期运行，随后 `service.sh` 启动守护进程并应用各开关。

判断是否成功前，值得先知道的首次开机事实：

*  `/data/local/tmp` 每次开机都以 `0771 shell:shell` 重建，因此其中任何内容都不会跨重启留存。
*  `touch $MODDIR/disable-linkd` 会阻止守护进程启动；把 `linkd` 开关设为 `off` 对一次开机有同样效果，
   再打开开关时 WebUI 会重新启动它。
*  缺少 payload 时 `service.sh` 记录 `payload missing` 且不启动守护进程——这正是无树构建（第 3 节）该有的样子。
*  模式默认 `mirror`：`okcar-linkd` 在 `/data/local/tmp/okcar-port-mode` 缺失时写入它，并把它注入守护进程参数。
*  `wired` 开关**不**在开机时应用。它会重新配置 USB，而现场设备曾因此陷入重启循环，所以开机不碰 USB，
   有线模式由你刻意启动（第 2.7 节）。

控制面出现在哪里：提供 WebUI 的管理器会拿到模块 action（`action.sh`）及其 `module.prop` 描述；任何安装方式
都可使用本地面板：

    okcar-port-panel start        # busybox httpd -f 监听 127.0.0.1:8088（PORT / OKCAR_PANEL_PORT 可覆盖）
    okcar-port-panel url          # 打印 http://127.0.0.1:8088/?token=<token>

地址始终是回环；token 是 `/data/local/tmp/okcar-port-panel.token` 里 16 字节随机十六进制，URL 在
`/data/local/tmp/okcar-port-panel.url`。`okcar-port-ctl` 可从 shell 驱动同一组开关；8088 是 `module/` 里
唯一的端口字面量（`docs/zh/webui.md`）。

### 2.7 第 7 步——验证清单

    okcar-port-check where     # 模块目录、模块 id，以及它解析到的 ctl/linkd 路径
    okcar-port-check caps      # 这个内核提供什么：FunctionFS、UDC、USB role、.ko 加载
    okcar-port-check start     # 启动守护进程，然后打印以上全部

`okcar-port-check` 从自身位置解析模块目录，然后探测已安装布局——`/data/adb/modules/{okcar-port,
okcar-port-lite,okcarlink}` 以及 `/data/adb/modules_update` 下的相同路径——因此在首次安装和原地更新后都能
工作。它报告安装/激活状态（zip 仍在 `modules_update` 下时给出警告，因为模块尚未开机），并在找不到模块目录、
`okcar-linkd` 或 `okcar-port-ctl` 时以 1 退出——那是硬错误，说明包不完整。

然后检查日志与状态，全部位于 `/data/local/tmp`：

| 路径 | 它能告诉你什么 |
| --- | --- |
| `okcar-linkd.log` | 监督进程与守护进程日志——`okcar-linkd status` 也会打印最后五行 |
| `okcar-port.log` / `okcar-port-net.log` | 模块日志 / 无线路径的网络拉起 |
| `okcar-port-panel.log`、`.url`、`.token` | 本地面板的日志、URL 与 token |
| `okcar-port-*` 与 `okcar-linkd.status` 报告 | 助手、守护进程与 WebUI 之间的状态契约：`okcar-port-switches.conf`、`okcar-port-mode`、`okcar-port-app.conf`、`okcar-carkit.state`、`okcar-linkd.*` 监督簿记及其余（`docs/zh/architecture.md` §9.1） |

无线路径方面，`daemon/src` 里的协议自检是纯 Java：`com.okcar.link.wireless.WirelessSelfTest` 及其同类带
`main()`，可用普通 `javac`/`java` 在主机 JVM 上运行——不需要设备，也不需要 AOSP 树。可选的 Wi-Fi 自检在
`okcar-wifi-selftest.conf` 存在时读取它（`ssid=`/`passphrase=`）；不要把那些值提交进文件。

有线模式：

    okcar-port-ctl switch wired on        # 刻意重新配置 USB 并绑定 Apple gadget

`okcar-carkit` 先在模块自带了固定密钥对时把 `payload/lockdown-{cert,key}.pem` 复制到
`/data/local/tmp`；否则（或只剩一半时）运行 `okcar-ffs-usbmux --gen-identity`，写出
`/data/local/tmp/lockdown-cert.pem`（`0644`）与 `/data/local/tmp/lockdown-key.pem`（`0600`），并记录
`TLS: 已现场生成自签名身份（CN=iPhone）`。原生服务端监听 lockdownd 端口 `62078`，并通过抽象套接字
`okcar-carkit` 与 Java 侧通信。新安装的密钥对只有在有线开关被循环一次后才生效，因为每个进程只构建一次
TLS 上下文；不一致会以 `tls: cert/key do not match` 报告一次。`okcar-port-ctl identity` 报告当前装的是什么，
`identity regen` 则换掉它——完整行集见 `docs/zh/crypto.md` §6。

开机自启由 `okcar-port-ctl wired-boot` 拉起，它在 `sys.boot_completed` 之后 45 秒运行
（`OKCAR_WIRED_BOOT_DELAY`）。如果连续两次开机都没能到达 `okcar-carkit.state=started`，它会写
`/data/local/tmp/okcar-wired-boot.disabled` 并且不再尝试——这样一次启动失败不会演变成重启循环。这也正是
有线开关显示"开"却什么都不发生的唯一情形：WebUI 会报*连续两次开机自启没站住，已自动停用*，并给出*恢复开机自启*
按钮。`okcar-port-ctl wired-boot-state` 打印 `ok` / `disabled` / `pending` / `fails=<n>`；
`okcar-port-ctl wired-boot-arm` 清掉标记，`switch wired on` 也会清。

### 2.8 第 8 步——在托管 runner 上做 CI

托管 runner 无法从零构建本模块：隐藏 API 的 framework jar 与静态 bionic/BoringSSL 原生都不来自公开 SDK，而
完整 AOSP 检出也放不进一次构建任务。因此本仓库把工作拆开（`.github/workflows/build.yml`）：

*  **有 AOSP 树的机器**产出 CI 拿不到的部分，`daemon/src` 或 `native/` 每次改动后重跑一次：

       TREE=/path/to/aosp bash tools/make-aosp-deps.sh    # -> build/okcarlink-aosp-deps.tar.gz
       gh release create aosp-deps build/okcarlink-aosp-deps.tar.gz --prerelease \
         --title "AOSP build dependencies" --notes "framework jars + prebuilt arm64 natives for CI"

   该包内含 `framework-jars/`（`framework-minus-apex.jar`、`android.jar`，以及树里存在的 wifi / connectivity /
   graphics / media jar）、`prebuilt/`（第 2.3 节构建出的两个原生）与逐文件 SHA-256 的 `deps-manifest.txt`。

*  **CI** 从该 release tag（`AOSP_DEPS_TAG`，默认 `aosp-deps`）下载这个包，用 `sha256sum -c` 校验，装好 JDK 17，
   `d8` 取自 runner 预装的 Android SDK（仅在缺失时才安装 `build-tools`），`android.jar` 用包自带的那个，
   所以只有 `d8` 依赖 runner：

       FRAMEWORK_JARS=/tmp/deps/framework-jars \
       ANDROID_JAR=/tmp/deps/framework-jars/android.jar \
       D8=$(ls "$ANDROID_SDK_ROOT"/build-tools/*/d8 | sort -V | tail -1) \
       REQUIRE_ARTIFACTS=1 bash tools/package-module.sh

   也就是说每次运行都由 CI 编译 `daemon/src`——值得公开构建的那部分——framework jar 与两个原生则作为固定输入
   随行。`REQUIRE_ARTIFACTS=1` 让打包器在缺产物时失败，而不是发一个残缺的 zip。之后任务会校验 zip（两个原生、
   dex 及其 `dex` magic、`webroot`、`module.prop` 版本），作为构建产物上传，并在 `v*` tag 上把 zip 与
   `.sha256` 附到 release。

   取舍是刻意的：CI 发出的原生是复制而非重建，所以 `native/` 一改就必须重跑并重新发布
   `tools/make-aosp-deps.sh`，否则 release 里的二进制会比源码旧。dex 每次都会重新编译，所以它的字节与本地 AOSP
`d8` 构建并不相同；两个原生因为只是复制，与包内逐字节一致。

两条路入口一致：本地 AOSP 检出用 `TREE=`（第 2.1–2.5 节），上面这个包用 `FRAMEWORK_JARS=`，两者都跑同一个
`tools/package-module.sh`。

## 3. 故障排查

| 现象 | 原因 | 怎么办 |
| --- | --- | --- |
| `SKIP: needs an AOSP source tree for the framework classes (set TREE=/path/to/aosp); skipping this build`，退出 0 | `tools/build-daemon.sh` 没找到树 | 设置 `TREE`，或把检出放到探测的位置；没有它就没有 dex，也没有守护进程 |
| `SKIP: needs an AOSP source tree (set TREE=/path/to/aosp); skipping this build`，退出 0，来自任一原生构建 | 同上，针对 `native/uinputd/build.sh` / `native/ffs-usbmux/build.sh` | 同上；随后打包器打印对应的 `note: ... missing` 行，zip 失去该能力 |
| `needs JDK 17: set JH=...` 且退出 2 | `$JH`、`$JDK`、`$JAVA_HOME`、`PATH`、`$HOME/*/tools/jdk-*`、`/usr/lib/jvm/*` 里都没有 `javac` | 安装 JDK 或设置 `$JH`；这是工具链失败中唯一的硬停 |
| `javac` 拒绝目标 release，或 d8 对 class file 版本 56 告警 | JDK 太旧，不支持 `--release 11`，或 class 版本高于支持范围 | 换一个接受 `--release 11` 的 `javac`；脚本不传任何 preview 标志，所以不匹配会立刻显现。class file 达到或高于 56 正是记录在案的崩溃场景 |
| `cannot find symbol`，指向平台内部 API | 编译 classpath 来自树，而非公开 SDK | 把树构建到能产出隐藏 framework jar 的程度；wifi/connectivity/graphics/media jar 只在存在时被取用 |
| `lockdownd: WARNING: no DevicePublicKey available - install the lockdown pair first (/data/local/tmp/lockdown-cert.pem; docs/en/building.md)` | 主机来索取 `GetValue DevicePublicKey`，但证书还没安装：只做镜像的构建，或密钥对被清掉 | 密钥对不在就无法完成有线配对——生成它（第 2.2 节）、重装让 `payload/` 带上它、循环一次有线开关。该警告由 `native/ffs-usbmux/okcar-ffs-usbmux.c` 在运行期发出 |
| `okcar-port-check` 以 1 退出并报 "not found / not executable" | zip 里缺 `okcar-linkd` 或 `okcar-port-ctl`，或模块目录又被改名 | 从完整的 `module/` 暂存重新构建；消息会指出缺的是哪个文件 |
| 有线开关报告守护进程缺失；UDC 始终不绑定 | `build/okcar-ffs-usbmux` 被跳过 | 带 `TREE` 重新构建并重刷（第 2.3 节） |
| 车机能镜像但车机屏幕没有触摸 | `build/okcar-uinputd` 被跳过 | 同上 |
| `EnableSessionSSL` 处 `NO_CERTIFICATE_SET`——"no certificate set" | 密钥对缺失、不可读或为空——恢复出厂设置会清空 `/data/local/tmp`，而生成只在有线启动时跑 | 循环一次有线开关让 `okcar-carkit` 生成密钥对，或跑 `okcar-port-ctl identity regen`；完整行集与不一致情形见 `docs/zh/crypto.md` §6 |
| 有线日志里 `tls: cert/key do not match` | 已安装的证书与密钥不是同一对（`payload/` 里固定的密钥对只覆盖了其中一半） | `okcar-port-ctl identity regen`，它会写出匹配的一对（第 2.2 节） |
| 有线开关显示"开"，`okcar-carkit.state` 一直为空，开机后什么都不重新枚举 | 连续两次开机都没到 `started`，于是 `okcar-port-ctl wired-boot` 自行停用并写了 `okcar-wired-boot.disabled` | `okcar-port-ctl wired-boot-arm`（或 `switch wired on`），然后看 `okcar-carkit.log` 里那两次开机为什么失败 |
| 新安装的密钥对不生效 | 每个进程只构建一次 TLS 上下文 | 循环有线开关，或重启模块——有线正在跑时 `okcar-port-ctl identity regen` 会替你做这件事 |
| `LEAK: <file>` ... `aborted`，退出 1，未产出 zip | 某个暂存文件命中三组模式之一 | 输出会指出文件与最多五个匹配字面量；删掉该内容后重跑 |
| 构建"成功"，但 zip 里是上周的二进制 | 被跳过的构建被 `build/` 里遗留的旧产物悄悄满足 | `rm -rf build/` 后重建（第 2.5 节） |
| `REQUIRE_ARTIFACTS=1: refusing to package an incomplete module`，退出 1 | 三个产物缺了一个，且开了 CI 式严格模式 | 带 `TREE` 补建缺的那件，或取依赖包（第 2.8 节） |
| 有线正常，但 WebUI 一直显示无线监听未发布 | 守护进程没再应答：`status` 的回复没有落盘，`okcar-linkd.status` 的时间戳一直是旧的 | 看 `/data/local/tmp/okcar-linkd.status` 的时间戳，并跑 `okcar-linkd status`——它必须打印 `running pid=…`；没有就 `okcar-linkd restart`。1.0.0 里监督者的每一次 `pgrep` 与 `/proc` 读取都加了上限，守护进程在探测中途死掉不会再把它卡死到永不重启 |
 | 新装好的模块一直不发布监听，且 `okcar-linkd.log` 每隔几秒就重复 `supervisor: mode drift file=mirror running=app — restarting the daemon` | 监督者的判活探针匹配到了它自己：这台 ROM 的 `pgrep -f` 连自己都不排除，于是 `timeout 3 pgrep -f <class>` 这条探针进程被当成了“守护进程”，而它的命令行里没有 `--mode`，回落到 `app`，与面板写的 `mirror` 就成了“模式漂移” | 1.0.0 已修：`module/system/bin/okcar-linkd` 现在会在把某个 pid 当守护进程之前先验明 `/proc/<pid>/cmdline`（`daemon_pids`），所有原来用裸 `pgrep -f "$DAEMON_CLASS"` 的地方——`daemon_mode`、`stop_daemon`、监督循环、`status`、`send` 以及两处 `start` 轮询——都改了。旧版本的判据：`pgrep -f com[.]okcar[.]link[.]daemon[.]LinkDaemon | wc -l` 数出 pid 但 `/proc/<pid>/cmdline` 是空的，就是撞上了这条；更新模块即可 |

## 4. 本仓库不构建什么

**没有 Android 应用，没有 APK。** 没有 Gradle 工程、没有 manifest、也没有安装签名要查——这是刻意的：
整套栈过去以应用形式发布（一个 UI activity 加一个前台服务），代价是后台 activity 启动、按网络随机化 MAC、
MediaProjection 授权弹窗、应用作用域截屏，以及 OEM ROM 上的安装签名校验。因此同样的类改以 root 守护进程运行：
`module/system/bin/okcar-linkd` 执行 `CLASSPATH=<module>/payload/okcar-link.dex app_process
com.okcar.link.daemon.LinkDaemon`，此前先填好 `app_process` 期望的 `ANDROID_*`/`BOOTCLASSPATH` 环境，并从自身
`$0` 定位模块目录。这也解释了为什么产物是 dex 而非 APK：没有任何东西通过包管理器安装它。

**没有预编译二进制。** `native/` 下没有任何提交的二进制，也没有 dex；两个原生二进制与 payload dex 都由本仓库
的三个脚本从源码、针对你自己的 AOSP 树构建。

**没有 NDK，没有 vendor 镜像。** 原生部分用 AOSP 树自带的 clang 与 soong 中间产物交叉编译，而非 NDK；不涉及
任何 vendor 分区或预编译 sysroot——树以只读方式提供编译器、头文件与静态库。

**没有内部套件里的配套应用。** 内部 CarWith 测试应用及随完整套件发布的其他配套包不在本发布范围内；本发布
包含 `module/` 下的模块树与 `docs/` 下的文档（哪些包含、哪些不包含见 `docs/zh/module.md`，公开改名说明见
`docs/zh/architecture.md` §0）。

## 5. 编写本页时验证了什么

这里写准确，因为一个夸大的构建页比没有更糟。以下都在作者主机上真实跑过——Linux x86_64、JDK 17、位于
`$HOME/okcaros/okcar-1.0` 的 AOSP 树，以及 `zip`、`unzip`、`file`、`openssl`；只有点名手机之处例外。

*  对全部五个脚本做了 `bash -n`（仅语法检查）：`tools/build-daemon.sh`、`tools/package-module.sh`、
   `tools/make-aosp-deps.sh` 与两个原生构建脚本。
*  跑过真实构建：`TREE=... bash tools/package-module.sh` 产出单个 805,580 字节的 `build/okcar-link.dex`、
   `build/okcar-uinputd`（1,964,472 B）与 `build/okcar-ffs-usbmux`（9,416,576 B，含身份生成器）——两者都被 `file` 确认为
   `ELF 64-bit LSB executable, ARM aarch64, statically linked, for Android 33`——以及 36 个文件的
   `build/okcarlink-1.0.0.zip`。
*  在没有 AOSP 检出的情况下本地跑过 CI 路径：`FRAMEWORK_JARS=<包>/framework-jars ANDROID_JAR=...
   D8_JAR=... REQUIRE_ARTIFACTS=1 bash tools/package-module.sh` 用包里的 framework jar 重新编译了
   `daemon/src`，并用包里预构建的原生产出同一个 36 文件 zip。
*  这个 workflow 本身在 GitHub 上跑绿了（`workflow_dispatch`，30 秒）：它下载已发布的包、校验清单、用 runner 的
`d8` 编译 `daemon/src`，上传了 36 文件的 zip 产物（dex 804,692 B、`7c7ac459…`——与本地不同的 `d8` 构建——两个原生
与包内逐字节一致）。
*  在 KernelSU 设备（Android 15、arm64）上真实安装过：`ksud module install` 接受该 zip，重启后模块以
   `okcarlink 1.0.0 (100)` 起来，`service.sh` 启动了 `app_process64 ... com.okcar.link.daemon.LinkDaemon`
   （记录在 `/data/local/tmp/okcar-linkd.status`），有线助手绑定了 `/dev/usb-ffs/okcar/ep0`，dex 与两个原生的
   设备侧 SHA-256 与主机构建逐字节一致。
*  发布准备期间，泄漏门禁及其 `LEAK: <file>` 失败路径在暂存树上被实际触发过。
*  自生成身份在同一台设备上实测过：删掉 `/data/local/tmp/lockdown-*.pem` 后一次有线启动记录了
   `TLS: 已现场生成自签名身份（CN=iPhone）`，写出 969 字节的 `lockdown-cert.pem`（`0644`）与 1,704 字节的
   `lockdown-key.pem`（`0600`）；在宿主机上用 `openssl x509` 读回为 `CN=iPhone`、自签名、RSA-2048、v3、
   sha256WithRSA、十年。`okcar-port-ctl identity` 报告大小，`identity regen` 换掉这一对。
*  在同一台设备上，用现场生成的密钥对、不做任何宿主机步骤，有线 CarPlay 完整跑通：`carkit: wired control
   channel accepted` → `iAP2 link up` → `announced CarPlay availability 0x4300` → `accessory asked to start the
   CarPlay session: WIRED address=fe80::2:7000` → `fe80::2%usb0 recovered after in-dial route re-assert` →
   `pair-setup done` → HEVC 1080x2400@60 视频与 opus 音频持续输出（连续十二次统计为 `49.4`–`50.2` fps，
   `droppedByFps=0`、`droppedByBacklog=0`，音频 `50.0 packets/s`、目标 `50/s`）。对端是车机模拟器，不是量产车机。

未验证、因而仍**未经测试**的：有线路径与量产车机之间的实际表现——上面那次的对端是车机模拟器。已报告的成功应该可复现，但这里没有任何一项是对着出厂车机测的；无线配对的自检仍然读的是录制向量，而不是真机。

## 6. 依据来源

| 路径 | 作为哪部分的事实依据 |
| --- | --- |
| `tools/build-daemon.sh` | dex 构建：树探测、JDK 解析顺序、`--release 11` / `--min-api 29`、输出路径 |
| `native/uinputd/build.sh`、`native/ffs-usbmux/build.sh` | static-bionic 配方、`OUT`/`TREE` 覆盖、TLS 握手所需的 BoringSSL |
| `tools/package-module.sh` | 暂存、产物提示、权限纪律、泄漏门禁、zip 名 |
| `docs/zh/{crypto,architecture,module,webui}.md` | lockdown 身份与为何不随附密钥材料、模块布局与无 APK 理由、构建表、运行期状态契约（§9.1）、`module/` 里发布什么，以及 8088 面板端口 |
| `module/module.prop` 与助手 `module/system/bin/{okcar-linkd,okcar-carkit,okcar-port-check,okcar-port-ctl,okcar-port-panel}` | 已构建模块呈现的运行期名称、动词与状态文件 |
| `native/ffs-usbmux/okcar-ffs-usbmux.c` | 运行期解析的 `DevicePublicKey`（从已安装证书读取，编译期常量作为覆盖）、lockdownd 端口 `62078`、`okcar-carkit` 抽象 socket、无密钥材料的警告 |
| `tools/make-aosp-deps.sh`、`.github/workflows/build.yml` | CI 依赖包的构成与发布，以及 runner 实际执行的组装路径与校验 |
| `README.md`、`README.zh-CN.md` | 本页不得与之矛盾的快速开始预期 |
