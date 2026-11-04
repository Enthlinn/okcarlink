/*
 * OkcarOS link stack — mDNS/DNS-SD browser (the missing half of the wired path).
 *
 * The wired flow needs the head unit's AirPlay endpoint before it can open the control
 * channel: the car publishes `_airplay._tcp` on usb0 and answers mDNS.  `resolveSessionHost`
 * used to say "wired mDNS discovery is not implemented yet (reserved)" and required an
 * explicit `--es sessionHost <ip>%usb0`, which is not something an operator can type on a
 * head unit that hands out a fresh IPv6 link-local address on every boot (measured
 * 2026-09-26: f8:a5ff:… → bcc9:23ff:… → d0e6:4c83:…).
 *
 * Design notes:
 *
 * - The phone's usb0 has an IPv6 link-local address only (the wire-capture notes §0), so
 *   the address we hand back must carry the interface scope (`fe80::…%usb0`); a bare
 *   link-local address cannot be connected to.  {@link Service#hostForSocket} adds it.
 * - The query sets the DNS-SD "unicast response requested" bit (QU, 0x8000 on the class) and
 *   is sent from an ephemeral port.  Binding 5353 to receive multicast answers would fight
 *   Android's own responder for the port, and we only need our own answers.
 * - Parsing is pure (`parse`/`assemble`, no sockets, no Android types) so the host harness
 *   the desktop mdns harness can cover name compression, the two address families and malformed
 *   input without a device.
 */

package com.okcar.link.net;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MdnsDiscovery {

    private static final String TAG = "OkCarLink/Mdns";

    /** The AirPlay service the receiver advertises; the wired control channel lives there. */
    public static final String SERVICE_AIRPLAY = "_airplay._tcp.local";

    private static final String GROUP_V6 = "ff02::fb";
    private static final String GROUP_V4 = "224.0.0.251";
    private static final int MDNS_PORT = 5353;

    private static final int TYPE_A = 1;
    private static final int TYPE_PTR = 12;
    private static final int TYPE_TXT = 16;
    private static final int TYPE_AAAA = 28;
    private static final int TYPE_SRV = 33;
    private static final int CLASS_IN = 1;
    /** DNS-SD "unicast response requested" (QU) bit on the question class. */
    private static final int CLASS_QU = 0x8000;

    private MdnsDiscovery() {
    }

    /** One resolved service instance. */
    public static final class Service {
        public final String instance;
        public final String target;
        public final int port;
        /** A/AAAA addresses of {@link #target}, in the order they were seen. */
        public final List<String> addresses;
        public final Map<String, String> txt;

        Service(String instance, String target, int port, List<String> addresses,
                Map<String, String> txt) {
            this.instance = instance;
            this.target = target;
            this.port = port;
            this.addresses = addresses;
            this.txt = txt;
        }

        /**
         * The first address usable as a socket target on {@code ifaceName}: an IPv6 link-local
         * needs the scope suffix, a global IPv6 or an IPv4 address does not.  {@code null} when
         * none is usable.
         */
        public String hostForSocket(String ifaceName) {
            for (String address : addresses) {
                if (address == null) {
                    continue;
                }
                final String bare = stripScope(address);
                if (isLinkLocalV6(bare)) {
                    /* The phone's usb0 has only a link-local address; the receiver answers on
                     * its own usbncm0 link-local.  Without the scope the connect() fails. */
                    return ifaceName == null || ifaceName.isEmpty() ? bare : bare + "%" + ifaceName;
                }
                if (isIpv4(bare)) {
                    return bare;
                }
                if (bare.indexOf(':') > 0) {
                    /* A global IPv6 needs no scope and is perfectly connectable. */
                    return bare;
                }
            }
            return null;
        }

        public String describe() {
            return instance + " -> " + target + ':' + port + ' '
                    + (addresses.isEmpty() ? "(no address)" : addresses.toString())
                    + (txt.isEmpty() ? "" : " txt=" + txt);
        }
    }

    /**
     * Browses {@code serviceType} on one interface and returns what answered within
     * {@code timeoutMs}.  Blocking; call it off the main thread.  Never throws.
     */
    public static List<Service> browse(String ifaceName, String serviceType, long timeoutMs) {
        final List<Service> out = new ArrayList<Service>();
        MulticastSocket socket = null;
        try {
            final NetworkInterface netIf = ifaceName == null || ifaceName.isEmpty()
                    ? null : NetworkInterface.getByName(ifaceName);
            if (netIf == null) {
                Log.w(TAG, "no interface named " + ifaceName + " — cannot browse");
                return out;
            }
            socket = new MulticastSocket();                 // ephemeral port: QU answers come back here
            socket.setTimeToLive(255);
            socket.setSoTimeout(400);
            try {
                socket.setNetworkInterface(netIf);
            } catch (Throwable t) {
                Log.w(TAG, "setNetworkInterface(" + ifaceName + ") rejected: " + t);
            }
            final InetAddress group = groupFor(netIf);
            final long deadline = System.currentTimeMillis() + Math.max(500L, timeoutMs);
            final List<Record> records = new ArrayList<Record>();
            records.addAll(ask(socket, group, queryFor(serviceType), deadline));
            /* One round is usually enough: mDNS responders put SRV/TXT/A in the additional
             * section.  When they do not, ask for the missing record types by name. */
            final List<String> instances = ptrInstances(records, serviceType);
            for (String instance : instances) {
                if (hasServiceRecords(records, instance)
                        || System.currentTimeMillis() >= deadline) {
                    continue;
                }
                records.addAll(ask(socket, group, queryForNames(instance), deadline));
            }
            out.addAll(assemble(records, serviceType));
        } catch (Throwable t) {
            Log.w(TAG, "browse failed: " + t);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                    /* closing a failed socket */
                }
            }
        }
        return out;
    }

    /** The first AirPlay service reachable on {@code ifaceName}, or null. */
    public static Service browseForAirPlay(String ifaceName, long timeoutMs) {
        final List<Service> found = browse(ifaceName, SERVICE_AIRPLAY, timeoutMs);
        for (Service service : found) {
            if (service.hostForSocket(ifaceName) != null) {
                return service;
            }
        }
        if (!found.isEmpty()) {
            Log.w(TAG, "found " + found.size() + " AirPlay instance(s) without a usable address");
        }
        return null;
    }

    /* ------------------------------------------------------------------ queries */

    private static byte[] queryFor(String serviceType) {
        return query(Collections.singletonList(serviceType), CLASS_QU);
    }

    /** SRV + TXT for one instance (the second round, when the responder sent no additional). */
    private static byte[] queryForNames(String instance) {
        final byte[] srv = query(Collections.singletonList(instance), CLASS_QU, TYPE_SRV);
        final byte[] txt = query(Collections.singletonList(instance), CLASS_QU, TYPE_TXT);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(srv, 0, srv.length);
        out.write(txt, 0, txt.length);
        return out.toByteArray();
    }

    private static byte[] query(List<String> names, int clazz) {
        return query(names, clazz, TYPE_PTR);
    }

    private static byte[] query(List<String> names, int clazz, int type) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, 0);              // transaction id (mDNS: 0)
        writeShort(out, 0);              // flags: standard query
        writeShort(out, names.size());   // qdcount
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        for (String name : names) {
            writeBytes(out, MdnsV4Responder.encodeName(name));
            writeShort(out, type);
            writeShort(out, CLASS_IN | clazz);
        }
        return out.toByteArray();
    }

    /** Sends one query and collects everything that arrives before {@code deadline}. */
    private static List<Record> ask(MulticastSocket socket, InetAddress group, byte[] query,
                                    long deadline) {
        final List<Record> records = new ArrayList<Record>();
        try {
            socket.send(new DatagramPacket(query, query.length, new InetSocketAddress(group,
                    MDNS_PORT)));
        } catch (Throwable t) {
            Log.w(TAG, "query send failed: " + t);
            return records;
        }
        final byte[] buffer = new byte[9000];
        while (System.currentTimeMillis() < deadline) {
            try {
                final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                records.addAll(parse(packet.getData(), packet.getLength()));
            } catch (java.net.SocketTimeoutException timeout) {
                /* keep waiting until the deadline */
            } catch (Throwable t) {
                Log.w(TAG, "receive failed: " + t);
                break;
            }
        }
        return records;
    }

    private static InetAddress groupFor(NetworkInterface netIf) {
        for (java.net.InterfaceAddress address : netIf.getInterfaceAddresses()) {
            if (address.getAddress() instanceof java.net.Inet6Address) {
                try {
                    return InetAddress.getByName(GROUP_V6);
                } catch (Throwable ignored) {
                    break;
                }
            }
        }
        try {
            return InetAddress.getByName(GROUP_V4);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ parsing */

    /** One resource record, as it came off the wire. */
    static final class Record {
        final String name;
        final int type;
        final int clazz;
        final long ttl;
        final byte[] rdata;
        /** For type PTR/SRV: the (decompressed) name inside rdata. */
        final String nameData;
        /** For type SRV: the port. */
        final int port;
        /** For type TXT. */
        final Map<String, String> txt;
        /** For type A/AAAA. */
        final String address;

        Record(String name, int type, int clazz, long ttl, byte[] rdata, String nameData,
               int port, Map<String, String> txt, String address) {
            this.name = name;
            this.type = type;
            this.clazz = clazz;
            this.ttl = ttl;
            this.rdata = rdata;
            this.nameData = nameData;
            this.port = port;
            this.txt = txt;
            this.address = address;
        }
    }

    /** Parses a DNS message; never throws.  Empty list on anything malformed. */
    static List<Record> parse(byte[] data) {
        return parse(data, data == null ? 0 : data.length);
    }

    static List<Record> parse(byte[] data, int length) {
        final List<Record> out = new ArrayList<Record>();
        if (data == null || length < 12) {
            return out;
        }
        try {
            final int qd = readShort(data, 4);
            final int an = readShort(data, 6);
            final int ns = readShort(data, 8);
            final int ar = readShort(data, 10);
            int offset = 12;
            final String[] name = new String[1];
            for (int i = 0; i < qd; i++) {
                offset = MdnsV4Responder.readName(data, offset, length, name);
                if (offset < 0 || offset + 4 > length) {
                    return out;
                }
                offset += 4;                      // qtype + qclass
            }
            final int total = an + ns + ar;
            for (int i = 0; i < total; i++) {
                offset = MdnsV4Responder.readName(data, offset, length, name);
                if (offset < 0 || offset + 10 > length) {
                    return out;
                }
                final String recordName = name[0];
                final int type = readShort(data, offset);
                final int clazz = readShort(data, offset + 2);
                final long ttl = readInt(data, offset + 4);
                final int rdLength = readShort(data, offset + 8);
                final int rdStart = offset + 10;
                if (rdStart + rdLength > length) {
                    return out;
                }
                final byte[] rdata = new byte[rdLength];
                System.arraycopy(data, rdStart, rdata, 0, rdLength);
                final Record record = decode(recordName, type, clazz, ttl, rdata, data, length,
                        rdStart);
                if (record != null) {
                    out.add(record);
                }
                offset = rdStart + rdLength;
            }
        } catch (Throwable t) {
            /* a truncated or hostile message just yields what was parsed so far */
        }
        return out;
    }

    private static Record decode(String name, int type, int clazz, long ttl, byte[] rdata,
                                 byte[] message, int length, int rdStart) {
        switch (type) {
            case TYPE_PTR: {
                final String[] out = new String[1];
                if (MdnsV4Responder.readName(message, rdStart, length, out) < 0) {
                    return null;
                }
                return new Record(name, type, clazz, ttl, rdata, out[0], 0, null, null);
            }
            case TYPE_SRV: {
                if (rdata.length < 6) {
                    return null;
                }
                /* priority(2) weight(2) port(2) target(name, possibly compressed — parse it in
                 * the original message so compression pointers resolve). */
                final int port = readShort(rdata, 4);
                final String[] out = new String[1];
                final int target = MdnsV4Responder.readName(message, rdStart + 6, length, out);
                if (target < 0) {
                    return null;
                }
                return new Record(name, type, clazz, ttl, rdata, out[0], port, null, null);
            }
            case TYPE_TXT: {
                final Map<String, String> txt = new LinkedHashMap<String, String>();
                int offset = 0;
                while (offset < rdata.length) {
                    final int len = rdata[offset] & 0xFF;
                    if (len == 0 || offset + 1 + len > rdata.length) {
                        break;
                    }
                    final String entry = new String(rdata, offset + 1, len, StandardCharsets.UTF_8);
                    final int equals = entry.indexOf('=');
                    if (equals > 0) {
                        txt.put(entry.substring(0, equals), entry.substring(equals + 1));
                    } else if (!entry.isEmpty()) {
                        txt.put(entry, "");
                    }
                    offset += 1 + len;
                }
                return new Record(name, type, clazz, ttl, rdata, null, 0, txt, null);
            }
            case TYPE_A: {
                if (rdata.length != 4) {
                    return null;
                }
                return new Record(name, type, clazz, ttl, rdata, null, 0, null,
                        (rdata[0] & 0xFF) + "." + (rdata[1] & 0xFF) + "."
                                + (rdata[2] & 0xFF) + "." + (rdata[3] & 0xFF));
            }
            case TYPE_AAAA: {
                if (rdata.length != 16) {
                    return null;
                }
                try {
                    return new Record(name, type, clazz, ttl, rdata, null, 0, null,
                            InetAddress.getByAddress(rdata).getHostAddress());
                } catch (Throwable t) {
                    return null;
                }
            }
            default:
                return new Record(name, type, clazz, ttl, rdata, null, 0, null, null);
        }
    }

    /** PTR answers for {@code serviceType} → instance names (de-duplicated, in order). */
    static List<String> ptrInstances(List<Record> records, String serviceType) {
        final List<String> out = new ArrayList<String>();
        for (Record record : records) {
            if (record.type == TYPE_PTR && sameName(record.name, serviceType)
                    && record.nameData != null && !out.contains(record.nameData)) {
                out.add(record.nameData);
            }
        }
        return out;
    }

    private static boolean hasServiceRecords(List<Record> records, String instance) {
        for (Record record : records) {
            if (record.type == TYPE_SRV && sameName(record.name, instance)) {
                return true;
            }
        }
        return false;
    }

    /** Joins PTR / SRV / TXT / A / AAAA into service instances. */
    static List<Service> assemble(List<Record> records, String serviceType) {
        final List<Service> out = new ArrayList<Service>();
        for (String instance : ptrInstances(records, serviceType)) {
            String target = null;
            int port = 0;
            Map<String, String> txt = new LinkedHashMap<String, String>();
            final List<String> addresses = new ArrayList<String>();
            for (Record record : records) {
                if (record.type == TYPE_SRV && sameName(record.name, instance)) {
                    target = record.nameData;
                    port = record.port;
                } else if (record.type == TYPE_TXT && sameName(record.name, instance)
                        && record.txt != null) {
                    txt.putAll(record.txt);
                }
            }
            if (target == null || port <= 0) {
                continue;
            }
            for (Record record : records) {
                if ((record.type == TYPE_A || record.type == TYPE_AAAA)
                        && sameName(record.name, target) && record.address != null
                        && !addresses.contains(record.address)) {
                    addresses.add(record.address);
                }
            }
            out.add(new Service(trimDot(instance), target, port, addresses, txt));
        }
        return out;
    }

    /* ------------------------------------------------------------------ helpers */

    private static boolean sameName(String a, String b) {
        return a != null && b != null && trimDot(a).equalsIgnoreCase(trimDot(b));
    }

    private static String trimDot(String name) {
        if (name == null) {
            return "";
        }
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    private static String stripScope(String address) {
        final int percent = address.indexOf('%');
        return percent > 0 ? address.substring(0, percent) : address;
    }

    private static boolean isLinkLocalV6(String address) {
        final String lower = address.toLowerCase(Locale.US);
        /* fe80::/10 — the only kind of address this phone's usb0 carries. */
        return lower.startsWith("fe8") && lower.contains(":");
    }

    private static boolean isIpv4(String address) {
        final String[] parts = address.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static long readInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }
}
