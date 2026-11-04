#!/system/bin/sh
# KernelSU/Magisk module installer hook for the lite (distribution) variant.  Nothing to compile at
# install time: the dex and the helpers are already in the zip.  The printed lines are the first
# thing an external tester reads, so they describe THIS variant, not the full one.
SKIPUNZIP=0
ui_print "- 无线 CarPlay 测试版：可能存在各种卡顿、掉帧、死机等 bug，仅供测试"
ui_print "- OkCarLink（无 APK）：payload/okcar-link.dex + system/bin/okcar-linkd"
ui_print "- 只做一件事：把手机屏整屏镜像到车机（车机侧使用其自带的互联 App）；不含任何调试通道"
ui_print "- 控制与日志在 KernelSU 模块 WebUI（webroot/）：一键启动＝profile 裁剪 → OUI + SDP DID → 守护进程"
ui_print "- 蓝牙相关全部是手动开关：oui / spoof-bt / bt-did / bt-name / bt-sdp；回退用 restore-bt"
ui_print "- 装完说明见包内 README.md；反馈请带型号/ROM/Android 版本与 /data/local/tmp 下的两个日志"
