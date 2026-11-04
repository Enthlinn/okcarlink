/*
 * OkcarOS link stack — display-2 RTSP: request/response codec.
 *
 * Pure Java on purpose (no android.* import): the wire format is the part that is worth checking
 * on a host JVM (the desktop rtsp2 harness), and the RTSP server itself is only the state machine around it.
 *
 * This is NOT com.okcar.link.rtsp.RtspMessage.  That class is the CarPlay control plane: it
 * carries RTSP/1.0 with Apple binary-plist bodies and is owned by the mirroring stack, which is
 * frozen.  The display-2 server speaks plain RTSP to a generic client (VLC, ffplay, the head-unit
 * program), so its codec is its own: no plist dependency, interleaved-frame aware, and it rejects
 * a garbage request line instead of treating it as a method name.
 */
package com.okcar.link.stream2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One RTSP/1.0 request (or response) as used by {@link Rtsp2Server}. */
public final class RtspRequest {

    public static final String VERSION = "RTSP/1.0";

    private static final int MAX_LINE = 8 * 1024;
    private static final int MAX_BODY = 1024 * 1024;

    private final String mMethod;
    private final String mUri;
    private final String mVersion;
    private final String[] mHeaderNames;
    private final String[] mHeaderValues;
    private final byte[] mBody;

    private RtspRequest(String method, String uri, String version, String[] names, String[] values,
                        byte[] body) {
        mMethod = method;
        mUri = uri;
        mVersion = version;
        mHeaderNames = names;
        mHeaderValues = values;
        mBody = body;
    }

    public String method() {
        return mMethod;
    }

    public String uri() {
        return mUri;
    }

    public String version() {
        return mVersion;
    }

    public byte[] body() {
        return mBody.clone();
    }

    public String header(String name) {
        for (int i = 0; i < mHeaderNames.length; i++) {
            if (mHeaderNames[i].equalsIgnoreCase(name)) {
                return mHeaderValues[i];
            }
        }
        return null;
    }

    /**
     * The RTSP request URI's path part, without query.  {@code rtsp://host:port/cast2} and
     * {@code /cast2} both answer {@code /cast2}; {@code *} (OPTIONS) answers {@code *}.
     */
    public String path() {
        String uri = mUri;
        if (uri == null) {
            return "";
        }
        if ("*".equals(uri)) {
            return "*";
        }
        final int scheme = uri.indexOf("://");
        if (scheme >= 0) {
            final int slash = uri.indexOf('/', scheme + 3);
            uri = slash < 0 ? "/" : uri.substring(slash);
        }
        final int query = uri.indexOf('?');
        if (query >= 0) {
            uri = uri.substring(0, query);
        }
        return uri;
    }

    /**
     * Parses one request from {@code in}.
     *
     * @throws ProtocolException when the start line is not a well-formed RTSP request line.  The
     *     caller answers 400 and closes the connection; there is no "guess what it meant" path.
     */
    public static RtspRequest read(InputStream in) throws IOException {
        final String startLine = readLine(in);
        if (startLine == null) {
            return null;
        }
        if (startLine.isEmpty()) {
            /* Tolerate one stray CRLF between messages rather than treating it as a request. */
            return read(in);
        }
        final String[] parts = startLine.split("\\s+");
        if (parts.length != 3) {
            throw new ProtocolException("malformed request line: " + startLine);
        }
        if (!VERSION.equals(parts[2])) {
            throw new ProtocolException("unsupported RTSP version: " + parts[2]);
        }
        final String method = parts[0].toUpperCase(Locale.US);
        if (!method.matches("[A-Z_]+")) {
            throw new ProtocolException("malformed method: " + parts[0]);
        }
        final String uri = parts[1];
        if (!"*".equals(uri) && !uri.startsWith("/") && uri.indexOf("://") < 0) {
            throw new ProtocolException("malformed request URI: " + uri);
        }

        final List<String> names = new ArrayList<String>();
        final List<String> values = new ArrayList<String>();
        String line;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) {
                break;
            }
            final int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new ProtocolException("malformed header: " + line);
            }
            names.add(line.substring(0, colon).trim());
            values.add(line.substring(colon + 1).trim());
        }

        byte[] body = new byte[0];
        final String length = value(names, values, "Content-Length");
        if (length != null) {
            final int size = parsePositive(length);
            if (size > MAX_BODY) {
                throw new ProtocolException("Content-Length too large: " + length);
            }
            body = readFully(in, size);
        }
        return new RtspRequest(method, uri, parts[2],
                names.toArray(new String[0]), values.toArray(new String[0]), body);
    }

    private static String value(List<String> names, List<String> values, String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return values.get(i);
            }
        }
        return null;
    }

    static int parsePositive(String text) throws ProtocolException {
        try {
            final int value = Integer.parseInt(text.trim());
            if (value < 0) {
                throw new ProtocolException("negative number: " + text);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ProtocolException("not a number: " + text);
        }
    }

    /** A malformed request: answered with 400 and a closed connection, never guessed at. */
    public static final class ProtocolException extends IOException {
        private static final long serialVersionUID = 1L;

        public ProtocolException(String message) {
            super(message);
        }
    }

    /* ------------------------------------------------------------------ response */

    /** Accumulates one RTSP response; the body may be text (SDP) or empty. */
    public static final class Response {

        private final StringBuilder mHead = new StringBuilder();
        private byte[] mBody = new byte[0];

        public Response(int status, String reason, String cseq) {
            mHead.append(VERSION).append(' ').append(status).append(' ').append(reason).append("\r\n");
            if (cseq != null) {
                mHead.append("CSeq: ").append(cseq).append("\r\n");
            }
            mHead.append("Server: okcar-linkd/cast2\r\n");
        }

        public Response header(String name, String value) {
            mHead.append(name).append(": ").append(value).append("\r\n");
            return this;
        }

        public Response body(String contentType, String text) {
            mBody = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            mHead.append("Content-Type: ").append(contentType).append("\r\n");
            mHead.append("Content-Length: ").append(mBody.length).append("\r\n");
            return this;
        }

        /**
         * Encodes the response.
         *
         * <p>The head is terminated by the mandatory empty line ({@code \r\n\r\n}) before the body:
         * without it a client reads the headers and then waits forever for the body, because nothing
         * in the message says where the headers end.  Measured on the loopback test — every response
         * looked "sent" and none was parsable.
         */
        public byte[] toByteArray() {
            final byte[] head = (mHead.toString() + "\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(head.length + mBody.length);
            out.write(head, 0, head.length);
            out.write(mBody, 0, mBody.length);
            return out.toByteArray();
        }

        public String toText() {
            return new String(toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }

        public void writeTo(OutputStream out) throws IOException {
            out.write(toByteArray());
            out.flush();
        }
    }

    /* ------------------------------------------------------------------- helpers */

    /** One CRLF/LF-terminated ASCII line; null at EOF before any byte. */
    public static String readLine(InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                continue;
            }
            buffer.write(c);
            if (buffer.size() > MAX_LINE) {
                throw new ProtocolException("line too long");
            }
        }
        if (c < 0 && buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static byte[] readFully(InputStream in, int length) throws IOException {
        final byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            final int n = in.read(data, read, length - read);
            if (n < 0) {
                throw new IOException("stream ended after " + read + " of " + length + " bytes");
            }
            read += n;
        }
        return data;
    }
}
