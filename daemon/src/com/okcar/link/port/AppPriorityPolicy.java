package com.okcar.link.port;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Policy for "which apps get their priority raised" (pure Java, host-testable).
 *
 * <p>User request 2026-10-05: let the user decide which apps need priority boosting, and give them an entry point.
 * Field motivation: on the phone, whenever a new app starts, its launch animation preempts scheduling and shoves aside
 * the player being cast (or our own encoder thread), causing a few dropped frames (§12.63/§12.66 measured: systemui
 * 13–17%, mpv 7–9%, SF 9–12% — not saturation).
 *
 * <p>The config format is deliberately as plain as possible: one package name per line (`#` comments, blank lines ignored).
 * Anyone can read and hand-edit it, and the panel wraps another layer with the same semantics.
 */
public final class AppPriorityPolicy {

    /** nice value to raise to: -10 is enough to block transient preemption such as a launch animation, without squeezing system-critical threads. */
    public static final int NICE = -10;

    /** Config path: same directory as the other panel configs; the panel writes this file directly. */
    public static final String CONF_PATH = "/data/local/tmp/okcar-port-priority.conf";

    private AppPriorityPolicy() {
    }

    /**
     * Parse config text: one package name per line, everything after `#` dropped, whitespace trimmed, deduped, order preserved.
     *
     * <p>No heavy "does this look like a package name" validation — a few extra characters only make the match miss; that is
     * safer than rejecting the whole line, but a line containing a space is definitely not a package name (`pkg:remote` child
     * processes are handled by {@link #matches}).
     */
    public static List<String> parsePackages(String text) {
        final LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) {
            return new ArrayList<>();
        }
        for (String raw : text.split("\n")) {
            final int hash = raw.indexOf('#');
            final String body = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            if (body.isEmpty() || body.indexOf(' ') >= 0 || body.indexOf('\t') >= 0) {
                continue;
            }
            out.add(body);
        }
        return new ArrayList<>(out);
    }

    /** Is this process (first field of {@code /proc/<pid>/cmdline}) an app in the list, including {@code pkg:remote} child processes. */
    public static boolean matches(String cmdline, List<String> packages) {
        if (cmdline == null || cmdline.isEmpty() || packages == null || packages.isEmpty()) {
            return false;
        }
        for (String pkg : packages) {
            if (cmdline.equals(pkg)) {
                return true;
            }
            if (cmdline.length() > pkg.length() + 1 && cmdline.startsWith(pkg)
                    && cmdline.charAt(pkg.length()) == ':') {
                return true;
            }
        }
        return false;
    }

    /** Callback: should the pid still be raised for {@code pkg} (a changed pid means raise it again). */
    public static boolean needsRaise(int alreadyRaisedPid, int currentPid) {
        return currentPid > 0 && currentPid != alreadyRaisedPid;
    }

    /** Build the config text written back to the panel (same semantics as the file, so panel display/save stay consistent). */
    public static String toConf(List<String> packages) {
        final StringBuilder sb = new StringBuilder();
        sb.append("# okcar-port app priority: one package name per line; apps listed here get raised scheduling priority\n");
        sb.append("# (nice=").append(NICE).append(", and joined to the top-app cpuset). Empty means no app is raised.\n");
        if (packages != null) {
            for (String pkg : packages) {
                sb.append(pkg).append('\n');
            }
        }
        return sb.toString();
    }
}
