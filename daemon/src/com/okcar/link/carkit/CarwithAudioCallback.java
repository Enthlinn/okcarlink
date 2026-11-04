/*
 * OkCarLink — our side of `ISystemAudioCallback` (2026-10-07).
 *
 * CarWith's mixed system audio does NOT come back through the microphone listener; it arrives here,
 * on the binder the component installed with `setSystemAudioCallback` (code 3):
 *
 *   onAudioStreamReady(byte[] pcm, int rate, int channels, int encoding, int category)   code 1
 *   onAudioStateChange(int state, int category)                                          code 2
 *
 * `category` is CarWith's own numbering; {@link CarwithContract#audioCategory} maps it to the
 * contract's 1=media / 2=navigation(TTS) / 0=unknown.  The encoding value is the AudioFormat
 * constant (2 = PCM16); rate/channels/encoding are passed through unmodified, because the sink (the
 * AirPlay audio path) is the only place that knows what it can carry.
 *
 * Frames that fail {@link CarwithContract#validPcm} are counted and dropped: forwarding a truncated
 * or mis-declared frame would surface as noise on the head unit and as an unexplained quality bug
 * later.
 */
package com.okcar.link.carkit;

import android.os.Binder;
import android.os.Parcel;
import android.util.Log;

public final class CarwithAudioCallback extends Binder {

    private static final String TAG = "okcar-carwith-audio";

    /** Where CarWith's system audio goes.  Implemented by the daemon's audio path. */
    public interface Sink {
        /**
         * @param pcm PCM16 little-endian frames, `channels` interleaved, `rate` Hz
         * @param category 1 = media, 2 = navigation/TTS, 0 = unknown (see CarwithContract)
         * @return true when the frame was taken; false is logged, never retried here
         */
        boolean onSystemAudio(byte[] pcm, int rate, int channels, int encoding, int category);

        void onAudioState(int state, int category);
    }

    private final Sink mSink;
    private volatile long mFrames;
    private volatile long mRejected;
    private volatile long mBytes;
    private volatile int mLastRate;
    private volatile int mLastChannels;
    private volatile int mLastState = -1;
    private volatile int mLastCategory = -1;

    public CarwithAudioCallback(Sink sink) {
        mSink = sink;
    }

    public long frames() {
        return mFrames;
    }

    public long rejected() {
        return mRejected;
    }

    public long bytes() {
        return mBytes;
    }

    /** One line for `carwith status`. */
    public String describe() {
        return "systemAudio frames=" + mFrames + " bytes=" + mBytes + " rejected=" + mRejected
                + " rate=" + mLastRate + " channels=" + mLastChannels
                + " state=" + mLastState + " category=" + mLastCategory;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        try {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(CarwithContract.SYSTEM_AUDIO_CALLBACK);
                }
                return true;
            }
            if (data == null) {
                return false;
            }
            data.enforceInterface(CarwithContract.SYSTEM_AUDIO_CALLBACK);
            switch (code) {
                case CarwithContract.SA_ON_AUDIO_STREAM_READY: {
                    final byte[] pcm = data.createByteArray();
                    final int rate = data.readInt();
                    final int channels = data.readInt();
                    final int encoding = data.readInt();
                    final int rawCategory = data.readInt();
                    final int category = CarwithContract.audioCategory(rawCategory);
                    final int size = pcm == null ? 0 : pcm.length;
                    if (!CarwithContract.validPcm(size, rate, channels, encoding)) {
                        mRejected++;
                        Log.w(TAG, "refusing system audio frame: size=" + size + " rate=" + rate
                                + " channels=" + channels + " encoding=" + encoding
                                + " category=" + rawCategory);
                        break;
                    }
                    mFrames++;
                    mBytes += size;
                    mLastRate = rate;
                    mLastChannels = channels;
                    mLastCategory = rawCategory;
                    mSink.onSystemAudio(pcm, rate, channels, encoding, category);
                    break;
                }
                case CarwithContract.SA_ON_AUDIO_STATE_CHANGE: {
                    final int state = data.readInt();
                    final int rawCategory = data.readInt();
                    mLastState = state;
                    mLastCategory = rawCategory;
                    Log.i(TAG, "onAudioStateChange state=" + state + " category=" + rawCategory);
                    mSink.onAudioState(state, CarwithContract.audioCategory(rawCategory));
                    break;
                }
                default:
                    Log.i(TAG, "unhandled system audio transaction " + code);
                    return false;
            }
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "system audio transaction " + code + " failed: " + t);
            return false;
        }
    }

    /** Convenience for tests/self-checks: the descriptor this binder answers. */
    public static String descriptor() {
        return CarwithContract.SYSTEM_AUDIO_CALLBACK;
    }
}
