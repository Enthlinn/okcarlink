package com.okcar.link.port;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiNetworkSuggestion;

import java.util.List;

/**
 * Adapter over the privileged/deprecated WifiManager surface used by
 * {@code WifiGroupJoiner}. The state machine stays untouched behind this
 * interface, and the port build installs exactly one implementation: the root
 * {@code cmd wifi} bridge ({@code ShellWifiOps}).  The framework-backed variant was removed
 * with the rest of the fallback stack — a phone build has no privileged WifiManager access to
 * fall back to, and keeping both meant two behaviours to verify.
 */
public interface WifiOps {

    /** @return {@code WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS} on success */
    int addNetworkSuggestions(List<WifiNetworkSuggestion> suggestions);

    int removeNetworkSuggestions(List<WifiNetworkSuggestion> suggestions);

    /** @return the framework netId, or -1 when refused */
    int addNetwork(WifiConfiguration config);

    /**
     * Adopts a network that is already configured in the framework under {@code netId}, so a
     * later {@link #enableNetwork(int, boolean)} can act on it.
     *
     * <p>The joiner reuses an existing config instead of adding a second one, which is right for
     * the framework path — its {@code enableNetwork} takes the real netId.  A shell-backed
     * implementation has no framework netId space; it maps its own handles to SSIDs, so a reused
     * config is invisible to it and {@code enableNetwork} silently answers false (measured on
     * 217: "found an existing config for car: netId=120" then "enableNetwork(120) = false", the
     * phone never left its own LAN).  The default is a no-op: only implementations that translate
     * handles need it.
     */
    default void adoptNetwork(int netId, String ssid, String passphrase) {
    }

    boolean enableNetwork(int netId, boolean attemptConnect);

    boolean disableNetwork(int netId);

    boolean removeNetwork(int netId);

    boolean saveConfiguration();

    boolean setWifiEnabled(boolean enabled);

    boolean isWifiEnabled();

    boolean reconnect();

    WifiInfo getConnectionInfo();

    List<WifiConfiguration> getConfiguredNetworks();

    List<ScanResult> getScanResults();

    /**
     * SSID of the network we are actually associated with, or "" when unknown.
     *
     * <p>Separate from {@link #getConnectionInfo()} on purpose: a normal-phone build
     * without location permission gets a redacted {@code <unknown ssid>} there, which
     * used to read as "join failed" and tore a good association down. The shell
     * backend answers this through the root shell instead.
     */
    String activeSsid();
}
