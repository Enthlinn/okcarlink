# OkCarLink module — install / 安装

**EN**

This directory is the KernelSU / Magisk module itself. The zip
(`okcarlink-1.0.0.zip`) is produced by `tools/package-module.sh` from the
repository root; flash that zip, do not copy this folder onto the device by hand.

1. Open your KernelSU / Magisk manager.
2. Modules → Install from storage → choose `build/okcarlink-1.0.0.zip`.
3. Reboot.
4. Open the WebUI to pick a mode: wireless CarPlay, wireless AirPlay, or wired
   CarPlay. A KernelSU manager serves `webroot/` directly; on a Magisk install
   run `okcar-port-panel start` and open the `okcar-port-panel url`
   (127.0.0.1:8088, token-protected).
5. On the phone: connect to the head unit's Wi-Fi / hotspot and wait for the
   CarPlay or AirPlay prompt.

Wired mode needs a lockdown TLS identity, and the module generates one on its
first wired start (self-signed RSA-2048). Nothing has to be prepared before
building; to replace it later use the WebUI action or `okcar-port-ctl identity
regen`. See `docs/en/crypto.md`. The wireless modes work without any key
material.

Runtime files are written under `/data/local/tmp` (`okcar-port-*` state files,
logs); uninstalling the module removes them.

**中文**

本目录即 KernelSU / Magisk 模块本体。安装包（`okcarlink-1.0.0.zip`）由仓库根目录的
`tools/package-module.sh` 生成，请刷入该 zip，不要把本目录手动拷到设备上。

1. 打开 KernelSU / Magisk 管理器。
2. 模块 → 从本地安装 → 选择 `build/okcarlink-1.0.0.zip`。
3. 重启。
4. 打开 WebUI 选择模式：无线 CarPlay、无线 AirPlay 或有线 CarPlay。KernelSU 管理器
   可直接打开 `webroot/`；Magisk 安装请执行 `okcar-port-panel start`，再打开
   `okcar-port-panel url` 打印的地址（127.0.0.1:8088，带 token）。
5. 手机侧：连接车机 Wi-Fi / 热点，等待 CarPlay 或 AirPlay 弹窗确认。

有线模式需要一份 lockdown TLS 身份，模块会在第一次有线启动时自己生成（RSA-2048 自签）。
构建前无需准备任何东西；日后要更换，用 WebUI 里的操作或 `okcar-port-ctl identity
regen`。详见 `docs/zh/crypto.md`。无线模式无需任何密钥材料。

运行期文件写在 `/data/local/tmp`（`okcar-port-*` 状态文件与日志），卸载模块时
会被清理。
