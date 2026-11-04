/*
 * OkCarLink — our side of `ICarLifeCastCallback` (2026-10-07).
 *
 * This is the binder CarWith calls back into: after the component hands it over with
 * `setCastCallback` (code 4 on ICarLifeCastManager), CarWith pushes
 *   - lifecycle:  onServiceReady(), onCastPrepared(displayId), onServiceDestroy()
 *   - the injector binders we are allowed to call: addKeyListener / addTouchListener /
 *     addAudioListener (and their remove* twins)
 *   - cosmetic state: notifyAudioState(int), notifyMusicProgress(long), sendMusicInfo(MusicInfo)
 *   - capability probes: invokeMethod(Intent) -> Bundle, with the `carlife_*` actions
 *
 * Transaction numbers are NOT declaration order (onTransact is a switch by constant); the table used
 * here is the one in CarWith's own generated Stub (`ICarLifeCastCallback.Stub`, classes.dex:
 * TRANSACTION_addTouchListener=3, addKeyListener=5, sendMusicInfo=7, notifyMusicProgress=8,
 * invokeMethod=9, onServiceDestroy=10, notifyAudioState=11, addAudioListener=12, onServiceReady=1,
 * onCastPrepared=2, remove*=4/6/13).
 *
 * Nothing here answers "success" on its own: every value is handed to {@link Events} so the daemon
 * can log/report what CarWith actually said.
 */
package com.okcar.link.carkit;

import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

public final class CarwithCastCallback extends Binder {

    private static final String TAG = "okcar-carwith-cb";

    /** What the daemon does with each callback; the callback never decides policy itself. */
    public interface Events {
        void onServiceReady();

        void onCastPrepared(int displayId);

        void onServiceDestroy();

        /** `notifyAudioState(int)`: CarWith's mixed-audio state, 0 when it did not say. */
        void onAudioState(int state);

        /** `notifyMusicProgress(long)` / `sendMusicInfo(...)`: cosmetic, logged only. */
        void onMusicInfo(String title, String artist, String album, long durationMs, int state,
                         int source);

        void onMusicProgress(long positionMs);

        /** `add*Listener`/`remove*Listener`: the injector set changed; null means "removed". */
        void onInjectorsChanged(IBinder key, IBinder touch, IBinder audio);

        /** `{carlife_frame=N}` — CarWith asks for a different capture frame rate. */
        void onFrameRateHint(int fps);
    }

    private final Events mEvents;
    private volatile IBinder mKeyListener;
    private volatile IBinder mTouchListener;
    private volatile IBinder mAudioListener;
    private volatile int mDisplayId = -1;
    private volatile boolean mReady;
    private volatile int mAudioState;
    private volatile String mLastProbe = "";

    public CarwithCastCallback(Events events) {
        mEvents = events;
    }

    public IBinder keyListener() {
        return mKeyListener;
    }

    public IBinder touchListener() {
        return mTouchListener;
    }

    public IBinder audioListener() {
        return mAudioListener;
    }

    public int displayId() {
        return mDisplayId;
    }

    public boolean serviceReady() {
        return mReady;
    }

    public int audioState() {
        return mAudioState;
    }

    public String lastProbe() {
        return mLastProbe;
    }

    /** One line for `carwith status`; never null. */
    public String describe() {
        return "castCallback ready=" + mReady + " displayId=" + mDisplayId
                + " key=" + (mKeyListener != null) + " touch=" + (mTouchListener != null)
                + " audio=" + (mAudioListener != null) + " audioState=" + mAudioState
                + (mLastProbe.isEmpty() ? "" : " lastProbe=" + mLastProbe);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        try {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(CarwithContract.CAST_CALLBACK);
                }
                return true;
            }
            if (data == null) {
                return false;
            }
            data.enforceInterface(CarwithContract.CAST_CALLBACK);
            switch (code) {
                case CarwithContract.CB_ON_SERVICE_READY:
                    mReady = true;
                    Log.i(TAG, "onServiceReady");
                    mEvents.onServiceReady();
                    break;
                case CarwithContract.CB_ON_CAST_PREPARED:
                    mDisplayId = data.readInt();
                    Log.i(TAG, "onCastPrepared displayId=" + mDisplayId);
                    mEvents.onCastPrepared(mDisplayId);
                    break;
                case CarwithContract.CB_ADD_TOUCH_LISTENER:
                    mTouchListener = data.readStrongBinder();
                    Log.i(TAG, "addTouchListener -> " + mTouchListener);
                    mEvents.onInjectorsChanged(mKeyListener, mTouchListener, mAudioListener);
                    break;
                case CarwithContract.CB_REMOVE_TOUCH_LISTENER:
                    if (sameBinder(mTouchListener, data.readStrongBinder())) {
                        mTouchListener = null;
                    }
                    mEvents.onInjectorsChanged(mKeyListener, mTouchListener, mAudioListener);
                    break;
                case CarwithContract.CB_ADD_KEY_LISTENER:
                    mKeyListener = data.readStrongBinder();
                    Log.i(TAG, "addKeyListener -> " + mKeyListener);
                    mEvents.onInjectorsChanged(mKeyListener, mTouchListener, mAudioListener);
                    break;
                case CarwithContract.CB_REMOVE_KEY_LISTENER:
                    if (sameBinder(mKeyListener, data.readStrongBinder())) {
                        mKeyListener = null;
                    }
                    mEvents.onInjectorsChanged(mKeyListener, mTouchListener, mAudioListener);
                    break;
                case CarwithContract.CB_ADD_AUDIO_LISTENER:
                    mAudioListener = data.readStrongBinder();
                    Log.i(TAG, "addAudioListener -> " + mAudioListener);
                    mEvents.onInjectorsChanged(mKeyListener, mTouchListener, mAudioListener);
                    break;
                case CarwithContract.CB_REMOVE_AUDIO_LISTENER:
                    if (sameBinder(mAudioListener, data.readStrongBinder())) {
                        mAudioListener = null;
                    }
                    mEvents.onInjectorsChanged(mKeyListener, mTouchListener, mAudioListener);
                    break;
                case CarwithContract.CB_SEND_MUSIC_INFO: {
                    /* MusicInfo is a nullable parcelable: present flag, then
                     * three strings, a long duration and two ints (state, source).  Read only what is
                     * needed to log it, and never fail the transaction on a shape we did not expect. */
                    final int present = data.dataAvail() >= 4 ? data.readInt() : 0;
                    String first = null;
                    String second = null;
                    String third = null;
                    long duration = 0;
                    int state = 0;
                    int source = 0;
                    if (present != 0 && data.dataAvail() > 0) {
                        try {
                            first = data.readString();
                            second = data.readString();
                            third = data.readString();
                            duration = data.readLong();
                            state = data.readInt();
                            source = data.readInt();
                        } catch (Throwable t) {
                            Log.w(TAG, "sendMusicInfo: short parcel (" + t + ')');
                        }
                    }
                    mEvents.onMusicInfo(first, second, third, duration, state, source);
                    break;
                }
                case CarwithContract.CB_NOTIFY_MUSIC_PROGRESS:
                    mEvents.onMusicProgress(data.readLong());
                    break;
                case CarwithContract.CB_INVOKE_METHOD: {
                    final Intent intent = data.readInt() != 0
                            ? (Intent) Intent.CREATOR.createFromParcel(data) : null;
                    final Bundle answer = capability(intent);
                    if (reply != null) {
                        reply.writeNoException();
                        if (answer != null) {
                            reply.writeInt(1);
                            answer.writeToParcel(reply, 1);
                        } else {
                            reply.writeInt(0);
                        }
                    }
                    return true;
                }
                case CarwithContract.CB_ON_SERVICE_DESTROY:
                    mReady = false;
                    Log.i(TAG, "onServiceDestroy");
                    mEvents.onServiceDestroy();
                    break;
                case CarwithContract.CB_NOTIFY_AUDIO_STATE:
                    mAudioState = data.readInt();
                    Log.i(TAG, "notifyAudioState=" + mAudioState);
                    mEvents.onAudioState(mAudioState);
                    break;
                default:
                    Log.i(TAG, "unhandled cast callback transaction " + code);
                    return false;
            }
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "cast callback transaction " + code + " failed: " + t);
            return false;
        }
    }

    /**
     * The `carlife_*` capability probes CarWith sends to the component.
     *
     * <p>The action list and the answer keys are the ones the community component answers with; an
     * unknown action gets an empty Bundle (its log line is "unsupported action=…; empty capability
     * response"), which is the only honest answer for a capability we have not measured.
     */
    private Bundle capability(Intent intent) {
        final Bundle out = new Bundle();
        final String action = intent == null ? null : intent.getAction();
        mLastProbe = String.valueOf(action);
        Log.i(TAG, "invokeMethod action=" + action + " extras="
                + (intent == null ? null : intent.getExtras()));
        if (action == null) {
            return out;
        }
        if ("carlife_vr_hu_wake_up_enable".equals(action)) {
            /* CarWith may use the head unit's own wake word only while it has a microphone path. */
            out.putBoolean("hu_wake_up_enable", mAudioListener != null);
        } else if ("carlife_vr_use_phone_mic".equals(action)) {
            out.putBoolean("is_use_phone_mic", false);
        } else if ("carlife_music_sample_rate".equals(action)) {
            out.putInt("music_sample_rate", 48000);
        } else if ("carlife_dynamic_params".equals(action)) {
            if (intent.hasExtra("carlife_frame")) {
                final int fps = intent.getIntExtra("carlife_frame", 0);
                mEvents.onFrameRateHint(fps);
            }
        } else if ("carlife_back_to_car_launcher".equals(action)) {
            /* Nothing to answer; the component is expected to move its own UI. */
            Log.i(TAG, "back-to-launcher requested");
        } else {
            Log.i(TAG, "unsupported action=" + action + "; empty capability response");
        }
        return out;
    }

    private static boolean sameBinder(IBinder current, IBinder incoming) {
        if (current == null || incoming == null) {
            return incoming == null;
        }
        return current == incoming || current.equals(incoming);
    }
}
