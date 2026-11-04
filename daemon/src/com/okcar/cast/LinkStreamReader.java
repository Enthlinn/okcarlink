package com.okcar.cast;

/**
 * okcar-cast 的收帧状态机（纯 Java，无 Android 依赖，可 host 测）。
 * TCP/本地套接字上"半包、粘包"是常态，这里把它们钉死：
 *   - 头行以换行结束，超长（无换行）直接丢弃并重置，不无限吃内存；
 *   - 负载按声明的长度精确累积，凑满才上交；
 *   - 声明长度超过上限的帧，按字节跳过整帧而不是把流搞错位。
 */
public final class LinkStreamReader {
    /** 收到完整帧或需要丢弃时的回调。 */
    public interface Sink {
        void frame(LinkCodec.Frame frame, byte[] payload);

        void drop(String reason);
    }

    private final Sink sink;
    private final int maxPayload;
    private final int maxHeader;
    private final StringBuilder header = new StringBuilder();
    private LinkCodec.Frame current;
    private byte[] payload;
    private int filled;
    private long skipping;
    private boolean overflowed;

    public LinkStreamReader(Sink sink, int maxPayload, int maxHeader) {
        this.sink = sink;
        this.maxPayload = maxPayload;
        this.maxHeader = maxHeader;
    }

    public void feed(byte[] data, int offset, int count) {
        int index = offset;
        int end = offset + count;
        while (index < end) {
            if (current != null) {
                index = fill(data, index, end);
            } else if (skipping > 0) {
                int take = (int) Math.min(skipping, end - index);
                index += take;
                skipping -= take;
            } else {
                index = readHeader(data, index, end);
            }
        }
    }

    private int fill(byte[] data, int index, int end) {
        int need = payload.length - filled;
        int take = Math.min(need, end - index);
        System.arraycopy(data, index, payload, filled, take);
        filled += take;
        index += take;
        if (filled == payload.length) {
            LinkCodec.Frame frame = current;
            byte[] body = payload;
            current = null;
            payload = null;
            filled = 0;
            sink.frame(frame, body);
        }
        return index;
    }

    private int readHeader(byte[] data, int index, int end) {
        char value = (char) (data[index++] & 0xff);
        if (overflowed) {
            // 已经判定这行坏掉：吃到换行为止，只报一次，别把后面正常的行也吞了
            if (value == '\n') {
                overflowed = false;
                sink.drop("header too long");
            }
            return index;
        }
        if (value == '\n') {
            LinkCodec.Frame frame = LinkCodec.parseHeader(header.toString());
            header.setLength(0);
            if (frame == null || frame.length <= 0) {
                sink.drop("bad header");
            } else if (frame.length > maxPayload) {
                skipping = frame.length;
                sink.drop("oversized frame " + frame.length);
            } else {
                current = frame;
                payload = new byte[frame.length];
                filled = 0;
            }
            return index;
        }
        header.append(value);
        if (header.length() > maxHeader) {
            header.setLength(0);
            overflowed = true;
        }
        return index;
    }
}
