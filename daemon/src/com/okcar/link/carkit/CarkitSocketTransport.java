package com.okcar.link.carkit;

import android.net.LocalSocket;

import com.okcar.link.iap2.Iap2Link;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@link Iap2Link.Transport} over the carkit service stream.
 *
 * <p>The device side of wired CarPlay reaches us like this: the host opens a USBMUX TCP connection to
 * the port our daemon returned for {@code StartService com.apple.carkit.service}, and from then on
 * that connection carries raw iAP2 (it opens with the six-byte marker {@code FF 55 02 00 EE 10} plus
 * a SYN). {@code native/ffs-usbmux/okcar-ffs-usbmux.c} bridges those bytes to the Android abstract
 * socket {@code okcar-carkit} and {@link CarkitServiceHost} hands the accepted end to this class.
 *
 * <p>There is no TLS here on purpose: no service in lockdownd's table declares
 * {@code EnableServiceSSL}, so the host never wraps this stream (see
 * {@code the CarKit reference} §2.1).
 */
public final class CarkitSocketTransport implements Iap2Link.Transport {

    private final LocalSocket mSocket;
    private final InputStream mIn;
    private final OutputStream mOut;
    private volatile boolean mClosed;

    public CarkitSocketTransport(LocalSocket socket) throws IOException {
        mSocket = socket;
        mIn = socket.getInputStream();
        mOut = socket.getOutputStream();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return mIn.read(buffer, offset, length);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        mOut.write(buffer, offset, length);
        mOut.flush();
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        try {
            mSocket.close();
        } catch (IOException ignored) {
            // Closing a socket that the peer already dropped is not an error worth reporting.
        }
    }

    @Override
    public String describe() {
        return "carkit-socket";
    }
}
