/*
 * OkCarLink — input/audio injection INTO CarWith (2026-10-07).
 *
 * Who calls whom (dex-proven, see {@link CarwithContract}): CarWith implements
 * `IRemoteKeyListener`/`IRemoteTouchListener`/`IRemoteAudioListener` and hands those binders to us
 * through our own `ICarLifeCastCallback` endpoint (`addKeyListener`/`addTouchListener`/
 * `addAudioListener`).  This class performs the three transactions on those binders:
 *
 *   IRemoteKeyListener.onKeyEvent(KeyEvent): boolean        code 1
 *       parcel = interface token, int 1 (non-null), KeyEvent.writeToParcel
 *       reply  = readException(), readInt() != 0                ← the boolean lives in the reply
 *   IRemoteTouchListener.onTouchEvent(MotionEvent): boolean code 1, same shape
 *   IRemoteAudioListener.onReceiveAudioStream(byte[],int,int,int): void  code 1
 *       parcel = token, writeByteArray(pcm), rate, channels, encoding
 *
 * CarWith's implementations (classes5.dex):
 *   CarlifeHw.onKeyEvent    — drops ACTION_UP, maps 8101..8115 then injects a fresh DOWN+UP pair.
 *   CarlifeUibc.onTouchEvent — re-maps coordinates with CarWith's own display scale and injects.
 *   CarlifeAudioListener     — feeds the microphone PCM into CarWith's voice pipeline.
 *
 * Failure policy: a binder that does not answer with the expected interface descriptor is refused
 * before any transaction (that is the same check the community component makes with
 * "Unexpected CarWith Binder contract"); a `false` reply is reported to the caller and logged, it
 * is never swallowed into an apparent success.
 */
package com.okcar.link.carkit;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

public final class CarwithInput {

    private static final String TAG = "okcar-carwith-io";

    private CarwithInput() {
    }

    /* ------------------------------------------------------------------ events */

    /** The KeyEvent CarWith expects: `new KeyEvent(downTime, eventTime, action, code, 0)`. */
    public static KeyEvent keyEvent(String name, boolean down) {
        final int code = CarwithContract.keyCode(name, down);
        if (code == 0) {
            return null;
        }
        final long now = SystemClock.uptimeMillis();
        return new KeyEvent(now, now, CarwithContract.keyAction(name, down), code, 0);
    }

    /**
     * A single-pointer MotionEvent whose action is one of {@link MotionEvent#ACTION_DOWN},
     * {@link MotionEvent#ACTION_UP}, {@link MotionEvent#ACTION_MOVE} or
     * {@link MotionEvent#ACTION_CANCEL}.
     */
    public static MotionEvent touchEvent(int action, float x, float y) {
        final long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    /* ------------------------------------------------------------------ transactions */

    public static boolean key(IBinder listener, KeyEvent event) {
        if (event == null) {
            return false;
        }
        Parcel request = null;
        Parcel reply = null;
        try {
            request = Parcel.obtain();
            reply = Parcel.obtain();
            request.writeInterfaceToken(CarwithContract.KEY_LISTENER);
            request.writeInt(1);                    /* non-null KeyEvent, the AIDL pattern */
            event.writeToParcel(request, 0);
            if (!transact(listener, CarwithContract.KEY_LISTENER,
                    CarwithContract.LISTENER_TRANSACTION, request, reply)) {
                return false;
            }
            return reply.readInt() != 0;
        } catch (Throwable t) {
            Log.w(TAG, "key injection failed (code=" + event.getKeyCode() + "): " + t);
            return false;
        } finally {
            recycle(request, reply);
        }
    }

    public static boolean touch(IBinder listener, MotionEvent event) {
        if (event == null) {
            return false;
        }
        Parcel request = null;
        Parcel reply = null;
        try {
            request = Parcel.obtain();
            reply = Parcel.obtain();
            request.writeInterfaceToken(CarwithContract.TOUCH_LISTENER);
            request.writeInt(1);
            event.writeToParcel(request, 0);
            if (!transact(listener, CarwithContract.TOUCH_LISTENER,
                    CarwithContract.LISTENER_TRANSACTION, request, reply)) {
                return false;
            }
            return reply.readInt() != 0;
        } catch (Throwable t) {
            Log.w(TAG, "touch injection failed (action=" + event.getAction() + "): " + t);
            return false;
        } finally {
            recycle(request, reply);
        }
    }

    /**
     * Microphone PCM for CarWith's voice pipeline: PCM16LE, 16 kHz, mono by default.
     *
     * @return true when CarWith accepted the frame; the preconditions are the same ones
     *     `CarWithDirectContract.validPcm` enforces, so a bad frame is refused here instead of
     *     being sent and silently dropped on the other side.
     */
    public static boolean microphone(IBinder listener, byte[] pcm, int rate, int channels,
                                     int encoding) {
        if (pcm == null || listener == null) {
            return false;
        }
        if (!CarwithContract.validPcm(pcm.length, rate, channels, encoding)) {
            Log.w(TAG, "microphone frame refused: size=" + pcm.length + " rate=" + rate
                    + " channels=" + channels + " encoding=" + encoding);
            return false;
        }
        Parcel request = null;
        Parcel reply = null;
        try {
            request = Parcel.obtain();
            reply = Parcel.obtain();
            request.writeInterfaceToken(CarwithContract.AUDIO_LISTENER);
            request.writeByteArray(pcm);
            request.writeInt(rate);
            request.writeInt(channels);
            request.writeInt(encoding);
            return transact(listener, CarwithContract.AUDIO_LISTENER,
                    CarwithContract.LISTENER_TRANSACTION, request, reply);
        } catch (Throwable t) {
            Log.w(TAG, "microphone injection failed: " + t);
            return false;
        } finally {
            recycle(request, reply);
        }
    }

    /* ------------------------------------------------------------------ plumbing */

    /**
     * One transaction with the descriptor check the contract needs.
     *
     * <p>The reply's exception is read before the caller reads any value — an AIDL method that fails
     * on the CarWith side must surface as an exception here, not as a zero.
     */
    private static boolean transact(IBinder remote, String descriptor, int code,
                                    Parcel request, Parcel reply) throws RemoteException {
        if (remote == null) {
            return false;
        }
        String actual = null;
        try {
            actual = remote.getInterfaceDescriptor();
        } catch (Throwable t) {
            Log.w(TAG, "cannot read the interface descriptor of " + remote + ": " + t);
        }
        if (!CarwithContract.matches(descriptor, actual)) {
            Log.w(TAG, "refusing " + descriptor + " transaction on a binder that answers '"
                    + actual + "'");
            return false;
        }
        if (!remote.transact(code, request, reply, 0)) {
            Log.w(TAG, "binder refused " + descriptor + '/' + code);
            return false;
        }
        reply.readException();
        return true;
    }

    private static void recycle(Parcel a, Parcel b) {
        if (a != null) {
            a.recycle();
        }
        if (b != null) {
            b.recycle();
        }
    }
}
