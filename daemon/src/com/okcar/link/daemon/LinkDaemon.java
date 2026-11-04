/*
 * OkcarOS link stack -- headless root entry point (KernelSU module, no APK).
 *
 * The stack used to live inside an Android app: LinkActivity drove it, OkCarLinkService hosted
 * it, and MIUI's app-facing restrictions decided what it was allowed to do (background activity
 * starts, per-network MAC randomisation, MediaProjection consent, app-scoped capture, install
 * signature checks).  The module runs the same compiled classes as a root app_process instead:
 * no manifest, no activity, no permission prompts, and the privileged pieces -- virtual display,
 * encoder, input injection, Wi-Fi and Bluetooth control -- belong to the process itself.
 *
 *   CLASSPATH=<module>/payload/okcar-link.dex app_process /system/bin \
 *       com.okcar.link.daemon.LinkDaemon [--app <pkg>] [--device-id <id>] [--display WxH]
 *                                      [--density <dpi>] [--bt-identity auto]
 *
 * Commands on stdin, one per line:
 *   start | stop | status | quit
 *   app <package>            place that app on our virtual display (empty clears it)
 *   connect <mac>            dial the accessory's iAP2 service (device-initiated receivers)
 *   tap <x> <y> [holdMs]     one synthetic touch through the receiver's own injection path
 *   place                    retry placing the current app
 *   display up [WxH] | down  warm-up display for a pre-session check / release it
 *   size auto|WxH            pin the connected unit's screen (saved per unit per orientation);
 *                            auto = forget it, follow the unit
 *   codec auto|h264|hevc     pin the connected unit's codec (saved per unit)
 *   bt auto|off              whether a session start may repair the Bluetooth identity itself
 *                            (off by default: every BT change is an explicit switch)
 *
 * The display size is NOT baked in: --display is only the fallback the uinput backend is built
 * with before a session exists, and `size` defaults to auto.  The video face -- and therefore the
 * public virtual display the shared app runs on -- is sized per session from what the head unit
 * declares, or from what `size` pinned for that unit; it is never a constant and never this phone's
 * panel (see AccessoryMemory and the port design notes §4).
 */
package com.okcar.link.daemon;

import android.content.Context;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.okcar.link.port.PortBootstrap;
import com.okcar.link.port.RootAppDisplayFactory;
import com.okcar.link.wireless.WifiP2pGroupJoiner;
import com.okcar.link.wireless.WirelessCarPlaySession;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class LinkDaemon {

    private LinkDaemon() {
    }

    public static void main(String[] args) {
        /* Exit explicitly; don't let the JVM wait for non-daemon threads in DestroyJavaVM: measured on lmi that after a
         * session start failure main had already returned, but some self-created thread was not a daemon, so the process
         * "stayed alive doing nothing" — neither publishing the RFCOMM listener nor writing status, which from outside looks
         * like "the module is installed but the wireless session never listens". Returning from run() exits, letting the supervisor restart/record per policy instead of hanging silently. */
        int code = 0;
        try {
            setLifeLogDir(args);
            lifeLog("main entered pid=" + android.os.Process.myPid() + " uid="
                    + android.os.Process.myUid() + " args=" + java.util.Arrays.toString(args));
            run(args);
            lifeLog("run() returned normally — exiting with code " + code);
        } catch (Throwable t) {
            /* Don't swallow the exception: System.exit(0) in finally would discard the pending exception outright, giving
             * "starts then vanishes, rc=0, no stack trace", and the field cause could never be found (learned the hard way). Print the stack and exit with a failure code. */
            t.printStackTrace();
            lifeLog("main caught " + t + " — exiting with code 1");
            code = 1;
        } finally {
            System.out.flush();
            System.exit(code);
        }
    }

private static void run(String[] args) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        /* One bad session thread must never take the module down.  By default an uncaught exception
         * on a non-main thread is fatal for the whole process, and on 2026-10-02 exactly that
         * happened: the video encode thread dereferenced a codec that a session restart had already
         * released, the daemon died, and the connected head unit then waited forever for a session
         * that no longer existed (the phone even stayed on the accessory's network).  Log the stack
         * and let the process — and its RFCOMM listener — live; the next dial starts a clean
         * session. */
        startSystemStallWatch();

        /* Keep-awake watch: a live cast must not let the phone fall asleep, or every touch the car sends is dropped. */
        startCastAwakeWatch();

        /* MIUI synergy mode: on a Xiaomi phone this replaces the blunt keep-awake answer with what
         * CarWith does — the phone sleeps normally and stays dark, yet injected input is still
         * dispatched. See MiuiSynergyMode for the mechanism and the field evidence. */
        startSynergyModeWatch();

        /* App priority watch: apps listed in the panel are raised nice and joined to the top-app cpuset (user-selected, see §12.67). */
        try {
            final com.okcar.link.port.AppPriorityWatch priority =
                    new com.okcar.link.port.AppPriorityWatch();
            priority.start();
            System.out.println("linkd: app priority watch started (conf "
                    + com.okcar.link.port.AppPriorityPolicy.CONF_PATH + ')');
        } catch (Throwable t) {
            System.out.println("linkd: app priority watch not started: " + t);
        }

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                final java.io.StringWriter writer = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(writer));
                System.out.println("linkd: uncaught exception in thread " + thread.getName()
                        + " — keeping the daemon alive:" + System.lineSeparator() + writer);
            }
        });

        /* On lmi, app_process's stdout is swallowed completely (not even com.android.commands.am.Am prints), so field
         * incidents like "starts then vanishes" cannot be found via System.out — key points also write a logcat copy. */
        android.util.Log.i(TAG_DAEMON, "run() entered args=" + java.util.Arrays.toString(args));
        String deviceId = null;
        String btProxy = null;
        String appPackage = "";
        String appUi = "";
        String accessory = null;
        String dataDir = DEFAULT_DATA_DIR;
        boolean btIdentity = false;
        boolean hostOnPhone = true;
        boolean keeperEnabled = true;
        boolean mirrorMode = false;
        /* --mode carwith 即"联动"：屏由 APK 侧建、内容由 APK 推，模块只做入口与会话转发。 */
        boolean coordinatedMode = false;
        int width = 0;
        int height = 0;
        int density = 0;
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--device-id".equals(args[i])) {
                deviceId = args[i + 1];
            } else if ("--bt-proxy".equals(args[i]) && i + 1 < args.length) {
                btProxy = args[i + 1];
            } else if ("--mode".equals(args[i])) {
                coordinatedMode = "carwith".equalsIgnoreCase(args[i + 1].trim());
                /* mirror|desktop: the startup form of the FIFO's `mode`, for a build whose whole
                 * product is mirroring the phone's screen (the lite variant boots with
                 * `--mode mirror`).  It has to be known here rather than sent later: a display's
                 * flags are fixed when it is created, and a session may already be up. */
                mirrorMode = "mirror".equalsIgnoreCase(args[i + 1].trim());
            } else if ("--app".equals(args[i])) {
                appPackage = args[i + 1];
            } else if ("--app-ui".equals(args[i])) {
                appUi = args[i + 1];
            } else if ("--keeper".equals(args[i])) {
                /* off = never pull a task that wandered off back to the display (that move recreates
                 * the activity, so it is visible as an app restart). */
                keeperEnabled = !"off".equalsIgnoreCase(args[i + 1].trim());
            } else if ("--app-host".equals(args[i])) {
                /* off = the app must not appear on the phone at all (see
                 * RootAppDisplayFactory.setHostOnPhone). */
                hostOnPhone = !"off".equalsIgnoreCase(args[i + 1].trim());
            } else if ("--accessory".equals(args[i])) {
                accessory = args[i + 1];
            } else if ("--data-dir".equals(args[i])) {
                dataDir = args[i + 1];
            } else if ("--bt-identity".equals(args[i])) {
                btIdentity = "auto".equalsIgnoreCase(args[i + 1]);
            } else if ("--density".equals(args[i])) {
                try {
                    density = Integer.parseInt(args[i + 1].trim());
                } catch (NumberFormatException ignored) {
                    /* auto */
                }
            } else if ("--display".equals(args[i])) {
                final String[] parts = args[i + 1].toLowerCase(java.util.Locale.ROOT).split("x");
                if (parts.length == 2) {
                    try {
                        width = Integer.parseInt(parts[0].trim());
                        height = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                        /* keep auto */
                    }
                }
            }
        }

        final Context context = systemContext();
        if (context == null) {
            System.out.println("linkd: no system context — cannot run the stack");
            android.util.Log.w(TAG_DAEMON, "no system context — returning from run()");
            return;
        }
        android.util.Log.i(TAG_DAEMON, "system context ok: " + context.getPackageName());
        sMainContext = context;
        giveDataDir(context, dataDir);
        initializeMainlineModules();

        /* The uinput device's ABS range is the panel's unrotated size (PortBootstrap reads that
         * itself); these values are only the fallback when it cannot be read.  The capture face is
         * resolved per session from the head unit's own declaration (never a constant). */
        if (width <= 0 || height <= 0) {
            final int[] size = logicalDisplaySize(context);
            width = size[0];
            height = size[1];
        }
        PortBootstrap.installDefaults(context, width, height);
        /* Bluetooth is never touched unless the operator asks for it: every BT-modifying
         * capability is an explicit switch (okcar-port-ctl / the module WebUI). */
        PortBootstrap.setAutoBtIdentity(btIdentity);
        /* The capture face: our own public virtual display, built per session at the size the
         * head unit declares and hosting the shared app (no MediaProjection, so no consent and
         * no MIUI restriction).  Playback capture keeps the REMOTE_SUBMIX default: uid 0 holds
         * CAPTURE_AUDIO_OUTPUT. */
        final RootAppDisplayFactory appDisplay =
                PortBootstrap.installRootCapture(context, appPackage, density);
        appDisplay.setAppUiComponent(appUi);
        appDisplay.setHostOnPhone(hostOnPhone);
        appDisplay.setKeeperEnabled(keeperEnabled);
        appDisplay.setMirrorMode(mirrorMode);
        if (coordinatedMode) {
            appDisplay.setModuleOwnsDisplay(true);
            final boolean markedOnBoot = com.okcar.link.castlink.CastLinkServer.setEnabled(true);
            final boolean wantedOnBoot = writeWant(true);
            /* 入口也要武装：桥给 CarWith 一个面，CarWith 才会建"它自己的"投屏屏；随后 APK 接管
             * 那个面、并把 CarWith 首页放上去——这样内容是 CarWith 自己的界面（持续变化），
             * 而不是我们探测出来的那一串。 */
            final String armedOnBoot = com.okcar.link.carkit.CarwithBridge.client()
                    .arm(1920, 720, 30);
            System.out.println("linkd: carwith coordinated at startup — moduleOwnsDisplay=true marker="
                    + markedOnBoot + " want=" + wantedOnBoot + " bridge=" + armedOnBoot);
            /* CarWith 是这条链的根进程：它一死，投屏虚拟屏被系统回收、接收端只剩心跳。看护只做
             * 「连续两次不在 + 过冷却 → 重启一次」，重启后 want 里的 castlaunch 会让 APK 重新建屏。 */
            if (sCarwithWatch == null) {
                sCarwithWatch = new com.okcar.link.carkit.CarwithWatchdog();
                sCarwithWatch.start();
            }
        }
        /* Remember what the desktop is: `desktop` / `mode desktop` must be able to restore it. */
        sDesktopApp = appPackage;
        sDesktopUi = appUi;
        sLastAppPackage = appPackage;
        sLastAppUi = appUi;
        System.out.println("linkd: root backends installed, fallback display " + width + 'x' + height
                + ", shared app=" + (appPackage.isEmpty() ? "(none)" : appPackage)
                + (appUi.isEmpty() ? "" : ", ui activity=" + appUi));
        /* CarWith 组件桥（2026-10-07 方向修正）：CarWith 起组件、组件绑 CarWith 的
         * CarlifeConnService，但**视频面由我们出**——组件通过本桥向 root 守护进程要一块"编码器输入
         * 面"，再放进 CastConfig 交给 CarWith；CarWith 拿它建虚拟屏并把桌面画进去
         * （dex 证据见 com.okcar.link.carkit.CarwithContract 的注释）。
         * 注册失败（SELinux 等）只记一行，不影响其它功能。 */
        com.okcar.link.carkit.CarwithBridge.install(context);
        /* The WebUI's app picker needs labels and launcher components, which only PackageManager can
         * give: `pm list packages` returns bare package names.  Dump the inventory once here so the
         * picker has something to read before anyone presses Refresh (and again on `apps`). */
        dumpAppInventory(context);

        final WirelessCarPlaySession session = WirelessCarPlaySession.get();
        /* A previous run may have died between the two halves of a radio toggle.  Repair that first,
         * so no start can come up with Wi-Fi off and need a human tap (measured 2026-10-02). */
        com.okcar.link.wireless.WifiGroupJoiner.restoreRadioIfPending(context);
        System.out.println("linkd: starting the wireless session (device id " + deviceId + ')');
        if (btProxy != null) {
            session.setBtProxy(btProxy);
        }
        final boolean started = session.start(context, deviceId);
        System.out.println("linkd: start=" + started + " status=" + session.status());
        /* Replace whatever the previous process (or an earlier session of this one) left in the
         * status file, so a reader can never mistake it for live state. */
        writeStatusFile(session.status() + '\n' + appDisplay.describe());
        /* Display 2 is OFF by default and is not built here: only a daemon that has already been
         * asked for cast2 (its config directory exists) gets a state snapshot written.  Everything
         * starts on the explicit `cast2 up` / `cast2 listen on` / `rtsp up` commands. */
        try {
            if (new java.io.File(com.okcar.link.stream2.Cast2Config.DIR).isDirectory()
                    || new java.io.File(com.okcar.link.stream2.Cast2Config.EXTRA_CONF).isFile()) {
                final com.okcar.link.stream2.Cast2Controller controller = ensureCast2(context);
                controller.writeStateFile("daemon startup (cast2 down)");
                System.out.println("linkd: cast2 controller ready (down) — " + controller.describe());
            }
        } catch (Throwable t) {
            System.out.println("linkd: cast2 controller unavailable: " + t);
        }
        if (!started) {
            /* The boot race is not always over by the time start() gives up waiting: the daemon comes
             * up in the late_start service stage, and on 217 the first start after a reboot still
             * answered "no Bluetooth adapter (waited 60s)".  Without a retry the RFCOMM listener is
             * never published and the head unit reports "temporarily can't find iPhone" until someone restarts the
             * daemon by hand — measured exactly that.  Keep trying in the background; start() returns
             * immediately once the session is running, so this is idempotent. */
            final String retryDeviceId = deviceId;
            final Thread retry = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int attempt = 1; attempt <= 60; attempt++) {
                        try {
                            Thread.sleep(30_000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (session.isRunning()) {
                            return;
                        }
                        final boolean ok = session.start(context, retryDeviceId);
                        System.out.println("linkd: bluetooth retry " + attempt + " start=" + ok
                                + " status=" + session.status());
                        System.out.flush();
                    }
                }
            }, "okcar-linkd-bt-retry");
            retry.setDaemon(true);
            retry.start();
        }
        if (accessory != null && !accessory.isEmpty()) {
            /* The device-initiated half: some receivers (Fruit Link's trial flow, the integrator/the production head unit)
             * wait for the phone to dial their iAP2 service before handing over Wi-Fi credentials. */
            final boolean dialling = session.connectToAccessory(accessory);
            System.out.println("linkd: connectAccessory " + accessory + " -> "
                    + (dialling ? "dialling" : "not started"));
        }

        /* Keep the process alive as the module's daemon and accept a tiny command surface; the
         * WebUI (webroot/) and okcar-port-ctl write to the supervisor's FIFO, which is this
         * process's stdin.  EOF is NOT a reason to exit: the FIFO has no writer most of the time
         * (every `echo start > fifo` opens and closes one), so the loop simply waits for the next
         * command and only `quit` ends the daemon. */
        restoreCarkitHost();
        watchCarkitWanted();
        try {
            final BufferedReader in =
                    new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            while (true) {
                final String line;
                try {
                    line = in.readLine();
                } catch (java.io.IOException e) {
                    Thread.sleep(500);
                    continue;
                }
                if (line == null) {
                    Thread.sleep(500);
                    continue;
                }
                final String command = line.trim();
                if (command.isEmpty()) {
                    continue;
                }
                try {
                    handle(command, session, appDisplay, context, deviceId);
                } catch (Throwable t) {
                    System.out.println("linkd: command '" + command + "' failed: " + t);
                }
                System.out.flush();
                if ("quit".equals(command) || "exit".equals(command)) {
                    return;
                }
            }
        } catch (Exception e) {
            System.out.println("linkd: command loop failed (" + e + ')');
            android.util.Log.w(TAG_DAEMON, "command loop failed: " + e, e);
        }
        android.util.Log.w(TAG_DAEMON, "command loop ended — run() is returning now"
                + " (only `quit` should do this; EOF must loop)");
        session.shutdown();
        /* Display 2, its encoder and its listeners go down with the daemon: a released process must
         * not leave an encoder surface or a bound RTSP port behind for the next one. */
        shutdownCast2();
        /* Leaving a final snapshot (not the last one somebody asked for) keeps a stopped daemon
         * from looking like a live session. */
        writeStatusFile("daemon exiting; session stopped");
    }

    /** The desktop app the daemon was configured with, so the desktop can always come back. */
    /**
     * Daemon lifeline log, written straight to a file.
     *
     * <p>On lmi, {@code app_process} stdout is swallowed completely and logcat may not show it either; but the
     * "starts then vanishes, rc=0" incident must leave evidence, so main entry, run() return and caught exception
     * each append a line to this file. Diagnostics only; it does not affect any business path.
     */
    private static void lifeLog(String message) {
        final String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                java.util.Locale.US).format(new java.util.Date()) + " " + message
                + System.lineSeparator();
        final String dir = sLifeLogDir;
        try {
            final java.io.FileWriter writer =
                    new java.io.FileWriter(dir + "/okcar-daemon-life.log", true);
            writer.write(line);
            writer.close();
        } catch (Throwable t) {
            System.out.println("linkd: lifeLog failed (" + dir + "): " + t);
        }
    }

    /**
     * Lifeline log directory: the first directory argument of {@code --data-dir}, default /data/local/tmp.
     *
     * <p>It must follow the data dir: /data/local/tmp is not writable when running as system uid (owned by root:shell),
     * so a "starts then vanishes" incident would have neither stdout (swallowed) nor lifeline, looking like main was never entered (learned the hard way).
     */
    private static void setLifeLogDir(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--data-dir".equals(args[i]) && args[i + 1] != null && !args[i + 1].isEmpty()) {
                sLifeLogDir = args[i + 1];
                return;
            }
        }
    }

    private static volatile String sLifeLogDir = "/data/local/tmp";

    private static final String TAG_DAEMON = "OkCarLink/Daemon";

    private static final String STATUS_FILE = "/data/local/tmp/okcar-linkd.status";

    private static String sDesktopApp = "";
    private static String sDesktopUi = "";
    /**
     * The last non-empty target app this daemon was told about.
     *
     * <p>Mirror mode legitimately has no app, so {@code appDisplay} is cleared for it; without this
     * memory, switching back to desktop (or a UI that persisted the clear) left the display empty
     * and the car screen black.  Startup args seed it, every {@code send app <pkg>} updates it.
     */
    private static String sLastAppPackage = "";
    private static String sLastAppUi = "";
    /** What the desktop asked us to cast, so "desktop" can stop it again. */
    private static String sCastPackage = "";

    /**
     * Wired CarPlay device role (217 as the iPhone): accepts the carkit service stream that
     * okcar-ffs-usbmux bridges to the abstract socket {@code okcar-carkit} and runs iAP2 on it.
     * Off until {@code send carkit on} — the wireless listener remains the default path.
     */
    private static final com.okcar.link.carkit.CarkitServiceHost sCarkitHost =
            new com.okcar.link.carkit.CarkitServiceHost();

    /**
     * Remembers that the operator wanted the wired listener, so a restart does not silently drop it.
     * A mode switch (app &lt;-&gt; mirror) restarts this process, and a host rebuilding its CarPlay stack
     * at that moment hits {@code carkit: no listener on abstract socket 'okcar-carkit' (errno=111)}
     * and resets the session (measured 2026-10-06).  The module wrapper used to re-send `carkit on`
     * through the FIFO right after launch, which correlated with a mirror-mode restart loop; the
     * request belongs here, where the process itself knows it is ready.
     */
    private static final String CARKIT_WANTED_FILE = "/data/local/tmp/okcar-carkit.wanted";

    private static void rememberCarkitWanted(boolean wanted) {
        try {
            final java.io.FileOutputStream out =
                    new java.io.FileOutputStream(CARKIT_WANTED_FILE);
            out.write((wanted ? "on" : "off").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
            new java.io.File(CARKIT_WANTED_FILE).setReadable(true, false);
        } catch (Throwable t) {
            System.out.println("linkd: could not remember the carkit request: " + t);
        }
    }

    private static boolean carkitWantedFromFile() {
        try {
            final byte[] raw = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(CARKIT_WANTED_FILE));
            return "on".equals(new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Keeps the wired listener in step with the operator's switch by polling the request file.
     *
     * <p>{@code okcar-port-ctl switch wired on} writes that file; nobody has to poke this process at
     * the right instant.  The old design did poke the command FIFO from the module's boot path, and
     * on 2026-10-06 that write correlated with the daemon restarting every 3 s in mirror mode (a
     * fresh process has no listener, the host rebuilt its stack, hit errno=111, and the switch tried
     * again).  Following a file removes the whole timing class: a restart now re-arms itself, and a
     * factory-fresh install gets a listener as soon as the switch is turned on.
     */
    private static void watchCarkitWanted() {
        final Thread watch = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        final boolean want = carkitWantedFromFile();
                        if (want == sCarkitHost.isRunning()) {
                            continue;
                        }
                        if (want) {
                            final boolean listening = sCarkitHost.start();
                            System.out.println("linkd: carkit host "
                                    + (listening ? "listening" : "FAILED to listen")
                                    + " (the wired switch asked for it)");
                        } else {
                            sCarkitHost.stop();
                            System.out.println("linkd: carkit host stopped"
                                    + " (the wired switch turned it off)");
                        }
                    } catch (Throwable t) {
                        System.out.println("linkd: carkit reconcile failed: " + t);
                    }
                }
            }
        }, "okcar-carkit-watch");
        watch.setDaemon(true);
        watch.start();
    }

    /** Starts the wired listener when a previous life was asked for it; called once at startup. */
    private static void restoreCarkitHost() {
        if (!carkitWantedFromFile()) {
            return;
        }
        final boolean listening = sCarkitHost.start();
        System.out.println("linkd: carkit host " + (listening ? "listening" : "FAILED to listen")
                + " (restored from " + CARKIT_WANTED_FILE + ')');
    }

    /**
     * Writes the status snapshot the car-screen desktop reads.
     *
     * <p>Always timestamped, and written by whoever knows the current state: the daemon at startup,
     * on every {@code status}, and on the way out.  Without that the file is a snapshot of the last
     * time somebody asked, and it gets read as if it described <em>now</em> — after a daemon restart
     * it still showed the previous session's accessory, screen and geometry, which is a leftover
     * presented as live state (measured on 217: a restart at 14:47 still displayed the 14:09 the emulator
     * session, in the desktop's status line and to anyone debugging from it).
     */
    private static void writeStatusFile(String text) {
        final String stamped = "# status snapshot "
                + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date())
                + " (" + System.currentTimeMillis() + ")\n" + text;
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(new java.io.File(STATUS_FILE));
            out.write(stamped.getBytes("UTF-8"));
            out.flush();
            new java.io.File(STATUS_FILE).setReadable(true, false);
        } catch (Throwable t) {
            System.out.println("linkd: could not write " + STATUS_FILE + ": " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    /* best effort */
                }
            }
        }
    }

    /** 只记日志的接收端 sink：bring-up 时不接媒体，只看收到了什么。 */
    private static final class CastLinkCounter implements com.okcar.link.castlink.CastLinkServer.Sink {
        private volatile long configs;
        private volatile long frames;
        private volatile long audioPackets;
        private volatile long bytes;

        @Override
        public void config(byte[] sps, byte[] pps) {
            configs++;
            System.out.println("castlink: config sps=" + sps.length + " pps=" + pps.length);
        }

        @Override
        public void frame(byte[] data, boolean keyFrame, long ptsMs) {
            frames++;
            bytes += data.length;
            if (frames <= 3 || keyFrame || frames % 30 == 0) {
                System.out.println("castlink: frame #" + frames + " " + data.length + "B key=" + keyFrame
                        + " pts=" + ptsMs);
            }
        }

        @Override
        public void audio(byte[] data, long ptsMs, int rate, int channels) {
            audioPackets++;
            bytes += data.length;
            if (audioPackets <= 2) {
                System.out.println("castlink: audio " + data.length + "B " + rate + "Hz/" + channels + "ch");
            }
        }

        String describe() {
            return "configs=" + configs + " frames=" + frames + " audio=" + audioPackets + " bytes=" + bytes;
        }
    }

    private static final CastLinkCounter S_CAST_LINK_COUNTER = new CastLinkCounter();

    /**
     * 联动通道：把"期望状态"写给 APK（世界可读，CarWith 侧的 LSPosed 模块每秒读一次）。
     * 用文件而不是 am/跨进程调用，因为 CarWith 的 uid 不能写 /data/local/tmp，而守护进程是 root。
     */
    private static boolean writeWant(boolean on) {
        final java.io.File file = new java.io.File("/data/local/tmp/okcar-cast.want");
        try {
            final StringBuilder sb = new StringBuilder();
            sb.append("takeSurface=").append(on ? 1 : 0).append('\n');
            sb.append("link=").append(on ? 1 : 0).append('\n');
            /* 这一条是"触发 CarWith 建它自己的投屏屏"的手段：实测光给面（桥 armed + surface
             * published）不够——CarWith 的 castCallback ready=false、prepared=false、displayId=-1，
             * 只有 APK 侧合成控制器调 displayConnected，CarWith 才会真的建屏。 */
            sb.append("castlaunch=").append(on ? 1 : 0).append('\n');
            /* 联动模式下触摸由 APK 注入到 CarWith 那块屏：不开这个开关，回传的触摸只会 dry-run */
            sb.append("injectTouch=").append(on ? 1 : 0).append('\n');
            /* 有线 ICCOA 的判定由 APK 合成（不插线）：**默认关**，要做有线实验时用
             * `carwith wired on` 单独打开。
             *
             * 2026-10-09 现场证据：随联动一起开时，APK 会合成接入并替用户点 "连接"
             * （`wired: CarlinkService.k() invoked`），CarWith 随后在 castlaunch 之后约 11 秒发出
             * `trace.displayDisconnected` —— 刚建好的投屏屏被拆掉，画面冻在最后一帧、发送端只剩心跳。
             * 同一次会话里把 wired 置 0 再重启 CarWith，投屏屏不再消失（无 displayDisconnected），
             * 帧正常到接收端。而且 §3.9.4 已证明这条合成路推不动 CarlinkStateMachine，
             * 所以它不该是联动的默认组成部分。 */
            sb.append("wired=0").append('\n');
            final java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            file.setReadable(true, false);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 改 want 文件里的单个键（其它键保留）；文件不存在时创建一个只含该键的。 */
    private static boolean setWantKey(String key, String value) {
        final java.io.File file = new java.io.File("/data/local/tmp/okcar-cast.want");
        try {
            final StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            if (file.exists()) {
                final java.io.BufferedReader in =
                        new java.io.BufferedReader(new java.io.FileReader(file));
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith(key + "=")) {
                        sb.append(key).append('=').append(value).append('\n');
                        replaced = true;
                    } else if (!line.trim().isEmpty()) {
                        sb.append(line).append('\n');
                    }
                }
                in.close();
            }
            if (!replaced) {
                sb.append(key).append('=').append(value).append('\n');
            }
            final java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            file.setReadable(true, false);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** APK 发布的投屏屏几何：{displayId, width, height}，读不到返回 {-1,0,0}。 */
    public static int[] apkGeometry() {
        final String[] candidates = {
                "/data/data/com.okcar.cast/files/cast-display-id",
                "/data/data/com.miui.carlink/files/cast-display-id",
        };
        for (String candidate : candidates) {
            try {
                final java.io.File file = new java.io.File(candidate);
                if (!file.exists()) {
                    continue;
                }
                final java.io.BufferedReader in =
                        new java.io.BufferedReader(new java.io.FileReader(file));
                final String line = in.readLine();
                in.close();
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                final String[] parts = line.trim().split("\\s+");
                final int id = Integer.parseInt(parts[0]);
                final int width = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                final int height = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                return new int[]{id, width, height};
            } catch (Throwable ignored) {
                // 换下一个候选路径
            }
        }
        return new int[]{-1, 0, 0};
    }

    /** APK 写下的 CarWith 投屏屏 id（root 可读），联动定位就靠它。 */
    private static String apkDisplayId() {
        final int[] geo = apkGeometry();
        return geo[0] < 0 ? "none" : (geo[0] + (geo[1] > 0 ? " (" + geo[1] + "x" + geo[2] + ")" : ""));
    }

    /** 联动模式下的 CarWith 存活看护（它一死，投屏屏被系统回收、画面冻住）。 */
    private static volatile com.okcar.link.carkit.CarwithWatchdog sCarwithWatch;

    /** carwith status 一行：标记 / want / 投屏屏 id / 这一侧收到多少 / 桥状态 / 看护。 */
    private static String coordinatedState(RootAppDisplayFactory appDisplay) {
        final com.okcar.link.carkit.CarwithWatchdog watch = sCarwithWatch;
        return "moduleOwnsDisplay=" + appDisplay.moduleOwnsDisplay()
                + " marker=" + com.okcar.link.castlink.CastLinkServer.enabled()
                + " apkDisplayId=" + apkDisplayId()
                + " | " + com.okcar.link.castlink.CastLinkServer.describe()
                + " | " + com.okcar.link.carkit.CarwithBridge.describe()
                + (watch == null ? "" : " | " + watch.describe())
                /* CarWith 侧的状态机状态不在守护进程这边：APK 写 /data/local/tmp 会被 SELinux 挡
                 * （与 want 只能 root 写同一个原因），要看它就读 APK 的 logcat（statekick watch）。 */
                ;
    }

    private static void handleCastLink(String command) {
        final String arg = command.length() > 8 ? command.substring(8).trim() : "";
        if (arg.startsWith("off")) {
            com.okcar.link.castlink.CastLinkServer.get().detach();
            com.okcar.link.castlink.CastLinkServer.get().stop();
            System.out.println("castlink: stopped (" + S_CAST_LINK_COUNTER.describe() + ')');
            return;
        }
        if (arg.startsWith("status")) {
            System.out.println("castlink: " + S_CAST_LINK_COUNTER.describe());
            return;
        }
        com.okcar.link.castlink.CastLinkServer.get().attachForTest(S_CAST_LINK_COUNTER);
        System.out.println("castlink: listening on abstract socket okcar-cast (test sink)");
    }

    private static void handle(String command, WirelessCarPlaySession session,
                               RootAppDisplayFactory appDisplay, Context context, String deviceId)
            throws Exception {
        if ("start".equals(command)) {
            System.out.println("linkd: start=" + session.start(context, deviceId)
                    + " status=" + session.status());
            return;
        }
        if ("stop".equals(command)) {
            session.shutdown();
            System.out.println("linkd: stopped, status=" + session.status());
            return;
        }
        if ("apps".equals(command)) {
            dumpAppInventory(context);
            return;
        }
        if ("status".equals(command)) {
            final String status = session.status();
            System.out.println("linkd: status=" + status);
            final StringBuilder snapshot = new StringBuilder(status);
            /* 每一段各自兜住。2026-10-18 现场：`status` 因为某一段里的一个 NPE 整条失败，于是面板和
             * 命令行都拿不到状态——而它们正是用来判断"现在到底怎么卡住"的人。一段查不出来就写一段
             * <unavailable: ...>，其余照常输出。 */
            final java.util.List<String> parts = new java.util.ArrayList<String>();
            try {
                parts.add("linkd: display " + appDisplay.describe());
            } catch (Throwable t) {
                parts.add("linkd: display <unavailable: " + t + '>');
            }
            try {
                parts.add("linkd: bt-identity-auto=" + PortBootstrap.autoBtIdentity());
            } catch (Throwable t) {
                parts.add("linkd: bt-identity-auto <unavailable: " + t + '>');
            }
            final com.okcar.link.stream2.Cast2Controller cast2 = cast2();
            if (cast2 != null) {
                try {
                    parts.addAll(cast2.statusLines());
                } catch (Throwable t) {
                    parts.add("linkd: cast2 <unavailable: " + t + '>');
                }
            }
            for (String line : parts) {
                System.out.println(line);
                snapshot.append('\n').append(line);
            }
            writeStatusFile(snapshot.toString());
            return;
        }
        /* `cast2 ...` (display 2 + its RTSP stream) MUST be matched before the `cast` branch below:
         * that branch is `command.startsWith("cast")`, so it would otherwise swallow every cast2
         * command and read it as a package name.  `cast` itself keeps its exact previous behaviour. */
        if (command.startsWith("cast2")) {
            handleCast2(command);
            return;
        }
        /* `carwith on|off|status|key|touch|mic`: CarWith 作为画面来源（组件在 CarLife 槽位上把
         * 我们给的 Surface 交给 CarWith）。必须在 `cast` 分支之前匹配。 */
        if (command.startsWith("carwith")) {
            handleCarwith(command, appDisplay);
            return;
        }
        /* `rtsp up|down|status`: the RTSP listener for display 2 is a separate switch from the
         * display itself, so the head-unit program can have the listener ready first. */
        if (command.startsWith("rtsp")) {
            handleRtsp(command);
            return;
        }
        /* `castlink on|off|status`: 把 okcar-cast 接收端单独拉起来（不受启用标记限制），用一个只记
         * 日志的 sink 验证「APK → 线格式 → 落点」整条通道。车机会话路径仍走 attach 的门控。 */
        if (command.startsWith("castlink")) {
            handleCastLink(command);
            return;
        }
        if (command.startsWith("cast")) {
            /* cast <pkg> -- the desktop's app list.  A generic app has no car-screen activity of its
             * own, so its launcher activity on the display is the right thing. */
            final String pkg = command.length() > 4 ? command.substring(4).trim() : "";
            if (pkg.isEmpty()) {
                System.out.println("linkd: cast wants a package name");
                return;
            }
            sCastPackage = pkg;
            appDisplay.setAppUiComponent("");
            appDisplay.setAppPackage(pkg);
            final boolean placed = appDisplay.placeApp(true);
            System.out.println("linkd: cast " + pkg + " placed=" + placed
                    + " (" + appDisplay.describe() + ')');
            writeStatusFile(session.status() + '\n' + appDisplay.describe());
            return;
        }
        if ("desktop".equals(command)) {
            /* Back to our own screen: stop whatever was cast, then put the desktop there again. */
            if (!sCastPackage.isEmpty()) {
                final String cast = sCastPackage;
                sCastPackage = "";
                try {
                    Runtime.getRuntime().exec(new String[]{"/system/bin/am", "force-stop", cast});
                } catch (Throwable t) {
                    System.out.println("linkd: could not stop the cast app " + cast + ": " + t);
                }
            }
            if (!sDesktopApp.isEmpty()) {
                appDisplay.setAppUiComponent(sDesktopUi);
                appDisplay.setAppPackage(sDesktopApp);
            }
            final boolean placed = appDisplay.placeApp(true);
            System.out.println("linkd: desktop placed=" + placed
                    + " (" + appDisplay.describe() + ')');
            writeStatusFile(session.status() + '\n' + appDisplay.describe());
            return;
        }
        if ("quit".equals(command) || "exit".equals(command)) {
            session.shutdown();
            appDisplay.release();
            shutdownCast2();
            System.out.println("linkd: exiting");
            return;
        }
        if (command.startsWith("tap")) {
            /* tap <x> <y> [holdMs] -- drive the receiver's own injection path once, on demand.
             * The space defaults to the display the car's touches are being mapped into. */
            final String[] parts = command.length() > 3
                    ? command.substring(3).trim().split("\\s+") : new String[0];
            if (parts.length < 2) {
                System.out.println("linkd: tap wants <x> <y> [holdMs]");
                return;
            }
            final int x;
            final int y;
            long hold = 800L;
            try {
                x = Integer.parseInt(parts[0]);
                y = Integer.parseInt(parts[1]);
                if (parts.length > 2) {
                    hold = Long.parseLong(parts[2]);
                }
            } catch (NumberFormatException e) {
                System.out.println("linkd: tap wants numbers, got '" + command + '\'');
                return;
            }
            final com.okcar.link.input.InputInjector injector =
                    new com.okcar.link.input.InputInjector(context);
            final int[] space = com.okcar.link.port.Ports.touchDisplaySize();
            final int spaceWidth = space == null ? 0 : space[0];
            final int spaceHeight = space == null ? 0 : space[1];
            final boolean ok = injector.injectTap(x, y, spaceWidth, spaceHeight, hold);
            System.out.println("linkd: tap " + x + ',' + y + " hold=" + hold + "ms targetDisplay="
                    + com.okcar.link.port.Ports.touchDisplayId() + " space=" + spaceWidth + 'x'
                    + spaceHeight + " ok=" + ok);
            return;
        }
        if (command.startsWith("touch")) {
            /* touch <x> <y>：把一对 DOWN/UP 经 okcar-cast 发给 APK，验证"回传触摸→APK 注入"这条路。
             * 坐标按 -1（=用会话里的投屏屏）发，APK 自己解析成 displayId。 */
            final String[] parts = command.length() > 5
                    ? command.substring(5).trim().split("\\s+") : new String[0];
            if (parts.length < 2) {
                System.out.println("linkd: touch wants <x> <y>");
                return;
            }
            try {
                final float x = Float.parseFloat(parts[0]);
                final float y = Float.parseFloat(parts[1]);
                com.okcar.link.castlink.CastLinkServer.get().sendTouch(0, x, y, -1);
                com.okcar.link.castlink.CastLinkServer.get().sendTouch(1, x, y, -1);
                System.out.println("linkd: touch -> apk at " + x + ',' + y + " | "
                        + com.okcar.link.castlink.CastLinkServer.describe());
            } catch (NumberFormatException e) {
                System.out.println("linkd: touch wants numbers, got '" + command + '\'');
            }
            return;
        }
        if (command.startsWith("connect")) {
            final String mac = command.length() > 7 ? command.substring(7).trim() : "";
            if (mac.isEmpty()) {
                System.out.println("linkd: connect needs the accessory MAC");
                return;
            }
            System.out.println("linkd: connectAccessory " + mac + " -> "
                    + (session.connectToAccessory(mac) ? "dialling" : "not started"));
            return;
        }
        if (command.startsWith("module-display")) {
            /* module-display on|off: the display is built by the in-app LSPosed module instead of
             * here, which is what lets the app launch onto it (SafeActivityOptions owner check). */
            final String value = command.length() > 14 ? command.substring(14).trim() : "";
            if (!"on".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value)) {
                System.out.println("linkd: module-display wants on|off, got '" + value + '\'');
                return;
            }
            appDisplay.setModuleOwnsDisplay("on".equalsIgnoreCase(value));
            System.out.println("linkd: module-display=" + value);
            return;
        }
        if (command.startsWith("keeper")) {
            final String value = command.length() > 6 ? command.substring(6).trim() : "";
            if (!"on".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value)) {
                System.out.println("linkd: keeper wants on|off, got '" + value + '\'');
                return;
            }
            appDisplay.setKeeperEnabled("on".equalsIgnoreCase(value));
            System.out.println("linkd: keeper=" + value);
            return;
        }
        if (command.startsWith("app-host")) {
            /* app-host on|off: whether the app's normal instance may live on the phone's display.
             * off = car screen only (Pano/NeoPlayer's engine is its own process, so it works). */
            final String value = command.length() > 8 ? command.substring(8).trim() : "";
            if (!"on".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value)) {
                System.out.println("linkd: app-host wants on|off, got '" + value + '\'');
                return;
            }
            appDisplay.setHostOnPhone("on".equalsIgnoreCase(value));
            System.out.println("linkd: app-host=" + value + " (" + appDisplay.describe() + ')');
            return;
        }
        if (command.startsWith("app-ui")) {
            /* app-ui <pkg>/<activity> | app-ui - : the activity that provides the car screen's UI,
             * when the app needs its own launch activity running as a host (see
             * RootAppDisplayFactory.setAppUiComponent).  Must be tested before "app". */
            final String value = command.length() > 6 ? command.substring(6).trim() : "";
            appDisplay.setAppUiComponent("-".equals(value) ? "" : value);
            System.out.println("linkd: display " + appDisplay.describe());
            return;
        }
        if (command.startsWith("app")) {
            final String pkg = command.length() > 3 ? command.substring(3).trim() : "";
            appDisplay.setAppPackage(pkg);
            if (!pkg.isEmpty() && !"-".equals(pkg)) {
                sLastAppPackage = pkg;
            }
            System.out.println("linkd: display " + appDisplay.describe());
            return;
        }
        if (command.startsWith("mode")) {
            /* Back to our own desktop, or all the way to mirroring the phone screen: both are
             * session-level (a display's flags are fixed when it is created). */
            final String what = command.length() > 4 ? command.substring(4).trim() : "";
            final boolean mirror = "mirror".equals(what);
            appDisplay.setMirrorMode(mirror);
            /* Back to desktop: prefer what this daemon was started with, else whatever app it was
             * last told about, so a mode round-trip cannot leave the display empty. */
            final String app = !sDesktopApp.isEmpty() ? sDesktopApp : sLastAppPackage;
            final String ui = !sDesktopUi.isEmpty() ? sDesktopUi : sLastAppUi;
            appDisplay.setAppPackage(mirror ? "" : app);
            appDisplay.setAppUiComponent(mirror ? "" : ui);
            System.out.println("linkd: mode " + (mirror ? "mirror" : "desktop")
                    + " — restarting the session");
            session.stop();
            final boolean ok = session.start(context, deviceId);
            System.out.println("linkd: restart start=" + ok + " status=" + session.status());
            return;
        }
        if ("place".equals(command)) {
            /* Explicit "put the app there again": force a clean restart, because the operator only
             * asks for this when the car screen is wrong (typically white after a display rebuild
             * moved a task whose content never came back). */
            System.out.println("linkd: placed=" + appDisplay.placeApp(true)
                    + " (" + appDisplay.describe() + ')');
            return;
        }
        /* Exercise the Wi-Fi Direct join on its own, without a live accessory: a group owner
         * can be stood up on the other test device (the desktop p2p-group harness), and this is how the
         * joiner's real behaviour — p2p0 comes up, wlan0 keeps the LAN — is observed instead of
         * inferred from a session that may be failing for an unrelated reason. */
        if (command.startsWith("p2pjoin")) {
            final String rest = command.length() > 7 ? command.substring(7).trim() : "";
            final String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+", 2);
            if (parts.length == 0) {
                System.out.println("linkd: p2pjoin needs <ssid> [passphrase]");
                return;
            }
            final String passphrase = parts.length > 1 ? parts[1] : "";
            final WifiP2pGroupJoiner.Result result = WifiP2pGroupJoiner.join(
                    context, parts[0], passphrase, 26000L);
            System.out.println("linkd: p2pjoin " + result.describe());
            return;
        }
        if (command.startsWith("size")) {
            final String value = command.length() > 4 ? command.substring(4).trim() : "auto";
            if ("auto".equalsIgnoreCase(value) || value.isEmpty()) {
                System.out.println("linkd: " + session.clearPinnedVideoSize());
                return;
            }
            final String[] parts = value.toLowerCase(java.util.Locale.ROOT).split("x");
            if (parts.length == 2) {
                try {
                    final int width = Integer.parseInt(parts[0].trim());
                    final int height = Integer.parseInt(parts[1].trim());
                    System.out.println("linkd: " + session.pinVideoSize(width, height));
                    return;
                } catch (NumberFormatException ignored) {
                    /* fall through */
                }
            }
            System.out.println("linkd: size wants auto or WxH, got '" + value + '\'');
            return;
        }
        if (command.startsWith("codec")) {
            final String value = command.length() > 5
                    ? command.substring(5).trim().toLowerCase(java.util.Locale.ROOT) : "auto";
            com.okcar.link.wireless.AccessoryMemory.Codec codec;
            if ("h264".equals(value) || "avc".equals(value)) {
                codec = com.okcar.link.wireless.AccessoryMemory.Codec.H264;
            } else if ("hevc".equals(value) || "h265".equals(value)) {
                codec = com.okcar.link.wireless.AccessoryMemory.Codec.HEVC;
            } else if ("auto".equals(value) || value.isEmpty()) {
                codec = com.okcar.link.wireless.AccessoryMemory.Codec.AUTO;
            } else {
                System.out.println("linkd: codec wants auto|h264|hevc, got '" + value + '\'');
                return;
            }
            System.out.println("linkd: " + session.pinVideoCodec(codec));
            return;
        }
        if (command.startsWith("stall-clear")) {
            /* Field escape hatch for the stall cooldown (see StallResponse): the window clears itself
             * when it expires, this is only for an operator standing next to a phone that has already
             * recovered and does not want to wait out the rest of it. */
            final long left = com.okcar.link.wireless.WirelessCarPlaySession.suspendRemainingMs();
            com.okcar.link.wireless.WirelessCarPlaySession.clearCastSuspension();
            System.out.println("linkd: stall cooldown cleared (had " + (left / 1000L)
                    + "s left) — the next dial will cast again");
            return;
        }
        if (command.startsWith("carkit")) {
            final String value = command.length() > 6
                    ? command.substring(6).trim().toLowerCase(java.util.Locale.ROOT) : "";
            if (value.isEmpty() || "status".equals(value)) {
                System.out.println("linkd: " + sCarkitHost.status());
            } else if ("on".equals(value) || "start".equals(value)) {
                rememberCarkitWanted(true);
                System.out.println("linkd: carkit host "
                        + (sCarkitHost.start() ? "listening" : "FAILED to listen")
                        + " on " + com.okcar.link.carkit.CarkitServiceHost.SOCKET_NAME);
            } else if ("off".equals(value) || "stop".equals(value)) {
                rememberCarkitWanted(false);
                sCarkitHost.stop();
                System.out.println("linkd: carkit host stopped");
            } else {
                System.out.println("linkd: carkit wants on|off|status, got '" + value + '\'');
            }
            return;
        }
        if (command.startsWith("bt")) {
            final String value = command.length() > 2
                    ? command.substring(2).trim().toLowerCase(java.util.Locale.ROOT) : "";
            if ("auto".equals(value) || "on".equals(value)) {
                PortBootstrap.setAutoBtIdentity(true);
            } else if ("off".equals(value) || "manual".equals(value)) {
                PortBootstrap.setAutoBtIdentity(false);
            } else {
                System.out.println("linkd: bt wants auto|off, got '" + value + '\'');
                return;
            }
            System.out.println("linkd: bt-identity-auto=" + PortBootstrap.autoBtIdentity());
            return;
        }
        if (command.startsWith("display")) {
            String value = command.length() > 7 ? command.substring(7).trim() : "";
            if ("down".equalsIgnoreCase(value)) {
                appDisplay.release();
                System.out.println("linkd: display released");
                return;
            }
            if (value.toLowerCase(java.util.Locale.ROOT).startsWith("up")) {
                value = value.substring(2).trim();
            }
            int width = 0;
            int height = 0;
            if (!value.isEmpty()) {
                final String[] parts = value.toLowerCase(java.util.Locale.ROOT).split("x");
                if (parts.length == 2) {
                    try {
                        width = Integer.parseInt(parts[0].trim());
                        height = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                        /* fall back to the current display */
                    }
                }
            }
            if (width <= 0 || height <= 0) {
                final int[] size = logicalDisplaySize(context);
                width = size[0];
                height = size[1];
            }
            /* Warm-up: build the public display and place the shared app *before* a head unit
             * connects, so the architecture can be checked (and a launcher pre-started) without a
             * session.  A session rebuilds the display at the size the head unit declares — that
             * is why this one is explicitly a fallback size and not a pinned resolution. */
            final Object display = appDisplay.createVirtualDisplay(
                    RootAppDisplayFactory.DISPLAY_NAME, width, height, 0, null);
            System.out.println("linkd: warmup " + width + 'x' + height + " -> "
                    + (display == null ? "failed" : appDisplay.describe()));
            return;
        }
        System.out.println("linkd: unknown command '" + command + '\'');
    }

    /**
     * Display 2 (`cast2`): a second 1488x616 virtual display hosting one fixed app, encoded H.264
     * and served over RTSP.  Built lazily and OFF until asked for; the head-unit-side program is not
     * written yet, so the whole surface is "make it and reserve the API".
     *
     * <p>Never created on a daemon that has never been asked to use cast2 — see {@link #ensureCast2}
     * — so a phone running only the mirroring stack gains no second display, no encoder and no
     * opened port from this code.
     */
    private static volatile com.okcar.link.stream2.Cast2Controller sCast2;

    /** The live cast2 controller, or null when this daemon never used cast2. */
    private static com.okcar.link.stream2.Cast2Controller cast2() {
        return sCast2;
    }

    /**
     * Creates the cast2 controller on first use, including the config/state files under
     * {@code /data/local/tmp/okcar-carplaypp/}.
     *
     * <p>A daemon that has never seen that directory stays at zero cost: the controller (and with it
     * the state snapshot) is only built once one of {@code cast2 ...}, {@code rtsp ...} or the HTTP
     * control API is actually asked for.
     */
    private static synchronized com.okcar.link.stream2.Cast2Controller ensureCast2(Context context) {
        if (sCast2 == null) {
            sCast2 = new com.okcar.link.stream2.Cast2Controller(context);
        }
        return sCast2;
    }

    /**
     * `carwith on [WxH] | off | status | key <name> <down> | touch <x> <y> <down> [srcW srcH] | mic <hex>`.
     *
     * <p>CarWith as the picture source (2026-10-07): `on` arms the daemon so that the next video
     * encoder start publishes its **input surface** through `okcar-carwith` instead of building a
     * mirror display; the installed CarLife-slot component fetches that surface and puts it in the
     * `CastConfig` it passes to CarWith, so what CarWith renders lands straight in the AirPlay
     * encoder.  `off` disarms and withdraws the surface.
     *
     * <p>The `key`/`touch`/`mic` verbs drive the same injection path the accessory uses, which is how
     * this gets verified without a head unit: they answer `true` only when CarWith really took the
     * event.
     */
    /** carwith swipe 每步之间的间隔（ms）：让注入的 DOWN/MOVE/UP 有真实时长。 */
    private static final long CARWITH_SWIPE_STEP_MS = 12L;

    /** 睡一小会儿；被中断就恢复中断位，调用方不需要关心。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void handleCarwith(String command, RootAppDisplayFactory appDisplay) {
        final String rest = command.length() > 7 ? command.substring(7).trim() : "";
        final String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        final String action = parts.length == 0 ? "" : parts[0].toLowerCase(java.util.Locale.ROOT);
        final com.okcar.link.carkit.CarwithCastClient client =
                com.okcar.link.carkit.CarwithBridge.client();
        if (action.isEmpty() || "status".equals(action)) {
            System.out.println("linkd: carwith coordinated: " + coordinatedState(appDisplay));
            return;
        }
        /* off：撤掉联动——标记删除、want 清零，APK 与接收端都不再接这条流 */
        /* content <pkg|pkg/activity>：让 APK（屏的属主）把目标应用直接放到 CarWith 投屏屏上。
         * 为什么要它：CarWith 首页在投屏屏上会盖一层空的 BeforeThirdAppEmptyActivity，注入的触摸
         * 落不到图标上；由属主直接放应用，内容才是应用本身。传空字符串表示清掉。 */
        if ("content".equals(action)) {
            final String value = parts.length > 1 ? parts[1].trim() : "";
            final boolean flagged = setWantKey("content", value);
            System.out.println("linkd: carwith content='" + value + "' written=" + flagged);
            return;
        }
        /* connect：一条命令把"联动连接"三件事一起打开——合成有线接入（让 CarlinkService/状态机出现）+
         * linkfill（造车侧连接对象 c2.b）+ statekick（只发一条车端 START）。实测这条组合能把
         * CarWith 推到 WirelessCastingState(q=2 C=true)，车机屏上是它自己的已连接车机界面。 */
        if ("connect".equals(action)) {
            setWantKey("wired", "1");
            setWantKey("linkfill", "1");
            setWantKey("statekick", "1");
            System.out.println("linkd: carwith connect armed (wired=1 linkfill=1 statekick=1) | "
                    + coordinatedState(appDisplay));
            return;
        }
        /* linkfill：踢状态机之前先造一个"车侧连接对象"（c2.b=CarlifeCastConfig）填给状态机，
         * 把我们的编码器输入面当车侧 Surface。默认关。 */
        if ("linkfill".equals(action)) {
            final String value = parts.length > 1 ? parts[1].trim() : "1";
            setWantKey("linkfill", value);
            System.out.println("linkd: carwith linkfill=" + value + " written");
            return;
        }
        /* statekick：让 APK 试着把 CarWith 的 CarlinkStateMachine 推到"已连接"（它只在车端发 START +
         * CastController 报 success 时才走那条路；我们这条 AirPlay 链路没有车端）。默认关，实验用。 */
        if ("statekick".equals(action)) {
            final String value = parts.length > 1 ? parts[1].trim() : "1";
            setWantKey("statekick", value);
            System.out.println("linkd: carwith statekick=" + value + " written");
            return;
        }
        /* projdebug <0|1|2>：写 CarWith 的投屏调试开关 `ucar_ae_projection_debug_mode`
         * （Settings.Global）。反汇编证据（classes2.dex，PhoneBlockTipsActivity.onCreate）：
         *   0 = 手机被占用时弹提示页；1 = 走 C0()；2 = **把原 intent 重定向到投屏屏**（照常开应用）。
         * 守护进程是 root，可以直接写全局设置；这条把它变成产品能力（开机时 apply 一次）。
         * 用绝对路径调 settings —— 裸 app_process 的 PATH 里没有它。 */
        if ("projdebug".equals(action)) {
            final String value = parts.length > 1 ? parts[1].trim() : "";
            if (!"0".equals(value) && !"1".equals(value) && !"2".equals(value)) {
                System.out.println("linkd: carwith projdebug wants 0|1|2, got '" + value + '\'');
                return;
            }
            try {
                final Process p = Runtime.getRuntime().exec(new String[] { "/system/bin/sh", "-c",
                        "/system/bin/settings put global ucar_ae_projection_debug_mode " + value
                                + "; /system/bin/settings get global ucar_ae_projection_debug_mode" });
                final StringBuilder out = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line.trim());
                    }
                }
                final int rc = p.waitFor();
                System.out.println("linkd: carwith projdebug=" + value + " rc=" + rc
                        + " now=" + out);
            } catch (Throwable t) {
                System.out.println("linkd: carwith projdebug failed: " + t);
            }
            return;
        }
        /* swipe <x1> <y1> <x2> <y2> [steps]：经 okcar-cast 发一条**真实拖拽**（DOWN + MOVE×N + UP），
         * 用来验证"车机的触摸能驱动投屏屏上的应用"。坐标是投屏屏自己的空间（如 1920x720），
         * displayId 发 -1 让 APK 用当前会话那块屏。 */
        if ("swipe".equals(action)) {
            if (parts.length < 5) {
                System.out.println("linkd: carwith swipe wants <x1> <y1> <x2> <y2> [steps]");
                return;
            }
            try {
                final float x1 = Float.parseFloat(parts[1]);
                final float y1 = Float.parseFloat(parts[2]);
                final float x2 = Float.parseFloat(parts[3]);
                final float y2 = Float.parseFloat(parts[4]);
                final int steps = parts.length > 5
                        ? Math.max(1, Math.min(32, Integer.parseInt(parts[5]))) : 8;
                com.okcar.link.castlink.CastLinkServer.get().sendTouch(0, x1, y1, -1);
                for (int i = 1; i <= steps; i++) {
                    final float t = (float) i / steps;
                    /* 步进之间要有真实时间：同一毫秒内发出的 DOWN/MOVE/UP 时间戳完全相同，
                     * 手势识别器（以及"按下-抬起"判定）会当成一次没有时长的怪事件——
                     * 实测这样点 CarWith 首页的图标（clickable=true）毫无反应。 */
                    sleepQuietly(CARWITH_SWIPE_STEP_MS);
                    com.okcar.link.castlink.CastLinkServer.get().sendTouch(2,
                            x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, -1);
                }
                sleepQuietly(CARWITH_SWIPE_STEP_MS);
                com.okcar.link.castlink.CastLinkServer.get().sendTouch(1, x2, y2, -1);
                sleepQuietly(CARWITH_SWIPE_STEP_MS);
                System.out.println("linkd: carwith swipe " + x1 + ',' + y1 + " -> " + x2 + ',' + y2
                        + " steps=" + steps + " | "
                        + com.okcar.link.castlink.CastLinkServer.describe());
            } catch (NumberFormatException e) {
                System.out.println("linkd: carwith swipe wants <x1> <y1> <x2> <y2> [steps]");
            }
            return;
        }
        /* wired on|off：单独控制"合成有线接入"这条旁路（不动联动其它键）。 */
        if ("wired".equals(action)) {
            final String value = parts.length > 1 ? parts[1].toLowerCase(java.util.Locale.ROOT) : "";
            if (!"on".equals(value) && !"off".equals(value)) {
                System.out.println("linkd: carwith wired wants on|off, got '" + value + '\'');
                return;
            }
            final boolean flagged = setWantKey("wired", "on".equals(value) ? "1" : "0");
            System.out.println("linkd: carwith wired=" + value + " written=" + flagged);
            return;
        }
        if ("off".equals(action) || "stop".equals(action)) {
            final boolean marked = com.okcar.link.castlink.CastLinkServer.setEnabled(false);
            final boolean wanted = writeWant(false);
            appDisplay.setModuleOwnsDisplay(false);
            /* 联动撤了就不再管 CarWith 的死活：留着看护会在模块已关的情况下反复重启它。 */
            if (sCarwithWatch != null) {
                sCarwithWatch.stop();
                sCarwithWatch = null;
            }
            System.out.println("linkd: carwith off — marker=" + marked + " want=" + wanted
                    + " | " + com.okcar.link.castlink.CastLinkServer.describe());
            return;
        }
        if ("on".equals(action) || "arm".equals(action)) {
            int width = 1920;
            int height = 720;
            int fps = 30;
            if (parts.length > 1) {
                final int[] size = com.okcar.link.stream2.Cast2Config.parseSize(parts[1]);
                if (size == null) {
                    System.out.println("linkd: carwith on wants an optional WxH, got '" + parts[1] + '\'');
                    return;
                }
                width = size[0];
                height = size[1];
            }
            if (parts.length > 2) {
                try {
                    fps = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    System.out.println("linkd: carwith on wants <WxH> <fps>");
                    return;
                }
            }
            /* 联动：入口保留（桥给 CarWith 一个面 + 官方触摸监听），但"内容"不再由模块产：
             * 标记让会话接受 APK 经 okcar-cast 推来的流，want 文件让 APK 接管投屏面并开始编码。
             * APK 接管后模块自己的编码器拿不到帧，也就不会再往这条流里写内容。 */
            final boolean marked = com.okcar.link.castlink.CastLinkServer.setEnabled(true);
            final boolean wanted = writeWant(true);
            appDisplay.setModuleOwnsDisplay(true);
            System.out.println("linkd: carwith " + client.arm(width, height, fps)
                    + " — entry kept; content from the APK: marker=" + marked + " want=" + wanted
                    + " | " + coordinatedState(appDisplay) + ')');
            return;
        }
        /* Publish an encoder input surface LOCALLY, without waiting for an AirPlay session
         * (2026-10-08).  Reason, measured: the wired ICCOA (AOA) attach puts CarWith into a real cast
         * session by itself, but that session is torn down as soon as an AirPlay one starts — while
         * the AirPlay one used to be the only thing that published the surface.  Starting the encoder
         * here breaks the circle; an AirPlay session started later can adopt it. */
        if ("local".equals(action) || "surface".equals(action)) {
            int width = 1920;
            int height = 720;
            int fps = 30;
            if (parts.length > 1) {
                final int[] size = com.okcar.link.stream2.Cast2Config.parseSize(parts[1]);
                if (size == null) {
                    System.out.println("linkd: carwith local wants an optional WxH, got '" + parts[1] + '\'');
                    return;
                }
                width = size[0];
                height = size[1];
            }
            if (parts.length > 2) {
                try {
                    fps = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    System.out.println("linkd: carwith local wants <WxH> <fps>");
                    return;
                }
            }
            client.setContext(appContext());
            System.out.println("linkd: carwith " + client.armLocal(width, height, fps));
            return;
        }
        /* Mirror an EXISTING display into the local encoder instead of letting CarWith render into
         * our surface (2026-10-08).  This is the working route for the wired ICCOA picture: CarWith
         * creates its own cast display (owner com.miui.carlink) on the AOA attach, that display has no
         * SurfaceFlinger capture token, and the CarLife-slot prepareCast is accepted but never routed.
         * Command: carwith mirror <displayId> [WxH] [fps] */
        if ("mirror".equals(action)) {
            if (parts.length < 2) {
                System.out.println("linkd: carwith mirror <displayId|auto> [WxH] [fps]");
                return;
            }
            final int source;
            if ("auto".equalsIgnoreCase(parts[1])) {
                /* CarWith re-creates its cast display for every wired session (ids 9..29 observed), so
                 * 'auto' is the recommended form: every session re-resolves it. */
                source = com.okcar.link.port.DisplayMirror.AUTO;
            } else {
                try {
                    source = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    System.out.println("linkd: carwith mirror wants a displayId or 'auto', got '"
                            + parts[1] + '\'');
                    return;
                }
            }
            int width = 1920;
            int height = 864;
            int fps = 30;
            if (parts.length > 2) {
                final int[] size = com.okcar.link.stream2.Cast2Config.parseSize(parts[2]);
                if (size == null) {
                    System.out.println("linkd: carwith mirror wants an optional WxH, got '" + parts[2] + '\'');
                    return;
                }
                width = size[0];
                height = size[1];
            }
            if (parts.length > 3) {
                try {
                    fps = Integer.parseInt(parts[3]);
                } catch (NumberFormatException e) {
                    System.out.println("linkd: carwith mirror wants <displayId> <WxH> <fps>");
                    return;
                }
            }
            client.setContext(appContext());
            if (parts.length > 4) {
                try {
                    client.setLocalMirrorFlags(Integer.parseInt(parts[4]));
                } catch (NumberFormatException e) {
                    System.out.println("linkd: carwith mirror flags must be an int, got '" + parts[4] + '\'');
                    return;
                }
            }
            System.out.println("linkd: carwith " + client.armLocalMirror(width, height, fps, source));
            return;
        }
        if ("off".equals(action) || "disarm".equals(action)) {
            System.out.println("linkd: carwith " + client.disarm("carwith off"));
            return;
        }
        /* Start CarWith's own CarlinkService (the only place CarlinkStateMachine is created) without
         * waiting for the component: the 2026-10-08 run showed prepareCast is dropped while that
         * field is null.  Optional argument is the display-only carlife_wireless_type_name. */
        if ("bootstrap".equals(action) || "ensure".equals(action)) {
            final String name = parts.length > 1 ? parts[1] : "okcar";
            System.out.println("linkd: carwith bootstrap "
                    + com.okcar.link.carkit.CarwithBridge.ensureCarwith(name).describe());
            return;
        }
        if ("caststate".equals(action)) {
            System.out.println("linkd: carwith " + com.okcar.link.carkit.CarwithBridge.castState());
            return;
        }
        if ("key".equals(action)) {
            if (parts.length < 3) {
                System.out.println("linkd: carwith key <name> <down>");
                return;
            }
            final boolean down = "1".equals(parts[2]) || "true".equalsIgnoreCase(parts[2]);
            System.out.println("linkd: carwith key " + parts[1] + " down=" + down + " -> "
                    + client.injectKey(parts[1], down));
            return;
        }
        if ("touch".equals(action)) {
            if (parts.length < 4) {
                System.out.println("linkd: carwith touch <x> <y> <down> [sourceW sourceH]");
                return;
            }
            final int sourceWidth = parts.length > 5 ? Integer.parseInt(parts[4]) : client.castWidth();
            final int sourceHeight = parts.length > 5 ? Integer.parseInt(parts[5]) : client.castHeight();
            final int x = Integer.parseInt(parts[1]);
            final int y = Integer.parseInt(parts[2]);
            final boolean down = "1".equals(parts[3]) || "true".equalsIgnoreCase(parts[3]);
            final int touchAction = down
                    ? android.view.MotionEvent.ACTION_DOWN : android.view.MotionEvent.ACTION_UP;
            System.out.println("linkd: carwith touch " + x + ',' + y + " down=" + down + " -> "
                    + client.injectTouch(touchAction, x, y, sourceWidth, sourceHeight));
            return;
        }
        if ("mic".equals(action)) {
            if (parts.length < 2) {
                System.out.println("linkd: carwith mic <hex PCM16LE>");
                return;
            }
            final byte[] pcm = parts[1].startsWith("silence")
                    ? new byte[Integer.parseInt(parts[1].substring("silence".length()))]
                    : com.okcar.link.util.Hex.decode(parts[1]);
            if (pcm == null || pcm.length == 0) {
                System.out.println("linkd: carwith mic wants non-empty PCM16LE hex");
                return;
            }
            System.out.println("linkd: carwith mic " + pcm.length + "B -> " + client.feedMicrophone(pcm));
            return;
        }
        System.out.println("linkd: carwith wants on|off|status|bootstrap|caststate|key|touch|mic");
    }

    /**
     * `cast2 up [WxH] | down | app <pkg> | status | listen on|off`.
     *
     * <p>Every failure is printed with its reason and the command answers with the resulting state;
     * nothing falls back to mirroring or to another codec.
     */
    private static void handleCast2(String command) throws Exception {
        final String rest = command.length() > 5 ? command.substring(5).trim() : "";
        final String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+", 2);
        final String action = parts.length == 0 ? "" : parts[0].toLowerCase(java.util.Locale.ROOT);
        if (action.isEmpty() || "status".equals(action)) {
            final com.okcar.link.stream2.Cast2Controller controller =
                    ensureCast2(mMainContext());
            for (String line : controller.statusLines()) {
                System.out.println(line);
            }
            controller.writeStateFile("cast2 status");
            return;
        }
        if ("up".equals(action)) {
            final com.okcar.link.stream2.Cast2Controller controller =
                    ensureCast2(mMainContext());
            if (parts.length > 1 && !parts[1].isEmpty()) {
                final int[] size = com.okcar.link.stream2.Cast2Config.parseSize(parts[1]);
                if (size == null) {
                    System.out.println("linkd: cast2 up wants an optional WxH, got '" + parts[1] + '\'');
                    return;
                }
                controller.setSize(size[0], size[1]);
            }
            if (controller.isUp()) {
                System.out.println("linkd: cast2 already up, nothing changed — " + controller.describe());
                controller.writeStateFile("cast2 up (already up)");
                return;
            }
            controller.start();
            System.out.println("linkd: cast2 up — " + controller.describe());
            return;
        }
        if ("down".equals(action)) {
            final com.okcar.link.stream2.Cast2Controller controller = cast2();
            if (controller == null) {
                System.out.println("linkd: cast2 already down (never started in this process)");
                return;
            }
            controller.stopCast2();
            System.out.println("linkd: cast2 down — " + controller.describe());
            return;
        }
        if ("app".equals(action)) {
            final com.okcar.link.stream2.Cast2Controller controller =
                    ensureCast2(mMainContext());
            final String pkg = parts.length > 1 ? parts[1].trim() : "";
            if (pkg.isEmpty()) {
                System.out.println("linkd: cast2 app wants a package name, got '" + rest + '\'');
                return;
            }
            controller.setApp(pkg);
            System.out.println("linkd: cast2 app=" + pkg + " — " + controller.describe());
            return;
        }
        if ("listen".equals(action)) {
            final com.okcar.link.stream2.Cast2Controller controller =
                    ensureCast2(mMainContext());
            final String value = parts.length > 1 ? parts[1].trim() : "";
            if (!"on".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value)) {
                System.out.println("linkd: cast2 listen wants on|off, got '" + value + '\'');
                return;
            }
            controller.setListening("on".equalsIgnoreCase(value));
            System.out.println("linkd: cast2 listen=" + value.toLowerCase(java.util.Locale.ROOT)
                    + " — " + controller.describe());
            return;
        }
        if ("size".equals(action)) {
            final com.okcar.link.stream2.Cast2Controller controller =
                    ensureCast2(mMainContext());
            final String value = parts.length > 1 ? parts[1].trim() : "";
            final int[] size = com.okcar.link.stream2.Cast2Config.parseSize(value);
            if (size == null) {
                System.out.println("linkd: cast2 size wants WxH, got '" + value + '\'');
                return;
            }
            controller.setSize(size[0], size[1]);
            System.out.println("linkd: cast2 size=" + size[0] + 'x' + size[1] + " — "
                    + controller.describe());
            return;
        }
        System.out.println("linkd: cast2 wants up [WxH]|down|app <pkg>|size WxH|listen on|off|status,"
                + " got '" + command + '\'');
    }

    /**
     * `rtsp up|down|status` — the display-2 RTSP listener on its own.
     *
     * <p>`rtsp up` needs a running encoder: there is nothing to serve before `cast2 up`, and it says
     * so instead of binding a port that would answer DESCRIBE with an error.
     */
    private static void handleRtsp(String command) throws Exception {
        final String rest = command.length() > 4 ? command.substring(4).trim() : "";
        final String action = rest.toLowerCase(java.util.Locale.ROOT);
        final com.okcar.link.stream2.Cast2Controller controller = ensureCast2(mMainContext());
        if (action.isEmpty() || "status".equals(action)) {
            for (String line : controller.statusLines()) {
                System.out.println(line);
            }
            return;
        }
        if ("up".equals(action)) {
            controller.startRtsp();
            System.out.println("linkd: rtsp up — " + controller.describe());
            return;
        }
        if ("down".equals(action)) {
            controller.stopRtsp();
            System.out.println("linkd: rtsp down — " + controller.describe());
            return;
        }
        System.out.println("linkd: rtsp wants up|down|status, got '" + command + '\'');
    }

    /**
     * The Context the lazily built cast2 pipeline uses.
     *
     * <p>Installed once in {@link #main} (the same system context the mirroring stack runs on); the
     * cast2 command handlers only need the Context, not the whole daemon state.
     */
    private static volatile Context sMainContext;

    /**
     * System stall watch: probes its own scheduling jitter every 250ms. Once the phone collapses (the measured
     * "device freezes after connecting → watchdog reboot"), we at least release the mirror display and end this session
     * first, turning "the phone is gone" into "casting dropped". Threshold read from {@code persist.okcar.stall_teardown_ms} (default 2000; 0 disables).
     */
    /** True while either the wireless or the wired path actually has AirPlay media streams running. */
    private static boolean castingActive() {
        try {
            if (com.okcar.link.wireless.WirelessCarPlaySession.get().isCasting()) {
                return true;
            }
        } catch (Throwable ignored) {
            // fall through to the wired check
        }
        try {
            return sCarkitHost.isCasting();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void startSystemStallWatch() {
        final long threshold = readStallThresholdMs();
        final com.okcar.link.port.SystemStallGuard guard =
                new com.okcar.link.port.SystemStallGuard(threshold, android.os.SystemClock.elapsedRealtime());
        if (!guard.enabled()) {
            android.util.Log.i(TAG_DAEMON, "system stall watch disabled (threshold=0)");
            return;
        }
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    final long nowMs = android.os.SystemClock.elapsedRealtime();
                    if (!castingActive()) {
                        /* Nothing is casting, so there is no pipeline to shed: re-base the guard and forget past
                         * stalls instead of counting this interval. Boot load alone (58 apps dumped, 2402 activities)
                         * used to score 18.3 s here, escalate to level 3 and suspend casting for 300 s — exactly when
                         * the user had just plugged the cable in, so the wired sender refused to start. */
                        guard.idle(nowMs);
                        continue;
                    }
                    final long stalled = guard.beat(nowMs);
                    if (stalled <= 0) {
                        continue;
                    }
                    /* Graded response: shedding the display alone is not enough — in both field "hard reset" incidents it reported every
                     * 30 seconds and shed none of the load. Now a mild jitter sheds the display, a second one in the same round stops the
                     * whole pipeline (encoder/audio/event channel/socket), and severe or three cumulative suspends casting, giving the phone a recovery window. */
                    final com.okcar.link.port.StallResponse.Actions actions =
                            com.okcar.link.port.StallResponse.forStall(stalled, guard.stalls());
                    System.out.println("linkd: SYSTEM STALL " + stalled + "ms (worst="
                            + guard.worstMs() + "ms, count=" + guard.stalls() + ") — "
                            + actions.describe());
                    if (actions.releaseDisplay) {
                        final com.okcar.link.port.MirrorDisplayFactory factory =
                                com.okcar.link.port.PortBootstrap.mirrorDisplayFactory();
                        if (factory instanceof com.okcar.link.port.RootAppDisplayFactory) {
                            try {
                                ((com.okcar.link.port.RootAppDisplayFactory) factory).release();
                            } catch (Throwable e) {
                                android.util.Log.w(TAG_DAEMON, "stall teardown failed: " + e);
                            }
                        }
                    }
                    if (actions.stopSession) {
                        try {
                            /* Stop the whole session: this is the only action that truly takes the encoder off Venus. */
                            com.okcar.link.wireless.WirelessCarPlaySession.get().stop();
                            System.out.println("linkd: stall response stopped the cast pipeline"
                                    + " (encoder/audio/event channel released)");
                        } catch (Throwable e) {
                            android.util.Log.w(TAG_DAEMON, "stall session stop failed: " + e);
                        }
                    }
                    if (actions.suspend) {
                        com.okcar.link.wireless.WirelessCarPlaySession.suspendCastsFor(
                                actions.suspendMs, "the phone was starved for " + stalled + "ms");
                    }
                }
            }
        }, "okcar-system-stall");
        t.setDaemon(true);
        t.start();
        android.util.Log.i(TAG_DAEMON, "system stall watch armed (threshold=" + threshold + "ms)");
    }

    /**
     * Keep-awake watch: while AirPlay media streams are live, hold a screen wake lock.
     *
     * <p>InputDispatcher silently drops injected touches once the device is not interactive, so a
     * sleeping phone leaves the car screen dead even though the virtual display keeps the app's window
     * focused and its input channel valid. Measured on 217 (Android 15, 2026-10-20) with the same
     * window and the same coordinates: awake, the tap lands and the app navigates ({@code MIUIInput:
     * publisher action=0x0} for the app's channel); asleep, the event never reaches the window
     * ({@code publisher} absent) even though {@code dumpsys input} still reports the window's focus
     * request as OK. The mirrored picture is the lock screen by then anyway.
     *
     * <p>CarWith keeps the phone up for exactly this reason (window {@code FLAG_KEEP_SCREEN_ON} plus a
     * wake lock, see {@code the companion module's README}), and the same trick applies to a headless
     * root daemon. {@code persist.okcar.keep_awake=0} turns it off, and then touch only works while the
     * phone happens to be awake.
     *
     * <p>On a MIUI phone this is only the fallback: {@link com.okcar.link.port.MiuiSynergyMode}
     * keeps injected input flowing while the device is non-interactive and dark, which is what the
     * car actually wants. The lock is only worth holding while that is unavailable, so the watch
     * skips itself whenever the synergy bridge is live — otherwise we would undo exactly the
     * behaviour it provides (SCREEN_BRIGHT_WAKE_LOCK turns the phone into a lit lamp in the
     * driver's pocket).
     */
    private static void startCastAwakeWatch() {
        if (!keepAwakeEnabled()) {
            android.util.Log.i(TAG_DAEMON, "keep-awake disabled (persist.okcar.keep_awake=0)");
            return;
        }
        if (com.okcar.link.port.MiuiSynergyMode.available()) {
            android.util.Log.i(TAG_DAEMON, "keep-awake running behind MiuiSynergyMode "
                    + "(the synergy bridge takes over once it arms; until then the lock still matters)");
        }
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.PowerManager.WakeLock lock = null;
                boolean held = false;
                while (true) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    final boolean want = castingActive();
                    if (want == held) {
                        continue;
                    }
                    try {
                        if (want) {
                            final Context context = appContext();
                            final android.os.PowerManager pm = context == null ? null
                                    : (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                            if (pm == null) {
                                continue;
                            }
                            /* SCREEN_BRIGHT, not PARTIAL: only the screen levels keep the device
                             * interactive. A partial lock keeps the CPU out of suspend but still lets the
                             * phone go to sleep, which is the state that eats the touches.
                             *
                             * On MIUI this is deliberately the second line of defence: the synergy
                             * bridge is what keeps injected input flowing while the phone sleeps, and
                             * it does that without lighting anything. Holding a bright lock on top of
                             * it would defeat the whole point (a lit phone in the driver's pocket). */
                            if (!com.okcar.link.port.MiuiSynergyMode.available()) {
                                lock = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                        | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "okcar:casting");
                                lock.setReferenceCounted(false);
                                lock.acquire();
                                held = true;
                                System.out.println("linkd: casting — holding a screen wake lock "
                                        + "(touch dies while the phone sleeps)");
                            }
                        } else {
                            if (lock != null) {
                                lock.release();
                            }
                            lock = null;
                            held = false;
                            System.out.println("linkd: casting ended — released the screen wake lock");
                        }
                    } catch (Throwable e) {
                        android.util.Log.w(TAG_DAEMON, "keep-awake: " + e);
                        held = false;
                        lock = null;
                    }
                }
            }
        }, "okcar-cast-awake");
        t.setDaemon(true);
        t.start();
        android.util.Log.i(TAG_DAEMON, "keep-awake watch armed (a live cast holds a screen wake lock)");
    }

    /**
     * Polls the MIUI synergy state so it follows the cast instead of only the boot cast.
     *
     * <p>Arming is debounced on purpose. {@link #castingActive()} reports the AirPlay senders, and a
     * sender legitimately stalls for a few seconds around re-negotiation. Disarming on the very
     * first false would drop synergy mode in the middle of a session, so every stall would cost the
     * cast its touch channel until the next tick brought it back. So: arm on the first true, but
     * disarm only once the cast has been gone for the whole hold window.
     */
    private static void startSynergyModeWatch() {
        final long debounceMs = readStallThresholdMs() > 0 ? readStallThresholdMs() * 5L : 10000L;
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long inactiveSince = 0L;
                while (true) {
                    try {
                        if (castingActive()) {
                            inactiveSince = 0L;
                            com.okcar.link.port.MiuiSynergyMode.apply(appContext(), true);
                            /* The platform clears the projection keys when the last projection
                             * client dies, so a single call is not enough to keep the behaviour for
                             * the rest of the session. Top it up every tick; the bridge itself
                             * skips the binder call while the key is still set. */
                            com.okcar.link.port.MiuiSynergyMode.rearmIfCasting();
                            /* Keep the cached key fresh for the touch hot path. InputInjector
                             * asks synergyActive() on every injected event and that call must stay
                             * a plain field read - reading the key costs a child process (53-73 ms
                             * measured 2026-10-21), which showed up as huge touch latency once the
                             * phone locked. */
                            com.okcar.link.port.MiuiSynergyMode.refreshKey();
                        } else {
                            final long now = android.os.SystemClock.elapsedRealtime();
                            if (inactiveSince == 0L) {
                                inactiveSince = now;
                            }
                            if (now - inactiveSince >= debounceMs) {
                                com.okcar.link.port.MiuiSynergyMode.apply(appContext(), false);
                            }
                        }
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable e) {
                        android.util.Log.w(TAG_DAEMON, "miui synergy watch: " + e);
                        try {
                            Thread.sleep(5000L);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            }
        }, "okcar-synergy");
        t.setDaemon(true);
        t.start();
        android.util.Log.i(TAG_DAEMON, "miui synergy watch started");
    }

    private static boolean keepAwakeEnabled() {
        try {
            final Class<?> props = Class.forName("android.os.SystemProperties");
            final java.lang.reflect.Method get = props.getMethod("get", String.class, String.class);
            final String raw = String.valueOf(get.invoke(null, "persist.okcar.keep_awake", "1")).trim();
            return !("0".equals(raw) || "false".equalsIgnoreCase(raw));
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static long readStallThresholdMs() {
        try {
            final Class<?> props = Class.forName("android.os.SystemProperties");
            final java.lang.reflect.Method get = props.getMethod("get", String.class, String.class);
            final String raw = String.valueOf(get.invoke(null,
                    "persist.okcar.stall_teardown_ms",
                    String.valueOf(com.okcar.link.port.SystemStallGuard.DEFAULT_THRESHOLD_MS))).trim();
            final long value = Long.parseLong(raw);
            return value < 0 ? 0 : value;
        } catch (Throwable ignored) {
            return com.okcar.link.port.SystemStallGuard.DEFAULT_THRESHOLD_MS;
        }
    }

    /**
     * The system context {@link #run} bootstrapped, for components that need one but are not created
     * with it (e.g. the wired CarPlay host's AirPlay client).  Null before run().
     */
    public static Context appContext() {
        return sMainContext;
    }

    private static Context mMainContext() {
        final Context context = sMainContext;
        if (context == null) {
            throw new IllegalStateException("the daemon has no system context yet");
        }
        return context;
    }

    /** Releases display 2, its encoder and its listeners; called on `quit` and at the end of main. */
    private static void shutdownCast2() {
        final com.okcar.link.stream2.Cast2Controller controller = sCast2;
        if (controller == null) {
            return;
        }
        try {
            controller.shutdown();
        } catch (Throwable t) {
            System.out.println("linkd: cast2 shutdown failed: " + t);
        }
    }

    /** Where the daemon keeps the context's SharedPreferences (Wi-Fi revert record, pairing). */
    private static final String DEFAULT_DATA_DIR = "/data/local/tmp/okcar-data";

    /**
     * Points the system context at a data directory of our own.
     *
     * <p>{@code ContextImpl.getDataDir()} throws "No data directory found for package android" for
     * the context {@code ActivityThread.systemMain()} builds — its {@code LoadedApk} never got a
     * data dir, because no package installation ever ran for "android".  Every
     * {@code getSharedPreferences()} in the stack goes through it, so the daemon died mid-handshake
     * on the very first Wi-Fi join (measured on 217: {@code FATAL EXCEPTION: okcar-iap2-rx},
     * {@code WifiGroupJoiner.prefs -> isJoinRecorded -> join -> beginSession}).  Giving that
     * LoadedApk a real directory fixes the whole stack at once and keeps the records persistent —
     * the Wi-Fi revert record and the AirPlay pairing identity are both meant to outlive the
     * process, and an in-memory fallback would silently re-pair on every restart.
     */
    private static void giveDataDir(Context context, String path) {
        try {
            final java.io.File dir = new java.io.File(path);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                System.out.println("linkd: cannot create data dir " + dir);
                return;
            }
            /* ContextImpl is package-private in android.app, so the class comes by name. */
            final java.lang.reflect.Field pkgInfoField =
                    Class.forName("android.app.ContextImpl").getDeclaredField("mPackageInfo");
            pkgInfoField.setAccessible(true);
            final Object loadedApk = pkgInfoField.get(context);
            if (loadedApk == null) {
                System.out.println("linkd: system context has no package info — prefs stay broken");
                return;
            }
            for (String field : new String[]{"mDataDirFile", "mCredentialProtectedDataDirFile",
                    "mDeviceProtectedDataDirFile"}) {
                try {
                    final java.lang.reflect.Field f =
                            loadedApk.getClass().getDeclaredField(field);
                    f.setAccessible(true);
                    f.set(loadedApk, dir);
                } catch (Throwable ignored) {
                    /* field absent on this build — the others are enough */
                }
            }
            System.out.println("linkd: system context data dir -> " + dir);
        } catch (Throwable t) {
            System.out.println("linkd: cannot give the system context a data dir: " + t);
        }
    }

    /**
     * Registers the mainline modules' service managers.
     *
     * <p>{@code ActivityThread.initializeMainlineModules()} is called from
     * {@code ActivityThread.handleBindApplication()}, which only ever runs for a real application
     * process.  An {@code app_process} daemon therefore starts with the Bluetooth mainline
     * module's service manager unset: {@code BluetoothAdapter.getDefaultAdapter()} and
     * {@code BluetoothManager.getAdapter()} both return null and the session dies with
     * "no Bluetooth adapter" even though the radio is on (measured on 217 — the same call works
     * inside the APK, which is why this only shows up after the move into the module).  Same
     * static call the framework makes, done here instead.
     */
    private static void initializeMainlineModules() {
        try {
            Class.forName("android.app.ActivityThread")
                    .getMethod("initializeMainlineModules").invoke(null);
            System.out.println("linkd: mainline module initializers installed");
        } catch (Throwable t) {
            System.out.println("linkd: mainline module initializers unavailable: " + t);
        }
    }

    /** The installed-app inventory the module WebUI's app/activity pickers read. */
    private static final String APPS_TSV = "/data/local/tmp/okcar-apps.tsv";
    private static final String ACTIVITIES_TSV = "/data/local/tmp/okcar-activities.tsv";

    /**
     * Dumps the installed-app inventory the WebUI picker reads.
     *
     * <p>Why the daemon and not the WebUI: the page can run `pm list packages`, but that returns bare
     * package names, and a picker that shows `com.baidu.BaiduMap` instead of Baidu Maps is not usable —
     * labels and launcher components only come from PackageManager.  The files are plain TSV so the
     * page can read them with `cat`:
     * <pre>okcar-apps.tsv        &lt;pkg&gt;\t&lt;label&gt;\t&lt;launcher activity&gt;
     * okcar-activities.tsv  &lt;pkg&gt;\t&lt;activity&gt;\t&lt;activity label&gt;</pre>
     * Dumped once at startup and again on the {@code apps} command; a failure is reported and the
     * previous files are left alone rather than silently emptied.
     */
    private static void dumpAppInventory(Context context) {
        try {
            final android.content.pm.PackageManager pm = context.getPackageManager();
            if (pm == null) {
                /* Measured 2026-10-04 in the field: this returned null, then queryIntentActivities threw an NPE that the outer
                 * layer caught, leaving only a single "apps dump failed" line. The app inventory feeds the WebUI's app picker,
                 * so if it can't be obtained, skip it — don't leave a half-written file behind. */
                System.out.println("linkd: apps dump skipped (no PackageManager)");
                return;
            }
            final java.util.List<String[]> rows = new java.util.ArrayList<String[]>();
            final java.util.Set<String> packages = new java.util.LinkedHashSet<String>();
            final android.content.Intent launcher =
                    new android.content.Intent(android.content.Intent.ACTION_MAIN);
            launcher.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(
                    launcher, android.content.pm.PackageManager.MATCH_ALL)) {
                if (info.activityInfo == null || info.activityInfo.packageName == null) {
                    continue;
                }
                rows.add(new String[]{info.activityInfo.packageName,
                        cleanLabel(String.valueOf(info.loadLabel(pm))),
                        info.activityInfo.name == null ? "" : info.activityInfo.name});
                packages.add(info.activityInfo.packageName);
            }
            java.util.Collections.sort(rows, new java.util.Comparator<String[]>() {
                @Override
                public int compare(String[] a, String[] b) {
                    return a[1].compareToIgnoreCase(b[1]);
                }
            });
            final java.util.List<String[]> activities = new java.util.ArrayList<String[]>();
            for (String pkg : packages) {
                try {
                    final android.content.pm.PackageInfo info = pm.getPackageInfo(pkg,
                            android.content.pm.PackageManager.GET_ACTIVITIES
                                    | android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
                    if (info.activities == null) {
                        continue;
                    }
                    for (android.content.pm.ActivityInfo activity : info.activities) {
                        if (activity.name == null || !activity.exported) {
                            /* The picker is for choosing which activity the car screen should show;
                             * a non-exported activity can never be launched from outside its app, so
                             * listing them would only bury the useful entries (measured on 217:
                             * every declared activity listed 49k rows for 202 apps). */
                            continue;
                        }
                        activities.add(new String[]{pkg, activity.name,
                                cleanLabel(String.valueOf(activity.loadLabel(pm)))});
                    }
                } catch (Throwable t) {
                    System.out.println("linkd: apps: activities for " + pkg + " unavailable: " + t);
                }
            }
            final StringBuilder appsText = new StringBuilder();
            for (String[] row : rows) {
                appsText.append(row[0]).append('\t').append(row[1]).append('\t')
                        .append(row[2]).append('\n');
            }
            final StringBuilder activitiesText = new StringBuilder();
            for (String[] row : activities) {
                activitiesText.append(row[0]).append('\t').append(row[1]).append('\t')
                        .append(row[2]).append('\n');
            }
            writeInventory(APPS_TSV, appsText.toString());
            writeInventory(ACTIVITIES_TSV, activitiesText.toString());
            System.out.println("linkd: apps dumped " + rows.size() + " apps / "
                    + activities.size() + " activities");
        } catch (Throwable t) {
            System.out.println("linkd: apps dump failed: " + t);
        }
    }

    /** A tab or newline inside a label would break the TSV the page parses. */
    private static String cleanLabel(String text) {
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void writeInventory(String path, String text) {
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(new java.io.File(path));
            out.write(text.getBytes("UTF-8"));
            out.flush();
            new java.io.File(path).setReadable(true, false);
        } catch (Throwable t) {
            System.out.println("linkd: apps: cannot write " + path + ": " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    /* best effort */
                }
            }
        }
    }

    private static int[] logicalDisplaySize(Context context) {
        try {
            final WindowManager windowManager =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                final DisplayMetrics metrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return new int[]{metrics.widthPixels, metrics.heightPixels};
                }
            }
        } catch (RuntimeException ignored) {
            /* fall through */
        }
        return new int[]{1080, 1920};
    }

    /**
     * A Context without an Activity — {@code ActivityThread.systemMain()} is the route the
     * framework's own shell tools and our harness helpers (the desktop display harness, the desktop ap-toggle harness)
     * take.  The main looper must exist first: systemMain() builds a Handler on this thread.
     */
    private static Context systemContext() {
        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
            final Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = null;
            try {
                thread = activityThread.getMethod("currentActivityThread").invoke(null);
            } catch (Throwable ignored) {
                /* not attached */
            }
            if (thread == null) {
                try {
                    thread = activityThread.getMethod("systemMain").invoke(null);
                } catch (Throwable t) {
                    System.out.println("linkd: systemMain failed: " + t
                            + (t.getCause() == null ? "" : " / " + t.getCause()));
                }
            }
            if (thread == null) {
                return null;
            }
            return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            System.out.println("linkd: context failed: " + t);
            return null;
        }
    }
}
