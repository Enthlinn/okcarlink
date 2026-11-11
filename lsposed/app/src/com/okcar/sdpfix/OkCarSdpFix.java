package com.okcar.sdpfix;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * OkCarLink SDP fix — LSPosed module.
 *
 * 问题（2026-11-10/11 现场与 libbluetooth_jni.so 反汇编，见仓库
 * docs/OKCAR_BT_CARPLAY_DETECT_STATUS.md §3.5/§3.28/§4）：
 *   车机的 SDP 发现数据库约 1600B，配对后的全服务响应 18 条 1602B 会溢出，
 *   按句柄顺序截断 —— OkCarLink 的记录（cafe 0x00010011 / Apple CarPlay
 *   0x00010012）固定排在第 17/18 位被丢，车机缓存不到 128 位 UUID。
 *   SDP_CreateRecord 为追加式顺序发号（句柄 = 末条 + 1），栈外无法改排序。
 *
 * 本模块的做法（不减少任何 profile，只挪时机）：
 *   1. 在 com.android.bluetooth 进程早期（AdapterService.onCreate 后、
 *      BT 栈读 bluetooth.profile.* 属性之前）把五个未用 profile 置 false
 *      —— 配对期间的 SDP 发现少五条记录，OkCarLink 的记录全部进缓存；
 *   2. 首个配对完成（BondStateMachine.bondStateChangeCallback 报 BONDED）
 *      后把属性置回 true 并重载蓝牙 —— 五个 profile 全部回来，
 *      配对后的会话功能完整；
 *   3. 开关：/data/local/tmp/okcar-sdpfix-off 存在时完全不起作用。
 *
 * 影响窗口（如实）：从 BT 进程启动到首个配对完成之间，OPP/SAP/MAP/PAN
 * 不可用；该窗口内不配对的话，可用 WebUI 的 bt-sdp restore 手动恢复。
 */
public class OkCarSdpFix implements IXposedHookLoadPackage {
    private static final String TAG = "OkCarSdpFix";
    private static final String OFF_SWITCH = "/data/local/tmp/okcar-sdpfix-off";
    static final String[] TRIM_PROPS = {
        "bluetooth.profile.opp.enabled",
        "bluetooth.profile.sap.server.enabled",
        "bluetooth.profile.map.server.enabled",
        "bluetooth.profile.pan.nap.enabled",
        "bluetooth.profile.pan.panu.enabled",
    };
    private static final String BT_PACKAGE = "com.android.bluetooth";
    private static boolean restored = false;

    static void setProps(String value) {
        for (String p : TRIM_PROPS) {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                sp.getMethod("set", String.class, String.class).invoke(null, p, value);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": setprop " + p + "=" + value + " failed: " + t);
            }
        }
    }

    static void reloadBluetooth(Object adapterService) {
        if (adapterService == null) {
            XposedBridge.log(TAG + ": reload skipped (no AdapterService)");
            return;
        }
        try {
            XposedHelpers.callMethod(adapterService, "disable");
            new Thread(new DelayedEnable(adapterService)).start();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": reload failed: " + t);
        }
    }

    private static final class DelayedEnable implements Runnable {
        private final Object adapterService;
        DelayedEnable(Object adapterService) { this.adapterService = adapterService; }
        @Override
        public void run() {
            try {
                Thread.sleep(2500);
                XposedHelpers.callMethod(adapterService, "enable");
                XposedBridge.log(TAG + ": bluetooth reloaded (profiles restored)");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": enable failed: " + t);
            }
        }
    }

    private final class OnCreateHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (new File(OFF_SWITCH).exists()) {
                XposedBridge.log(TAG + ": off switch present - inactive");
                return;
            }
            setProps("false");
            XposedBridge.log(TAG + ": trim applied at AdapterService.onCreate");
        }
    }

    private final class BondHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (restored || new File(OFF_SWITCH).exists()) return;
            boolean bonded = false;
            for (Object a : param.args) {
                if (a instanceof Integer && (Integer) a == 12) bonded = true; // BOND_BONDED
            }
            if (!bonded) return;
            restored = true;
            setProps("true");
            XposedBridge.log(TAG + ": first bond completed - restoring profiles");
            Object adapterService =
                    XposedHelpers.getObjectField(param.thisObject, "mAdapterService");
            reloadBluetooth(adapterService);
        }
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!BT_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": loaded into " + BT_PACKAGE);

        final ClassLoader cl = lpparam.classLoader;

        // 1) AdapterService.onCreate —— BT 栈读 profile 属性之前的一个可靠时点。
        try {
            Class<?> adapter = XposedHelpers.findClass(
                    "com.android.bluetooth.btservice.AdapterService", cl);
            XposedHelpers.findAndHookMethod(adapter, "onCreate", new OnCreateHook());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": onCreate hook failed: " + t);
        }

        // 2) 首个配对完成 —— 属性回 true + 重载蓝牙（profile 回来）。
        try {
            Class<?> bond = XposedHelpers.findClass(
                    "com.android.bluetooth.btservice.BondStateMachine", cl);
            XposedHelpers.findAndHookMethod(bond, "bondStateChangeCallback",
                    int.class, byte[].class, int.class, int.class, new BondHook());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bond hook failed: " + t);
        }
    }
}
