package project;

/**
 * JNI bridge to the C outpost search (lysh-c/src/outpost.h, exports in jni_bridge.c).
 * Minecraft version is fixed to 26.2 on the C side.
 */
public final class NativeOutpost {

    /** ints per hit in {@link #band}'s hit buffer. */
    public static final int INTS_PER_HIT = 5;
    public static final int F_X = 0, F_Y = 1, F_Z = 2, F_PIECE = 3, F_BASE = 4;

    /** {@link #band} result: long[8]. */
    public static final int S_SCANNED = 0, S_FREQ = 1, S_VILLAGE = 2, S_PREFILTER = 3,
            S_BIOME = 4, S_Y = 5, S_OK = 6, S_WRITTEN = 7;

    private NativeOutpost() {
    }

    /** Opens a scan session (noise stacks built once per thread). 0 = failed. threads 0 = all CPUs. */
    public static native long open(long seed, int threads);

    /** Scans regions [rx0,rx1) x [rz0,rz1); hits lowest first, {@link #INTS_PER_HIT} ints each. */
    public static native long[] band(long handle, int rx0, int rx1, int rz0, int rz1, int maxY, int[] outHits);

    public static native void close(long handle);

    public static native String coreVersion();

    private static final String STATUS;
    private static final boolean AVAILABLE;

    static {
        // NativePhase1 owns the loading (search order, jar-embedded copy); same library.
        String status;
        boolean ok = false;
        if (!NativePhase1.isAvailable()) {
            status = "native core unavailable; tried: " + String.join(" | ", NativePhase1.loadAttempts());
        } else {
            try {
                status = coreVersion();
                ok = true;
            } catch (Throwable t) {
                status = "native core is too old (no outpost search): " + t;
            }
        }
        STATUS = status;
        AVAILABLE = ok;
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static String describe() {
        return (AVAILABLE ? "engine: " : "engine: OFF - ") + STATUS;
    }
}
