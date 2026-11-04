#!/system/bin/sh
# 卸载清理（KernelSU / Magisk 在删模块目录之前执行本脚本）。
#
# 为什么必须有它：模块把所有运行状态都写在 /data/local/tmp，而卸载模块**不会**清那里。于是
# "卸载重装"并不等于回到出厂 —— 开关文件、模式文件、g1 身份存档、lockdown 证书都还在，重装后
# 直接沿用上一次的选择。2026-10-18 实测：用户卸载重装后 okcar-port-mode 仍是 app，而 2.03 的
# 默认是 mirror，界面与预期不符（"配置还在，这个也得修掉"）。
#
# 所以卸载要做两件事，顺序不能反：
#   1) 先把 USB 交还给框架（解绑我们的 gadget、还原 g1 身份、放行 init 重建 adb）；
#   2) 再删这些"用户选择"和运行时残留。
# 每一步都套 timeout：USB 栈在异常时会让 configfs 读写永久阻塞，卸载脚本一旦挂住，模块管理器就
# 会一直转圈（2026-10-18 23:29 现场：守卫的 cat 全部卡在 D 态，整机 load 6+）。
set -u
TMP=/data/local/tmp
MODDIR=${0%/*}

log() { echo "uninstall: $*" >&2; }

# 1) 交还控制权。stop 自己会按"先解绑再放行 init"的顺序恢复（写 UDC 会阻塞，所以限时）。
if [ -x "$MODDIR/system/bin/okcar-carkit" ]; then
  if ! timeout 20 sh "$MODDIR/system/bin/okcar-carkit" stop >/dev/null 2>&1; then
    log "carkit stop 超时或失败 —— USB 可能仍被模块占着；重启手机可恢复（框架 gadget 会自动重建）"
  fi
fi
if [ -x "$MODDIR/system/bin/okcar-port-ctl" ]; then
  timeout 15 sh "$MODDIR/system/bin/okcar-port-ctl" switch wired off >/dev/null 2>&1 || true
  timeout 15 sh "$MODDIR/system/bin/okcar-port-ctl" switch linkd off >/dev/null 2>&1 || true
fi

# 2) 收掉进程（stop 正常时它们已退出；这里兜住卡住的那些）。
pkill -f 'okcar-linkd' >/dev/null 2>&1 || true
pkill -x 'okcar-ffs-usbmux' >/dev/null 2>&1 || true
pkill -f 'okcar-carkit.*guard' >/dev/null 2>&1 || true

# 3) 删运行时状态。
#
# 全部是模块自己写的东西，用名字前缀圈定，**不碰** /data/local/tmp 里别人的文件：
#   okcar-port-switches.conf  七个开关的意图（重装后不该继承）
#   okcar-port-mode           投屏模式（app / mirror / carwith）
#   okcar-carkit.state/.wanted 有线链接的意图与状态（重装后由用户重新开）
#   okcar-g1-orig.conf        g1 原身份存档（模块不在了就不需要留）
#   okcar-usb-quiet           configfs 原值标记（留着会让下次 start 误判 init 已被静默）
#   okcar-*.conf / *.log      各子系统的配置与日志
#   lockdown-{cert,key}.pem   随包带来的实验身份（重装时由 install_lockdown_identity 重新装）
#   ffs-usbmux.log / bind.err / 各 .orig 备份
for f in \
  "$TMP"/okcar-port-switches.conf \
  "$TMP"/okcar-port-mode \
  "$TMP"/okcar-carkit.state \
  "$TMP"/okcar-carkit.wanted \
  "$TMP"/okcar-carkit-guard.pid \
  "$TMP"/okcar-g1-orig.conf \
  "$TMP"/okcar-usb-quiet \
  "$TMP"/okcar-port.log \
  "$TMP"/okcar-carkit.log \
  "$TMP"/okcar-linkd.log \
  "$TMP"/okcar-port-net.log \
  "$TMP"/ffs-usbmux.log \
  "$TMP"/bind.err \
  "$TMP"/lockdown-cert.pem \
  "$TMP"/lockdown-key.pem \
  "$TMP"/bt.mac.orig \
  "$TMP"/bt_did_vendor.orig \
  "$TMP"/bt_name.orig \
  "$TMP"/device_name.orig \
  "$TMP"/okcar-animations.saved \
  ; do
  [ -e "$f" ] && rm -f "$f" 2>/dev/null
done
# 带变体后缀的那些（okcar-cap-config.*.conf、okcar-port-switches.conf.bak 之类）用前缀统收；
# 仍然只匹配 okcar-*，不会误删别的模块。
# okcar-port-video.applied 是 daemon 记的"实际生效值"（2026-10-20 加），面板读它；
# 卸载后必须一起清掉，否则重装后面板会先显示上一任的值。
for f in "$TMP"/okcar-port-*.conf "$TMP"/okcar-port-video.conf "$TMP"/okcar-port-video.applied \
         "$TMP"/okcar-port-app.conf \
         "$TMP"/okcar-port-app-display.conf "$TMP"/okcar-wifi-selftest.conf \
         "$TMP"/okcar-cap-config.* "$TMP"/okcar-port-switches.conf.*; do
  [ -e "$f" ] && rm -f "$f" 2>/dev/null
done

log "完成：USB 已交还框架，运行时状态已清空（重装后是默认配置）"
exit 0
