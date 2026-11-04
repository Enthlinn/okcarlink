package com.okcar.cast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * okcar-cast 线格式的编解码（纯 Java，无 Android 依赖，可 host 测）。
 * 规范见 docs/okcar-cast-link.md；守护进程侧用同一套规则解析。
 *
 *   出：V pts flags len + 换行 + len 字节            视频帧（Annex-B H.264）
 *       C len + 换行 + len 字节                      编码配置（SPS+PPS）
 *       A pts rate ch len + 换行 + len 字节          音频
 *   入：T action x y displayId + 换行
 */
public final class LinkCodec {
    public static final int FLAG_KEY_FRAME = 1;
    private static final byte LF = (byte) 10;

    private LinkCodec() {
    }

    public static byte[] videoHeader(long ptsMs, int flags, int length) {
        return ("V " + ptsMs + " " + flags + " " + length + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] configHeader(int spsLength, int ppsLength) {
        return ("C " + spsLength + " " + ppsLength + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] audioHeader(long ptsMs, int rate, int channels, int length) {
        return ("A " + ptsMs + " " + rate + " " + channels + " " + length + "\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] touchLine(int action, float x, float y, int displayId) {
        return ("T " + action + " " + x + " " + y + " " + displayId + "\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** 解析一行文本头；不是已知类型或字段非法时返回 null（坏行直接丢，不打断取流）。 */
    public static Frame parseHeader(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.trim().split(" ");
        try {
            if (parts.length == 4 && "V".equals(parts[0])) {
                return new Frame("V", Long.parseLong(parts[1]), Integer.parseInt(parts[2]), 0, 0,
                        Integer.parseInt(parts[3]), 0, 0);
            }
            if (parts.length == 3 && "C".equals(parts[0])) {
                int sps = Integer.parseInt(parts[1]);
                int pps = Integer.parseInt(parts[2]);
                return new Frame("C", 0L, 0, 0, 0, sps + pps, sps, pps);
            }
            if (parts.length == 5 && "A".equals(parts[0])) {
                return new Frame("A", Long.parseLong(parts[1]), 0, Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), 0, 0);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** 解析触摸行；坏行返回 null。 */
    public static Touch parseTouch(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.trim().split(" ");
        if (parts.length != 5 || !"T".equals(parts[0])) {
            return null;
        }
        try {
            return new Touch(Integer.parseInt(parts[1]), Float.parseFloat(parts[2]),
                    Float.parseFloat(parts[3]), Integer.parseInt(parts[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从缓冲区切出完整的头行（不含换行）；没有完整行时返回 null。 */
    public static String takeLine(List<Byte> buffer) {
        int index = buffer.indexOf(LF);
        if (index < 0) {
            return null;
        }
        List<Byte> head = new ArrayList<Byte>(buffer.subList(0, index));
        buffer.subList(0, index + 1).clear();
        byte[] raw = new byte[head.size()];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = head.get(i);
        }
        return new String(raw, StandardCharsets.US_ASCII);
    }

    public static final class Frame {
        public final String kind;
        public final long ptsMs;
        public final int flags;
        public final int rate;
        public final int channels;
        public final int length;
        public final int spsLength;
        public final int ppsLength;

        Frame(String kind, long ptsMs, int flags, int rate, int channels, int length,
              int spsLength, int ppsLength) {
            this.kind = kind;
            this.ptsMs = ptsMs;
            this.flags = flags;
            this.rate = rate;
            this.channels = channels;
            this.length = length;
            this.spsLength = spsLength;
            this.ppsLength = ppsLength;
        }

        /** C 帧负载是 SPS 紧跟 PPS，这里按长度切开；长度不合法返回 null。 */
        public byte[][] splitConfig(byte[] payload) {
            if (spsLength <= 0 || ppsLength <= 0 || payload == null
                    || payload.length < spsLength + ppsLength) {
                return null;
            }
            byte[] sps = new byte[spsLength];
            byte[] pps = new byte[ppsLength];
            System.arraycopy(payload, 0, sps, 0, spsLength);
            System.arraycopy(payload, spsLength, pps, 0, ppsLength);
            return new byte[][] {sps, pps};
        }

        public boolean keyFrame() {
            return (flags & FLAG_KEY_FRAME) != 0;
        }
    }

    public static final class Touch {
        public final int action;
        public final float x;
        public final float y;
        public final int displayId;

        Touch(int action, float x, float y, int displayId) {
            this.action = action;
            this.x = x;
            this.y = y;
            this.displayId = displayId;
        }
    }
}
