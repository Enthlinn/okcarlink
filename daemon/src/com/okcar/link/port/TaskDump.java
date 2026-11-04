package com.okcar.link.port;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Find the target app from a "task stack dump" (pure Java, host-testable).
 *
 * <p>Why its own class: on 2026-10-05, on 217 (HyperOS), `dumpsys activity activities` returned
 * <b>0 bytes</b> — `dumpsys display` and `dumpsys window` were likewise empty — and keeper relies on it to tell
 * "which screen the target app's task moved to". So after MIUI cleared the target app from recents (`am_kill … SwipeUpClean`),
 * keeper always got `findTaskId → -1`, neither re-placed nor reported anything, and the head-unit screen just sat there dead.
 *
 * <p>Measured on this device: **`am stack list` is alive** (61ms / 6.4KB), with a two-level format:
 * <pre>
 * RootTask id=162 bounds=[0,0][1080,2400] displayId=0 userId=0
 *   taskId=162: live.alist.mpv/live.alist.mpv.MainActivity bounds=… visible=false topActivity=…
 * </pre>
 * So this recognizes both formats: old ROMs' `dumpsys activity activities` (`Display #N` sections +
 * `RootTask{… #12 …}` / `Task{… #123 …}`) and MIUI's `am stack list`.
 */
public final class TaskDump {

    /** {@code Display #12} — section header of the dumpsys format. */
    private static final Pattern DISPLAY_SECTION = Pattern.compile("Display #(\\d+)");
    /** {@code RootTask{… #12 …}} — must be tried before {@link #TASK_LINE}, which also matches it. */
    private static final Pattern ROOT_TASK_LINE = Pattern.compile("RootTask\\{[^}]*#(\\d+)");
    private static final Pattern TASK_LINE = Pattern.compile("Task\\{[^}]*#(\\d+)");
    /** {@code RootTask id=162 bounds=[…] displayId=0 userId=0} — am stack list format. */
    private static final Pattern AM_ROOT_LINE =
            Pattern.compile("RootTask id=(\\d+).*?displayId=(-?\\d+)");
    /** {@code taskId=162: live.alist.mpv/live.alist.mpv.MainActivity …} — am stack list format. */
    private static final Pattern AM_TASK_LINE = Pattern.compile("taskId=(\\d+):\\s*(\\S+)");

    private TaskDump() {
    }

    /**
     * Is this dump "blind" — the command ran but returned nothing (that is what `dumpsys activity` does on HyperOS).
     * The caller uses this to switch data sources, instead of treating "empty" as "app not running".
     */
    public static boolean looksBlind(String[] lines) {
        if (lines == null || lines.length == 0) {
            return true;
        }
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            /* Only lines that carry task info count: some ROMs answer with an error/usage line, which is also blind. */
            if (AM_ROOT_LINE.matcher(trimmed).find() || AM_TASK_LINE.matcher(trimmed).find()
                    || ROOT_TASK_LINE.matcher(trimmed).find()
                    || TASK_LINE.matcher(trimmed).find()
                    || DISPLAY_SECTION.matcher(trimmed).find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The (root-preferred) task id of the running {@code pkg}, or -1 if not found.
     *
     * <p>{@code moveRootTaskToDisplay} wants the root task, and a dump shows both kinds; only the root id makes
     * "move it back" actually take effect rather than spinning on a leaf task.
     */
    public static int findTask(String[] lines, String pkg) {
        if (lines == null || pkg == null || pkg.isEmpty()) {
            return -1;
        }
        int rootTask = -1;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher amRoot = AM_ROOT_LINE.matcher(line);
            if (amRoot.find()) {
                rootTask = Integer.parseInt(amRoot.group(1));
                if (line.contains(pkg)) {
                    return rootTask;
                }
                continue;
            }
            final Matcher amTask = AM_TASK_LINE.matcher(line);
            if (amTask.find()) {
                final int id = Integer.parseInt(amTask.group(1));
                if (pkg.equals(packageOf(amTask.group(2)))) {
                    return rootTask > 0 ? rootTask : id;
                }
                continue;
            }
            final Matcher root = ROOT_TASK_LINE.matcher(line);
            if (root.find()) {
                rootTask = Integer.parseInt(root.group(1));
                if (line.contains(pkg)) {
                    return rootTask;
                }
                continue;
            }
            final Matcher task = TASK_LINE.matcher(line);
            if (task.find()) {
                final int id = Integer.parseInt(task.group(1));
                if (line.contains(pkg)) {
                    return rootTask > 0 ? rootTask : id;
                }
                continue;
            }
            if (line.contains("ActivityRecord{") && line.contains(pkg)) {
                return rootTask;
            }
        }
        /* If it really isn't running, return -1 rather than "whoever the dump stopped at" — moving the launcher task onto the head unit is worse than failing. */
        return -1;
    }

    /** Display the task {@code taskId} currently sits on, or -1 if not found. */
    public static int displayOfTask(String[] lines, int taskId) {
        if (lines == null || taskId <= 0) {
            return -1;
        }
        int display = -1;
        int rootTask = -1;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher amRoot = AM_ROOT_LINE.matcher(line);
            if (amRoot.find()) {
                rootTask = Integer.parseInt(amRoot.group(1));
                display = Integer.parseInt(amRoot.group(2));
                if (rootTask == taskId) {
                    return display;
                }
                continue;
            }
            final Matcher amTask = AM_TASK_LINE.matcher(line);
            if (amTask.find()) {
                if (Integer.parseInt(amTask.group(1)) == taskId) {
                    return display;
                }
                continue;
            }
            final Matcher section = DISPLAY_SECTION.matcher(line);
            if (section.find()) {
                display = Integer.parseInt(section.group(1));
                continue;
            }
            /* dumpsys shape: the `Display #N` section header comes first and the RootTask{}/Task{} below it belong to it —
             * do not clear display here, or every RootTask line would wipe the ownership to -1 (host regression pinned this down). */
            final Matcher task = TASK_LINE.matcher(line);
            if (task.find() && Integer.parseInt(task.group(1)) == taskId) {
                return display;
            }
        }
        return -1;
    }

    /** Package name from {@code pkg/Act} or {@code pkg/.Act}; returned as-is when there is no slash. */
    static String packageOf(String component) {
        if (component == null) {
            return "";
        }
        final int slash = component.indexOf('/');
        return slash > 0 ? component.substring(0, slash) : component;
    }
}
