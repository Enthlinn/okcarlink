package com.okcar.link.castlink;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import com.okcar.cast.LinkCodec;
import com.okcar.cast.LinkStreamReader;
import com.okcar.link.LinkState;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 守护进程侧的 okcar-cast 接收端：接受 APK（跑在 CarWith 进程里）推来的编码帧，
 * 直接交给 AirPlay 流的 ScreenStreamSender，不再自己抓屏编码；触摸反向经同一连接发 T 行。
 *
 * 线格式由 com.okcar.cast.LinkCodec 定义（与 APK 侧同一份源码），host 自检见
 * the desktop cast-link harness script。默认不启动：只有 /data/local/tmp/okcar-cast.enable 存在时才挂，
 * 这样模块原有的镜像编码路径不受影响。
 */
public final class CastLinkServer {
    public static final String SOCKET = "okcar-cast";
    private static final String ENABLE_FILE = "/data/local/tmp/okcar-cast.enable";
    private static final int MAX_PAYLOAD = 4 * 1024 * 1024;
    private static final int MAX_HEADER = 512;

    /** 收到帧后往哪儿送；由 OkCarLinkService 在会话建立时挂上。 */
    public interface Sink {
        void config(byte[] sps, byte[] pps) throws IOException;

        void frame(byte[] data, boolean keyFrame, long ptsMs) throws IOException;

        void audio(byte[] data, long ptsMs, int rate, int channels) throws IOException;
    }

    private static final CastLinkServer INSTANCE = new CastLinkServer();

    private LocalServerSocket server;
    private LocalSocket client;
    private OutputStream clientOut;
    private Sink sink;
    private volatile boolean running;
    /** 收帧状态机（半包/粘包/超长帧的边界由 host 自检保证，见 the desktop cast-link harness script）。 */
    private final LinkStreamReader reader = new LinkStreamReader(new LinkStreamReader.Sink() {
        @Override
        public void frame(LinkCodec.Frame frame, byte[] payload) {
            bytes += payload.length;
            dispatch(frame, payload);
        }

        @Override
        public void drop(String reason) {
            LinkState.get().log("cast-link", "dropped: " + reason);
        }
    }, MAX_PAYLOAD, MAX_HEADER);
    private volatile long configs;
    private volatile long frames;
    private volatile long audioPackets;
    private volatile long bytes;

    private CastLinkServer() {
    }

    public static CastLinkServer get() {
        return INSTANCE;
    }

    /** 置/撤启用标记（carwith 联动入口用它把 APK 的流接进会话）。 */
    public static boolean setEnabled(boolean on) {
        final File file = new File(ENABLE_FILE);
        try {
            if (on) {
                if (!file.exists()) {
                    file.createNewFile();
                }
                file.setReadable(true, false);
            } else if (file.exists()) {
                file.delete();
            }
            return file.exists() == on;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 供 carwith status 使用：这一侧收到了多少配置/帧/音频。 */
    public static String describe() {
        final CastLinkServer server = INSTANCE;
        return "okcar-cast: attached=" + (server.sink != null) + ' ' + server.counters();
    }

    public static boolean enabled() {
        return new File(ENABLE_FILE).exists();
    }

    /** bring-up：显式命令起监听，不受标记文件限制（会话路径仍走 attach 的门控）。 */
    public synchronized void attachForTest(Sink value) {
        sink = value;
        start();
    }

    public synchronized void attach(Sink value) {
        sink = value;
        if (enabled()) {
            start();
        }
    }

    public synchronized void detach() {
        sink = null;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            server = new LocalServerSocket(SOCKET);
            running = true;
            Thread thread = new Thread(new AcceptLoop(), "okcar-cast-link");
            thread.setDaemon(true);
            thread.start();
            LinkState.get().log("cast-link", "listening on abstract socket " + SOCKET);
        } catch (IOException e) {
            LinkState.get().log("cast-link", "listen failed: " + e);
        }
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(client);
        client = null;
        clientOut = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // ignore
            }
            server = null;
        }
    }

    /** 触摸回传：APK 收到后用 CarWith 自己的注入器送进 CarWith。 */
    public void sendTouch(int action, float x, float y, int displayId) {
        OutputStream out = clientOut;
        if (out == null) {
            return;
        }
        try {
            out.write(LinkCodec.touchLine(action, x, y, displayId));
            out.flush();
        } catch (IOException e) {
            LinkState.get().log("cast-link", "touch send failed: " + e);
        }
    }

    public long frames() {
        return frames;
    }

    public long audioPackets() {
        return audioPackets;
    }

    public long bytes() {
        return bytes;
    }

    private final class AcceptLoop implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    LocalSocket socket = server.accept();
                    client = socket;
                    clientOut = socket.getOutputStream();
                    LinkState.get().log("cast-link", "client connected");
                    pump(socket.getInputStream());
                } catch (IOException e) {
                    if (running) {
                        LinkState.get().log("cast-link", "session ended: " + e);
                    }
                } finally {
                    clientOut = null;
                    closeQuietly(client);
                    client = null;
                }
            }
        }
    }

    private void pump(InputStream in) throws IOException {
        byte[] chunk = new byte[16 * 1024];
        int read;
        while (running && (read = in.read(chunk)) > 0) {
            reader.feed(chunk, 0, read);
        }
    }

    private void dispatch(LinkCodec.Frame frame, byte[] payload) {
        Sink target = sink;
        if (target == null) {
            return;
        }
        try {
            if ("C".equals(frame.kind)) {
                byte[][] parts = frame.splitConfig(payload);
                if (parts != null) {
                    configs++;
                    target.config(parts[0], parts[1]);
                }
            } else if ("V".equals(frame.kind)) {
                frames++;
                target.frame(payload, frame.keyFrame(), frame.ptsMs);
            } else if ("A".equals(frame.kind)) {
                audioPackets++;
                target.audio(payload, frame.ptsMs, frame.rate, frame.channels);
            }
        } catch (IOException e) {
            LinkState.get().log("cast-link", "sink rejected frame: " + e);
        }
    }

    /** carwith status 用的可读计数。 */
    String counters() {
        return "configs=" + configs + " frames=" + frames + " audio=" + audioPackets
                + " bytes=" + bytes;
    }

    private static void closeQuietly(LocalSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
