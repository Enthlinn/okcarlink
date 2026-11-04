/*
 * OkcarOS link stack — display-2 (cast2) persisted configuration.
 *
 * `/data/local/tmp/okcar-carplaypp/extra.conf`, key=value lines, world-readable, the same shape the
 * module already uses for `/data/local/tmp/okcar-port-app.conf`.  The directory is created 0777 and
 * the files 0644 because the reader is an ordinary app (the head-unit-side program that will call
 * the HTTP API), not root.
 *
 * Unknown keys are preserved on rewrite: the file is a shared drop box in a fixed directory, and
 * silently dropping a key another program put there would be a data loss the operator cannot see.
 */
package com.okcar.link.stream2;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Cast2Config {

    private static final String TAG = "okcar-cast2";

    public static final String DIR = "/data/local/tmp/okcar-carplaypp";
    public static final String EXTRA_CONF = DIR + "/extra.conf";
    public static final String STATE_FILE = DIR + "/cast2.state";
    /** Display 2's own id, published for out-of-process readers (`-1` when there is no display). */
    public static final String DISPLAY_ID_FILE = DIR + "/cast2-display-id";

    public static final int DEFAULT_WIDTH = 1488;
    public static final int DEFAULT_HEIGHT = 616;
    public static final String DEFAULT_APP = "com.baidu.naviauto";
    public static final int DEFAULT_RTSP_PORT = 8554;
    public static final int DEFAULT_HTTP_PORT = 18080;
    public static final int DEFAULT_BITRATE_BPS = 4_000_000;
    public static final int DEFAULT_FPS = 30;

    private final File mFile;
    private final LinkedHashMap<String, String> mValues = new LinkedHashMap<String, String>();

    public Cast2Config() {
        this(new File(EXTRA_CONF));
    }

    public Cast2Config(File file) {
        mFile = file;
        load();
    }

    /** Reads the file; a missing file is simply "all defaults", a broken one is logged in full. */
    public synchronized void load() {
        mValues.clear();
        if (!mFile.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(mFile), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                final String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                final int equals = text.indexOf('=');
                if (equals <= 0) {
                    Log.w(TAG, "extra.conf: ignoring a line without '=': " + text);
                    continue;
                }
                mValues.put(text.substring(0, equals).trim(), text.substring(equals + 1).trim());
            }
        } catch (Throwable t) {
            /* Not a "fall back to defaults" path: the values we could not read are reported, and the
             * operator sees the file is unreadable instead of silently losing a pinned size. */
            Log.w(TAG, "cannot read " + mFile + ": " + t);
        } finally {
            close(reader);
        }
    }

    /** Writes the current values back, preserving the ones this class does not own. */
    public synchronized void save() {
        ensureDir();
        final List<String> lines = new ArrayList<String>();
        lines.add("# OkCarLink cast2 (display 2) configuration — key=value, unknown keys preserved");
        for (Map.Entry<String, String> entry : mValues.entrySet()) {
            lines.add(entry.getKey() + '=' + entry.getValue());
        }
        final StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        writeFile(mFile, text.toString());
    }

    public synchronized String raw(String key, String fallback) {
        final String value = mValues.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    public synchronized void put(String key, String value) {
        if (value == null) {
            mValues.remove(key);
        } else {
            mValues.put(key, value);
        }
    }

    public synchronized void remove(String key) {
        mValues.remove(key);
    }

    public String app() {
        return raw("app", DEFAULT_APP);
    }

    public void setApp(String pkg) {
        put("app", pkg);
    }

    /** The configured exclusive activity, or empty when the launch activity is the right one. */
    public String activity() {
        return raw("activity", "");
    }

    public void setActivity(String component) {
        put("activity", component);
    }

    public int rtspPort() {
        return intValue("rtsp_port", DEFAULT_RTSP_PORT);
    }

    public int httpPort() {
        return intValue("http_port", DEFAULT_HTTP_PORT);
    }

    public int bitrateBps() {
        return intValue("bitrate_bps", DEFAULT_BITRATE_BPS);
    }

    public int fps() {
        return intValue("fps", DEFAULT_FPS);
    }

    /** `size` as width/height; a malformed value is logged and the default stands. */
    public int[] size() {
        final String value = raw("size", DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT);
        final int[] parsed = parseSize(value);
        if (parsed == null) {
            Log.w(TAG, "extra.conf: size='" + value + "' is not WxH; using "
                    + DEFAULT_WIDTH + 'x' + DEFAULT_HEIGHT);
            return new int[]{DEFAULT_WIDTH, DEFAULT_HEIGHT};
        }
        return parsed;
    }

    public void setSize(int width, int height) {
        put("size", width + "x" + height);
    }

    /** {@code WxH}, or null when the text is not one. */
    public static int[] parseSize(String value) {
        if (value == null) {
            return null;
        }
        final String[] parts = value.trim().toLowerCase(java.util.Locale.ROOT).split("x");
        if (parts.length != 2) {
            return null;
        }
        try {
            final int width = Integer.parseInt(parts[0].trim());
            final int height = Integer.parseInt(parts[1].trim());
            if (width <= 0 || height <= 0) {
                return null;
            }
            return new int[]{width, height};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int intValue(String key, int fallback) {
        final String value = mValues.get(key);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            final int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) {
                Log.w(TAG, "extra.conf: " + key + '=' + value + " is not positive; using " + fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            Log.w(TAG, "extra.conf: " + key + '=' + value + " is not a number; using " + fallback);
            return fallback;
        }
    }

    /** Creates the shared directory 0777; a failure is logged, not hidden. */
    public static void ensureDir() {
        try {
            final File dir = new File(DIR);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                Log.w(TAG, "cannot create " + DIR);
                return;
            }
            chmod(DIR, "0777");
        } catch (Throwable t) {
            Log.w(TAG, "cannot prepare " + DIR + ": " + t);
        }
    }

    /** Writes a world-readable file into {@link #DIR} (0644), logging a failure instead of hiding it. */
    public static void writeFile(File file, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "cannot write " + file + ": " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    /* best effort */
                }
            }
        }
        file.setReadable(true, false);
        chmod(file.getAbsolutePath(), "0644");
    }

    /**
     * {@code java.io.File} cannot set group/other write or execute bits, and this directory has to
     * be writable by an ordinary app, so the mode goes through the root daemon's own chmod.  A
     * failure is logged and does not stop the daemon.
     */
    public static void chmod(String path, String mode) {
        try {
            final Process process =
                    Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", mode, path});
            process.waitFor();
        } catch (Throwable t) {
            /* chmod is best effort: the value is still world-readable through setReadable(). */
            Log.w(TAG, "chmod " + mode + ' ' + path + " failed: " + t);
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable ignored) {
                /* best effort */
            }
        }
    }
}
