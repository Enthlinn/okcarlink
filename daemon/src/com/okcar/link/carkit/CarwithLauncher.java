/*
 * OkCarLink — bring CarWith's own `CarlinkService` up the way CarWith starts it (2026-10-08).
 *
 * WHY THIS CLASS EXISTS
 * `CarlifeConnService.CarLifeCastManagerImpl.prepareCast(CastConfig)` does exactly two things
 * (classes5.dex, verified line by line — see the builder's own key store/):
 *
 *     c2.e.h().w(false);
 *     c2.e.h().p(new c2.b(cfg.a, cfg.b, cfg.c, cfg.d));
 *
 * `c2.e.p(c2.b)` dispatches the config to the registered `c2.c` listeners.  The one that matters is
 * `com.miui.carlink.castfwk.CarlinkService` (`implements Lc2/c;`, registered from its `p()`), and
 * its receiver is minimal (CarlinkService.dump @25b680):
 *
 *     b1.d("CarlinkService", "prepareCast " + cfg);
 *     if (this.b == null) {                       // b == CarlinkStateMachine
 *         b1.g("CarlinkService", "prepareCast error mStateMachine is null");
 *         return;                                 // ← the whole CastConfig is dropped here
 *     }
 *     if (a0.l())      this.b.l1(cfg);            // a0.l() == a0.a.contains("usb")
 *     else if (a0.n()) this.b.n1(cfg);            // a0.n() == a0.a.contains("wifi")
 *
 * `this.b` is created only inside `CarlinkService.onStartCommand` and only on the first start of
 * the process's instance (`if (this.f == 0) { …; this.b = new CarlinkStateMachine(ctx); … }`,
 * CarlinkService.dump @25a8f2–@25a95e).  So an AIDL `prepareCast` from the CarLife component is
 * silently dropped unless CarWith's *own* service start has happened first.  The community
 * component worked around that with an Xposed hook that cached the CastConfig and replayed it once
 * the native listener was ready; this 217 has no Xposed/LSPosed, so the fix has to be the real
 * entry point.
 *
 * THE REAL ENTRY POINT (dex-proven, not guessed)
 * CarWith's own wireless-CarLife bootstrap is `f9.c.U(connectMode, carBtName, foundMode)`
 * (f9c.dump @29ea10):
 *
 *     a0.p(connectMode.equals("Baidu_ap") ? "wifi_ap_carlife_connect" : "wifi_p2p_carlife_connect");
 *     a0.q(foundMode == 3);
 *     Intent i = new Intent();
 *     i.putExtra("carlife_wireless_type", connectMode);          // "Baidu_ap" / "Baidu_wlan"
 *     i.putExtra("startType", 4);                                // CARLINK_STATUS_CASTING_CARLIFE
 *     i.putExtra("carlife_wireless_type_name", carBtName);
 *     com.miui.carlink.castfwk.c.a().d(context, i);
 *
 * and `com.miui.carlink.castfwk.c.d(Context,Intent)` (castfwk_c.dump @25a2bc) either retries every
 * 200 ms while `CarlinkService.s()` (the static `o` flag, set true by the first onStartCommand) says
 * it is already running — up to 10 times — or does
 * `intent.setClass(context, CarlinkService.class); p.g().m(context, intent)`, where
 * `p.m` is `context.startForegroundService(intent)` (castfwk_p.dump @269ae0).
 *
 * `am start-foreground-service -n com.miui.carlink/com.miui.carlink.castfwk.CarlinkService --ei
 * startType 4 --es carlife_wireless_type Baidu_ap --es carlife_wireless_type_name <name>` is that
 * start, expressed the way the root daemon can issue it.  It is run as root on purpose: the service
 * is declared `android:permission="miui.carlink.permission.START_CARLINKSERVICE"`
 * (carwith-manifest.txt:339–343), which root (uid 0) passes and the `shell` uid does not.
 *
 * WHAT IT DOES *NOT* DO
 * It cannot set `a0.a` — that static is written only by CarWith's own connect flow (`a0.p`), i.e. by
 * the user starting a CarLife connection inside CarWith.  Without that, `b(CastConfig)` finds both
 * `a0.l()` and `a0.n()` false and still drops the config.  So this class guarantees the
 * `mStateMachine == null` half of the gate, and the component-side replay in
 * `CarlifeConnectService` covers the timing; the mode half comes from CarWith's own UI flow.
 *
 * Pure Java: the shell and the clock are injected, and the Intent arguments / the
 * `dumpsys activity services` probe are pure functions, so that harness drives every branch
 * on the host JVM with no device.
 */
package com.okcar.link.carkit;

/**
 * Builds and runs the "start CarWith's CarlinkService the way CarWith does" command.
 */
public final class CarwithLauncher {

    /** `com.miui.carlink` — the CarWith package (CarwithContract.CARWITH_PACKAGE). */
    public static final String PACKAGE = CarwithContract.CARWITH_PACKAGE;

    /** The only class whose `onStartCommand` creates `CarlinkStateMachine`. */
    public static final String SERVICE_CLASS = CarwithContract.CARLINK_SERVICE_CLASS;

    /** `-n` argument: `<package>/<class>`. */
    public static final String COMPONENT = CarwithContract.CARLINK_SERVICE_COMPONENT;

    /* Extras read by CarlinkService.onStartCommand (CarlinkService.dump @25a9ee, @25abf4, @25ac00). */
    public static final String EXTRA_START_TYPE = CarwithContract.EXTRA_START_TYPE;
    public static final String EXTRA_CARLIFE_WIRELESS_TYPE = CarwithContract.EXTRA_CARLIFE_WIRELESS_TYPE;
    public static final String EXTRA_CARLIFE_WIRELESS_TYPE_NAME =
            CarwithContract.EXTRA_CARLIFE_WIRELESS_TYPE_NAME;

    /**
     * `startType` values (CarlinkService.dump @0x0148 switch): 1 = USB (`usb_accessory_info`
     * Parcelable), 2 = P2P (`ScanResult` Parcelable), 3 = CARLIFE_USB_ADB, 4 = CARLIFE (the
     * wireless CarLife path, no Parcelable required), 5 = EASYCONNECT (`ScanResult` + 2 Strings).
     */
    public static final int START_TYPE_USB = CarwithContract.START_TYPE_USB;
    public static final int START_TYPE_P2P = CarwithContract.START_TYPE_P2P;
    public static final int START_TYPE_CARLIFE_USB_ADB = CarwithContract.START_TYPE_CARLIFE_USB_ADB;
    public static final int START_TYPE_CARLIFE = CarwithContract.START_TYPE_CARLIFE;
    public static final int START_TYPE_EASYCONNECT = CarwithContract.START_TYPE_EASYCONNECT;

    /** `carlife_wireless_type` values `f9.c.U` uses: `Baidu_ap` → AP mode, `Baidu_wlan` → P2P. */
    public static final String WIRELESS_TYPE_AP = CarwithContract.WIRELESS_TYPE_AP;
    public static final String WIRELESS_TYPE_WLAN = CarwithContract.WIRELESS_TYPE_WLAN;

    /** `carlife_wireless_type_name` is only a display string (`h9.a.o().v(name)`). */
    public static final String DEFAULT_WIRELESS_TYPE_NAME = "okcar";

    /**
     * The probe whose output tells whether the ServiceRecord exists.
     *
     * <p>The absolute path is deliberate (measured on 217, 2026-10-08): this ROM ships BOTH
     * `/vendor/bin/dumpsys` (vendor services only) and `/system/bin/dumpsys`, and a non-interactive
     * `sh -c` there has `PATH=/vendor/bin:/vendor/xbin`, so a bare `dumpsys activity …` answers
     * "Can't find service: activity" and the probe would report "not running" for a service that
     * is running.
     */
    public static final String DUMPSYS = "/system/bin/dumpsys";
    public static final String DUMPSYS_COMMAND = DUMPSYS + " activity services " + PACKAGE;

    /**
     * `dumpsys` prints a record either fully qualified
     * (`com.miui.carlink.castfwk.CarlinkService`) or in the short package form
     * (`com.miui.carlink/.castfwk.CarlinkService`); both must be recognised.
     */
    public static final String SHORT_COMPONENT =
            PACKAGE + "/." + SERVICE_CLASS.substring(PACKAGE.length() + 1);

    /** How long the probe waits between the start and the check, and how many rounds it may take. */
    public static final long DEFAULT_RETRY_MS = 200L;
    public static final long MAX_RETRY_MS = 1000L;
    /** Three rounds ≈ 200+400+800 ms of backoff plus three `am`/`dumpsys` pairs — bounded, short. */
    public static final int DEFAULT_ATTEMPTS = 3;

    /** Runs one shell command as root; appends combined stdout/stderr to {@code stdout}. */
    public interface Shell {
        /** @return the exit status, or negative when the command could not be started at all */
        int run(String command, StringBuilder stdout);
    }

    /** Time source and sleep, injected so the host regression can reach every deadline. */
    public interface Clock {
        long nowMs();

        void sleep(long ms) throws InterruptedException;
    }

    /** What one bootstrap did; every field is meant to be logged verbatim. */
    public static final class Outcome {
        /** True when the probe found the CarlinkService ServiceRecord (or the start succeeded and
         * the caller only asked for the start). */
        public final boolean started;
        /** Number of `am start-foreground-service` invocations. */
        public final int attempts;
        /** Number of probes that saw the service. */
        public final int probes;
        /** The last probe's one-line state, or the last error. */
        public final String detail;
        /** Exit status of the last `am` invocation, -1 when it never ran. */
        public final int exitCode;

        Outcome(boolean started, int attempts, int probes, String detail, int exitCode) {
            this.started = started;
            this.attempts = attempts;
            this.probes = probes;
            this.detail = detail == null ? "" : detail;
            this.exitCode = exitCode;
        }

        /** One-line, loggable summary; never null, never contains credentials. */
        public String describe() {
            return (started ? "state machine started" : "state machine not confirmed")
                    + " attempts=" + attempts + " probes=" + probes + " exit=" + exitCode
                    + (detail.isEmpty() ? "" : " — " + detail);
        }
    }

    private CarwithLauncher() {
    }

    /* ------------------------------------------------------------------ the command */

    /**
     * The exact `am start-foreground-service` line, with the factory's own extras.
     *
     * <p>Every argument is single-quoted: `carlife_wireless_type_name` comes from the accessory and
     * a space, quote, `$` or backtick in it must reach `am` as one literal, not as shell syntax.
     */
    public static String startCommand(String wirelessTypeName) {
        final String name = wirelessTypeName == null || wirelessTypeName.isEmpty()
                ? DEFAULT_WIRELESS_TYPE_NAME : wirelessTypeName;
        return "am start-foreground-service -n " + COMPONENT
                + " --ei " + EXTRA_START_TYPE + ' ' + START_TYPE_CARLIFE
                + " --es " + EXTRA_CARLIFE_WIRELESS_TYPE + ' ' + shellQuote(WIRELESS_TYPE_AP)
                + " --es " + EXTRA_CARLIFE_WIRELESS_TYPE_NAME + ' ' + shellQuote(name);
    }

    /** The command as it may appear in a log — identical here; kept so callers never improvise. */
    public static String logCommand(String wirelessTypeName) {
        return startCommand(wirelessTypeName);
    }

    /**
     * True when the `dumpsys activity services com.miui.carlink` output carries a ServiceRecord for
     * CarlinkService.  An empty/absent section is a "no"; the probe never guesses.
     */
    public static boolean serviceListed(String dumpsys) {
        if (dumpsys == null || dumpsys.isEmpty()) {
            return false;
        }
        for (String raw : dumpsys.split("\n")) {
            final String line = raw.trim();
            /* Require BOTH the record marker and our class, so a stray log line naming the class
             * ("log: starting …CarlinkService") cannot pass as a live service. */
            if (line.indexOf("ServiceRecord") < 0) {
                continue;
            }
            if (line.indexOf(SERVICE_CLASS) >= 0 || line.indexOf(SHORT_COMPONENT) >= 0) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ the action */

    /**
     * Probe only: is the CarlinkService ServiceRecord there right now?  Zero starts, one probe.
     *
     * <p>Used before deciding to start anything — a second `startType=4` `onStartCommand` re-runs
     * CarWith's whole CarLife connect flow, so an already-running service must be left alone.
     */
    public static Outcome probe(Shell shell) {
        if (shell == null) {
            return new Outcome(false, 0, 0, "no shell available", -1);
        }
        final StringBuilder out = new StringBuilder();
        shell.run(DUMPSYS_COMMAND, out);
        final boolean up = serviceListed(out.toString());
        return new Outcome(up, 0, 1,
                up ? "CarlinkService ServiceRecord present (probe only)"
                        : "no CarlinkService ServiceRecord", -1);
    }

    /**
     * Starts CarWith's CarlinkService through CarWith's own entry and confirms the record exists.
     *
     * <p>Bounded and never throwing: each round probes FIRST and only issues the start when the
     * record is absent, then waits a {@value #DEFAULT_RETRY_MS}ms → {@value #MAX_RETRY_MS}ms backoff
     * and probes again.  A service that never shows up is reported as a not-confirmed
     * {@link Outcome} — the caller keeps its own bounded replay going and reports the failure;
     * nothing here retries forever.
     */
    public static Outcome ensure(Shell shell, Clock clock, int attempts, String wirelessTypeName) {
        if (shell == null) {
            return new Outcome(false, 0, 0, "no shell available", -1);
        }
        final int rounds = Math.max(1, attempts);
        final String command = startCommand(wirelessTypeName);
        int starts = 0;
        int probes = 0;
        int exit = -1;
        String detail = "";
        long backoff = DEFAULT_RETRY_MS;
        for (int round = 1; round <= rounds; round++) {
            /* Never start what is already running: CarWith's own flow may be mid-connect. */
            final StringBuilder pre = new StringBuilder();
            shell.run(DUMPSYS_COMMAND, pre);
            probes++;
            if (serviceListed(pre.toString())) {
                return new Outcome(true, starts, probes,
                        starts == 0 ? "already running (no start was needed)"
                                : "CarlinkService ServiceRecord present", exit);
            }
            final StringBuilder out = new StringBuilder();
            exit = shell.run(command, out);
            starts++;
            final String startOutput = oneLine(out.toString());
            if (exit != 0) {
                detail = "`am` exited " + exit + (startOutput.isEmpty() ? "" : ": " + startOutput);
                return new Outcome(false, starts, probes, detail, exit);
            }
            /* `am` answers before the service is created; probe after the factory's own 200ms. */
            sleep(clock, backoff);
            final StringBuilder probe = new StringBuilder();
            shell.run(DUMPSYS_COMMAND, probe);
            probes++;
            if (serviceListed(probe.toString())) {
                return new Outcome(true, starts, probes,
                        "CarlinkService ServiceRecord present (" + startOutput + ')', exit);
            }
            detail = startOutput.isEmpty() ? "no ServiceRecord yet" : startOutput;
            backoff = Math.min(MAX_RETRY_MS, Math.max(DEFAULT_RETRY_MS, backoff * 2));
        }
        return new Outcome(false, starts, probes,
                "CarlinkService did not appear in `" + DUMPSYS_COMMAND + "` after " + starts
                        + " start(s) — " + detail,
                exit);
    }

    /** The production shell: `sh -c` first (the module daemon runs as root), then `su -c`. */
    public static Shell rootShell() {
        return new Shell() {
            @Override
            public int run(String command, StringBuilder stdout) {
                final StringBuilder direct = new StringBuilder();
                final int directStatus = exec(new String[]{"sh", "-c", command}, direct);
                if (directStatus == 0) {
                    append(stdout, direct);
                    return 0;
                }
                final StringBuilder viaSu = new StringBuilder();
                final int suStatus = exec(new String[]{"su", "-c", command}, viaSu);
                if (suStatus == 0) {
                    append(stdout, viaSu);
                    return 0;
                }
                append(stdout, direct);
                return directStatus >= 0 ? directStatus : suStatus;
            }
        };
    }

    /** Wall clock and real sleep; the host regression injects its own. */
    public static Clock systemClock() {
        return new Clock() {
            @Override
            public long nowMs() {
                return System.currentTimeMillis();
            }

            @Override
            public void sleep(long ms) throws InterruptedException {
                Thread.sleep(ms);
            }
        };
    }

    /* ---------------------------------------------------------------------- internals */

    private static void sleep(Clock clock, long ms) {
        if (clock == null) {
            return;
        }
        try {
            clock.sleep(Math.max(0L, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void append(StringBuilder target, StringBuilder text) {
        if (target != null && text != null) {
            target.append(text);
        }
    }

    /** One line, so a shell answer can go straight into a status/event string. */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replace('\n', ' ').replace('\r', ' ');
    }

    private static int exec(String[] argv, StringBuilder sink) {
        try {
            /* One merged pipe: draining stdout and stderr separately can deadlock when the child
             * fills the pipe nobody is reading yet. */
            final ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(true);
            final Process process = builder.start();
            final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            final java.io.InputStream in = process.getInputStream();
            final byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, n);
            }
            sink.append(buffer.toString("UTF-8"));
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Single-quote a shell argument: ' → '\'' so nothing inside can end the quote. */
    static String shellQuote(String value) {
        return "'" + (value == null ? "" : value).replace("'", "'\\''") + "'";
    }
}
