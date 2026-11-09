# OkCarLink

**让 Android 设备在 CarPlay / AirPlay 中扮演 iPhone。**

[English](README.md) | [中文说明](README.zh-CN.md)

OkCarLink 是一个 KernelSU / Magisk 模块，把整套 iPhone 侧协议栈运行在 Android 设备
上——不需要手机端配套 App。设备接入一台主机：运行 iPhone 互联 App 的车机，或第二台
运行车机模拟器的 Android 设备。主机屏幕显示本设备投出的画面，
主机上的触摸、点击与按键回送到本设备。

| 模式 | 传输方式 | 本设备扮演的身份 | 状态 |
| --- | --- | --- | --- |
| 无线（AirPlay） | Wi-Fi | iPhone 的无线链路：发布 iAP2 RFCOMM 服务由主机拨入，再以 AirPlay 承载画面镜像与音频——车机的「无线 CarPlay」走的就是这套栈，它本身就是 AirPlay，并非另一个模式 | 已支持 |
| 有线 CarPlay | USB | Apple USB 设备（USBMUX + NCM），自行应答 lockdown | 已支持（需自备 lockdown 身份，见下） |

一切都在模块内完成：一个以 root 运行的无界面守护进程
（`payload/okcar-link.dex`，通过 `app_process` 启动）、两个原生二进制，以及一个
用于切换模式和查看日志的 WebUI。

> **现状**：这是可在真车机上使用的移植实现，但并非商业产品，仍可能有粗糙之处；
> 欢迎提交 issue 与 PR。

## 适配与实测

- **适配真机**：Redmi Note 12 Turbo（Android 15、KernelSU）——本移植的开发与调试机。
- **真车机实测**：有线 CarPlay 链路（Apple USB 枚举、lockdown、FunctionFS 上的 iAP2）与
  无线 AirPlay 链路均在真实车机上端到端跑通（画面镜像、音频与触摸回传）。

## 环境要求

- Android 10+ 设备（移植目标覆盖至 Android 15 前后时代的设备；原生二进制为 ARM64），
  已 Root：KernelSU 或 Magisk
- 一台对端主机：运行 iPhone 互联 App 的车机（无线 CarPlay / AirPlay），或第二台运行
  车机模拟器的 Android 设备
- 无线模式需要 Wi-Fi；有线模式需要一个可工作在 device 模式的 USB 口——模块会把设备
  重新枚举为 Apple USB 设备
- **从源码构建**：需要 JDK 17，以及一份 AOSP 源码树或已发布的依赖包；CI 每次推送都会构建
  （见 [docs/zh/building.md](docs/zh/building.md)）

## 仓库内容

| 路径 | 内容 |
| --- | --- |
| `module/` | KernelSU/Magisk 模块本体：开机钩子、辅助脚本、WebUI、SELinux 规则 |
| `daemon/` | 协议栈 Java 源码（`com.okcar.link` 与共用线格 `com.okcar.cast`），编译为单一 dex |
| `native/` | 两个原生二进制的 C 源码（触控注入、有线模式 FunctionFS/mux/lockdown 服务） |
| `tools/` | 构建脚本：dex 构建、原生构建、模块打包、CI 依赖包 |
| `docs/` | 中英双语开发文档（`docs/en` 与 `docs/zh`） |

## 快速开始（构建）

**本地构建**（`TREE=` 等参数见 `docs/zh/building.md`）：

```bash
git clone <本仓库> okcarlink && cd okcarlink
export TREE=/path/to/aosp
bash tools/package-module.sh      # -> build/okcarlink-1.0.0.zip
```

**CI 构建**：`.github/workflows/build.yml` 在托管 runner 上做同一件事，但它不用 AOSP 检出，而是取一个
已发布的依赖包（framework jar + 预构建原生）——隐藏 API 的 framework 类与静态原生工具链无法从公开 SDK
复现。两条路径都写在 `docs/zh/building.md`；打 `v*` tag 时会把 zip 及其 SHA-256 附到 release。

有线模式需要一份 TLS 身份，而模块会在第一次有线启动时自己生成（`okcar-ffs-usbmux --gen-identity`，
RSA-2048 自签）——全新安装不需要任何密钥步骤；要更换时用 `okcar-port-ctl identity regen`（或 WebUI
里的*重新生成 TLS 身份*按钮）。固定一对选定密钥是可选的：

```bash
mkdir -p lockdown
# 可选：将 lockdown-cert.pem + lockdown-key.pem 放入该目录——见 docs/zh/crypto.md
```

随后在 KernelSU/Magisk 管理器中刷入 `build/okcarlink-1.0.0.zip`，重启，即可从管理器
打开模块 WebUI（或直接在设备上使用 `okcar-port-ctl`）。

完整说明：[docs/zh/building.md](docs/zh/building.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/zh/architecture.md](docs/zh/architecture.md) | 分层结构、守护进程各包职责、三种模式及其数据流 |
| [docs/zh/module.md](docs/zh/module.md) | 模块目录布局、开机流程、辅助脚本 |
| [docs/zh/webui.md](docs/zh/webui.md) | WebUI 结构、控制协议、如何新增开关 |
| [docs/zh/building.md](docs/zh/building.md) | 工具链、构建步骤、打包与安装 |
| [docs/zh/crypto.md](docs/zh/crypto.md) | 有线模式的 lockdown 身份及其生成方法 |

以上每篇文档都有对应英文版本，位于 `docs/en/`，文件名相同。

## 安全说明

- 本仓库有意**不携带任何密钥材料**：有线模式需要一份 lockdown TLS 身份，模块会在第一次
  有线启动时于设备上自签生成（`okcar-ffs-usbmux --gen-identity`），随后对外宣告从该证书
  读回的公钥。因此每次安装的身份都是独立的。见 [docs/zh/crypto.md](docs/zh/crypto.md)。
- 打包脚本 `tools/package-module.sh` 内置外发泄漏闸门（私网地址、凭据、密钥
  材料），一旦命中即中止构建，而非把不该发的内容发出去。
- 模块运行期写入的文件都位于 `/data/local/tmp`（`okcar-port-*` 状态文件），
  `uninstall.sh` 会负责清理。

## 许可证

MIT，见 [LICENSE](LICENSE)。

## 致谢

模块化思路与 [KernelSU](https://github.com/tiann/KernelSU) 等项目一脉相承；
各协议处理（CarPlay、AirPlay、蓝牙 HID）均为面向 Android 平台的独立实现，
代码按协议分层组织，便于单独阅读与自测。
