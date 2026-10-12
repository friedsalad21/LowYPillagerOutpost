package project;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Command-line mode: java -jar LowYPillagerOutpost.jar --seed S [options]. */
public final class CmdLineRunner {

    static final String[] OPTIONS = {"--seed", "-s", "--seeds-file", "-f", "--max-y", "--square-side",
            "--min-x", "--max-x", "--min-z", "--max-z", "--threads", "--output", "-o", "--no-progress", "--help", "-h"};

    private CmdLineRunner() {
    }

    public static void run(String[] args) {
        String seedText = null, seedsFile = null, output = "result.txt";
        int maxY = -40, threads = 0;
        Integer side = null;
        int minX = OutpostSearch.WORLD_MIN, maxX = OutpostSearch.WORLD_MAX,
                minZ = OutpostSearch.WORLD_MIN, maxZ = OutpostSearch.WORLD_MAX;
        boolean progress = true;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--help", "-h" -> { help(); return; }
                    case "--no-progress" -> progress = false;
                    default -> {
                        if (!java.util.Arrays.asList(OPTIONS).contains(a)) {
                            throw new IllegalArgumentException("unknown argument: " + a);
                        }
                        if (i + 1 >= args.length) throw new IllegalArgumentException(a + " is missing a value");
                        String v = args[++i];
                        switch (a) {
                            case "--seed", "-s" -> seedText = v;
                            case "--seeds-file", "-f" -> seedsFile = v;
                            case "--max-y" -> maxY = Integer.parseInt(v);
                            case "--square-side" -> side = Integer.parseInt(v);
                            case "--min-x" -> minX = Integer.parseInt(v);
                            case "--max-x" -> maxX = Integer.parseInt(v);
                            case "--min-z" -> minZ = Integer.parseInt(v);
                            case "--max-z" -> maxZ = Integer.parseInt(v);
                            case "--threads" -> threads = Integer.parseInt(v);
                            case "--output", "-o" -> output = v;
                            default -> throw new IllegalArgumentException("unknown argument: " + a);
                        }
                    }
                }
            }
            if ((seedText == null) == (seedsFile == null)) {
                throw new IllegalArgumentException("give exactly one of --seed or --seeds-file");
            }
            if (side != null) {
                if (side < 1) throw new IllegalArgumentException("--square-side must be > 0");
                minX = minZ = -side / 2;
                maxX = maxZ = minX + side - 1;
            }
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("min must be <= max");
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Run with --help for usage.");
            System.exit(2);
            return;
        }

        if (!NativeOutpost.isAvailable()) {
            System.err.println(NativeOutpost.describe());
            System.exit(1);
        }
        List<Long> seeds = new ArrayList<>();
        if (seedsFile != null) {
            try {
                for (String line : Files.readAllLines(Path.of(seedsFile), StandardCharsets.UTF_8)) {
                    Long s = OutpostSearch.parseSeedLine(line);
                    if (s != null) seeds.add(s);
                }
            } catch (IOException e) {
                System.err.println("Error: could not read " + seedsFile + ": " + e.getMessage());
                System.exit(1);
            }
        } else {
            seeds.add(OutpostSearch.parseSeed(seedText));
        }

        long perSeed = OutpostSearch.regionCount(minX, maxX, minZ, maxZ);
        long total = perSeed * seeds.size();
        System.err.printf("Searching %d seed(s) | max Y %d | regions X %d..%d Z %d..%d | %s%n",
                seeds.size(), maxY, minX, maxX, minZ, maxZ, NativeOutpost.describe());
        List<OutpostSearch.Hit> hits = new ArrayList<>();
        OutpostSearch.Control control = new OutpostSearch.Control();
        long t0 = System.currentTimeMillis();
        long[] base = {0}, lastPrint = {0};
        boolean showProgress = progress;
        for (long seed : seeds) {
            OutpostSearch.scan(seed, threads, maxY, minX, maxX, minZ, maxZ, control, (h, done) -> {
                hits.addAll(h);
                long now = System.currentTimeMillis();
                if (showProgress && now - lastPrint[0] >= 2000) {
                    lastPrint[0] = now;
                    long d = base[0] + done;
                    long el = now - t0;
                    System.err.printf("  %.2f%%  hits %d  elapsed %s  remaining %s%n", 100.0 * d / total, hits.size(),
                            OutpostSearch.formatDuration(el), OutpostSearch.formatDuration((long) (el * (double) (total - d) / d)));
                }
            });
            base[0] += perSeed;
        }
        hits.sort(Comparator.comparingInt(OutpostSearch.Hit::y).thenComparingDouble(OutpostSearch.Hit::distance));

        boolean many = seeds.size() > 1;
        try (PrintWriter w = new PrintWriter(output, StandardCharsets.UTF_8)) {
            for (OutpostSearch.Hit h : hits) w.println(many ? h.seed() + "  " + h : h.toString());
        } catch (IOException e) {
            System.err.println("Error: could not write " + output + ": " + e.getMessage());
        }
        for (OutpostSearch.Hit h : hits.subList(0, Math.min(20, hits.size()))) {
            System.out.println(many ? h.seed() + "  " + h : h.toString());
        }
        System.err.printf("Done in %s: %d outpost(s), saved to %s%n",
                OutpostSearch.formatDuration(System.currentTimeMillis() - t0), hits.size(), output);
    }

    private static void help() {
        System.out.println("""
                LowYPillagerOutpost %s - lowest pillager outposts, Minecraft 26.2
                Usage: java -jar LowYPillagerOutpost-%s.jar --seed <seed> | --seeds-file <file> [options]
                (no arguments opens the GUI)

                  --seed, -s S          one seed (number or text)
                  --seeds-file, -f F    one seed per line, # starts a comment
                  --max-y Y             keep outposts whose bounding box bottom is <= Y (default -40)
                  --square-side N       square of N x N regions (512 blocks each) centred on 0,0
                  --min-x/--max-x/--min-z/--max-z R
                                        region rectangle, inclusive (default: whole world)
                  --threads T           default: all CPU threads
                  --output, -o FILE     results file (default result.txt)
                  --no-progress         no progress lines

                Example: java -jar LowYPillagerOutpost-%s.jar --seed 123 --square-side 3908 --max-y -40
                """.formatted(AppVersion.VERSION, AppVersion.VERSION, AppVersion.VERSION));
    }
}
