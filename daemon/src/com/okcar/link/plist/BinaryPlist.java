/*
 * OkcarOS link stack — L4 groundwork: the Apple binary property list codec.
 *
 * CarPlay / AirPlay control traffic is RTSP with
 * `Content-Type: application/x-apple-binary-plist`, i.e. a `bplist00` document
 * (the format the closed-source app handles through netty).  We need to read
 * those bodies (stream setup, /info answers, event payloads) and to build our
 * own, so this class implements decode *and* encode.
 *
 * Format (Apple "binary property list", version 00):
 *
 *   "bplist00"
 *   <object table>            each object starts with a marker byte
 *   <offset table>            one entry per object, offsetIntSize bytes each,
 *                             values are ABSOLUTE file offsets
 *   <32-byte trailer>         6 unused | offsetIntSize(1) | objectRefSize(1) |
 *                             numObjects(8) | topObject(8) |
 *                             offsetTableOffset(8)
 *
 * Markers: 0x00 null, 0x08 false, 0x09 true,
 * 0x1n int (n = 2^n bytes, big endian), 0x2n real (n = 2^n bytes),
 * 0x3n date (8-byte big-endian double, seconds since 2001-01-01),
 * 0x4n data, 0x5n ASCII string, 0x6n UTF-16BE string, 0x8n uid,
 * 0xAn array, 0xBn/0xCn set, 0xDn dict.
 *
 * For containers, strings and data the low nibble is the count.  When it is
 * 0xF the real count is an **integer object written inline right after the
 * marker** (not a table reference!) — that detail is easy to get wrong and is
 * exactly what python's plistlib output pinned down.
 *
 * Decoded values are plain Java types: Map<String,Object> (LinkedHashMap, key
 * order preserved), List<Object>, String, Long, Double, byte[], Boolean, null.
 * Dates decode to Double (seconds since the Apple epoch) so this class stays
 * free of both Android and custom types.
 *
 * Deliberately pure Java (no android.* imports): it therefore runs on the host
 * JVM, which is how it was cross-checked against Python's plistlib (see
 * `.car-test/plistcheck/`).
 */

package com.okcar.link.plist;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BinaryPlist {

    private static final byte[] MAGIC = {'b', 'p', 'l', 'i', 's', 't', '0', '0'};
    private static final int TRAILER_LENGTH = 32;
    private static final int MAX_DEPTH = 64;

    /** Apple's epoch (2001-01-01T00:00:00Z) in Unix seconds. */
    public static final long APPLE_EPOCH_UNIX_SECONDS = 978307200L;

    private static final Charset UTF16_BE = Charset.forName("UTF-16BE");
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private BinaryPlist() {
    }

    public static class PlistException extends IOException {
        public PlistException(String message) {
            super(message);
        }
    }

    /* ---------------------------------------------------------------- decode */

    public static boolean looksLikePlist(byte[] data) {
        if (data == null || data.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** Decodes a whole `bplist00` document into plain Java objects. */
    public static Object decode(byte[] data) throws IOException {
        if (!looksLikePlist(data)) {
            throw new PlistException("not a bplist00 document");
        }
        if (data.length < MAGIC.length + TRAILER_LENGTH) {
            throw new PlistException("truncated bplist");
        }
        final int trailer = data.length - TRAILER_LENGTH;
        final int offsetIntSize = data[trailer + 6] & 0xFF;
        final int objectRefSize = data[trailer + 7] & 0xFF;
        final long numObjects = readLong(data, trailer + 8, 8);
        final long topObject = readLong(data, trailer + 16, 8);
        final long offsetTableOffset = readLong(data, trailer + 24, 8);

        if (offsetIntSize < 1 || offsetIntSize > 8 || objectRefSize < 1 || objectRefSize > 8) {
            throw new PlistException("bad trailer sizes: offsetIntSize=" + offsetIntSize
                    + " objectRefSize=" + objectRefSize);
        }
        if (numObjects <= 0 || numObjects > Integer.MAX_VALUE) {
            throw new PlistException("bad object count " + numObjects);
        }
        if (offsetTableOffset + numObjects * offsetIntSize > trailer) {
            throw new PlistException("offset table runs past the trailer");
        }
        final int[] offsets = new int[(int) numObjects];
        for (int i = 0; i < offsets.length; i++) {
            final long value = readLong(data,
                    (int) (offsetTableOffset + (long) i * offsetIntSize), offsetIntSize);
            if (value < MAGIC.length || value >= offsetTableOffset) {
                throw new PlistException("object offset out of range: " + value);
            }
            offsets[i] = (int) value;
        }
        if (topObject < 0 || topObject >= offsets.length) {
            throw new PlistException("top object out of range: " + topObject);
        }
        return parseAt(data, offsets, objectRefSize, offsets[(int) topObject], 0).value;
    }

    /** A decoded value plus the offset just past its serialized form. */
    private static final class Parsed {
        final Object value;
        final int next;

        Parsed(Object value, int next) {
            this.value = value;
            this.next = next;
        }
    }

    /** Element count and where the payload starts. */
    private static final class Count {
        long count;
        int payloadStart;
    }

    private static Object parseObject(byte[] data, int[] offsets, int objectRefSize, int index,
            int depth) throws IOException {
        if (index < 0 || index >= offsets.length) {
            throw new PlistException("object reference out of range: " + index);
        }
        return parseAt(data, offsets, objectRefSize, offsets[index], depth).value;
    }

    private static Parsed parseAt(byte[] data, int[] offsets, int objectRefSize, int pos, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) {
            throw new PlistException("object nesting too deep");
        }
        if (pos < 0 || pos >= data.length) {
            throw new PlistException("object offset out of range: " + pos);
        }
        final int marker = data[pos] & 0xFF;
        final int type = marker & 0xF0;
        final int info = marker & 0x0F;

        switch (type) {
            case 0x00:
                if (marker == 0x08) {
                    return new Parsed(Boolean.FALSE, pos + 1);
                }
                if (marker == 0x09) {
                    return new Parsed(Boolean.TRUE, pos + 1);
                }
                return new Parsed(null, pos + 1); // 0x00 null, 0x0F fill

            case 0x10: { // integer
                final int size = 1 << info;
                checkRange(data, pos + 1, size, "int");
                return new Parsed(Long.valueOf(readLong(data, pos + 1, size)), pos + 1 + size);
            }

            case 0x20: { // real
                final int size = 1 << info;
                checkRange(data, pos + 1, size, "real");
                final double value;
                if (size == 4) {
                    value = Float.intBitsToFloat((int) readLong(data, pos + 1, 4));
                } else if (size == 8) {
                    value = Double.longBitsToDouble(readLong(data, pos + 1, 8));
                } else {
                    throw new PlistException("unsupported real size " + size);
                }
                return new Parsed(Double.valueOf(value), pos + 1 + size);
            }

            case 0x30: { // date
                checkRange(data, pos + 1, 8, "date");
                final double seconds = Double.longBitsToDouble(readLong(data, pos + 1, 8));
                return new Parsed(Double.valueOf(seconds), pos + 9);
            }

            case 0x40: { // data
                final Count count = countAt(data, offsets, objectRefSize, pos, info, depth);
                checkRange(data, count.payloadStart, (int) count.count, "data");
                final int end = count.payloadStart + (int) count.count;
                return new Parsed(Arrays.copyOfRange(data, count.payloadStart, end), end);
            }

            case 0x50: { // ASCII string (count = bytes)
                final Count count = countAt(data, offsets, objectRefSize, pos, info, depth);
                checkRange(data, count.payloadStart, (int) count.count, "ascii string");
                final int end = count.payloadStart + (int) count.count;
                return new Parsed(new String(data, count.payloadStart, (int) count.count, ASCII),
                        end);
            }

            case 0x60: { // UTF-16BE string (count = characters)
                final Count count = countAt(data, offsets, objectRefSize, pos, info, depth);
                checkRange(data, count.payloadStart, (int) count.count * 2, "utf16 string");
                final int end = count.payloadStart + (int) count.count * 2;
                return new Parsed(
                        new String(data, count.payloadStart, (int) count.count * 2, UTF16_BE), end);
            }

            case 0x80: { // uid
                final int size = info + 1;
                checkRange(data, pos + 1, size, "uid");
                return new Parsed(Long.valueOf(readLong(data, pos + 1, size)), pos + 1 + size);
            }

            case 0xA0:   // array
            case 0xB0:   // ordered set (same layout)
            case 0xC0: { // set
                final Count count = countAt(data, offsets, objectRefSize, pos, info, depth);
                checkRange(data, count.payloadStart, (int) count.count * objectRefSize, "array");
                final List<Object> list = new ArrayList<Object>((int) count.count);
                for (int i = 0; i < count.count; i++) {
                    final int ref = (int) readLong(data, count.payloadStart + i * objectRefSize,
                            objectRefSize);
                    list.add(parseObject(data, offsets, objectRefSize, ref, depth + 1));
                }
                return new Parsed(list,
                        count.payloadStart + (int) count.count * objectRefSize);
            }

            case 0xD0: { // dict: count keys, then count values
                final Count count = countAt(data, offsets, objectRefSize, pos, info, depth);
                checkRange(data, count.payloadStart, (int) count.count * objectRefSize * 2, "dict");
                final Map<String, Object> map = new LinkedHashMap<String, Object>();
                for (int i = 0; i < count.count; i++) {
                    final int keyRef = (int) readLong(data, count.payloadStart + i * objectRefSize,
                            objectRefSize);
                    final int valueRef = (int) readLong(data,
                            count.payloadStart + ((int) count.count + i) * objectRefSize,
                            objectRefSize);
                    final Object key = parseObject(data, offsets, objectRefSize, keyRef, depth + 1);
                    map.put(key == null ? "null" : key.toString(),
                            parseObject(data, offsets, objectRefSize, valueRef, depth + 1));
                }
                return new Parsed(map,
                        count.payloadStart + (int) count.count * objectRefSize * 2);
            }

            default:
                throw new PlistException("unsupported marker 0x"
                        + Integer.toHexString(marker) + " at offset " + pos);
        }
    }

    /**
     * Element count for a container/string/data marker.  When the low nibble is
     * 0xF the count is an integer object written inline right after the marker.
     */
    private static Count countAt(byte[] data, int[] offsets, int objectRefSize, int pos, int info,
            int depth) throws IOException {
        final Count count = new Count();
        if (info != 0x0F) {
            count.count = info;
            count.payloadStart = pos + 1;
            return count;
        }
        final Parsed lengthObject = parseAt(data, offsets, objectRefSize, pos + 1, depth + 1);
        if (!(lengthObject.value instanceof Long)) {
            throw new PlistException("inline length object is not an integer at " + pos);
        }
        count.count = ((Long) lengthObject.value).longValue();
        if (count.count < 0 || count.count > Integer.MAX_VALUE) {
            throw new PlistException("bad element count " + count.count);
        }
        count.payloadStart = lengthObject.next;
        return count;
    }

    private static void checkRange(byte[] data, int start, int length, String what)
            throws PlistException {
        if (start < 0 || length < 0 || start + length > data.length) {
            throw new PlistException("truncated " + what + " (start=" + start
                    + " length=" + length + " file=" + data.length + ')');
        }
    }

    private static long readLong(byte[] data, int offset, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    /* ---------------------------------------------------------------- encode */

    /**
     * Encodes plain Java objects (the shapes {@link #decode} returns, plus
     * Integer/Short/Byte/Float/Date) into a `bplist00` document.
     */
    public static byte[] encode(Object root) throws IOException {
        final List<Node> nodes = new ArrayList<Node>();
        final int top = flatten(root, nodes, 0);

        final int objectRefSize = byteSize(Math.max(1, nodes.size()));
        final ByteArrayOutputStream table = new ByteArrayOutputStream();
        final int[] offsets = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            offsets[i] = table.size();
            writeNode(table, nodes.get(i), objectRefSize);
        }
        final byte[] objectTable = table.toByteArray();

        // Object offsets are absolute file offsets, so they include the magic.
        final int offsetTableOffset = MAGIC.length + objectTable.length;
        final int offsetIntSize = byteSize(offsetTableOffset);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC, 0, MAGIC.length);
        out.write(objectTable, 0, objectTable.length);
        for (int offset : offsets) {
            writeLong(out, MAGIC.length + offset, offsetIntSize);
        }
        writeLong(out, 0, 6);                  // 6 unused bytes
        writeLong(out, offsetIntSize, 1);      // trailer[6]
        writeLong(out, objectRefSize, 1);      // trailer[7]
        writeLong(out, nodes.size(), 8);       // trailer[8]
        writeLong(out, top, 8);                // trailer[16]
        writeLong(out, offsetTableOffset, 8);  // trailer[24]
        return out.toByteArray();
    }

    /** One entry of the object table. */
    private static final class Node {
        static final int SCALAR = 0;
        static final int ARRAY = 1;
        static final int DICT = 2;

        final int kind;
        final Object scalar;
        final int[] keys;
        final int[] values;
        final int count;

        Node(int kind, Object scalar, int[] keys, int[] values, int count) {
            this.kind = kind;
            this.scalar = scalar;
            this.keys = keys;
            this.values = values;
            this.count = count;
        }
    }

    private static int addScalar(Object value, List<Node> nodes) {
        nodes.add(new Node(Node.SCALAR, value, null, null, 0));
        return nodes.size() - 1;
    }

    private static int flatten(Object value, List<Node> nodes, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new PlistException("object nesting too deep");
        }
        if (value == null || value instanceof Boolean) {
            return addScalar(value, nodes);
        }
        if (value instanceof byte[] || value instanceof String) {
            return addScalar(value, nodes);
        }
        if (value instanceof Float || value instanceof Double) {
            return addScalar(Double.valueOf(((Number) value).doubleValue()), nodes);
        }
        if (value instanceof Number) {
            return addScalar(Long.valueOf(((Number) value).longValue()), nodes);
        }
        if (value instanceof Date) {
            final double seconds = ((Date) value).getTime() / 1000.0 - APPLE_EPOCH_UNIX_SECONDS;
            return addScalar(Double.valueOf(seconds), nodes);
        }
        if (value instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) value;
            final int count = map.size();
            final int[] keys = new int[count];
            final int[] values = new int[count];
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                keys[i] = flatten(String.valueOf(entry.getKey()), nodes, depth + 1);
                values[i] = flatten(entry.getValue(), nodes, depth + 1);
                i++;
            }
            nodes.add(new Node(Node.DICT, null, keys, values, count));
            return nodes.size() - 1;
        }
        if (value instanceof Iterable || value instanceof Object[]) {
            final List<Object> items = new ArrayList<Object>();
            if (value instanceof Object[]) {
                items.addAll(Arrays.asList((Object[]) value));
            } else {
                for (Object item : (Iterable<?>) value) {
                    items.add(item);
                }
            }
            final int count = items.size();
            final int[] values = new int[count];
            for (int i = 0; i < count; i++) {
                values[i] = flatten(items.get(i), nodes, depth + 1);
            }
            nodes.add(new Node(Node.ARRAY, null, null, values, count));
            return nodes.size() - 1;
        }
        throw new PlistException("cannot encode " + value.getClass().getName());
    }

    private static void writeNode(ByteArrayOutputStream out, Node node, int objectRefSize) {
        if (node.kind == Node.DICT) {
            writeMarker(out, 0xD0, node.count);
            for (int key : node.keys) {
                writeLong(out, key, objectRefSize);
            }
            for (int value : node.values) {
                writeLong(out, value, objectRefSize);
            }
            return;
        }
        if (node.kind == Node.ARRAY) {
            writeMarker(out, 0xA0, node.count);
            for (int value : node.values) {
                writeLong(out, value, objectRefSize);
            }
            return;
        }

        final Object value = node.scalar;
        if (value == null) {
            out.write(0x00);
            return;
        }
        if (value instanceof Boolean) {
            out.write(((Boolean) value).booleanValue() ? 0x09 : 0x08);
            return;
        }
        if (value instanceof byte[]) {
            final byte[] bytes = (byte[]) value;
            writeMarker(out, 0x40, bytes.length);
            out.write(bytes, 0, bytes.length);
            return;
        }
        if (value instanceof String) {
            final String text = (String) value;
            if (isAscii(text)) {
                writeMarker(out, 0x50, text.length());
                out.write(text.getBytes(ASCII), 0, text.length());
            } else {
                writeMarker(out, 0x60, text.length());
                out.write(text.getBytes(UTF16_BE), 0, text.length() * 2);
            }
            return;
        }
        if (value instanceof Double) {
            out.write(0x23); // 8-byte real
            writeLong(out, Double.doubleToLongBits(((Double) value).doubleValue()), 8);
            return;
        }
        if (value instanceof Long) {
            final long number = ((Long) value).longValue();
            final int size = integerByteSize(number);
            out.write(0x10 | trailingZeros(size));
            writeLong(out, number, size);
            return;
        }
        throw new IllegalStateException("cannot write " + value.getClass().getName());
    }

    /** Writes a marker; counts >= 15 go inline as an integer object. */
    private static void writeMarker(ByteArrayOutputStream out, int base, int count) {
        if (count < 15) {
            out.write(base | (count & 0x0F));
            return;
        }
        out.write(base | 0x0F);
        final int size = integerByteSize(count);
        out.write(0x10 | trailingZeros(size));
        writeLong(out, count, size);
    }

    private static int integerByteSize(long value) {
        if (value >= -128 && value <= 127) {
            return 1;
        }
        if (value >= -32768 && value <= 32767) {
            return 2;
        }
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            return 4;
        }
        return 8;
    }

    private static int trailingZeros(int size) {
        switch (size) {
            case 1:
                return 0;
            case 2:
                return 1;
            case 4:
                return 2;
            default:
                return 3;
        }
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static int byteSize(int value) {
        if (value <= 0xFF) {
            return 1;
        }
        if (value <= 0xFFFF) {
            return 2;
        }
        if (value <= 0xFFFFFF) {
            return 3;
        }
        return 4;
    }

    private static void writeLong(ByteArrayOutputStream out, long value, int size) {
        for (int i = size - 1; i >= 0; i--) {
            out.write((int) ((value >>> (8 * i)) & 0xFF));
        }
    }
}
