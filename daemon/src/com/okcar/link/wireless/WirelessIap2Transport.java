package com.okcar.link.wireless;

import com.okcar.link.iap2.Iap2Link;
import com.okcar.link.LinkState;

import java.io.IOException;
import java.util.Arrays;

/**
 * The iAP2 transport of a wireless session: Bluetooth RFCOMM while it lasts, the sealed AirPlay
 * event channel afterwards.
 *
 * <p>Measured on the production head unit (2026-10-02, `TUNNEL-2026-10-01.md` §4.25 and the 19:07 run): the
 * accessory closes its RFCOMM socket the moment the AirPlay session starts, because its iAP2 control
 * channel has moved onto Wi-Fi.  Two things follow, and both were wrong before this class existed:
 *
 * <ul>
 *   <li><b>outbound</b> — every iAP2 message we answered on that dead socket was lost.  The car
 *       re-sent its DETECT once a second, never saw our identification, and tore the session down
 *       after ~13 s, which the driver side reads as "the connection keeps dropping";</li>
 *   <li><b>inbound</b> — returning EOF from {@code read()} raised a link-down the accessory never
 *       asked for.  Inbound frames arrive through the AirPlay client's event-channel sink, so this
 *       stream parks instead and the link stays up.</li>
 * </ul>
 *
 * <p>When the primary write fails the frame is retried in the same call over the event channel, so
 * the switch costs one failed write rather than a lost message.
 */
public final class WirelessIap2Transport implements Iap2Link.Transport {

    /** Sends one iAP2 frame over the sealed AirPlay event channel. */
    public interface EventChannel {
        boolean sendIap(byte[] frame);
    }

    private final Iap2Link.Transport mPrimary;
    private final EventChannel mEventChannel;
    private volatile boolean mPrimaryFailed;
    private volatile boolean mClosed;

    public WirelessIap2Transport(Iap2Link.Transport primary, EventChannel eventChannel) {
        mPrimary = primary;
        mEventChannel = eventChannel;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        while (!mClosed) {
            if (!mPrimaryFailed) {
                try {
                    final int read = mPrimary.read(buffer, offset, length);
                    if (read >= 0) {
                        return read;
                    }
                    LinkState.get().log("wireless", "iAP2 primary transport ended — the accessory's"
                            + " messages arrive on the event channel now");
                } catch (IOException e) {
                    LinkState.get().log("wireless", "iAP2 primary transport read failed (" + e
                            + ") — the accessory's messages arrive on the event channel now");
                }
                mPrimaryFailed = true;
            }
            /* Park: nothing to read here any more, and reporting EOF would tear down a link the
             * accessory is still using over the event channel. */
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        if (mClosed) {
            throw new IOException("transport closed");
        }
        if (!mPrimaryFailed) {
            try {
                mPrimary.write(buffer, offset, length);
                return;
            } catch (IOException e) {
                mPrimaryFailed = true;
                LinkState.get().log("wireless", "iAP2 primary transport write failed (" + e
                        + ") — switching to the AirPlay event channel");
            }
        }
        if (mEventChannel == null) {
            throw new IOException("no event-channel transport");
        }
        final byte[] frame = Arrays.copyOfRange(buffer, offset, offset + length);
        if (!mEventChannel.sendIap(frame)) {
            throw new IOException("event-channel iAP2 send refused");
        }
    }

    /** True once Bluetooth is out of the picture and the event channel carries iAP2. */
    public boolean onEventChannel() {
        return mPrimaryFailed;
    }

    @Override
    public void close() {
        mClosed = true;
        mPrimary.close();
    }

    @Override
    public String describe() {
        return "Bluetooth RFCOMM + AirPlay event channel"
                + (mPrimaryFailed ? " (on the event channel)" : "");
    }
}
