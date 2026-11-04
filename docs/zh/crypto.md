# 有线模式的身份标识（lockdown）

有线 CarPlay 需要一份只以文件形式存在于设备上的身份：一张 TLS 证书和它的私钥。本文说明它们的用途、为什么必须与编译进原生二进制的常量保持一致、为什么本仓库不提供现成的一份，以及构建者如何生成自己的。

总体架构见 `docs/zh/architecture.md`。

## 1. lockdown 握手是什么

有线开关打开时，`module/system/bin/okcar-carkit` 拉起 FunctionFS gadget 并启动
`native/ffs-usbmux/okcar-ffs-usbmux`。车机看到一个兼容 iPhone 的 USB 设备，向它说 usbmux
（16 字节大端帧、版本握手，之后是 TCP-over-usbmux），并拨 lockdow 服务的 62078 端口
（`LOCKDOWN_PORT`，`native/ffs-usbmux/okcar-ffs-usbmux.c:94`）。

lockdown 对话很短，但顺序很关键：

1. **先明文。** 主机以 `GetValue`/`SetValue` plist 开场。成帧方式是 4 字节大端长度 + UTF-8 XML
   （`native/ffs-usbmux/lockdown_proto.h`，由 `LOCKDOWN_MAX_PLIST` 限定上界）。这些请求被忽略时，
   主机会拆掉会话并不停重试，所以它们必须被最先应答。
2. **`StartSession`** 以 `EnableSessionSSL=true` 应答。此后对话进入 TLS：每条连接一个 BoringSSL
   SSL 对象，经一对 BIO 驱动（`tls_begin()`，`native/ffs-usbmux/okcar-ffs-usbmux.c:614`）。
3. **`GetValue DevicePublicKey`** 以 plist `<data>` 负载应答——一份 PKCS#1 RSA 公钥 PEM 的 base64，
   该值在运行期从这个二进制自己安装的证书（`LOCKDOWN_CERT` / `device_pubkey_from_cert()`，
   `native/ffs-usbmux/okcar-ffs-usbmux.c`）里提取；编译期常量 `DEVICE_PUBKEY_PKCS1_PEM_B64` 非空时
   仍优先生效。主机以这个值建立自己的 pair 记录，且拒绝任何不是 `<data>` 的形状。
4. **`Pair` / `ValidatePair`** 一律接受。假配件不验证也不存储任何东西：pair 记录存在主机自己的应用
   数据里。这是刻意为之，不是偷懒——设备没有地方能存下它并还能满足主机的期待。
5. **`StartService com.apple.carkit.service`** 以一个端口 + **不带** `EnableServiceSSL` 应答，于是主机
   打开一条原始字节管道（6 字节标记 + SYN），由原生二进制桥接到抽象套接字 `okcar-carkit`
   （`CARKIT_SOCKET_NAME`，`native/ffs-usbmux/okcar-ffs-usbmux.c:107`）。Java 侧（`CarkitServiceHost`、
   `UsbIap2Transport`、`com.okcar.link.iap2`）从这里接管 iAP2 设备角色。

第 3–4 步就是这两个身份文件存在的原因：没有它们，TLS 服务端没有证书，也就没有可对外宣告的
`DevicePublicKey`，会话恰好死在 `EnableSessionSSL`。

## 2. 对外公布的公钥从哪来

整个协议里设备只有一份公开身份：装在 `/data/local/tmp/lockdown-cert.pem`（`LOCKDOWN_CERT`）的证书的公钥
半。`GetValue DevicePublicKey` 返回的就是这份公钥，TLS 服务端出示的也是同一张证书，所以主机记录下来的
东西与设备后来证明的东西不可能不一致——本文过去描述的那种不一致是被设计掉的，而不是被文档绕开的。

仍然有三点关键：

- 该值必须以 plist `<data>` 应答——即 PKCS#1 公钥 PEM 的 base64——因为配对客户端拒绝其他形状；
- 主机来问之前证书必须已经装好：没有证书时回复是空的 `<data>`，原生会打印
  `lockdown: WARNING: no DevicePublicKey available - install the lockdown pair first`；
- 形式恰恰是 **PKCS#1**（`BEGIN RSA PUBLIC KEY`），而不是 `openssl rsa -pubout` 打印的
  SubjectPublicKeyInfo 形式（`BEGIN PUBLIC KEY`）；见第 5 节第 4 步。

`DEVICE_PUBKEY_PKCS1_PEM_B64` 在构建者填入非空值时仍优先生效。那是给固定过密钥的部署留的兼容路径，它会
把上面说的不一致风险带回来：请保持它为空。

## 3. 为什么本仓库不包含密钥材料

- 模块在车机上以 root 运行。提交进仓库的私钥会在每个 fork 里公开、在每次安装里完全相同——任何人都
  能构建一个出示同一设备身份的模块。
- 因此 `tools/package-module.sh` 只从构建者取密钥对，绝不从源码树里取：它把
  `lockdown/lockdown-cert.pem` 与 `lockdown/lockdown-key.pem`（gitignored，构建者提供）复制进暂存的
  `payload/` 目录；两者缺失时打印一条提示并继续——有线模式就是无法完成 `EnableSessionSSL`。
- 扫描每个暂存文件的私钥泄漏闸门恰好豁免这两个文件：每次安装都必须出示真实的密钥对，所以它们的存在
  是刻意的，而它们的内容不公开。
- 模块**确实**在 payload 里带一份密钥对，因为 `/data/local/tmp` 会被恢复出厂设置清空，而一个被清空的
  车机完全跑不了有线模式。把它放进 `payload/`，让重装（或助手自己的安装步骤）成为一条命令的恢复。

## 4. 密钥对从哪里读取

| 项目 | 位置 |
| --- | --- |
| 设备上（证书） | `/data/local/tmp/lockdown-cert.pem`，权限 `0644`——`LOCKDOWN_CERT`，`native/ffs-usbmux/okcar-ffs-usbmux.c:100` |
| 设备上（私钥） | `/data/local/tmp/lockdown-key.pem`，权限 `0600`——`LOCKDOWN_KEY`，`native/ffs-usbmux/okcar-ffs-usbmux.c:101` |
| zip 内的来源 | `payload/lockdown-cert.pem`、`payload/lockdown-key.pem` |
| 构建者输入 | `lockdown/lockdown-cert.pem`、`lockdown/lockdown-key.pem`（仓库根，gitignored） |

`module/system/bin/okcar-carkit` 里的 `install_lockdown_identity()` 负责这次复制
（`module/system/bin/okcar-carkit:771`，由 `start` verb 在 `:1281` 调用）。它保留上面两个权限、目标已
完全一致时跳过（`cmp`），并在密钥对缺失时于启动时就明说"有线会在 `EnableSessionSSL` 失败"。它
不把密钥对与公布的常量做交叉校验——不匹配只在握手里才可见。

### 4.1 注意事项：SSL 上下文每进程只建一次

`tls_begin()` 在第一次 TLS 会话开始时建一个 `SSL_CTX`，随后在 `okcar-ffs-usbmux` 进程生命周期内
复用（静态上下文创建于 `native/ffs-usbmux/okcar-ffs-usbmux.c:155`）。证书与私钥文件只在那一刻读取，
之后不再重读：

- 有线会话进行中装上去的新密钥对，**必须让有线开关走完一轮**（`okcar-carkit stop` 再
  `okcar-carkit start`，或 WebUI 开关 off/on）才会生效。
- `SSL_CTX_check_private_key` 在建上下文时执行，因此证书/私钥不匹配只在第一次握手时报一次：
  `tls: cert/key do not match`。

### 4.2 注意事项：恢复出厂设置会抹掉密钥对

`/data/local/tmp` 会被恢复出厂设置清空。此后的故障没有任何"某个文件不见了"的提示：usbmux 依旧枚举、
TLS 服务端依旧就绪，然后每次握手都死在 `EnableSessionSSL`（这是记录在案的事故，不是假想）。恢复办法是
重装模块、或从其 `payload/` 目录复制一份密钥对过去，然后让有线开关走完一轮。模块随包发布密钥对的全部
理由就在这里。

## 5. 生成自己的密钥对

下面的命令都在仓库根执行，且只写进 gitignored 的 `lockdown/` 目录（`tools/package-module.sh` 从那里
取）。全部是普通 `openssl`；生成这份身份不需要本仓库的任何东西。

**1. 生成自签证书与私钥。**

````sh
mkdir -p lockdown
openssl req -x509 -newkey rsa:2048 \
  -keyout lockdown/lockdown-key.pem \
  -out lockdown/lockdown-cert.pem \
  -days 3650 -nodes -subj "/CN=OkCarLink"
```

RSA-2048 与 PKCS#1 与公布的常量所声明的形式一致（`native/ffs-usbmux/okcar-ffs-usbmux.c:96`）。主题随意——
协议里没有任何东西检查它，车机读的是密钥。单张自签证书足够：加载器
（`SSL_CTX_use_certificate_chain_file`）接受只含一张证书的 chain 文件，而主机从来不会去校验一个"本
应是 iPhone 配件"的设备的证书链。

**2. 验证两个文件是同一个密钥对。**

````sh
openssl x509 -in lockdown/lockdown-cert.pem -noout -modulus | openssl md5
openssl rsa  -in lockdown/lockdown-key.pem  -noout -modulus | openssl md5
```

两个摘要必须相同。不同就是密钥对不匹配——用第 1 步一条命令重新生成两个，而不是把不同次运行的两半拼
在一起。

**3. 确认证书里带的确实是这个密钥。**

````sh
openssl x509 -in lockdown/lockdown-cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform DER | sha256sum
openssl rsa -in lockdown/lockdown-key.pem -pubout \
  | openssl pkey -pubin -outform DER | sha256sum
```

摘要相同即证书公钥与私钥公钥是同一个密钥。

**4. 按对外公布的形式（PKCS#1）导出公钥。**

````sh
openssl rsa -in lockdown/lockdown-key.pem -RSAPublicKey_out -out lockdown/lockdown-pub.pem
```

`-RSAPublicKey_out` 打印 `BEGIN RSA PUBLIC KEY`，即 plist 应答与车机 pair 记录使用的 PKCS#1 形式。这里
**不要**用 `-pubout`：它打印 SubjectPublicKeyInfo 形式（`BEGIN PUBLIC KEY`），是同一密钥的另一种编码，
不是常量持有的形式。

**5.（可选）Base64 编码。**

````sh
base64 -w0 lockdown/lockdown-pub.pem
```

2048 位密钥会得到一行约 568 个字符。它是整个 PEM 文件的 base64——头部、折行的密钥体、尾部都包含——
正是 `device_pubkey_from_cert()` 在运行期产出的形状，因此也是固定进构建时常量必须持有的形状。

默认到此为止：什么都不用改，二进制会从它自己安装的证书里推导该值。本步与下一步只服务于要把密钥固定进
构建的部署。

**6.（可选）固定它，而不是推导它。**

编辑 `DEVICE_PUBKEY_PKCS1_PEM_B64`（`native/ffs-usbmux/okcar-ffs-usbmux.c:100-101`）：保持它是一个字符串
初始化式（愿意折行也可以写成相邻字符串字面量拼接），该代码块里其他内容一概不动。非空常量优先于证书，
所以它必须是你所装密钥对的公钥半——下面那个不一致故障就是这样回来的。

**7. 重新构建模块。**

````sh
tools/package-module.sh
```

它运行 `native/ffs-usbmux/build.sh`（运行期推导与可选的固定常量都在这个二进制里）、dex 构建、暂存
`module/`、把 `lockdown/` 密钥对复制进 `stage/payload/`、写入 WebUI variant、跑泄漏闸门，最后产出
发布 zip。原生构建需要 AOSP 树（见 `docs/zh/architecture.md`），因为它从树里链接静态 BoringSSL 与静态
bionic。

**8. 校验发布产物。**

打包后，确认暂存 payload 里的密钥对就是你构建的那一对：用第 2–3 步的两个摘要比较，对象是
`stage/payload/lockdown-cert.pem` 与 `lockdown/lockdown-key.pem`。如果你在第 6 步固定了常量，再用同一个
密钥核对它——固定常量是宣告公钥与证书仍可能不一致的唯一途径，而故障只会在车里才显形。

## 6. 故障排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 每条有线连接都死在 `EnableSessionSSL`，日志形如 `tls: handshake r=-1 err=1 errq=… NO_CERTIFICATE_SET` | `/data/local/tmp` 没有可用的证书/私钥：从未安装、不可读、或为空 | 安装密钥对（`okcar-carkit` 的 `start` verb 会做）或从 `payload/` 复制；保持 `0644`/`0600`；让有线开关走完一轮 |
| 恢复出厂设置后立刻同样的失败 | `/data/local/tmp` 被清空，而日志里没有任何地方提示缺文件 | 重装模块或从其 `payload/` 目录复制密钥对，然后让有线开关走完一轮 |
| 第一次握手时报 `tls: cert/key do not match` | 装上去的证书与私钥不是一对 | 装一份匹配的密钥对，然后重启 `okcar-ffs-usbmux`（有线开关 off/on） |
| 新装的密钥对不生效；开关不拨就一直用旧错误失败 | `okcar-ffs-usbmux` 每进程只建一次 `SSL_CTX`，不会重读文件 | 让有线开关走完一轮（或重启机器）；见 4.1 |
| 设备日志出现 `lockdown: WARNING: no DevicePublicKey available - install the lockdown pair first`，或主机记录里的公钥为空 | 主机来索取时证书还没装好，`GetValue DevicePublicKey` 只能回空 `<data>` | 安装密钥对并让有线开关走完一轮（见上） |
| 车机迟迟配不对；反复要求配对，或 TLS 成功但 pair 记录仍被拒 | 构建期固定的 `DEVICE_PUBKEY_PKCS1_PEM_B64`（或设备上仍是旧二进制）与已装证书不再匹配 | 让常量为空，改为从证书推导，重新构建并重新安装；或密钥对与常量一次性一起换（第 5 节第 4–7 步） |
| 会话在 `EnableSessionSSL` 之前就被拆掉，主机循环重试 | 不是密钥材料问题：主机以明文 `GetValue`/`SetValue` 开场，会拆掉忽略它们的会话 | 确认有线二进制就是本仓库构建出来的那个，并读 `/data/local/tmp` 下的助手/原生日志（`okcar-carkit.log`、`ffs-usbmux.log`）；如果改过这条路径，让应答保持请求进来时的成帧方式 |

## 7. 不要与 AirPlay 身份混淆

上面这份是有线 **USB** 身份，以设备上两个文件的形式存在。无线模式另有一套无关的身份：AirPlay SRP
配对与 Ed25519 长期密钥，存在设备自己的应用偏好里（`com.okcar.link.airplay.AirPlayPairing` 使用的
AirPlay 身份存储）。密钥不同、协议不同、存储位置不同——重新生成 lockdown 身份对无线配对没有影响，
反之亦然。

## 8. 说明与坦白的空白

- 除公钥之外，车机是否还检查证书里的其他内容（主题、有效期、扩展）在本文不做断言：现场跑的是单张
  自签 RSA-2048 叶证书，本文只描述实测行为。
- 表中错误文本引用握手日志的打印内容（`ERR_error_string_n` 的输出）；`errq` 的数字部分随实际安装的
  BoringSSL 版本而变。
