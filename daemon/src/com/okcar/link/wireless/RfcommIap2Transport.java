/*
 * OkcarOS link stack — iAP2 over Bluetooth RFCOMM (the wireless CarPlay bootstrap).
 *
 * For wireless CarPlay the accessory (head unit) is the RFCOMM *client*: it SDP
 * queries the device for 00000000-deca-fade-deca-deafdecacafe and dials it
 * (field log from the accessory side: `btsock_rfc_connect ... is_server=false`,
 * SCN 1, mtu 990).  So this transport is the device's side of that socket.
 *
 * Read/write are the raw BluetoothSocket streams.  `Iap2Link.readLoop` tolerates
 * both blocking reads and 0-returns (it heartbeats), and `close()` unblocks a
 * pending read with IOException, which the loop treats as link down.
 */

package com.okcar.link.wireless;

import android.bluetooth.BluetoothSocket;
import android.util.Log;

import com.okcar.link.iap2.Iap2Link;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class RfcommIap2Transport implements Iap2Link.Transport {

    private static final String TAG = "OkCarLink/Wireless";

    private final BluetoothSocket mSocket;
    private final InputStream mIn;
    private final OutputStream mOut;
    private final String mDescription;
    private volatile boolean mClosed;

    public RfcommIap2Transport(BluetoothSocket socket) throws IOException {
        mSocket = socket;
        mIn = socket.getInputStream();
        mOut = socket.getOutputStream();
        final String peer = socket.getRemoteDevice() == null
                ? "?" : socket.getRemoteDevice().getAddress();
        mDescription = "RFCOMM to " + peer;
    }

    /** For the a13 special package: Bluetooth listening is done by the companion app; this is just the byte stream it proxies over. */
    public RfcommIap2Transport(InputStream in, OutputStream out, String description) {
        mSocket = null;
        mIn = in;
        mOut = out;
        mDescription = description;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (mClosed) {
            throw new IOException("transport closed");
        }
        return mIn.read(buffer, offset, length);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        if (mClosed) {
            throw new IOException("transport closed");
        }
        mOut.write(buffer, offset, length);
        mOut.flush();
    }

    @Override
    public void close() {
        mClosed = true;
        if (mSocket == null) {
            try {
                mIn.close();
            } catch (IOException e) {
                Log.w(TAG, "closing the proxy stream: " + e);
            }
            return;
        }
        try {
            mSocket.close();
        } catch (IOException e) {
            Log.w(TAG, "closing RFCOMM socket: " + e);
        }
    }

    @Override
    public String describe() {
        return mDescription;
    }
}
