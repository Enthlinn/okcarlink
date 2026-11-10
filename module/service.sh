#!/system/bin/sh
# OkCarLink boot hook.
#
# The distribution variant, and deliberately a separate, smaller script instead of a patched copy
# of the full module's service.sh: the two products differ exactly here.  This one installs
# nothing, starts no debug channel and hosts nothing on a display of its own -- the phone's screen
# is mirrored to the head unit.
#
# /data/local/tmp is recreated with mode 0771 shell:shell on every boot; the confs, the command
# FIFO and the status snapshot live there and are read/written by other processes, so make the
# directory traversable before the daemon publishes anything.
chmod 755 /data/local/tmp 2>/dev/null

MODDIR=${0%/*}
CTL=/system/bin/okcar-port-ctl
[ -x "$CTL" ] || CTL="$MODDIR/system/bin/okcar-port-ctl"
LINKD=$MODDIR/system/bin/okcar-linkd

# a13 特制包：payload/bt-proxy.name 存在时，蓝牙监听交给伴生 App（com.okcar.btproxy），
# 守护进程只连它的抽象命名空间套接字。主线包没有这个文件，以下逻辑整体不生效。
BT_PROXY_NAME=""
[ -f "$MODDIR/payload/bt-proxy.name" ] && BT_PROXY_NAME=$(cat "$MODDIR/payload/bt-proxy.name" 2>/dev/null)
[ -x "$LINKD" ] || LINKD=/system/bin/okcar-linkd
PAYLOAD=$MODDIR/payload/okcar-link.dex
LOG=/data/local/tmp/okcar-port.log

# okcar-uinputd 建出来的虚拟触摸设备必须被当成 touchScreen，否则注进去的事件语义不对。
# /system/usr/idc 只有 root 管理器真的 overlay 了模块才存在（实测这台机器上不存在：ls 报
# No such file or directory），所以再往 Android 同样会读的 /data/system/devices/idc 放一份。
IDC_SRC=$MODDIR/system/usr/idc/okcar-uinput-touch.idc
IDC_DST=/data/system/devices/idc/okcar-uinput-touch.idc
if [ -f "$IDC_SRC" ] && ! cmp -s "$IDC_SRC" "$IDC_DST" 2>/dev/null; then
  mkdir -p /data/system/devices/idc 2>/dev/null
  cp -f "$IDC_SRC" "$IDC_DST" 2>/dev/null
  chmod 644 "$IDC_DST" 2>/dev/null
  chown system:system "$IDC_DST" 2>/dev/null
  echo "$(date '+%F %T') idc: installed $IDC_DST" >> /data/local/tmp/okcar-port.log
fi

boot_log() { echo "$(date '+%F %T') service(lite): $*" >> "$LOG"; }

boot_log "boot hook entered; ctl=$CTL linkd=$LINKD payload=$( [ -f "$PAYLOAD" ] && echo present || echo absent ) idc=$( [ -f /system/usr/idc/okcar-uinput-touch.idc ] && echo present || echo absent )"

# Wait for the framework and for a real Bluetooth adapter, exactly like the full module: the session
# cannot start without one, and starting early leaves the RFCOMM listener unpublished for minutes.
i=0
while [ "$i" -lt 180 ]; do
  [ "$(getprop sys.boot_completed)" = "1" ] && break
  sleep 1
  i=$((i + 1))
done
boot_log "boot_completed after ${i}s"
i=0
while [ "$i" -lt 120 ]; do
  addr=$(settings get secure bluetooth_address 2>/dev/null)
  case "$addr" in
    ""|null|NULL) ;;
    *) [ -n "$(pidof com.android.bluetooth)" ] && { boot_log "bluetooth ready after ${i}s (address=$addr)"; break; } ;;
  esac
  sleep 1
  i=$((i + 1))
done

# Apply the switch set.  Only switches that are ON are applied; with the shipped defaults that is
# the single cheap SDP DID setprop, so boot normally does not cycle the radio.
#
# `apply boot` —— 不是裸的 `apply`：裸写等于 "manual"，会把 `wired` 开关也一起应用，
# 那会在这个钩子里重绑 USB gadget（sys.usb.config + UDC）。217 上就是这样产生了
# 2026-10-06 的 开机 -> USB 重绑 -> adbd/网络消失 -> 重启 循环；当时修了完整版，
# 漏掉了这一份。出厂新机若从 /data 带过来一份开关文件，就会永远循环下去。
# wired gadget 仍是刻意动作：WebUI 里的开关，或 `okcar-port-ctl switch wired on`。
[ -x "$CTL" ] && "$CTL" apply boot >> "$LOG" 2>&1

# The daemon is opt-out, same flag name as the full module: `touch $MODDIR/disable-linkd` keeps a
# device dark while the module (and the WebUI) stay installed.
if [ -f "$MODDIR/disable-linkd" ]; then
    boot_log "linkd disabled by flag, not starting"
    exit 0
fi

# 守护进程开关（WebUI 里那个左右开关）与上面的 flag 等价，开机同样听它的：
# 关着就不起进程，WebUI 里打开时会自己拉起（不需要重启）。
if [ "$("$CTL" switch-state linkd 2>/dev/null)" = "off" ]; then
    boot_log "linkd 开关关闭 — 本次开机不起守护进程（WebUI 里打开即会启动）"
    exit 0
fi
if [ ! -f "$PAYLOAD" ]; then
    boot_log "payload missing ($PAYLOAD), not starting linkd"
    exit 0
fi

# 镜像也是这个变体能干的活，但**不是它的默认**：模式由用户的选择（/data/local/tmp/okcar-port-mode）
# 决定，okcar-linkd 负责把文件里的值注进守护进程的启动参数 —— 文件不存在时先写默认值 mirror 再注入（新装没有应用目标，能投的是手机屏幕本身），
# 面板读的是同一份文件，所以"面板显示应用投屏、连上是镜像"不会再出现（2026-10-18 现场）。
# 这里从不写 --mode：早前写死 --mode mirror 会让 WebUI 里选的应用投屏永远被拉回镜像（2026-10-03）。
if [ -n "$BT_PROXY_NAME" ]; then
    # 伴生 App 必须先在跑（它就是那个"有应用归属的进程"），并保证装的是随包版本
    BT_APK=$MODDIR/payload/OkCarBtProxy.apk
    if [ -f "$BT_APK" ]; then
        STAGE_BT=/data/local/tmp/okcar-install-OkCarBtProxy.apk
        cp -f "$BT_APK" "$STAGE_BT" 2>/dev/null && chmod 0644 "$STAGE_BT" 2>/dev/null
        pm install -r "$STAGE_BT" >> "$LOG" 2>&1
        boot_log "bt proxy apk installed -> $(pm path com.okcar.btproxy 2>/dev/null | head -n 1)"
    fi
    am start-foreground-service --user 0 -n com.okcar.btproxy/.BtProxyService >> "$LOG" 2>&1
    boot_log "bt proxy service started (name=$BT_PROXY_NAME)"
    setsid "$LINKD" supervise >> "$LOG" 2>&1 &   # 代理参数由启动器按 payload/bt-proxy.name 自动补
else
    setsid "$LINKD" supervise >> "$LOG" 2>&1 &
fi
# 本机控制面板：重启后网址仍然可用（只监听 127.0.0.1，URL 带 token）。没有带 httpd 的
# busybox 时它自己会明确报错退出，不影响开机。
if [ -x "$MODDIR/system/bin/okcar-port-panel" ]; then
  sh "$MODDIR/system/bin/okcar-port-panel" start >/dev/null 2>&1 &
fi
sleep 3
boot_log "linkd supervise -> $("$LINKD" status 2>&1 | head -n 1)"

# 无线会话开关同理：进程照起，但开关关着就不监听。
if [ "$("$CTL" switch-state session 2>/dev/null)" = "off" ]; then
    "$LINKD" send stop >> "$LOG" 2>&1
    boot_log "session 开关关闭 — 进程已起但不监听（WebUI 里打开即恢复）"
fi

# ── 有线开机自启（后台、延后） ─────────────────────────────────────────────────
# 不在这个钩子的关键路径上碰 USB：早前就是这样进了 开机→wired→重启 循环（2026-10-06）。
# 但完全不恢复也不行：重启后 gadget 不绑、开机默认 mode=host，用户插拔数据线毫无反应
# （2026-10-20 "重插了好几次都不触发连接"）。所以丢到后台，等 boot_completed + 静默期
# 之后再拉；`wired-boot` 自己带"连续两次自启后重启就永久停用"的保护。
if [ -x "$CTL" ]; then
  setsid sh -c "'$CTL' wired-boot" >> "$LOG" 2>&1 &
fi

# ── 系统驱动的有线再断言（与 init rc 触发器同一语义的模块内实现） ───────────────
# 方向来自 2026-11-10 的现场："让系统主动把 USB 送过来"。init rc 路线（system/etc/init/
# okcarlink.rc，仓库里已备）被 meta 模块 Magic Mount-rs 挡住——实测它挂 /system/bin/*
# 与 usr/idc、却跳过 /system/etc/init，extra_mount 只收分区名（2026-11-10）。于是同一
# 行为改由模块自己的常驻路径实现：观察 sys.usb.config，系统每重建一次 USB，就调用
# okcar-wired-init 再断言一次 Apple 身份——那个包装自带 开关检查 / 健康检查（已健康
# 纯 no-op）/ 10 秒防抖，全部 fail-closed；有线活跃时系统不重建 USB，观察器天然安静。
if [ -x "$MODDIR/system/bin/okcar-wired-init" ]; then
  WIRED_INIT=$MODDIR/system/bin/okcar-wired-init
  setsid sh -c 'WIRED_INIT=$1; LAST=""; while :; do C=$(getprop sys.usb.config 2>/dev/null); if [ -n "$C" ] && [ "$C" != "$LAST" ]; then LAST="$C"; "$WIRED_INIT" >/dev/null 2>&1; fi; sleep 3; done' okcar-wired-init-watch "$WIRED_INIT" >> "$LOG" 2>&1 &
  boot_log "wired-init watcher started (system USB lifecycle -> re-assert)"
fi
