package com.okcar.link.wireless;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * What we know about each head unit we have served, kept per device <em>and per orientation</em>.
 *
 * <p>Every connection used to re-learn the same facts the hard way: the screen the head unit is
 * driven at (most units never declare it, so it only became known from what the wire showed) and
 * how it hands the session over (some answer the iAP2 credential request with {@code 0x4301}, the
 * the integrator-style units report {@code A5 5A} credentials and never name a receiver at all).  Both are
 * stable per unit, so both are remembered to make the next connect exact instead of guessed.
 *
 * <p><b>One slot per unit was not enough.</b> A head unit can be driven landscape in one session and
 * portrait in the next (or its app can report the other way round), and a single value made the two
 * overwrite each other: the landscape value saved last session was then applied to a portrait
 * session — the picture came out 8:3 on a 20:9 screen and touch was scaled by the same wrong space.
 * So a screen is stored per {@link Orientation} and a session only ever reads the bucket matching
 * the orientation it is actually going to use.  Nothing crosses orientations and nothing crosses
 * units.
 *
 * <p>Values are stored in {@code okcar-devices}, keyed by the accessory's Bluetooth address, one
 * flat set of keys per device:
 *
 * <pre>
 *   &lt;mac&gt;.screen.landscape = 1920x720      the screen we last drove / learned for this unit
 *   &lt;mac&gt;.screen.landscape.src = REPORTED  where that value came from (see Source)
 *   &lt;mac&gt;.screen.portrait = 720x1920
 *   &lt;mac&gt;.orientation = landscape          which bucket the last session used (never a value)
 *   &lt;mac&gt;.handover = IAP2                  how the credentials arrived
 *   &lt;mac&gt;.seen = &lt;epoch ms&gt;                last time this unit talked to us
 * </pre>
 *
 * <p>Nothing here is authoritative over the live session: it is what the next session starts from.
 * A value the unit sends during a session always wins for that session, and is then stored here.
 */
public final class AccessoryMemory {

    private static final String PREFS = "okcar-devices";

    /** The single value the first version of this cache kept per unit; migrated on read. */
    private static final String OLD_SPACE_SUFFIX = ".space";

    /** How each stored screen was obtained, so a log line can say what it is worth. */
    public static final class Source {
        /** The head unit declared it on the wire (`/info displays[0]` or the stream SETUP). */
        public static final String DECLARED = "DECLARED";
        /** The head unit's own reports were in this space. */
        public static final String REPORTED = "REPORTED";
        /** An operator pinned it for this unit (`okcar-linkd send size WxH`). */
        public static final String PINNED = "PINNED";

        private Source() {
        }
    }

    /** Which video codec a unit was pinned to take, over its own advertisement. */
    public enum Codec {
        /** Follow what the unit advertises — the product behaviour. */
        AUTO,
        /** Always H.264. */
        H264,
        /** Prefer HEVC. */
        HEVC;

        static Codec parse(String value) {
            if (value == null) {
                return null;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.US));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** The operator spelling, so the command surface and this store cannot drift apart. */
        public String command() {
            return name().toLowerCase(Locale.US);
        }
    }

    /** Which way round a screen is.  Derived from the size itself, never assumed. */
    public enum Orientation {
        LANDSCAPE,
        PORTRAIT;

        /** A square or wider-than-tall screen is landscape; only a taller one is portrait. */
        public static Orientation of(int width, int height) {
            return width >= height ? LANDSCAPE : PORTRAIT;
        }

        public String key() {
            return name().toLowerCase(Locale.US);
        }

        public Orientation turned() {
            return this == LANDSCAPE ? PORTRAIT : LANDSCAPE;
        }

        static Orientation parse(String value) {
            if (value == null) {
                return null;
            }
            final String text = value.trim().toLowerCase(Locale.US);
            if (LANDSCAPE.key().equals(text)) {
                return LANDSCAPE;
            }
            return PORTRAIT.key().equals(text) ? PORTRAIT : null;
        }
    }

    /** One remembered screen: its size and where the value came from. */
    public static final class Screen {
        public final int width;
        public final int height;
        public final String source;

        Screen(int width, int height, String source) {
            this.width = width;
            this.height = height;
            this.source = source == null ? Source.REPORTED : source;
        }

        public Orientation orientation() {
            return Orientation.of(width, height);
        }

        @Override
        public String toString() {
            return width + "x" + height + " (" + source + ')';
        }
    }

    /** How an accessory was measured to hand the session over. */
    public enum Handover {
        /** Never seen this unit complete a hand-over. */
        UNKNOWN,
        /** It answers the credential request with 0x4301 (the emulator, Fruit Link, the production head unit). */
        IAP2,
        /** It only reports credentials (the integrator A5 5A) and never names a receiver. */
        DIRECT
    }

    private AccessoryMemory() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String address) {
        return address == null ? "" : address.trim().toUpperCase(Locale.US);
    }

    /* ------------------------------------------------------------------ screen */

    /**
     * The screen this unit was last driven at in {@code orientation}, or null.
     *
     * <p>Exact match only: a landscape record is never handed to a portrait session.  That is the
     * whole point of splitting the cache — see the class comment for what the single slot did.
     */
    public static Screen screen(Context context, String address, Orientation orientation) {
        if (context == null || orientation == null || key(address).isEmpty()) {
            return null;
        }
        try {
            final SharedPreferences prefs = prefs(context);
            final String prefix = key(address);
            rememberOldSpace(prefs, prefix);
            return readScreen(prefs, prefix, orientation);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The screen to start a session from when the live session has not said which orientation it
     * wants yet: the bucket the last session of this unit used, else the only bucket there is.
     *
     * <p>Returns the stored orientation along with the size, so the caller can tell the session
     * which way round it is about to be driven without inventing one.
     */
    public static Screen lastScreen(Context context, String address) {
        if (context == null || key(address).isEmpty()) {
            return null;
        }
        try {
            final SharedPreferences prefs = prefs(context);
            final String prefix = key(address);
            rememberOldSpace(prefs, prefix);
            final Orientation last = Orientation.parse(prefs.getString(prefix + ".orientation", null));
            if (last != null) {
                final Screen stored = readScreen(prefs, prefix, last);
                if (stored != null) {
                    return stored;
                }
            }
            for (Orientation candidate : Orientation.values()) {
                final Screen stored = readScreen(prefs, prefix, candidate);
                if (stored != null) {
                    return stored;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Stores what the wire showed for this unit, under the screen's own orientation.
     *
     * <p>An operator pin outranks a learned value: once a size has been pinned for a unit and an
     * orientation, a session that merely repeated a learned value must not silently replace it.
     */
    public static void rememberScreen(Context context, String address, int width, int height,
                                      String source) {
        if (context == null || key(address).isEmpty() || !usable(width, height)) {
            return;
        }
        final Orientation orientation = Orientation.of(width, height);
        try {
            final SharedPreferences prefs = prefs(context);
            final String prefix = key(address);
            final Screen existing = readScreen(prefs, prefix, orientation);
            if (existing != null && Source.PINNED.equals(existing.source)
                    && !Source.PINNED.equals(source)) {
                prefs.edit().putLong(prefix + ".seen", System.currentTimeMillis()).apply();
                return;
            }
            prefs.edit()
                    .putString(prefix + ".screen." + orientation.key(), formatSpace(width, height))
                    .putString(prefix + ".screen." + orientation.key() + ".src",
                            source == null ? Source.REPORTED : source)
                    .putString(prefix + ".orientation", orientation.key())
                    .putLong(prefix + ".seen", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
            /* a cache that cannot be written is a slower connect, never a broken one */
        }
    }

    /** Pins the size an operator asked for, so it survives into every later session of this unit. */
    public static void pinScreen(Context context, String address, int width, int height) {
        rememberScreen(context, address, width, height, Source.PINNED);
    }

    /**
     * Forgets every stored screen for this unit ({@code okcar-linkd send size auto}).
     *
     * <p>Both orientations go: "auto" means the next session follows the unit, and leaving one
     * bucket behind would make a later portrait session inherit an old landscape value.
     */
    public static void clearScreens(Context context, String address) {
        if (context == null || key(address).isEmpty()) {
            return;
        }
        try {
            final SharedPreferences prefs = prefs(context);
            final String prefix = key(address);
            final SharedPreferences.Editor editor = prefs.edit();
            for (Orientation orientation : Orientation.values()) {
                editor.remove(prefix + ".screen." + orientation.key());
                editor.remove(prefix + ".screen." + orientation.key() + ".src");
            }
            editor.remove(prefix + ".orientation");
            editor.apply();
        } catch (Throwable ignored) {
            /* see rememberScreen */
        }
    }

    private static Screen readScreen(SharedPreferences prefs, String prefix, Orientation orientation) {
        final String stored = prefs.getString(prefix + ".screen." + orientation.key(), null);
        final int[] parsed = parseSpace(stored);
        if (parsed == null) {
            return null;
        }
        /* Trust the stored bucket only for the orientation it claims: a value that no longer
         * matches its bucket is corrupt, and using it is exactly the cross-orientation mistake
         * this cache is shaped to prevent. */
        if (Orientation.of(parsed[0], parsed[1]) != orientation) {
            return null;
        }
        return new Screen(parsed[0], parsed[1],
                prefs.getString(prefix + ".screen." + orientation.key() + ".src", null));
    }

    /**
     * Moves the first version's single value into the orientation bucket it belongs to.
     *
     * <p>That value was written before orientation was distinguished, so its own aspect is the only
     * honest thing we can key it by; it is recorded as REPORTED because a unit's reports are what
     * taught it.  The old key is left in place (the read path no longer consults it) so an older
     * build still finds its data if this build is rolled back.
     */
    private static void rememberOldSpace(SharedPreferences prefs, String prefix) {
        final String old = prefs.getString(prefix + OLD_SPACE_SUFFIX, null);
        final int[] parsed = parseSpace(old);
        if (parsed == null) {
            return;
        }
        final Orientation orientation = Orientation.of(parsed[0], parsed[1]);
        if (prefs.getString(prefix + ".screen." + orientation.key(), null) != null) {
            return;
        }
        prefs.edit()
                .putString(prefix + ".screen." + orientation.key(), formatSpace(parsed[0], parsed[1]))
                .putString(prefix + ".screen." + orientation.key() + ".src", Source.REPORTED)
                .apply();
    }

    /* ------------------------------------------------------------------- codec */

    /**
     * The codec this unit was pinned to, or null when it follows its own advertisement.
     *
     * <p>Per unit, like the screen.  A pin is a statement about one head unit (what it can decode,
     * or a test of that branch), and the session-wide pin this replaced travelled: a {@code codec
     * hevc} typed while testing one accessory was still in force for the next one — the same
     * "last session decides the next one" failure the screen store was reshaped for.
     */
    public static Codec codec(Context context, String address) {
        if (context == null || key(address).isEmpty()) {
            return null;
        }
        try {
            return Codec.parse(prefs(context).getString(key(address) + ".codec", null));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Pins a codec for this unit; {@link Codec#AUTO} forgets the pin instead of storing it. */
    public static void pinCodec(Context context, String address, Codec value) {
        if (context == null || key(address).isEmpty()) {
            return;
        }
        if (value == null || value == Codec.AUTO) {
            clearCodec(context, address);
            return;
        }
        try {
            prefs(context).edit()
                    .putString(key(address) + ".codec", value.name())
                    .putLong(key(address) + ".seen", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
            /* see rememberScreen */
        }
    }

    public static void clearCodec(Context context, String address) {
        if (context == null || key(address).isEmpty()) {
            return;
        }
        try {
            prefs(context).edit().remove(key(address) + ".codec").apply();
        } catch (Throwable ignored) {
            /* see rememberScreen */
        }
    }

    /* ---------------------------------------------------------------- handover */

    /** How this unit handed over last time. */
    public static Handover handover(Context context, String address) {
        if (context == null) {
            return Handover.UNKNOWN;
        }
        try {
            return parseHandover(prefs(context).getString(key(address) + ".handover", null));
        } catch (Throwable t) {
            return Handover.UNKNOWN;
        }
    }

    public static void rememberHandover(Context context, String address, Handover value) {
        if (context == null || value == null || value == Handover.UNKNOWN
                || key(address).isEmpty()) {
            return;
        }
        try {
            prefs(context).edit()
                    .putString(key(address) + ".handover", value.name())
                    .putLong(key(address) + ".seen", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
            /* see rememberScreen */
        }
    }

    /* ------------------------------------------------------------------ report */

    /** One log line about a unit, so a connect shows exactly what was already known about it. */
    public static String describe(Context context, String address) {
        if (context == null || key(address).isEmpty()) {
            return "no accessory address";
        }
        final Screen last = lastScreen(context, address);
        final Handover handover = handover(context, address);
        final Codec codec = codec(context, address);
        final long seen = lastSeen(context, address);
        if (last == null && handover == Handover.UNKNOWN && codec == null) {
            if (seen > 0L) {
                /* We have met this unit but stored nothing reusable: saying "first time" would hide
                 * the fact that the record exists (and that its .seen stamp is the only trace). */
                return "seen " + key(address) + ' ' + describeAge(seen, System.currentTimeMillis())
                        + ", but nothing stored to reuse yet";
            }
            return "first time with " + key(address) + " — nothing to reuse yet";
        }
        final StringBuilder out = new StringBuilder("known accessory ").append(key(address));
        if (last != null) {
            out.append(", screen ").append(last).append(" for ").append(last.orientation().key());
            final Screen other = screen(context, address, last.orientation().turned());
            if (other != null) {
                out.append(" (also ").append(other).append(" for ")
                        .append(other.orientation().key()).append(')');
            }
        }
        if (codec != null) {
            out.append(", codec ").append(codec.name());
        }
        if (handover != Handover.UNKNOWN) {
            out.append(", hand-over ").append(handover);
        }
        if (seen > 0L) {
            /* .seen was written on every remember*() and never read: either it is reported here or it
             * is dead bytes in the per-unit record.  An operator asking "is this stored size still
             * current?" needs exactly this. */
            out.append(", last seen ").append(describeAge(seen, System.currentTimeMillis()));
        }
        return out.toString();
    }

    /** When this unit was last seen (epoch ms), or 0 when there is no record of it. */
    public static long lastSeen(Context context, String address) {
        if (context == null || key(address).isEmpty()) {
            return 0L;
        }
        try {
            return prefs(context).getLong(key(address) + ".seen", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Renders a last-seen stamp as an age, so one log line stays readable at any scale. */
    public static String describeAge(long seenEpochMs, long nowMs) {
        if (seenEpochMs <= 0L) {
            return "never";
        }
        final long seconds = Math.max(0L, (nowMs - seenEpochMs) / 1000L);
        if (seconds < 60L) {
            return "just now";
        }
        final long minutes = seconds / 60L;
        if (minutes < 90L) {
            return minutes + " min ago";
        }
        final long hours = minutes / 60L;
        if (hours < 48L) {
            return hours + " h ago";
        }
        return (hours / 24L) + " d ago";
    }

    static String formatSpace(int width, int height) {
        return width + "x" + height;
    }

    /** Parses {@code 1920x720}; null for anything malformed (a bad value must not break a connect). */
    static int[] parseSpace(String value) {
        if (value == null) {
            return null;
        }
        final String text = value.trim().toLowerCase(Locale.US);
        final int x = text.indexOf('x');
        if (x <= 0 || x == text.length() - 1) {
            return null;
        }
        try {
            final int width = Integer.parseInt(text.substring(0, x).trim());
            final int height = Integer.parseInt(text.substring(x + 1).trim());
            return usable(width, height) ? new int[] {width, height} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Handover parseHandover(String value) {
        if (value == null) {
            return Handover.UNKNOWN;
        }
        try {
            return Handover.valueOf(value.trim().toUpperCase(Locale.US));
        } catch (IllegalArgumentException e) {
            return Handover.UNKNOWN;
        }
    }

    private static boolean usable(int width, int height) {
        return width >= 320 && height >= 240 && width <= 8192 && height <= 8192;
    }

    /** Host-runnable: the stored form is what a connect depends on, so keep it checked. */
    public static void main(String[] args) {
        int passed = 0;
        int failed = 0;

        final int[] space = parseSpace("1920x720");
        final boolean okShape = space != null && space[0] == 1920 && space[1] == 720;
        report("parses the production head unit's space", okShape);
        passed += okShape ? 1 : 0;
        failed += okShape ? 0 : 1;

        final boolean okRoundTrip = parseSpace(formatSpace(2400, 1080)) != null
                && parseSpace(formatSpace(2400, 1080))[0] == 2400;
        report("round-trips through the stored form", okRoundTrip);
        passed += okRoundTrip ? 1 : 0;
        failed += okRoundTrip ? 0 : 1;

        final boolean okReject = parseSpace(null) == null && parseSpace("") == null
                && parseSpace("1920") == null && parseSpace("1920x") == null
                && parseSpace("0x0") == null && parseSpace("x720") == null
                && parseSpace("smallxbig") == null;
        report("rejects every malformed space", okReject);
        passed += okReject ? 1 : 0;
        failed += okReject ? 0 : 1;

        final boolean okHandover = parseHandover("IAP2") == Handover.IAP2
                && parseHandover("direct") == Handover.DIRECT
                && parseHandover("nonsense") == Handover.UNKNOWN
                && parseHandover(null) == Handover.UNKNOWN;
        report("parses the hand-over mechanism", okHandover);
        passed += okHandover ? 1 : 0;
        failed += okHandover ? 0 : 1;

        /* The split is the whole reason this cache was reshaped: a size must land in the bucket
         * its own aspect names, and the two buckets must be told apart. */
        final boolean okOrientation = Orientation.of(2400, 1080) == Orientation.LANDSCAPE
                && Orientation.of(1080, 2400) == Orientation.PORTRAIT
                && Orientation.of(720, 720) == Orientation.LANDSCAPE
                && Orientation.LANDSCAPE.turned() == Orientation.PORTRAIT
                && Orientation.PORTRAIT.turned() == Orientation.LANDSCAPE
                && Orientation.LANDSCAPE.key().equals("landscape")
                && Orientation.PORTRAIT.key().equals("portrait");
        report("derives the orientation from the size itself", okOrientation);
        passed += okOrientation ? 1 : 0;
        failed += okOrientation ? 0 : 1;

        final boolean okOrientationParse = Orientation.parse("landscape") == Orientation.LANDSCAPE
                && Orientation.parse("PORTRAIT") == Orientation.PORTRAIT
                && Orientation.parse("sideways") == null
                && Orientation.parse(null) == null;
        report("parses a stored orientation name", okOrientationParse);
        passed += okOrientationParse ? 1 : 0;
        failed += okOrientationParse ? 0 : 1;

        final Screen screen = new Screen(1920, 720, Source.PINNED);
        final boolean okScreen = screen.orientation() == Orientation.LANDSCAPE
                && screen.toString().contains("PINNED")
                && new Screen(720, 1920, null).source.equals(Source.REPORTED);
        report("names a stored screen's orientation and source", okScreen);
        passed += okScreen ? 1 : 0;
        failed += okScreen ? 0 : 1;

        final boolean okSources = !Source.DECLARED.equals(Source.REPORTED)
                && !Source.REPORTED.equals(Source.PINNED);
        report("keeps the three provenances apart", okSources);
        passed += okSources ? 1 : 0;
        failed += okSources ? 0 : 1;

        System.out.println("accessory memory self test: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            throw new AssertionError(failed + " check(s) failed");
        }
    }

    private static void report(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }
}
