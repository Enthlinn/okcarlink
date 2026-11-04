/*
 * OkcarOS link stack — L4 groundwork: RTSP/1.0 message codec.
 *
 * The CarPlay control plane is RTSP/1.0 carrying
 * `application/x-apple-binary-plist` bodies (the closed-source app uses netty
 * RtspDecoder/RtspEncoder for exactly this).  This class is direction-agnostic:
 * it parses whatever the peer sends (request line or status line) and can build
 * either kind, so it serves the control session regardless of which side ends up
 * being the RTSP client — a question the field capture will settle.
 *
 * Methods seen in the app's dex (§8, session 2):
 *   OPTIONS ANNOUNCE SETUP RECORD PAUSE FLUSH TEARDOWN GET_PARAMETER SET_PARAMETER
 * plus the AirPlay-side POST /auth-setup and the reverse-control
 * POST /ctrl-int/1/<cmd>.
 *
 * Pure Java (no android.* imports) so it runs on the host JVM for testing.
 */

package com.okcar.link.rtsp;

import com.okcar.link.plist.BinaryPlist;
import com.okcar.link.util.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RtspMessage {

    public static final String VERSION = "RTSP/1.0";
    public static final String CONTENT_TYPE_BINARY_PLIST = "application/x-apple-binary-plist";
    public static final String CONTENT_TYPE_TEXT = "text/parameters";

    public static final String METHOD_OPTIONS = "OPTIONS";
    public static final String METHOD_ANNOUNCE = "ANNOUNCE";
    public static final String METHOD_SETUP = "SETUP";
    public static final String METHOD_RECORD = "RECORD";
    public static final String METHOD_PAUSE = "PAUSE";
    public static final String METHOD_FLUSH = "FLUSH";
    public static final String METHOD_TEARDOWN = "TEARDOWN";
    public static final String METHOD_GET_PARAMETER = "GET_PARAMETER";
    public static final String METHOD_SET_PARAMETER = "SET_PARAMETER";
    public static final String METHOD_POST = "POST";
    public static final String METHOD_GET = "GET";

    private static final int MAX_LINE_LENGTH = 16 * 1024;
    private static final int MAX_BODY_LENGTH = 8 * 1024 * 1024;

    private String mMethod;
    private String mUri;
    private int mStatusCode = -1;
    private String mReason = "";
    private final List<String[]> mHeaders = new ArrayList<String[]>();
    private byte[] mBody = new byte[0];

    private RtspMessage() {
    }

    /* --------------------------------------------------------------- factories */

    public static RtspMessage request(String method, String uri) {
        final RtspMessage message = new RtspMessage();
        message.mMethod = method;
        message.mUri = uri;
        return message;
    }

    public static RtspMessage response(RtspMessage request, int statusCode, String reason) {
        final RtspMessage message = new RtspMessage();
        message.mStatusCode = statusCode;
        message.mReason = reason == null ? "" : reason;
        if (request != null) {
            final String cseq = request.header("CSeq");
            if (cseq != null) {
                message.setHeader("CSeq", cseq);
            }
        }
        return message;
    }

    public static RtspMessage response(int statusCode, String reason) {
        return response(null, statusCode, reason);
    }

    /* ---------------------------------------------------------------- accessors */

    public boolean isRequest() {
        return mMethod != null;
    }

    public String method() {
        return mMethod;
    }

    public String uri() {
        return mUri;
    }

    public int statusCode() {
        return mStatusCode;
    }

    public String reason() {
        return mReason;
    }

    public byte[] body() {
        return mBody;
    }

    public int bodyLength() {
        return mBody.length;
    }

    public List<String[]> headers() {
        return mHeaders;
    }

    public String header(String name) {
        for (String[] header : mHeaders) {
            if (header[0].equalsIgnoreCase(name)) {
                return header[1];
            }
        }
        return null;
    }

    public int cseq() {
        final String value = header("CSeq");
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public String contentType() {
        final String value = header("Content-Type");
        if (value == null) {
            return null;
        }
        final int semicolon = value.indexOf(';');
        return (semicolon < 0 ? value : value.substring(0, semicolon)).trim();
    }

    public boolean isBinaryPlist() {
        final String type = contentType();
        return type != null && type.toLowerCase(Locale.US)
                .startsWith(CONTENT_TYPE_BINARY_PLIST);
    }

    /* -------------------------------------------------------------- mutators */

    public RtspMessage setHeader(String name, String value) {
        for (int i = 0; i < mHeaders.size(); i++) {
            if (mHeaders.get(i)[0].equalsIgnoreCase(name)) {
                mHeaders.set(i, new String[]{name, value});
                return this;
            }
        }
        mHeaders.add(new String[]{name, value});
        return this;
    }

    public RtspMessage addHeader(String name, String value) {
        mHeaders.add(new String[]{name, value});
        return this;
    }

    public RtspMessage removeHeader(String name) {
        for (int i = mHeaders.size() - 1; i >= 0; i--) {
            if (mHeaders.get(i)[0].equalsIgnoreCase(name)) {
                mHeaders.remove(i);
            }
        }
        return this;
    }

    public RtspMessage setBody(byte[] body, String contentType) {
        mBody = body == null ? new byte[0] : body.clone();
        if (contentType != null) {
            setHeader("Content-Type", contentType);
        }
        setHeader("Content-Length", String.valueOf(mBody.length));
        return this;
    }

    public RtspMessage setTextBody(String text) {
        try {
            return setBody(text == null ? new byte[0] : text.getBytes("UTF-8"), CONTENT_TYPE_TEXT);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Encodes a plist body and sets the matching Content-Type/Content-Length. */
    public RtspMessage setPlistBody(Object value) throws IOException {
        return setBody(BinaryPlist.encode(value), CONTENT_TYPE_BINARY_PLIST);
    }

    /** Decodes the body as an Apple binary plist, or null when it is not one. */
    public Object plistBody() throws IOException {
        if (mBody.length == 0 || !looksLikePlist(mBody)) {
            return null;
        }
        return BinaryPlist.decode(mBody);
    }

    private static boolean looksLikePlist(byte[] body) {
        return BinaryPlist.looksLikePlist(body);
    }

    /* ----------------------------------------------------------------- parse */

    /**
     * Reads one message (headers + Content-Length body) from a stream.
     *
     * @return the message, or null when the stream ended cleanly before a line.
     */
    public static RtspMessage parse(InputStream in) throws IOException {
        final String startLine = readLine(in);
        if (startLine == null) {
            return null;
        }
        if (startLine.isEmpty()) {
            // Tolerate a stray CRLF between messages.
            return parse(in);
        }
        return parseAfterStartLine(in, startLine);
    }

    /**
     * Parses the headers and body of a message whose start line has already been
     * consumed.  Exists because a caller that had to peek at the first byte (e.g.
     * to tell an RTSP line from an interleaved '$' media frame) cannot hand the
     * byte back to the stream.
     */
    public static RtspMessage parseAfterStartLine(InputStream in, String startLine)
            throws IOException {
        final RtspMessage message = new RtspMessage();
        if (startLine.startsWith(VERSION)) {
            final int space = startLine.indexOf(' ', VERSION.length() + 1);
            final String code = space < 0
                    ? startLine.substring(VERSION.length()).trim()
                    : startLine.substring(VERSION.length(), space).trim();
            message.mStatusCode = parseInt(code, -1);
            message.mReason = space < 0 ? "" : startLine.substring(space + 1).trim();
        } else {
            final String[] parts = startLine.split("\\s+", 3);
            if (parts.length < 3) {
                throw new IOException("malformed RTSP request line: " + startLine);
            }
            message.mMethod = parts[0];
            message.mUri = parts[1];
        }

        String line;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) {
                break;
            }
            final int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("malformed RTSP header: " + line);
            }
            message.mHeaders.add(new String[]{
                    line.substring(0, colon).trim(), line.substring(colon + 1).trim()});
        }

        final String lengthHeader = message.header("Content-Length");
        final int contentLength;
        if (lengthHeader == null) {
            contentLength = 0;
        } else {
            contentLength = parseInt(lengthHeader.trim(), 0);
            if (contentLength < 0 || contentLength > MAX_BODY_LENGTH) {
                throw new IOException("bad Content-Length: " + lengthHeader);
            }
        }
        if (contentLength > 0) {
            message.mBody = readFully(in, contentLength);
        }
        return message;
    }

    /* ----------------------------------------------------------------- write */

    public byte[] toByteArray() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out);
        return out.toByteArray();
    }

    public void write(OutputStream out) throws IOException {
        final StringBuilder head = new StringBuilder();
        if (isRequest()) {
            head.append(mMethod).append(' ').append(mUri == null ? "*" : mUri)
                    .append(' ').append(VERSION).append("\r\n");
        } else {
            head.append(VERSION).append(' ').append(mStatusCode).append(' ')
                    .append(mReason).append("\r\n");
        }
        boolean hasLength = false;
        for (String[] header : mHeaders) {
            if (header[0].equalsIgnoreCase("Content-Length")) {
                hasLength = true;
            }
            head.append(header[0]).append(": ").append(header[1]).append("\r\n");
        }
        if (!hasLength && mBody.length > 0) {
            head.append("Content-Length: ").append(mBody.length).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes("US-ASCII"));
        if (mBody.length > 0) {
            out.write(mBody);
        }
        out.flush();
    }

    /* --------------------------------------------------------------- display */

    public String summary() {
        final StringBuilder sb = new StringBuilder();
        if (isRequest()) {
            sb.append(mMethod).append(' ').append(mUri);
        } else {
            sb.append(mStatusCode).append(' ').append(mReason);
        }
        sb.append(" CSeq=").append(cseq());
        if (mBody.length > 0) {
            sb.append(" body=").append(mBody.length).append("B");
            final String type = contentType();
            if (type != null) {
                sb.append(" (").append(type).append(')');
            }
            if (looksLikePlist(mBody)) {
                sb.append(" plist");
            } else if (mBody.length <= 32) {
                sb.append(" [").append(Hex.encode(mBody)).append(']');
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "RtspMessage{" + summary() + '}';
    }

    /* ---------------------------------------------------------------- helpers */

    /** Reads one CRLF/LF-terminated line; null at EOF before any byte. */
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
            if (buffer.size() > MAX_LINE_LENGTH) {
                throw new IOException("RTSP line too long (> " + MAX_LINE_LENGTH + ")");
            }
        }
        if (c < 0 && buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), "US-ASCII");
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

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
