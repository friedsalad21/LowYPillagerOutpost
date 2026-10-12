package project;

import java.util.ArrayList;
import java.util.List;

/**
 * One-seed outpost scan over a region rectangle, split into Z bands so callers get progress
 * and can pause / stop between bands. Shared by the GUI and the command line.
 * Region bounds are inclusive region indices (1 region = 512 x 512 blocks).
 */
public final class OutpostSearch {

    /** Full world in regions (30M blocks / 512). */
    public static final int WORLD_MIN = -58594, WORLD_MAX = 58593;

    public record Hit(long seed, int x, int y, int z, int pieceY, int baseY) {
        public double distance() {
            return Math.sqrt((double) x * x + (double) z * z);
        }

        public String tp() {
            return "/tp " + x + " " + y + " " + z;
        }

        @Override
        public String toString() {
            return tp() + "   (lowest piece " + pieceY + ", base " + baseY + ")";
        }
    }

    public interface Listener {
        /** Called after each band with new hits (may be empty) and regions done so far. */
        void band(List<Hit> newHits, long regionsDone);
    }

    /** Pause / stop flags polled between bands. */
    public static final class Control {
        public volatile boolean stopped;
        public volatile boolean paused;

        void waitIfPaused() {
            while (paused && !stopped) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    stopped = true;
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private OutpostSearch() {
    }

    public static long regionCount(int minX, int maxX, int minZ, int maxZ) {
        return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
    }

    /** Scans one seed; returns false if stopped early. */
    public static boolean scan(long seed, int threads, int maxY, int minX, int maxX, int minZ, int maxZ,
                               Control control, Listener listener) {
        if (!NativeOutpost.isAvailable()) {
            throw new IllegalStateException(NativeOutpost.describe());
        }
        if (threads <= 0) {
            threads = Runtime.getRuntime().availableProcessors();
        }
        long handle = NativeOutpost.open(seed, threads);
        if (handle == 0) {
            throw new IllegalStateException("could not open native session (out of memory?)");
        }
        try {
            long width = maxX - minX + 1;
            // ~0.5 s bands on a fast PC; a multiple of threads so no thread idles (rows are interleaved).
            long rows = Math.max(1, 30_000 / width / threads) * threads;
            int[] buf = new int[4096 * NativeOutpost.INTS_PER_HIT];
            long done = 0;
            for (long z = minZ; z <= maxZ; z += rows) {
                control.waitIfPaused();
                if (control.stopped) {
                    return false;
                }
                int z1 = (int) Math.min((long) maxZ + 1, z + rows);
                long[] s = NativeOutpost.band(handle, minX, maxX + 1, (int) z, z1, maxY, buf);
                if (s[NativeOutpost.S_OK] > s[NativeOutpost.S_WRITTEN]) {
                    // buffer too small for this band: grow and redo it
                    buf = new int[(int) s[NativeOutpost.S_OK] * NativeOutpost.INTS_PER_HIT];
                    s = NativeOutpost.band(handle, minX, maxX + 1, (int) z, z1, maxY, buf);
                }
                List<Hit> hits = new ArrayList<>();
                for (int i = 0; i < s[NativeOutpost.S_WRITTEN]; i++) {
                    int p = i * NativeOutpost.INTS_PER_HIT;
                    hits.add(new Hit(seed, buf[p + NativeOutpost.F_X], buf[p + NativeOutpost.F_Y],
                            buf[p + NativeOutpost.F_Z], buf[p + NativeOutpost.F_PIECE], buf[p + NativeOutpost.F_BASE]));
                }
                done += s[NativeOutpost.S_SCANNED];
                listener.band(hits, done);
            }
            return true;
        } finally {
            NativeOutpost.close(handle);
        }
    }

    /** Seed from text: a number, or (like Minecraft) the hashCode of any other text. */
    public static long parseSeed(String text) {
        text = text.trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text.hashCode();
        }
    }

    /** Seed-list line: blank / # comment -> null. */
    public static Long parseSeedLine(String line) {
        line = line == null ? "" : line.trim();
        if (line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        return parseSeed(line);
    }

    public static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s >= 86400) return String.format("%dd %dh %dm", s / 86400, s % 86400 / 3600, s % 3600 / 60);
        if (s >= 3600) return String.format("%dh %dm %ds", s / 3600, s % 3600 / 60, s % 60);
        if (s >= 60) return String.format("%dm %ds", s / 60, s % 60);
        return s + "s";
    }
}
