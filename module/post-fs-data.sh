#!/system/bin/sh
# Early boot hook.
#
# This stage has exactly one Bluetooth job now: assert the SDP Device ID vendor (Apple 0x004C)
# BEFORE the Bluetooth stack initialises.  The stack builds its DID SDP record at init, so a
# setprop done afterwards is only advertised after a radio restart — and the production head unit selects the
# phone by that record, not by the MAC OUI (the port design notes).  Without this
# the head unit spends the first minutes after a reboot not classifying the phone at all, which is
# the "开机后有一段时间模拟器连不上" symptom.
#
# It is deliberately the ONLY Bluetooth action here, and it is cheap:
#   * the `did` switch is the only one that defaults to on, and applying it is one setprop — no
#     radio cycle, no MAC rewrite, no waiting;
#   * the `oui` switch (rewrites the Bluetooth MAC address) and the `trim` switch (needs a
#     Bluetooth reload) are applied in service.sh instead, where a radio cycle cannot break early
#     boot.
#
# Writing bt.mac from the overlay is still NOT done here: nv_mac rebuilds that file from QMI NV
# during Bluetooth init, so an early write is frequently not the address the controller ends up
# with (measured 2026-09-30).  That is why the OUI switch is applied later, with its radio cycle.
#
# post-fs-data runs *synchronously* and blocks the rest of the boot, so nothing heavy may live
# here at all.
MODDIR=${0%/*}
CTL=/system/bin/okcar-port-ctl
MOUNTED=yes
[ -x "$CTL" ] || { CTL="$MODDIR/system/bin/okcar-port-ctl"; MOUNTED=no; }
LOG=/data/local/tmp/okcar-port.log

# /data/local/tmp is recreated 0771 at boot; the confs, the FIFO and the status file all live there
# and have to be reachable by ordinary app processes (com.okcar.desktop, com.okcar.carplaypp).
mkdir -p /data/local/tmp 2>/dev/null
chmod 755 /data/local/tmp 2>/dev/null

# The overlay can legitimately be missing (KernelSU reported the module enabled but did not mount
# it, 2026-09-30) — record which path ran so the next boot explains itself.
echo "$(date '+%F %T') post-fs-data: ctl=$CTL overlay_mounted=$MOUNTED" >> "$LOG"

# ── A2: give the okcar-carwith bridge its own SELinux type ──────────────────────────────────────
# The component app finds the daemon's binder with ServiceManager.getService("okcar-carwith"), and
# servicemanager only knows a type for that name if /system/etc/selinux/plat_service_contexts says so
# (an unlisted name falls back to default_android_service, which no app may find — that was the one
# hard blocker of the 2026-10-07 session).  The whole story, why an overmount plus a policy reload is
# the only thing that works on this device, and the label trap that has to be avoided, is documented
# in the helper.  It is a separate script so it can be run and inspected on its own
# (`okcar-carwith-svc status`), and so this early boot stage stays as small as it was.
CARWITH_SVC_BIN=$MODDIR/system/bin/okcar-carwith-svc
[ -x "$CARWITH_SVC_BIN" ] || CARWITH_SVC_BIN=/system/bin/okcar-carwith-svc
if [ -x "$CARWITH_SVC_BIN" ]; then
  "$CARWITH_SVC_BIN" setup >> "$LOG" 2>&1
else
  echo "$(date '+%F %T') post-fs-data: $CARWITH_SVC_BIN missing — the app will not find the okcar-carwith bridge" >> "$LOG"
fi

# `apply early` is the one-shot early hook: it applies the `did` setprop and nothing else, and it
# exits before the wired switch is even looked at.  It used to be a bare `apply`, which also ran
# `wired_switch_apply on` here — before boot_completed, while init still owns the USB port.  Measured
# 2026-10-26 (17:05 and 18:34 boots): eight EBUSY bind attempts, the port handed back to init with a
# dirty Android identity, and the retry 60 s later wedged in configfs (linking a function into the
# config the kernel has already bound is EINVAL; the process sat in D on the configfs directory write
# lock).  On the wire that is exactly "plug it in and nothing happens, plug it in many times and it
# still will not connect".  `oui`/`trim` stay in service.sh, where their radio cycle is affordable.
if [ -x "$CTL" ]; then
  "$CTL" apply early >> "$LOG" 2>&1
else
  echo "$(date '+%F %T') post-fs-data: $CTL missing — cannot apply the switch set" >> "$LOG"
fi
