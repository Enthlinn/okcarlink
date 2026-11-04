package com.okcar.link.port;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Normal-phone {@link WifiOps}. Only the deprecated write surface is restricted
 * for apps targeting Android 30+, so reads stay on the real {@code WifiManager}
 * (accurate SSID polling for the join state machine) while writes go through
 * the root shell — {@code cmd wifi connect-network} / {@code forget-network}
 * and {@code svc wifi}. Framework netIds are simulated as monotonic handles
 * mapped back to SSIDs (plan §1).
 */
public final class ShellWifiOps implements WifiOps {

    private final WifiManager mWifi;
    private final Map<Integer, String> mHandles = new HashMap<Integer, String>();
    private int mNextHandle = 10000;

    public ShellWifiOps(WifiManager wifi) {
        mWifi = wifi;
    }

    @Override
    public int addNetworkSuggestions(List<WifiNetworkSuggestion> suggestions) {
        return mWifi.addNetworkSuggestions(suggestions);
    }

    @Override
    public int removeNetworkSuggestions(List<WifiNetworkSuggestion> suggestions) {
        return mWifi.removeNetworkSuggestions(suggestions);
    }

    @Override
    public int addNetwork(WifiConfiguration config) {
        final String ssid = unquote(config.SSID);
        if (ssid == null) {
            return -1;
        }
        final boolean open = config.preSharedKey == null || config.preSharedKey.isEmpty();
        final StringBuilder command = new StringBuilder("cmd wifi connect-network ")
                .append(shellQuote(ssid)).append(open ? " open" : " wpa2 ")
                .append(open ? "" : shellQuote(unquote(config.preSharedKey)));
        if (run(command.toString()) != 0) {
            return -1;
        }
        final int handle = mNextHandle++;
        mHandles.put(handle, ssid);
        mLastSsid = ssid;
        mLastPsk = open ? null : unquote(config.preSharedKey);
        return handle;
    }

    @Override
    public void adoptNetwork(int netId, String ssid, String passphrase) {
        if (ssid == null || ssid.isEmpty()) {
            return;
        }
        mHandles.put(netId, unquote(ssid));
        mLastSsid = unquote(ssid);
        mLastPsk = passphrase == null || passphrase.isEmpty() ? null : unquote(passphrase);
    }

    @Override
    public boolean enableNetwork(int netId, boolean attemptConnect) {
        final String handleSsid = mHandles.get(netId);
        final WifiConfiguration config = handleSsid == null ? configOfNetId(netId) : null;
        final String ssid = handleSsid != null ? handleSsid
                : (config == null || config.SSID == null ? null : unquote(config.SSID));
        if (ssid == null) {
            return false;
        }
        final String psk = handleSsid != null
                ? (hasPsk(ssid) ? lastPsk(ssid) : null)
                : (config.preSharedKey == null ? null : unquote(config.preSharedKey));
        /* "Current" for reconnect(): whichever network this adapter was last asked for.  It used to
         * be set only by addNetwork/adoptNetwork, so the revert's enableNetwork(targetNetId) left it
         * pointing at the accessory and the reconnect() that follows pulled the phone straight back
         * onto the network the revert was leaving (measured 2026-10-02: the revert logged
         * `enableNetwork=false reconnect=true` while the phone stayed on "car"). */
        mLastSsid = ssid;
        mLastPsk = psk == null || psk.isEmpty() ? null : psk;
        if (!attemptConnect || onSsid(ssid)) {
            return true;
        }
        return run("cmd wifi connect-network " + shellQuote(ssid)
                + (mLastPsk == null ? " open" : " wpa2 " + shellQuote(mLastPsk))) == 0;
    }

    /** The framework's configuration for {@code netId}, or null when it does not list it. */
    private WifiConfiguration configOfNetId(int netId) {
        try {
            @SuppressWarnings("deprecation")
            final List<WifiConfiguration> configs = mWifi.getConfiguredNetworks();
            if (configs == null) {
                return null;
            }
            for (WifiConfiguration config : configs) {
                if (config != null && config.networkId == netId) {
                    return config;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    @Override
    public boolean disableNetwork(int netId) {
        return true; // leaving happens through removeNetwork/forget
    }

    /**
     * Forgets a configured network by its netId.
     *
     * <p>{@code cmd wifi forget-network} takes a <em>netId</em>, not an SSID.  Both this method and
     * the handle path it used to take passed the SSID, and the shell answered
     * {@code Invalid args … NumberFormatException: For input string: "car"} with exit 255 — so no
     * accessory network was ever actually removed through this path.  Because the old code also
     * answered {@code true} without running anything when it had no handle, the failure stayed
     * invisible: the revert logged "removed leftover accessory netId=258" while
     * {@code cmd wifi list-networks} still listed it, and the phone kept coming back to the
     * accessory hotspot (measured 2026-10-02).  The numeric form works — verified on 217:
     * {@code cmd wifi forget-network 258 → Forget successful}.
     */
    @Override
    public boolean removeNetwork(int netId) {
        mHandles.remove(netId);
        return run("cmd wifi forget-network " + netId) == 0;
    }

    @Override
    public boolean saveConfiguration() {
        return true; // the shell path persists natively; nothing in memory to save
    }

    @Override
    public boolean setWifiEnabled(boolean enabled) {
        return run("svc wifi " + (enabled ? "enable" : "disable")) == 0;
    }

    @Override
    public boolean isWifiEnabled() {
        return mWifi.isWifiEnabled();
    }

    @Override
    public boolean reconnect() {
        // The framework's reconnect() is deprecated/no-op for our target SDK, so this re-issues the
        // connect for the network the adapter was last asked for ("current"): the accessory while
        // joining, the original while reverting — enableNetwork() keeps that pointer up to date.
        if (mLastSsid == null) {
            return true;
        }
        return run("cmd wifi connect-network " + shellQuote(mLastSsid)
                + (mLastPsk == null ? " open" : " wpa2 " + shellQuote(mLastPsk))) == 0
                || onSsid(mLastSsid);
    }

    @Override
    public WifiInfo getConnectionInfo() {
        return mWifi.getConnectionInfo();
    }

    @Override
    public List<WifiConfiguration> getConfiguredNetworks() {
        return mWifi.getConfiguredNetworks();
    }

    @Override
    public List<ScanResult> getScanResults() {
        return mWifi.getScanResults();
    }

    /**
     * The framework view is redacted without location permission, so ask the shell
     * for the real association. Falls back to the framework value when the shell
     * cannot answer.
     */
    @Override
    public String activeSsid() {
        try {
            final Process process = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "cmd wifi status"});
            final java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final int start = line.indexOf("SSID: \"");
                if (start >= 0) {
                    final int end = line.indexOf('"', start + 7);
                    if (end > start) {
                        process.waitFor();
                        return line.substring(start + 7, end);
                    }
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
            // fall through to the framework value
        }
        final WifiInfo info = mWifi.getConnectionInfo();
        return info == null || info.getSSID() == null ? "" : info.getSSID();
    }

    /* ------------------------------------------------------------------ utils */

    private boolean onSsid(String ssid) {
        try {
            final WifiInfo info = mWifi.getConnectionInfo();
            return info != null && info.getSSID() != null
                    && unquote(info.getSSID()).equals(ssid);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean hasPsk(String ssid) {
        return mLastPsk != null && ssid.equals(mLastSsid);
    }

    private String lastPsk(String ssid) {
        return mLastPsk == null ? "" : mLastPsk;
    }

    private String mLastSsid;
    private String mLastPsk;

    static String unquote(String quoted) {
        if (quoted == null) {
            return null;
        }
        String value = quoted.trim();
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Runs one shell command, as root when this process already is.
     *
     * <p>It used to be `su -c` unconditionally.  In the module the stack runs as root through
     * {@code app_process}, where `su` may not exist at all, so every such call failed — and because
     * {@link #removeNetwork} used to answer {@code true} without running anything when it had no
     * handle, that failure was invisible: the revert reported "removed leftover accessory
     * netId=258" while {@code cmd wifi list-networks} still listed it (measured 2026-10-02), and the
     * phone kept returning to the accessory hotspot.  Direct execution first, `su -c` as the
     * fallback for the APK path (system app, no root).
     */
    private int run(String command) {
        final int direct = exec(new String[]{"sh", "-c", command});
        if (direct == 0) {
            return 0;
        }
        final int viaSu = exec(new String[]{"su", "-c", command});
        if (viaSu == 0) {
            return 0;
        }
        /* Report the direct result: it is the one that describes what this process can do itself. */
        return direct >= 0 ? direct : viaSu;
    }

    private int exec(String[] argv) {
        try {
            final Process process = Runtime.getRuntime().exec(argv);
            return process.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }
}
