package com.okcar.link.net;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal IPv4-only mDNS responder for one service.
 *
 * <p>Why not NsdManager: on a dual-stack interface Android's responder answered the accessory's
 * query with an <b>IPv6</b> address, and the head unit has only IPv4 on that link, so every probe
 * failed — measured on 2026-10-01 with the emulator:
 *
 * <pre>
 *   wireless bonjour: control resolved family=IPv6 port=40787
 *   wireless bonjour: control discovery stage=NO_MATCHING_ADDRESS ipv4=1 ipv6=0
 *   iap2 tx=0x4301 carplay-start-session          ← 6 s later, and again every 1-2 minutes
 * </pre>
 *
 * <p>That failing probe is what made the emulator recycle its AirPlay receiver over and over (each recycle
 * killed the video stream, and the car screen went stale).  This responder publishes the same service
 * with explicit {@code A} records for the IPv4 addresses of the link, answers PTR/SRV/TXT/A queries
 * itself, and re-announces periodically so the accessory's cache cannot go stale.
 */
public final class MdnsV4Responder {

    private static final String TAG = "OkCarLink/MdnsV4";
    private static final String GROUP_ADDRESS = "224.0.0.251";
    private static final int MDNS_PORT = 5353;
    private static final int TTL_SECONDS = 120;
    private static final long ANNOUNCE_INTERVAL_MS = 60_000L;

    /* DNS record types we speak. */
    private static final int TYPE_A = 1;
    private static final int TYPE_AAAA = 28;
    private static final int TYPE_PTR = 12;
    private static final int TYPE_TXT = 16;
    private static final int TYPE_SRV = 33;
    private static final int CLASS_IN = 1;
    /** The cache-flush bit, set on the records that describe us uniquely (SRV/TXT/A). */
    private static final int CLASS_FLUSH = 0x8000;

    private final String mServiceType;
    private final String mInstance;
    private final String mHost;
    private final int mServicePort;
    private final Map<String, String> mTxt;

    private final List<NetworkInterface> mInterfaces = new ArrayList<NetworkInterface>();
    /** Last time each query name/type was logged, so the log stays readable. */
    private final Map<String, Long> mSeenQueries = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private MulticastSocket mSocket;
    private volatile boolean mRunning;
    private Thread mThread;

    /**
     * @param serviceType fully qualified, e.g. {@code _carplay-ctrl._tcp.local}
     * @param instance    the service instance (the accessory shows this name)
     * @param host        the SRV target, e.g. {@code okcarlink.local}
     */
    public MdnsV4Responder(String serviceType, String instance, String host, int servicePort,
                           Map<String, String> txt) {
        final String qualified = serviceType.toLowerCase(Locale.US).endsWith(".local")
                ? serviceType : serviceType + ".local";
        mServiceType = qualified.endsWith(".") ? qualified : qualified + ".";
        mInstance = instance;
        mHost = (host.endsWith(".") ? host : host + ".").toLowerCase(Locale.US);
        mServicePort = servicePort;
        mTxt = txt == null ? new LinkedHashMap<String, String>() : txt;
    }

    public String host() {
        return mHost;
    }

    public List<String> addresses() {
        final List<String> out = new ArrayList<String>();
        for (Inet4Address address : localAddresses()) {
            out.add(address.getHostAddress());
        }
        return out;
    }

    /** Joins the multicast group on every usable interface and starts answering. */
    public boolean start() {
        if (mRunning) {
            return true;
        }
        try {
            final MulticastSocket socket = new MulticastSocket(MDNS_PORT);
            socket.setReuseAddress(true);
            socket.setTimeToLive(255);
            socket.setLoopbackMode(true);
            for (NetworkInterface candidate : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!candidate.isUp() || candidate.isLoopback() || !candidate.supportsMulticast()) {
                    continue;
                }
                try {
                    socket.joinGroup(new InetSocketAddress(group(), MDNS_PORT), candidate);
                    mInterfaces.add(candidate);
                } catch (Throwable t) {
                    Log.w(TAG, "cannot join the mDNS group on " + candidate.getName() + ": " + t);
                }
            }
            if (mInterfaces.isEmpty()) {
                Log.w(TAG, "no multicast-capable interface — not advertising");
                socket.close();
                return false;
            }
            mSocket = socket;
            mRunning = true;
            mThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "okcar-mdns-v4");
            mThread.setDaemon(true);
            mThread.start();
            announce();
            scheduleAnnouncements();
            Log.i(TAG, "advertising " + mInstance + '.' + mServiceType + " on " + mHost
                    + " port " + mServicePort + " with IPv4 " + addresses()
                    + " via " + interfaceNames());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "cannot start the IPv4 mDNS responder: " + t);
            return false;
        }
    }

    public void stop() {
        mRunning = false;
        send(buildAnswer(0x0000));   // goodbye: same records, TTL 0
        final MulticastSocket socket = mSocket;
        mSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
                /* closing anyway */
            }
        }
    }

    /* -------------------------------------------------------------- announce */

    private void scheduleAnnouncements() {
        /* Two extra bursts a second apart catch a query that arrived before the first one was
         * processed; the long-interval repeat keeps the A record from ageing out of the accessory's
         * cache (it re-handshakes every couple of minutes, so a stale cache is expensive). */
        for (int i = 1; i <= 2; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (mRunning) {
                        announce();
                    }
                }
            }, "okcar-mdns-burst").start();
        }
        final Thread refresher = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mRunning) {
                    try {
                        Thread.sleep(ANNOUNCE_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (mRunning) {
                        announce();
                    }
                }
            }
        }, "okcar-mdns-refresh");
        refresher.setDaemon(true);
        refresher.start();
    }

    private void announce() {
        send(buildAnswer(TTL_SECONDS));
    }

    /* ------------------------------------------------------------------ wire */

    private void loop() {
        final byte[] buffer = new byte[4096];
        while (mRunning) {
            final MulticastSocket socket = mSocket;
            if (socket == null) {
                return;
            }
            try {
                final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                if (packet.getLength() < 12) {
                    continue;
                }
                final int flags = ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
                if ((flags & 0x8000) != 0) {
                    continue;   // a response, not a query
                }
                final int questions = ((buffer[4] & 0xFF) << 8) | (buffer[5] & 0xFF);
                if (questions == 0) {
                    continue;
                }
                if (!answerToQuery(buffer, packet.getLength(), packet)) {
                    continue;
                }
                final byte[] answer = buildAnswer(TTL_SECONDS);
                /* Answer both ways.  A plain multicast send on a multi-homed phone leaves through
                 * whatever the default route picks, which is not necessarily the interface facing
                 * the accessory (measured 2026-10-01: the accessory queried us, we answered, and it
                 * never followed up).  Sending per joined interface fixes the direction, and the
                 * unicast reply to the query's own address covers the rest. */
                sendOnEveryInterface(answer);
                try {
                    socket.send(new DatagramPacket(answer, answer.length,
                            packet.getAddress(), packet.getPort()));
                    Log.i(TAG, "answered " + packet.getAddress() + ':' + packet.getPort()
                            + " directly");
                } catch (Throwable t) {
                    Log.w(TAG, "unicast answer: " + t);
                }
            } catch (Throwable t) {
                if (mRunning) {
                    Log.w(TAG, "mdns loop: " + t);
                }
            }
        }
    }

    /** True when the query asks about one of the names this responder owns. */
    private boolean answerToQuery(byte[] data, int length, DatagramPacket packet) {
        int offset = 12;
        final int questions = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        for (int i = 0; i < questions && offset < length; i++) {
            final String[] name = new String[1];
            offset = readName(data, offset, length, name);
            if (offset < 0 || offset + 4 > length) {
                return false;
            }
            final int type = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            offset += 4;
            final String queried = name[0] == null ? "" : name[0].toLowerCase(Locale.US);
            logQuery(queried, type, packet.getAddress());
            if (sameName(queried, mServiceType) && (type == TYPE_PTR || type == 255)) {
                Log.i(TAG, "answering PTR for " + queried + " from " + packet.getAddress());
                return true;
            }
            /* Any instance of our service type: see mAnswerInstance. */
            if ((type == TYPE_SRV || type == TYPE_TXT || type == 255)
                    && (sameName(queried, instanceName()) || isInstanceOfOurService(queried))) {
                mAnswerInstance = queried;
                adoptHostOfInstance(queried);
                Log.i(TAG, "answering SRV/TXT for " + queried + " (host " + mHost
                        + " -> adopted " + adoptedHost() + ") from " + packet.getAddress());
                return true;
            }
            if ((type == TYPE_A || type == TYPE_AAAA || type == 255)
                    && (sameName(queried, mHost) || mAdoptedHosts.contains(trimDot(queried)))) {
                Log.i(TAG, "answering A/AAAA for " + queried + " (adopted="
                        + mAdoptedHosts.contains(trimDot(queried)) + ") with v4 " + addresses()
                        + " v6 " + linkLocalV6() + " from " + packet.getAddress());
                return true;
            }
        }
        return false;
    }

    /**
     * Compares DNS names, tolerating the trailing root dot on either side.
     *
     * <p>Measured 2026-10-01: the names we own are normalised with a trailing dot while the name
     * parsed out of a query is not, so a plain {@code equals} meant the responder received the
     * accessory's PTR query and answered nothing at all — the failure looked exactly like "the
     * accessory never resolves us".
     */
    private static boolean sameName(String queried, String owned) {
        return trimDot(queried).equalsIgnoreCase(trimDot(owned));
    }

    /** True for {@code <anything>.<serviceType>}, i.e. an instance of the service we serve. */
    private boolean isInstanceOfOurService(String queried) {
        return isInstanceOfService(mServiceType, queried);
    }

    /**
     * Pure form of {@link #isInstanceOfOurService(String)}, so the rule is testable off-device.
     *
     * <p>Tolerates a trailing root dot: a query may carry the fully qualified form
     * ({@code X._carplay-ctrl._tcp.local.}) while our own names do not, which is the same trap
     * {@link #sameName} documents — the new off-device check caught exactly that case.
     */
    static boolean isInstanceOfService(String serviceType, String queried) {
        if (queried == null || serviceType == null) {
            return false;
        }
        final String suffix = "." + trimDot(serviceType).toLowerCase(Locale.US);
        return trimDot(queried).toLowerCase(Locale.US).endsWith(suffix);
    }

    /**
     * The host name an instance implies: Apple names both the same
     * ({@code X._carplay-ctrl._tcp.local} on {@code X.local}), so answering an instance means we
     * must also serve A/AAAA for {@code X.local}.  Returns null when the name is not our service's.
     */
    static String hostOfInstance(String serviceType, String instance) {
        if (!isInstanceOfService(serviceType, instance)) {
            return null;
        }
        final String label = trimDot(instance);
        final String suffix = "." + trimDot(serviceType);
        if (!label.endsWith(suffix) || label.length() == suffix.length()) {
            return null;
        }
        return label.substring(0, label.length() - suffix.length()) + ".local";
    }

    private void adoptHostOfInstance(String instance) {
        final String host = hostOfInstance(mServiceType, instance);
        if (host != null) {
            synchronized (mAdoptedHosts) {
                mAdoptedHosts.add(host);
            }
        }
    }

    /** The host we should point SRV at: the adopted one when we are impersonating an instance. */
    private String adoptedHost() {
        synchronized (mAdoptedHosts) {
            for (String host : mAdoptedHosts) {
                return host;
            }
        }
        return null;
    }

    private static String trimDot(String name) {
        return name != null && name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    /**
     * Records every query we see (throttled per name/type), so "the accessory never asked" and "we
     * answered wrongly" are distinguishable from the log alone.
     */
    private void logQuery(String name, int type, InetAddress from) {
        final String key = name + '/' + type;
        final Long last = mSeenQueries.get(key);
        final long now = android.os.SystemClock.elapsedRealtime();
        if (last != null && now - last < 5000L) {
            return;
        }
        mSeenQueries.put(key, now);
        Log.i(TAG, "query " + name + " type=" + type + " from " + from);
    }

    /**
     * The instance name we are answering for right now, and the host names we have adopted.
     *
     * <p>Learned on 2026-10-02 with Fruit Link: after a real iPhone had once connected, its mDNS
     * cache kept `iPhone-se3._carplay-ctrl._tcp.local` -> `iPhone-se3.local` -> the iPhone's old
     * address, so every later session resolved *that* (dead) record instead of ours and the head
     * unit sat at "connecting" while our stream was already flowing.  A real iPhone would have
     * sent goodbyes when it left; we never owned that record, so instead we answer it: whatever
     * `_carplay-ctrl` instance is asked about is answered with our port and our addresses, and the
     * host name that instance implies becomes a name we serve A/AAAA for.
     */
    private volatile String mAnswerInstance;
    private final java.util.Set<String> mAdoptedHosts = new java.util.HashSet<String>();

    private byte[] buildAnswer(int ttl) {
        final String answeredInstance = mAnswerInstance != null ? mAnswerInstance : instanceName();
        final String targetHost = adoptedHost() != null ? adoptedHost() : mHost;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<Inet4Address> addresses = localAddresses();
        final List<Inet6Address> v6 = localV6Addresses();
        final int answers = 3 + addresses.size() + v6.size();
        writeShort(out, 0x0000);        // id: mDNS uses 0
        writeShort(out, 0x8400);        // response + authoritative
        writeShort(out, 0);             // questions
        writeShort(out, answers);
        writeShort(out, 0);
        writeShort(out, 0);

        /* PTR: _carplay-ctrl._tcp.local -> <instance>._carplay-ctrl._tcp.local */
        final byte[] instanceName = encodeName(answeredInstance);
        writeRecord(out, mServiceType, TYPE_PTR, CLASS_IN, ttl, instanceName);

        /* SRV: <instance>... -> priority 0, weight 0, port, <host>.local */
        final ByteArrayOutputStream srv = new ByteArrayOutputStream();
        writeShort(srv, 0);
        writeShort(srv, 0);
        writeShort(srv, mServicePort);
        writeBytes(srv, encodeName(targetHost));
        writeRecord(out, answeredInstance, TYPE_SRV, CLASS_IN | CLASS_FLUSH, ttl,
                srv.toByteArray());

        /* TXT: the key=value pairs the accessory reads (id, srcvers). */
        final ByteArrayOutputStream txt = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : mTxt.entrySet()) {
            final byte[] pair = (entry.getKey() + '=' + entry.getValue())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            txt.write(Math.min(pair.length, 255));
            txt.write(pair, 0, Math.min(pair.length, 255));
        }
        if (txt.size() == 0) {
            txt.write(0);
        }
        writeRecord(out, answeredInstance, TYPE_TXT, CLASS_IN | CLASS_FLUSH, ttl,
                txt.toByteArray());

        /* A and AAAA: the accessory compares the answer's address family with the address it bound
         * its own mDNS socket to and refuses the whole record when they differ.  the emulator bound to the
         * IPv6 link-local address of its wlan interface, so an A-only answer was answered with
         * `NO_MATCHING_ADDRESS ipv4=1 ipv6=0` (its own log) and the reverse-control probe never ran.
         * Both families are published; the accessory applies the scope id itself. */
        for (Inet4Address address : addresses) {
            writeRecord(out, mHost, TYPE_A, CLASS_IN | CLASS_FLUSH, ttl, address.getAddress());
        }
        for (Inet6Address address : v6) {
            writeRecord(out, mHost, TYPE_AAAA, CLASS_IN | CLASS_FLUSH, ttl, address.getAddress());
        }
        return out.toByteArray();
    }

    private void send(byte[] data) {
        sendOnEveryInterface(data);
    }

    /** Multicasts {@code data} once per joined interface, so the accessory's link is always covered. */
    private void sendOnEveryInterface(byte[] data) {
        final MulticastSocket socket = mSocket;
        if (socket == null) {
            return;
        }
        for (NetworkInterface networkInterface : mInterfaces) {
            try {
                socket.setNetworkInterface(networkInterface);
                socket.send(new DatagramPacket(data, data.length, group(), MDNS_PORT));
            } catch (Throwable t) {
                Log.w(TAG, "send via " + networkInterface.getName() + ": " + t);
            }
        }
        try {
            socket.setNetworkInterface(null);
        } catch (Throwable ignored) {
            /* best effort */
        }
    }

    /* -------------------------------------------------------------- helpers */

    private String instanceName() {
        return (mInstance + '.' + mServiceType).toLowerCase(Locale.US);
    }

    private List<Inet4Address> localAddresses() {
        final List<Inet4Address> out = new ArrayList<Inet4Address>();
        for (NetworkInterface networkInterface : mInterfaces) {
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()
                        && !address.isLinkLocalAddress()) {
                    if (!out.contains(address)) {
                        out.add((Inet4Address) address);
                    }
                }
            }
        }
        return out;
    }

    /** The IPv6 addresses of the link, link-local included: that is the family the emulator asks for. */
    private List<Inet6Address> localV6Addresses() {
        final List<Inet6Address> out = new ArrayList<Inet6Address>();
        for (NetworkInterface networkInterface : mInterfaces) {
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                if (address instanceof Inet6Address && !address.isLoopbackAddress()
                        && !address.isMulticastAddress() && !isUniqueLocalV6(address)) {
                    if (!out.contains(address)) {
                        out.add((Inet6Address) address);
                    }
                }
            }
        }
        return out;
    }

    /** Link-local addresses are what the accessory can use on this link; skip ULA/link-local noise. */
    private static boolean isUniqueLocalV6(InetAddress address) {
        final byte first = address.getAddress()[0];
        return (first & 0xFE) == 0xFC;
    }

    private List<String> linkLocalV6() {
        final List<String> out = new ArrayList<String>();
        for (Inet6Address address : localV6Addresses()) {
            out.add(address.getHostAddress());
        }
        return out;
    }

    private String interfaceNames() {
        final StringBuilder sb = new StringBuilder();
        for (NetworkInterface networkInterface : mInterfaces) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(networkInterface.getName());
        }
        return sb.toString();
    }

    private static InetAddress group() {
        try {
            return InetAddress.getByName(GROUP_ADDRESS);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }

    private static void writeRecord(ByteArrayOutputStream out, String name, int type, int clazz,
                                    int ttl, byte[] rdata) {
        writeBytes(out, encodeName(name));
        writeShort(out, type);
        writeShort(out, clazz);
        /* TTL 0 on the goodbye packet, which is how a responder withdraws a record. */
        out.write((ttl >>> 24) & 0xFF);
        out.write((ttl >>> 16) & 0xFF);
        out.write((ttl >>> 8) & 0xFF);
        out.write(ttl & 0xFF);
        writeShort(out, rdata.length);
        writeBytes(out, rdata);
    }

    /** DNS name without compression: length-prefixed labels, root-terminated.
     *  Package-visible: {@link MdnsDiscovery} sends its queries with the same codec. */
    static byte[] encodeName(String name) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final String trimmed = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        for (String label : trimmed.split("\\.")) {
            if (label.isEmpty()) {
                continue;
            }
            final byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(Math.min(bytes.length, 63));
            out.write(bytes, 0, Math.min(bytes.length, 63));
        }
        out.write(0);
        return out.toByteArray();
    }

    /** Reads one (uncompressed, or compressed) name; returns the offset after it.
     *  Package-visible: {@link MdnsDiscovery} decodes answers with the same codec. */
    static int readName(byte[] data, int offset, int length, String[] out) {
        final StringBuilder name = new StringBuilder();
        int guard = 0;
        while (offset < length && guard++ < 128) {
            final int len = data[offset] & 0xFF;
            if (len == 0) {
                offset++;
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                if (offset + 1 >= length) {
                    return -1;
                }
                final int pointer = ((len & 0x3F) << 8) | (data[offset + 1] & 0xFF);
                final String[] pointed = new String[1];
                readName(data, pointer, length, pointed);
                if (pointed[0] != null) {
                    name.append(pointed[0]);
                }
                offset += 2;
                break;
            }
            if (offset + 1 + len > length) {
                return -1;
            }
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(data, offset + 1, len,
                    java.nio.charset.StandardCharsets.UTF_8));
            offset += 1 + len;
        }
        out[0] = name.toString();
        return offset;
    }

    /**
     * Host-runnable check for the rule that unblocked Fruit Link: it resolved a <em>stale</em>
     * instance advertised by a real iPhone ({@code iPhone-se3._carplay-ctrl._tcp.local} on
     * {@code iPhone-se3.local}) long after that phone was gone, so every later session dialled a
     * dead host and the head unit sat at "connecting".  Answering any instance of our service, and
     * serving A/AAAA for the host it implies, is what fixed it.
     */
    public static void main(String[] args) {
        int passed = 0;
        int failed = 0;
        final String svc = "_carplay-ctrl._tcp.local";

        final boolean[] ok = new boolean[1];
        ok[0] = isInstanceOfService(svc, "iPhone-se3._carplay-ctrl._tcp.local");
        report("a foreign instance of our service is answered", ok[0], passed, failed);
        passed += ok[0] ? 1 : 0;
        failed += ok[0] ? 0 : 1;

        ok[0] = !isInstanceOfService(svc, "iPhone-se3._airplay._tcp.local");
        report("another service's instance is not", ok[0], passed, failed);
        passed += ok[0] ? 1 : 0;
        failed += ok[0] ? 0 : 1;

        ok[0] = "x.local".equals(hostOfInstance(svc, "x._carplay-ctrl._tcp.local"));
        report("host is derived from the instance label", ok[0], passed, failed);
        passed += ok[0] ? 1 : 0;
        failed += ok[0] ? 0 : 1;

        ok[0] = "iPhone-se3.local".equals(hostOfInstance(svc, "iPhone-se3._carplay-ctrl._tcp.local"));
        report("Apple-style instance maps to its host", ok[0], passed, failed);
        passed += ok[0] ? 1 : 0;
        failed += ok[0] ? 0 : 1;

        ok[0] = "iPhone-se3.local".equals(
                hostOfInstance(svc, "iPhone-se3._carplay-ctrl._tcp.local."));
        report("trailing root dot tolerated", ok[0], passed, failed);
        passed += ok[0] ? 1 : 0;
        failed += ok[0] ? 0 : 1;

        ok[0] = hostOfInstance(svc, "_carplay-ctrl._tcp.local") == null
                && hostOfInstance(svc, "other._airplay._tcp.local") == null
                && hostOfInstance(svc, null) == null;
        report("nothing is adopted from a non-instance", ok[0], passed, failed);
        passed += ok[0] ? 1 : 0;
        failed += ok[0] ? 0 : 1;

        System.out.println("mdns responder self test: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            throw new AssertionError(failed + " check(s) failed");
        }
    }

    private static void report(String what, boolean ok, int passed, int failed) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }
}
