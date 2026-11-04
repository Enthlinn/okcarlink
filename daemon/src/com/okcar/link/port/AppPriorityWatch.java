package com.okcar.link.port;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * App priority watch: raise the processes of configured apps per the panel config (nice + top-app cpuset).
 *
 * <p>Why: while casting, starting a new app on the phone lets the launch animation's transient preemption shove the
 * player/encoder thread aside, dropping a few frames (§12.63 measured, not saturation). The user asked that the
 * operator pick "which apps are worth protecting", so the config lives in the panel and this class only applies it.
 *
 * <p>The two steps are deliberately the most reliable ones (root available, measured working):
 * <ol>
 *   <li>{@code /system/bin/renice -n -10 -p <pid>}: nice 0 → -10 (field-measured effective);</li>
 *   <li>append the pid to {@code /dev/cpuset/top-app/tasks}: take the foreground scheduling group's CPU share.</li>
 * </ol>
 * A process restart (changed pid) raises it again; once the config is empty nothing is done (already-raised pids are
 * not lowered again — that would need the original values recorded, and lowering could interrupt ongoing playback).
 */
public final class AppPriorityWatch {

    private static final String TAG = "OkCarLink/AppPriority";

    /** Watch interval: enough to keep up with an app being launched, without scanning /proc too often. */
    private static final long INTERVAL_MS = 3_000L;

    private final File mConf;

    private volatile boolean mRunning;
    private Thread mThread;

    /** Pids already raised (tracked per package name), used to spot "the app restarted, raise it again". */
    private final Map<String, Integer> mRaised = new HashMap<>();

    public AppPriorityWatch() {
        this(new File(AppPriorityPolicy.CONF_PATH));
    }

    public AppPriorityWatch(File conf) {
        mConf = conf;
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        final Thread t = new Thread(() -> {
            while (mRunning) {
                try {
                    tick();
                } catch (Throwable e) {
                    Log.w(TAG, "priority watch: " + e);
                }
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "okcar-app-priority");
        t.setDaemon(true);
        mThread = t;
        t.start();
    }

    public void stop() {
        mRunning = false;
        final Thread t = mThread;
        mThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** One round: read config → scan processes → raise what should be raised. An empty config skips even scanning /proc. */
    void tick() {
        final List<String> packages = AppPriorityPolicy.parsePackages(readConf());
        if (packages.isEmpty()) {
            mRaised.clear();
            return;
        }
        final File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            final String name = entry.getName();
            if (name.isEmpty() || name.charAt(0) < '0' || name.charAt(0) > '9') {
                continue;
            }
            final int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            final String cmdline = readCmdline(entry);
            if (!AppPriorityPolicy.matches(cmdline, packages)) {
                continue;
            }
            final String pkg = cmdline;
            final Integer already = mRaised.get(pkg);
            if (!AppPriorityPolicy.needsRaise(already == null ? -1 : already, pid)) {
                continue;
            }
            if (raise(pid)) {
                mRaised.put(pkg, pid);
            }
        }
    }

    /** Raise one process; either of the two steps succeeding counts as success (the log states which step ran). */
    private boolean raise(int pid) {
        boolean ok = false;
        try {
            final Process p = Runtime.getRuntime().exec(new String[] {
                    "/system/bin/renice", "-n", String.valueOf(AppPriorityPolicy.NICE),
                    "-p", String.valueOf(pid)});
            ok = p.waitFor() == 0;
        } catch (Throwable e) {
            Log.w(TAG, "renice " + pid + " failed: " + e);
        }
        boolean cpuset = false;
        try (FileOutputStream out = new FileOutputStream("/dev/cpuset/top-app/tasks", true)) {
            out.write((pid + "\n").getBytes("UTF-8"));
            cpuset = true;
        } catch (Throwable e) {
            /* Devices without cpuset are normal; a successful renice is enough */
        }
        if (ok || cpuset) {
            Log.i(TAG, "raised pid=" + pid + " nice=" + AppPriorityPolicy.NICE
                    + " cpuset=" + cpuset);
            return true;
        }
        return false;
    }

    private String readConf() {
        if (!mConf.isFile()) {
            return "";
        }
        try (FileInputStream in = new FileInputStream(mConf)) {
            final byte[] buffer = new byte[(int) Math.max(1, Math.min(mConf.length(), 32_768))];
            final int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static String readCmdline(File procDir) {
        try (FileInputStream in = new FileInputStream(new File(procDir, "cmdline"))) {
            final byte[] buffer = new byte[256];
            final int read = in.read(buffer);
            if (read <= 0) {
                return null;
            }
            int end = 0;
            while (end < read && buffer[end] != 0) {
                end++;
            }
            return new String(buffer, 0, end, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
