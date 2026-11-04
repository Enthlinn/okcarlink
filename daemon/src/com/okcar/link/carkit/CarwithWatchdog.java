package com.okcar.link.carkit;

import java.io.File;
import java.io.FileInputStream;

/**
 * CarWith 存活看护（仅在联动模式启用）。
 *
 * <p>为什么需要：CarWith 是这条链的根进程——投屏虚拟屏归它所有，它一死
 * （`Virtual display device released because application token died`）屏就被系统回收，
 * 接收端立刻只剩心跳、画面冻住。模块侧原有的看护只管自己的守护进程与虚拟屏放置，
 * 不管 CarWith 本身。这里只做一件保守的事：联动模式下确认它**连续两次**不在、且过了冷却，
 * 就重启它一次；重启后 want 里的 `castlaunch=1` 会让 APK 重新建屏。
 *
 * <p>判定与动作分开写：{@link #shouldRestart} 是纯函数（可直接在宿主上断言），
 * {@link #watchLoop} 只负责读 /proc 与执行 am。连续两次是为了避开"进程正在重启"的窗口，
 * 冷却是为了避免重启风暴（CarWith 起不来时每 5 s 打一次 am 只会更糟）。
 */
public final class CarwithWatchdog {

    public static final String PACKAGE = "com.miui.carlink";
    /** 与 APK castlaunch 里 launcher 首页一致：重启后 APK 会照旧接管它建的屏。 */
    public static final String HOME_ACTIVITY =
            PACKAGE + "/com.carwith.launcher.home.HomeActivity";

    /** 扫描周期：CarWith 猝死后最坏 2 个周期被发现，足够快又不至于频繁扫 /proc。 */
    static final long INTERVAL_MS = 5_000L;
    /** 连续两次读不到才算死：一次读不到可能只是它正在被系统重启。 */
    static final int MISSES_BEFORE_RESTART = 2;
    /** 重启冷却：留出重启 + APK 重新 castlaunch 的时间，避免把 am start 打成风暴。 */
    static final long RESTART_COOLDOWN_MS = 30_000L;

    /** 纯函数：丢了几次、当前时间、上次重启时间 → 现在该不该重启。 */
    public static boolean shouldRestart(int consecutiveMisses, long nowMs, long lastRestartMs) {
        if (consecutiveMisses < MISSES_BEFORE_RESTART) {
            return false;
        }
        return lastRestartMs <= 0L || nowMs - lastRestartMs >= RESTART_COOLDOWN_MS;
    }

    private volatile boolean mRunning;
    private Thread mThread;
    private int mMisses;
    private long mLastRestartMs;
    private long mRestarts;
    private int mLastPid = -1;

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        final Thread t = new Thread(this::watchLoop, "okcar-carwith-watch");
        t.setDaemon(true);
        mThread = t;
        t.start();
        System.out.println("linkd: carwith watch on (" + PACKAGE + ", every " + INTERVAL_MS
                + "ms, restart after " + MISSES_BEFORE_RESTART + " misses, cooldown "
                + RESTART_COOLDOWN_MS + "ms)");
    }

    public void stop() {
        mRunning = false;
        final Thread t = mThread;
        mThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    public String describe() {
        return "carwith-watch running=" + mRunning + " misses=" + mMisses + " restarts=" + mRestarts
                + " lastPid=" + mLastPid;
    }

    private void watchLoop() {
        while (mRunning) {
            try {
                tick();
            } catch (Throwable t) {
                System.out.println("linkd: carwith watch: " + t);
            }
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** 一轮：找进程 → 丢没丢 → 该不该重启。 */
    void tick() {
        final int pid = findPid();
        final long nowMs = android.os.SystemClock.elapsedRealtime();
        if (pid > 0) {
            if (mMisses > 0) {
                System.out.println("linkd: carwith watch: back (pid=" + pid + ")");
            }
            mMisses = 0;
            mLastPid = pid;
            return;
        }
        mMisses++;
        if (!shouldRestart(mMisses, nowMs, mLastRestartMs)) {
            if (mMisses == 1) {
                System.out.println("linkd: carwith watch: " + PACKAGE
                        + " not found (first miss — waiting one more round before acting)");
            }
            return;
        }
        restart(nowMs);
    }

    /** 重启一次；命令与 APK 的 castlaunch 用同一个 launcher 首页。 */
    void restart(long nowMs) {
        mLastRestartMs = nowMs;
        mRestarts++;
        System.out.println("linkd: carwith watch: " + PACKAGE + " is gone (misses=" + mMisses
                + ") — restarting it (restart #" + mRestarts + "); the APK's castlaunch rebuilds"
                + " the cast display afterwards");
        try {
            /* 绝对路径：守护进程是裸 app_process，PATH 里没有 am（第一次实测就栽在这里——
             * 命令"跑了"但 CarWith 没起来，因为 sh 报的是 am: not found，而输出被丢掉了）。 */
            final Process p = Runtime.getRuntime().exec(new String[] {
                    "/system/bin/sh", "-c",
                    "/system/bin/am force-stop " + PACKAGE
                            + "; /system/bin/am start --user 0 -n " + HOME_ACTIVITY});
            final String out = readShort(p);
            final int rc = p.waitFor();
            System.out.println("linkd: carwith watch: restart rc=" + rc
                    + (out.isEmpty() ? "" : " out=" + out));
        } catch (Throwable e) {
            System.out.println("linkd: carwith watch: restart failed: " + e);
        }
    }

    /** 有界读进程输出（只留首行前 160 字符），并把两个流都关掉防止管道写满。 */
    private static String readShort(Process p) {
        final StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line = r.readLine();
            while (line != null && sb.length() < 160) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(line.trim());
                line = r.readLine();
            }
        } catch (Throwable ignored) {
            /* 读不到就当没有 */
        }
        try {
            p.getErrorStream().close();
        } catch (Throwable ignored) {
            /* nothing to do */
        }
        return sb.length() > 160 ? sb.substring(0, 160) : sb.toString();
    }

    /** 扫 /proc 找 cmdline 就是包名的那条进程；找不到返回 -1。 */
    int findPid() {
        final File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return -1;
        }
        for (File entry : entries) {
            final String name = entry.getName();
            if (name.isEmpty() || name.charAt(0) < '0' || name.charAt(0) > '9') {
                continue;
            }
            final String cmdline = readCmdline(entry);
            if (cmdline == null) {
                continue;
            }
            /* app_process 起的进程 cmdline 就是包名；带 :remote 的子进程不算。 */
            if (cmdline.equals(PACKAGE)) {
                try {
                    return Integer.parseInt(name);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String readCmdline(File procDir) {
        try (FileInputStream in = new FileInputStream(new File(procDir, "cmdline"))) {
            final byte[] buf = new byte[256];
            final int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            int end = 0;
            while (end < n && buf[end] != 0) {
                end++;
            }
            return new String(buf, 0, end, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
