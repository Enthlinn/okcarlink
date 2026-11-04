/*
 * OkCarLink — the CarWith ↔ CarLife-component contract, as measured (not guessed).  2026-10-07.
 *
 * Everything in this class is a constant or a pure function: no android.* import, so
 * A small desktop harness compiles this one file with the host JDK and asserts the whole table
 * against the values read out of the two dexes that implement the contract:
 *
 *   - CarWith (com.miui.carlink, /product/app/CarWith/CarWith.apk): the AIDL, and the only real
 *     server  — `com.xiaomi.ucar.carlife.CarlifeConnService.CarLifeCastManagerImpl extends
 *     ICarLifeCastManager.Stub`, returned from `onBind` (classes5.dex, CarlifeConnService.java:189).
 *   - the working community component (carplay-xiaomi-carlife-v11.apk): a real client of that
 *     server (classes8.dex, CarWithDirectClient + CarWithDirectContract).
 *
 * DIRECTION (this is the part the first cut of CarwithBridge had backwards):
 *   CarWith starts `com.baidu.carlife.xiaomi/com.baidu.carlife.service.CarlifeConnectService` with
 *   `bindPackageName`/`bindClassName`/`bindVersion`/`manufacturer`/`usb_type` extras; the component
 *   then binds `com.miui.carlink/com.xiaomi.ucar.carlife.CarlifeConnService`, gets an
 *   ICarLifeCastManager and calls prepareCast(CastConfig{width,height,dpi,Surface}) on it.
 *   **The Surface is created by the component side and CarWith renders its virtual display into
 *   it** (CastController.displayConnected(): `mSurface = surface;` then
 *   `createVirtualDisplay(new VirtualDisplayConfig.Builder(...).setSurface(mSurface)...)`).
 *   So `CarwithBridge.surface()` is the encoder input surface WE hand over, not one CarWith gave us.
 */
package com.okcar.link.carkit;

public final class CarwithContract {

    private CarwithContract() {
    }

    /* ------------------------------------------------------------------ descriptors */

    /** Implemented by CarWith (`CarlifeConnService.CarLifeCastManagerImpl`); we are the caller. */
    public static final String MANAGER = "com.baidu.carlife.mixing.aidl.ICarLifeCastManager";
    /** Implemented by us; CarWith calls it (status, capability queries, listener delivery). */
    public static final String CAST_CALLBACK = "com.baidu.carlife.mixing.aidl.ICarLifeCastCallback";
    /** Implemented by us; CarWith pushes its mixed system audio here. */
    public static final String SYSTEM_AUDIO_CALLBACK = "com.baidu.carlife.mixing.aidl.ISystemAudioCallback";
    /** Implemented by CarWith (`CarlifeHw`); we call it to inject keys. */
    public static final String KEY_LISTENER = "com.baidu.carlife.mixing.aidl.IRemoteKeyListener";
    /** Implemented by CarWith (`CarlifeUibc`); we call it to inject touch. */
    public static final String TOUCH_LISTENER = "com.baidu.carlife.mixing.aidl.IRemoteTouchListener";
    /** Implemented by CarWith (`CarlifeAudioListener`); we call it to feed microphone PCM. */
    public static final String AUDIO_LISTENER = "com.baidu.carlife.mixing.aidl.IRemoteAudioListener";

    /** `CastConfig`'s parcelable name; it is written inline (nullable pattern), see {@link #M_PREPARE_CAST}. */
    public static final String CAST_CONFIG = "com.baidu.carlife.mixing.CastConfig";

    /** The CarWith service our component binds; the component's own service is started BY CarWith. */
    public static final String CARWITH_PACKAGE = "com.miui.carlink";
    public static final String CARWITH_SERVICE = "com.xiaomi.ucar.carlife.CarlifeConnService";
    public static final String COMPONENT_PACKAGE = "com.baidu.carlife.xiaomi";
    public static final String COMPONENT_SERVICE = "com.baidu.carlife.service.CarlifeConnectService";
    /** String CarWith puts in `bindClassName`; it is what the component must bind back to. */
    public static final String CARWITH_BIND_CLASS = "com.xiaomi.ucar.carlife.CarlifeConnService";
    /** CarWith refuses to talk to anything that does not answer with this version string. */
    public static final String CARWITH_PROTOCOL_VERSION = "8.1.1";

    /* ----------------------------------- CarWith's own cast service (the state-machine owner) */

    /**
     * `com.miui.carlink.castfwk.CarlinkService` — CarWith's cast service.  Its `onStartCommand` is
     * the only place that creates `com.miui.carlink.castfwk.CarlinkStateMachine`, and
     * `CarlinkService.b(CastConfig)` drops the whole config while that field is null.  Exported, but
     * declared with `android:permission="miui.carlink.permission.START_CARLINKSERVICE"` (CarWith
     * manifest, service #…), which root passes and the `shell` uid does not.
     */
    public static final String CARLINK_SERVICE_CLASS = "com.miui.carlink.castfwk.CarlinkService";
    /** The `-n` argument for the bootstrap start. */
    public static final String CARLINK_SERVICE_COMPONENT =
            CARWITH_PACKAGE + "/" + CARLINK_SERVICE_CLASS;

    /**
     * `CarlinkService.onStartCommand`'s `startType` switch (CarlinkService.dump @0x0148):
     * 1 = USB (needs a `usb_accessory_info` Parcelable), 2 = P2P (needs a `ScanResult`),
     * 3 = CARLIFE_USB_ADB, **4 = CARLIFE** (the wireless CarLife path; no Parcelable required),
     * 5 = EASYCONNECT.  CarWith's own wireless entry, `f9.c.U`, uses 4.
     */
    public static final int START_TYPE_USB = 1;
    public static final int START_TYPE_P2P = 2;
    public static final int START_TYPE_CARLIFE_USB_ADB = 3;
    public static final int START_TYPE_CARLIFE = 4;
    public static final int START_TYPE_EASYCONNECT = 5;

    /** Intent extras the `startType=4` branch reads (CarlinkService.dump @25a9ee, @25abf4, @25ac00). */
    public static final String EXTRA_START_TYPE = "startType";
    public static final String EXTRA_CARLIFE_WIRELESS_TYPE = "carlife_wireless_type";
    public static final String EXTRA_CARLIFE_WIRELESS_TYPE_NAME = "carlife_wireless_type_name";
    /** `carlife_wireless_type` values `f9.c.U` uses: AP mode / Wi-Fi Direct mode. */
    public static final String WIRELESS_TYPE_AP = "Baidu_ap";
    public static final String WIRELESS_TYPE_WLAN = "Baidu_wlan";

    /* ------------------------------------------------- ICarLifeCastManager (CarWith = server) */

    /** prepareCast(CastConfig): int present, int width, int height, int dpi, Surface surface. */
    public static final int M_PREPARE_CAST = 1;
    /** stopCast(): no arguments. */
    public static final int M_STOP_CAST = 2;
    /** setSystemAudioCallback(ISystemAudioCallback): strong binder. */
    public static final int M_SET_SYSTEM_AUDIO_CALLBACK = 3;
    /** setCastCallback(ICarLifeCastCallback): strong binder. */
    public static final int M_SET_CAST_CALLBACK = 4;
    /** invokeMethod(Intent): Bundle — capability probes (`carlife_*` actions). */
    public static final int M_INVOKE_METHOD = 5;

    /* ------------------------------------- ICarLifeCastCallback (we are the server) */

    /** onServiceReady(): the CarLife module is up; reply nothing. */
    public static final int CB_ON_SERVICE_READY = 1;
    /** onCastPrepared(int displayId): CarWith's virtual display is live on that display. */
    public static final int CB_ON_CAST_PREPARED = 2;
    /** addTouchListener(IRemoteTouchListener): CarWith hands us the injector binder. */
    public static final int CB_ADD_TOUCH_LISTENER = 3;
    public static final int CB_REMOVE_TOUCH_LISTENER = 4;
    public static final int CB_ADD_KEY_LISTENER = 5;
    public static final int CB_REMOVE_KEY_LISTENER = 6;
    /** sendMusicInfo(MusicInfo). */
    public static final int CB_SEND_MUSIC_INFO = 7;
    /** notifyMusicProgress(long). */
    public static final int CB_NOTIFY_MUSIC_PROGRESS = 8;
    /** invokeMethod(Intent): Bundle — CarWith asks US for capabilities. */
    public static final int CB_INVOKE_METHOD = 9;
    /** onServiceDestroy(). */
    public static final int CB_ON_SERVICE_DESTROY = 10;
    /** notifyAudioState(int). */
    public static final int CB_NOTIFY_AUDIO_STATE = 11;
    /** addAudioListener(IRemoteAudioListener): the microphone injector binder. */
    public static final int CB_ADD_AUDIO_LISTENER = 12;
    public static final int CB_REMOVE_AUDIO_LISTENER = 13;

    /* ---------------------------------- ISystemAudioCallback (we are the server) */

    /** onAudioStreamReady(byte[] pcm, int rate, int channels, int encoding, int category). */
    public static final int SA_ON_AUDIO_STREAM_READY = 1;
    /** onAudioStateChange(int state, int category). */
    public static final int SA_ON_AUDIO_STATE_CHANGE = 2;

    /** All three input/listener interfaces have exactly one method. */
    public static final int LISTENER_TRANSACTION = 1;

    /* ------------------------------------------------------------------ key codes (CarlifeHw) */

    public static final int KEY_SEEK_SUB = 8101;
    public static final int KEY_SEEK_ADD = 8102;
    public static final int KEY_MAIN = 8103;
    public static final int KEY_TEL = 8104;
    public static final int KEY_NAVI = 8105;
    public static final int KEY_MEDIA = 8106;
    public static final int KEY_VR_START = 8107;
    public static final int KEY_VR_STOP = 8108;
    public static final int KEY_MEDIA_START = 8109;
    public static final int KEY_MEDIA_STOP = 8110;
    public static final int KEY_PHONE_CALL = 8111;
    public static final int KEY_PHONE_END = 8112;
    public static final int KEY_NAVI_QUIT = 8113;
    public static final int KEY_FOCUS_NEXT = 8114;
    public static final int KEY_FOCUS_PRE = 8115;

    /** The one key name CarWith splits into two codes; every other name is one code either way. */
    public static final String NAME_VOICE = "语音";

    /* ------------------------------------------------------------------ audio parameters */

    public static final int AUDIO_ENCODING_PCM_16BIT = 2;
    public static final int AUDIO_MIN_RATE = 8000;
    /** Exclusive: `CarWithDirectContract.validPcm` refuses anything >= 48001. */
    public static final int AUDIO_MAX_RATE_EXCLUSIVE = 48001;
    public static final int AUDIO_MAX_BYTES = 16384;
    /** What the community component sends for the microphone. */
    public static final int MIC_RATE = 16000;
    public static final int MIC_CHANNELS = 1;
    public static final int MIC_ENCODING = AUDIO_ENCODING_PCM_16BIT;

    /* ------------------------------------------------------------------ pure helpers */

    /**
     * Name → CarWith key code, or 0 when the name is not one of ours.
     *
     * <p>Values are from `CarlifeHw`'s private constants (classes5.dex) and the community
     * `CarWithDirectContract.keyCode`.  `语音` is the only name that depends on the edge: press is
     * KEY_VR_START, release is KEY_VR_STOP, and both are sent as ACTION_DOWN because CarWith's
     * `CarlifeHw.onKeyEvent` returns early for ACTION_UP and dispatches the press/release from the
     * code itself (`n0.g().l()` vs `n0.g().j()`).
     */
    public static int keyCode(String name, boolean down) {
        if (name == null) {
            return 0;
        }
        if (NAME_VOICE.equals(name)) {
            return down ? KEY_VR_START : KEY_VR_STOP;
        }
        if ("上一曲".equals(name)) return KEY_SEEK_SUB;
        if ("下一曲".equals(name)) return KEY_SEEK_ADD;
        if ("播放".equals(name)) return KEY_MEDIA_START;
        if ("暂停".equals(name)) return KEY_MEDIA_STOP;
        if ("通话 Hook Switch".equals(name)) return KEY_PHONE_CALL;
        if ("通话 Drop".equals(name)) return KEY_PHONE_END;
        return 0;
    }

    /**
     * CarWith's own ACTION_DOWN/ACTION_UP rule: only ACTION_DOWN is dispatched.
     *
     * <p>`CarlifeHw.onKeyEvent` starts with `if (keyEvent.getAction() == 1) return true;`, so a
     * release is a no-op for every key **except** 语音, whose release is carried by
     * KEY_VR_STOP/ACTION_DOWN.  (The community reference inverts this — it sends ACTION_UP for a
     * press of every non-语音 key, so those keys never dispatch.  We do not copy that.)
     */
    public static int keyAction(String name, boolean down) {
        return 0;   /* ACTION_DOWN, always */
    }

    /**
     * Scale one axis of a touch from the AirPlay/car coordinate space into CarWith's display.
     *
     * <p>Straight from `CarWithDirectContract.coordinate(value, source, target)`:
     * `round(value * target / max(source,1))` clamped to `[0, max(target-1,0)]`.
     */
    public static float coordinate(int value, int source, int target) {
        final double scaled = (double) value * (double) target / Math.max(source, 1);
        final float lower = 0f;
        final float upper = Math.max(target - 1, 0);
        final float clamped = (float) scaled;
        if (clamped < lower) return lower;
        if (clamped > upper) return upper;
        return clamped;
    }

    /** `CarWithDirectContract.validPcm`: 1..2 channels, 8k..48k, PCM16, 1..16384 B, frame-aligned. */
    public static boolean validPcm(int size, int rate, int channels, int encoding) {
        if (channels < 1 || channels > 2) {
            return false;
        }
        if (rate < AUDIO_MIN_RATE || rate >= AUDIO_MAX_RATE_EXCLUSIVE) {
            return false;
        }
        if (encoding != AUDIO_ENCODING_PCM_16BIT) {
            return false;
        }
        if (size < 1 || size > AUDIO_MAX_BYTES) {
            return false;
        }
        return size % (channels * 2) == 0;
    }

    /**
     * Android key code (what an AirPlay/iAP2 media report carries) → the name CarWith knows.
     *
     * <p>The mapping is the inverse of what `CarlifeHw.onKeyEvent` does after it receives our 81xx
     * code: 8101→88 (previous), 8102→87 (next), 8109→126 (play), 8110→127 (pause), 8107/8108→the
     * voice assistant.  85 (PLAY_PAUSE) is folded into play, which is what the head unit sends when
     * it has a single transport button.
     *
     * @return null when the key is not one CarWith owns (volume, home, … must stay on the phone)
     */
    public static String nameForKeyCode(int keyCode) {
        switch (keyCode) {
            case 88:    /* KEYCODE_MEDIA_PREVIOUS */
                return "上一曲";
            case 87:    /* KEYCODE_MEDIA_NEXT */
                return "下一曲";
            case 126:   /* KEYCODE_MEDIA_PLAY */
            case 85:    /* KEYCODE_MEDIA_PLAY_PAUSE */
                return "播放";
            case 127:   /* KEYCODE_MEDIA_PAUSE */
                return "暂停";
            case 231:   /* KEYCODE_VOICE_ASSIST */
            case 219:   /* KEYCODE_ASSIST */
                return NAME_VOICE;
            case 79:    /* KEYCODE_HEADSETHOOK — what a head-unit call button usually sends */
                return "通话 Hook Switch";
            case 6:     /* KEYCODE_ENDCALL */
                return "通话 Drop";
            default:
                return null;
        }
    }

    /**
     * `CarWithDirectContract.audioCategory`: 1 → 1 (media), 2 → 2 (navigation/tts), 3 → 0.
     * 0 means "not one of ours" and is what an unknown category maps to.
     */
    public static int audioCategory(int category) {
        if (category == 1) return 1;
        if (category == 2) return 2;
        if (category == 3) return 0;
        return 0;
    }

    /** True when a binder really implements the interface we are about to transact with. */
    public static boolean matches(String expectedDescriptor, String actualDescriptor) {
        return expectedDescriptor != null && expectedDescriptor.equals(actualDescriptor);
    }
}
