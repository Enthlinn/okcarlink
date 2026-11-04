#!/system/bin/sh
# Magisk 的模块按钮（KernelSU 用户直接用管理器 WebUI，不需要这里）。
#
# 只打印网址在 Magisk 日志里等于看不见（实测），所以这里做三件事：
#   1) 起本机控制面板；
#   2) 直接把网址用浏览器打开（am start VIEW）；
#   3) 再发一条通知，方便长按复制或稍后打开。
TMPDIR=/data/local/tmp
MOD=$(cd "$(dirname "$0")" && pwd)
export PATH=/system/bin:/system/xbin:/data/adb/magisk:/data/adb/ksu/bin:$PATH
PANEL="$MOD/system/bin/okcar-port-panel"

echo "== okcar-port 状态 =="
head -2 "$TMPDIR/okcar-linkd.status" 2>/dev/null
echo
sh "$PANEL" start >/dev/null 2>&1
# 面板可能已经在跑：start 会回 "already running"（不是网址），所以网址一律向 url 动词取。
URL=$(sh "$PANEL" url 2>/dev/null | tail -1)
case "$URL" in http*) ;; *) echo "面板启动失败，见 $TMPDIR/okcar-port-panel.log"; exit 3 ;; esac
echo "== 控制面板网址 =="
echo "$URL"
echo "(已尝试用浏览器打开；打不开就手动复制上面这行)"
echo

am start -a android.intent.action.VIEW -d "$URL" >/dev/null 2>&1 || echo "（浏览器未能自动打开）"
cmd notification post -S bigtext -t "OkCarLink 控制面板" okcar-panel "$URL" >/dev/null 2>&1
