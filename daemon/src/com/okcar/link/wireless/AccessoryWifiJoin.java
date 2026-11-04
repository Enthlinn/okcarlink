/*
 * OkcarOS link stack — "join the AP the accessory handed us, and prove we are on it".
 *
 * The wireless CarPlay bring-up receives the receiver's hotspot in iAP2 0x5703
 * (AccessoryWiFiConfigurationInformation: ssid / passphrase / securityType / channel) and, until
 * this class existed, that was where it ended: the credentials were parsed into a log line and the
 * actual association was left to the framework's own auto-join of an already-saved network.  With
 * 217's saved "K30" configuration gone the whole chain parked at "credentials received" while the
 * receiver's AirPlay server timed out — the accessory had done its part and nobody came.
 *
 * This is the missing action, in the shape the task and the device agree on (217, Android 15,
 * `cmd wifi help`):
 *
 *   1. map the announced securityType through {@link WirelessMessages.Security} — an unknown value is
 *      an explicit error, never a guess;
 *   2. run `cmd wifi connect-network <ssid> <open|wep|wpa2|wpa3> <passphrase>` as root
 *      (direct `sh -c`, then `su -c` for the non-root APK path, exactly like ShellWifiOps);
 *   3. poll `cmd wifi status` until it says `Wifi is connected to "<ssid>"` (the literal line the
 *      platform prints; a *different* SSID does not count), then keep the IPv4 address and netId
 *      from the same output;
 *   4. give up on a bounded deadline with a numbered {@link Error} — never hang, never throw into
 *      the session state machine, and never retry in a loop of its own.  The caller owns retries
 *      (the join guard and the session's one-join-per-session claim) and the session's bounded
 *      watchdog owns the "put the radio back" side.
 *
 * Pure Java: the shell and the clock are injected, so that harness drives every branch —
 * known and unknown security types, command assembly and escaping, the status parser, and the
 * command-failed / never-associated / interrupted failures — on the host JVM with no device.
 */

package com.okcar.link.wireless;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public final class AccessoryWifiJoin {

    /** How long the whole action may take: the connect command plus the association poll. */
    public static final long DEFAULT_TIMEOUT_MS = 20000L;

    /** Interval between two `cmd wifi status` polls. */
    public static final long DEFAULT_POLL_MS = 500L;

    /** The status command whose output step 3 reads. */
    public static final String STATUS_COMMAND = "cmd wifi status";

    /** The marker line the platform prints once associated. */
    public static final String CONNECTED_MARKER = "Wifi is connected to \"";

    /** Numbered failure codes: greppable in the field, pinned by the host regression. */
    public enum Error {
        NONE(0, "connected"),
        EMPTY_SSID(1, "the hand-off carries no SSID"),
        UNKNOWN_SECURITY(2, "the hand-off's securityType is not in the mapping table — refusing to guess"),
        MISSING_PASSPHRASE(3, "a secured network was handed over without a passphrase"),
        COMMAND_FAILED(4, "`cmd wifi connect-network` failed"),
        TIMEOUT(5, "`cmd wifi status` never reported this SSID as connected"),
        SHELL_FAILED(6, "the root shell could not run the command"),
        INTERRUPTED(7, "the join was interrupted");

        /** Stable number for logs and tests. */
        public final int code;
        public final String text;

        Error(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }

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

    public static final class Result {
        public final boolean connected;
        public final Error error;
        /** Exit status of `cmd wifi connect-network`, -1 when it was never run. */
        public final int exitCode;
        /** One-line, loggable summary.  Never contains the passphrase. */
        public final String detail;
        /** IPv4 address of the association, "" when unknown. */
        public final String ipv4;
        /** Framework netId from `cmd wifi status`, -1 when unknown. */
        public final int netId;
        /** Number of `cmd wifi status` polls that were made. */
        public final int polls;
        /** Raw stdout of the connect command (trimmed), for the report/log. */
        public final String output;
        /** The "Wifi is connected to …" line that matched, or the last state line seen. */
        public final String statusLine;

        Result(boolean connected, Error error, int exitCode, String detail, String ipv4,
               int netId, int polls, String output, String statusLine) {
            this.connected = connected;
            this.error = error;
            this.exitCode = exitCode;
            this.detail = detail;
            this.ipv4 = ipv4 == null ? "" : ipv4;
            this.netId = netId;
            this.polls = polls;
            this.output = output == null ? "" : output;
            this.statusLine = statusLine == null ? "" : statusLine;
        }

        /**
         * True when the same credentials are worth another attempt later (the command failed, the
         * association timed out, the shell was missing).  False for the refusals — an unknown
         * security type or a missing passphrase will not become joinable by trying again.
         */
        public boolean retryable() {
            return error == Error.COMMAND_FAILED || error == Error.TIMEOUT
                    || error == Error.SHELL_FAILED || error == Error.INTERRUPTED;
        }

        /** Log line: `connected polls=3 ip=192.0.2.227 netId=0` or `failed(TIMEOUT/5) …`. */
        public String describe() {
            final StringBuilder sb = new StringBuilder();
            sb.append(connected ? "connected" : "failed(" + error.name() + '/' + error.code + ')')
                    .append(" exit=").append(exitCode)
                    .append(" polls=").append(polls);
            if (!ipv4.isEmpty()) {
                sb.append(" ip=").append(ipv4);
            }
            if (netId >= 0) {
                sb.append(" netId=").append(netId);
            }
            if (!detail.isEmpty()) {
                sb.append(" — ").append(detail);
            }
            if (!statusLine.isEmpty()) {
                sb.append(" [").append(statusLine).append(']');
            }
            return sb.toString();
        }
    }

    private AccessoryWifiJoin() {
    }

    /* ------------------------------------------------------------------ the action */

    /**
     * Joins {@code ssid} with the announced credentials and verifies the association.
     *
     * <p>Never throws for a join failure and never blocks past {@code timeoutMs}: every outcome is
     * a {@link Result}.  An already-connected phone is reported as success without touching the
     * radio — 0x5703 is delivered twice by the captured accessory and re-issuing the connect for an
     * established association only churns the supplicant.
     */
    public static Result join(String ssid, int securityType, String passphrase, long timeoutMs,
                              long pollMs, Shell shell, Clock clock) {
        if (ssid == null || ssid.isEmpty()) {
            return failed(Error.EMPTY_SSID, -1, Error.EMPTY_SSID.text, "", -1, 0, "", "");
        }
        final WirelessMessages.Security.Mapping security = WirelessMessages.Security.of(securityType);
        if (!security.known) {
            return failed(Error.UNKNOWN_SECURITY, -1,
                    "securityType=" + securityType + " is not in the table (" + WirelessMessages.Security.table()
                            + ") — not guessing WPA2",
                    "", -1, 0, "", "");
        }
        final String psk = passphrase == null ? "" : passphrase;
        if (security.passphraseRequired && psk.isEmpty()) {
            return failed(Error.MISSING_PASSPHRASE, -1,
                    "security=" + security.name + " needs a passphrase and 0x5703 carried none",
                    "", -1, 0, "", "");
        }
        if (shell == null) {
            return failed(Error.SHELL_FAILED, -1, "no shell available", "", -1, 0, "", "");
        }

        /* Cheap pre-check: 0x5703 arrives twice on the captured link, and an association that is
         * already up must not be torn down and rebuilt. */
        final StringBuilder out = new StringBuilder();
        final int preStatus = shell.run(STATUS_COMMAND, out);
        if (preStatus == 0 && connectedLine(out.toString(), ssid) != null) {
            final String text = out.toString();
            return new Result(true, Error.NONE, -1, "already connected to " + ssid,
                    ipv4Of(text), netIdOf(text), 0, "", connectedLine(text, ssid));
        }

        final String command = connectCommand(ssid, securityType, psk);
        out.setLength(0);
        final int exit = shell.run(command, out);
        final String output = redact(trim(out), psk);
        if (exit != 0) {
            return failed(Error.COMMAND_FAILED, exit,
                    "`" + logCommand(ssid, security) + "` exited " + exit
                            + (output.isEmpty() ? "" : ": " + output),
                    "", -1, 0, output, "");
        }

        final long deadline = clock.nowMs() + Math.max(0L, timeoutMs);
        int polls = 0;
        String lastLine = "";
        while (true) {
            out.setLength(0);
            final int status = shell.run(STATUS_COMMAND, out);
            final String text = out.toString();
            if (status == 0) {
                polls++;
                final String line = connectedLine(text, ssid);
                if (line != null) {
                    return new Result(true, Error.NONE, exit, "joined " + ssid + " via cmd wifi",
                            ipv4Of(text), netIdOf(text), polls, output, line);
                }
                lastLine = stateLine(text);
            } else {
                lastLine = "cmd wifi status exited " + status;
            }
            if (clock.nowMs() >= deadline) {
                return failed(Error.TIMEOUT, exit,
                        "not connected to \"" + ssid + "\" after " + Math.max(0L, timeoutMs)
                                + "ms (" + polls + " status poll(s))",
                        ipv4Of(text), netIdOf(text), polls, output, lastLine);
            }
            final long remaining = deadline - clock.nowMs();
            try {
                clock.sleep(Math.max(1L, Math.min(pollMs, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failed(Error.INTERRUPTED, exit, "interrupted while polling",
                        ipv4Of(text), netIdOf(text), polls, output, lastLine);
            }
        }
    }

    /* ------------------------------------------------------------- command and parsing */

    /**
     * `cmd wifi connect-network <ssid> <token> [<passphrase>]`, every argument single-quoted so an
     * SSID or passphrase with a space, a quote, `$` or a backtick reaches the shell as one literal.
     */
    public static String connectCommand(String ssid, WirelessMessages.Security.Mapping security) {
        final StringBuilder sb = new StringBuilder("cmd wifi connect-network ")
                .append(shellQuote(ssid)).append(' ').append(security.token);
        return sb.toString();
    }

    /** The full command including the passphrase; {@code security} must be known. */
    public static String connectCommand(String ssid, int securityType, String passphrase) {
        final WirelessMessages.Security.Mapping security = WirelessMessages.Security.of(securityType);
        if (!security.known) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(connectCommand(ssid, security));
        if (security.passphraseRequired) {
            sb.append(' ').append(shellQuote(passphrase == null ? "" : passphrase));
        }
        return sb.toString();
    }

    /**
     * The command as it may appear in a log: identical, minus the passphrase.  The access point's
     * key must never reach a log line, an event and therefore the WebUI/uploaded logs (the same rule
     * that keeps the Wi-Fi self-test credentials out of the dex).
     */
    public static String logCommand(String ssid, WirelessMessages.Security.Mapping security) {
        final StringBuilder sb = new StringBuilder(connectCommand(ssid, security));
        if (security.passphraseRequired) {
            sb.append(" ***");
        }
        return sb.toString();
    }

    /** Replace the access point's key wherever it appears in captured output. */
    static String redact(String text, String passphrase) {
        if (text == null || text.isEmpty() || passphrase == null || passphrase.isEmpty()) {
            return text == null ? "" : text;
        }
        return text.replace(passphrase, "***");
    }

    /** The `Wifi is connected to "<ssid>"` line, or null when the phone is on something else. */
    public static String connectedLine(String statusText, String ssid) {
        if (statusText == null || ssid == null || ssid.isEmpty()) {
            return null;
        }
        for (String raw : statusText.split("\n")) {
            final String line = raw.trim();
            final int at = line.indexOf(CONNECTED_MARKER);
            if (at < 0) {
                continue;
            }
            final int start = at + CONNECTED_MARKER.length();
            final int end = line.lastIndexOf('"');
            if (end < start) {
                continue;
            }
            /* Exact match: an SSID that merely starts with ours is a different network. */
            if (line.substring(start, end).equals(ssid)) {
                return line;
            }
        }
        return null;
    }

    public static boolean connectedTo(String statusText, String ssid) {
        return connectedLine(statusText, ssid) != null;
    }

    /** The first IPv4 address of the association (`IP: /192.0.2.227`), or "". */
    public static String ipv4Of(String statusText) {
        if (statusText == null) {
            return "";
        }
        for (String raw : statusText.split("\n")) {
            final int at = raw.indexOf("IP: /");
            if (at < 0) {
                continue;
            }
            final String rest = raw.substring(at + 5).trim();
            final int end = firstOf(rest, ',', ' ', '"');
            final String candidate = end < 0 ? rest : rest.substring(0, end);
            if (isIpv4(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    /** `Net ID: <n>` out of `cmd wifi status`, or -1 when the line is absent/unparsable. */
    public static int netIdOf(String statusText) {
        if (statusText == null) {
            return -1;
        }
        for (String raw : statusText.split("\n")) {
            final int at = raw.indexOf("Net ID: ");
            if (at < 0) {
                continue;
            }
            final String rest = raw.substring(at + 8).trim();
            int end = 0;
            if (end < rest.length() && rest.charAt(0) == '-') {
                end++;
            }
            while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
                end++;
            }
            if (end == 0 || (end == 1 && rest.charAt(0) == '-')) {
                continue;
            }
            try {
                return Integer.parseInt(rest.substring(0, end));
            } catch (NumberFormatException ignored) {
                /* keep scanning */
            }
        }
        return -1;
    }

    /** `Security type: <n>` as the device reports it in `cmd wifi status`, or -1. */
    public static int statusSecurityTypeOf(String statusText) {
        if (statusText == null) {
            return -1;
        }
        for (String raw : statusText.split("\n")) {
            final int at = raw.indexOf("Security type: ");
            if (at < 0) {
                continue;
            }
            final String rest = raw.substring(at + 15).trim();
            int end = 0;
            while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
                end++;
            }
            if (end > 0) {
                try {
                    return Integer.parseInt(rest.substring(0, end));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** The netId of an SSID in `cmd wifi list-networks`, or -1; used by the field report/cleanup. */
    public static int netIdInList(String listText, String ssid) {
        if (listText == null || ssid == null || ssid.isEmpty()) {
            return -1;
        }
        for (String raw : listText.split("\n")) {
            final String line = raw.trim();
            if (!line.contains(ssid)) {
                continue;
            }
            int i = 0;
            while (i < line.length() && Character.isDigit(line.charAt(i))) {
                i++;
            }
            if (i > 0 && i < line.length() && Character.isWhitespace(line.charAt(i))) {
                try {
                    return Integer.parseInt(line.substring(0, i));
                } catch (NumberFormatException ignored) {
                    /* keep scanning */
                }
            }
        }
        return -1;
    }

    /** The line to log when the association did not happen: the state/supplicant hints. */
    public static String stateLine(String statusText) {
        if (statusText == null) {
            return "";
        }
        for (String raw : statusText.split("\n")) {
            final String line = raw.trim();
            if (line.startsWith("Wifi is connected") || line.startsWith("Wifi is disconnected")
                    || line.startsWith("Wifi is ") || line.contains("Supplicant state:")) {
                return line;
            }
        }
        return "";
    }

    /* ------------------------------------------------------------------- the root shell */

    /**
     * The production shell: `sh -c` first (the module's own {@code app_process} runs as root, where
     * {@code su} may not exist at all), then `su -c` for the non-root APK path.  Mirrors
     * {@code ShellWifiOps.run}, including "report the direct result" — it is the one that describes
     * what this process can do itself.
     */
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

    private static Result failed(Error error, int exitCode, String detail, String ipv4,
                                 int netId, int polls, String output, String statusLine) {
        return new Result(false, error, exitCode, detail, ipv4, netId, polls, output, statusLine);
    }

    private static void append(StringBuilder target, StringBuilder text) {
        if (target != null && text != null) {
            target.append(text);
        }
    }

    private static int exec(String[] argv, StringBuilder sink) {
        try {
            /* One merged pipe: draining stdout and stderr separately can deadlock when the child
             * fills the pipe nobody is reading yet. */
            final ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(true);
            final Process process = builder.start();
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            final InputStream in = process.getInputStream();
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

    private static String trim(StringBuilder text) {
        final String value = text == null ? "" : text.toString().trim();
        /* One line, so it can go straight into a LinkState event. */
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static int firstOf(String text, char a, char b, char c) {
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            if (ch == a || ch == b || ch == c) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isIpv4(String value) {
        if (value == null || value.isEmpty() || value.indexOf(':') >= 0) {
            return false;
        }
        int dots = 0;
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            if (ch == '.') {
                dots++;
            } else if (!Character.isDigit(ch)) {
                return false;
            }
        }
        return dots == 3 && !value.startsWith("127.") && !value.startsWith("0.");
    }

    /** Single-quote a shell argument: ' → '\'' so nothing inside can end the quote. */
    static String shellQuote(String value) {
        return "'" + (value == null ? "" : value).replace("'", "'\\''") + "'";
    }
}
