package local.oracle;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ground truth for the C outpost search: runs Minecraft's own placement + Structure.generate
 * (same call as ChunkGenerator.tryGenerateStructure) over a region square and dumps every
 * candidate. Line format:
 *   cx cz status [baseH stubY biome pieceMinY bboxMinY nPieces]
 * status: freq|excl = placement rejected, biome = generate() returned nothing, OK = outpost.
 */
public class OutpostOracle implements ModInitializer {
    static String biomeDiag(net.minecraft.world.level.chunk.ChunkGenerator gen, net.minecraft.world.level.levelgen.RandomState rs,
                            ServerLevel level, long ws, ChunkPos cp) {
        var rnd = new net.minecraft.world.level.levelgen.WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(0L));
        rnd.setLargeFeatureSeed(ws, cp.x(), cp.z());
        int rot = rnd.nextInt(4);
        int px = cp.getMinBlockX(), pz = cp.getMinBlockZ();
        int[][] t = { {0, 0, 15, 15}, {-15, 0, 0, 15}, {-15, -15, 0, 0}, {0, -15, 15, 0} };
        int cx = (2 * px + t[rot][0] + t[rot][2]) / 2, cz = (2 * pz + t[rot][1] + t[rot][3]) / 2;
        int h = gen.getBaseHeight(cx, cz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
        var res = new Object() { net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getNoiseBiome(int a, int b, int c) { return gen.getBiomeSource().getNoiseBiome(a, b, c, rs.sampler()); } };
        StringBuilder sb = new StringBuilder(h + " " + cx + "," + cz);
        for (int dy = -8; dy <= 8; dy += 4)
            sb.append(' ').append(dy).append('=').append(res.getNoiseBiome(QuartPos.fromBlock(cx), QuartPos.fromBlock(h + dy), QuartPos.fromBlock(cz))
                    .unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
        return sb.toString();
    }

    /** Pre-order dump of Climate.ParameterList.index (the RTree MC searches). */
    static void dumpTree(Object biomeSource, Path out) throws Exception {
        var pm = biomeSource.getClass().getDeclaredMethod("parameters");
        pm.setAccessible(true);
        Object plist = pm.invoke(biomeSource);
        var idx = plist.getClass().getDeclaredField("index");
        idx.setAccessible(true);
        Object rtree = idx.get(plist);
        var rootF = rtree.getClass().getDeclaredField("root");
        rootF.setAccessible(true);
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
            walk(rootF.get(rtree), pw);
        }
    }

    static void walk(Object node, PrintWriter pw) throws Exception {
        Class<?> c = node.getClass();
        var psF = c.getSuperclass().getDeclaredField("parameterSpace");
        psF.setAccessible(true);
        Object[] ps = (Object[]) psF.get(node);
        StringBuilder sb = new StringBuilder();
        for (Object p : ps) {
            var rec = (net.minecraft.world.level.biome.Climate.Parameter) p;
            sb.append(' ').append(rec.min()).append(' ').append(rec.max());
        }
        if (c.getSimpleName().equals("Leaf")) {
            var vF = c.getDeclaredField("value");
            vF.setAccessible(true);
            @SuppressWarnings("unchecked")
            var h = (Holder<net.minecraft.world.level.biome.Biome>) vF.get(node);
            pw.println("L " + h.unwrapKey().map(k -> k.identifier().getPath()).orElse("?") + sb);
        } else {
            var chF = c.getDeclaredField("children");
            chF.setAccessible(true);
            Object[] ch = (Object[]) chF.get(node);
            pw.println("N " + ch.length + sb);
            for (Object o : ch) walk(o, pw);
        }
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
        String seed = System.getProperty("oracle.seed", "-3767261087998042360");
        int r = Integer.getInteger("oracle.r", 32);
        Path out = Path.of(System.getProperty("oracle.out", "oracle.txt")).toAbsolutePath();
                ServerLevel level = server.overworld();
                var cs = level.getChunkSource();
                var gen = cs.getGenerator();
                var rs = cs.randomState();
                var state = cs.getGeneratorState();
                var reg = level.registryAccess();
                Holder<StructureSet> set = reg.lookupOrThrow(Registries.STRUCTURE_SET)
                        .getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.withDefaultNamespace("pillager_outposts")));
                var placement = (RandomSpreadStructurePlacement) set.value().placement();
                var structHolder = set.value().structures().get(0).structure();
                var structure = structHolder.value();
                long ws = level.getSeed();
                try { dumpTree(gen.getBiomeSource(), Path.of("biome-tree.txt").toAbsolutePath()); } catch (Exception e) { throw new RuntimeException(e); }
                if (Files.exists(Path.of("biome-points.txt"))) {
                    var res = new Object() { net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getNoiseBiome(int a, int b, int c) { return gen.getBiomeSource().getNoiseBiome(a, b, c, rs.sampler()); } };
                    try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Path.of("biome-oracle.txt")))) {
                        for (String ln : Files.readAllLines(Path.of("biome-points.txt"))) {
                            String[] f = ln.trim().split(" ");
                            int x = Integer.parseInt(f[0]), y = Integer.parseInt(f[1]), z = Integer.parseInt(f[2]);
                            pw.println(x + " " + y + " " + z + " " + res.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z))
                                    .unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
                        }
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
                var climate = rs.sampler();
                try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
                    pw.println("# seed " + ws + " r " + r);
                    java.util.List<ChunkPos> todo = new java.util.ArrayList<>();
                    String list = System.getProperty("oracle.list", Files.exists(Path.of("oracle-list.txt")) ? "oracle-list.txt" : null);
                    if (list != null) {
                        for (String ln : Files.readAllLines(Path.of(list))) {
                            String[] f = ln.trim().split("\s+");
                            if (f.length >= 2) todo.add(new ChunkPos(Integer.parseInt(f[0]), Integer.parseInt(f[1])));
                        }
                    } else {
                        for (int rz = -r; rz < r; rz++)
                            for (int rx = -r; rx < r; rx++)
                                todo.add(placement.getPotentialStructureChunk(ws, rx * 32, rz * 32));
                    }
                    for (ChunkPos cp : todo) {
                        {
                            int cx = cp.x(), cz = cp.z();
                            if (!placement.applyAdditionalChunkRestrictions(cx, cz, ws)) { pw.println(cx + " " + cz + " freq"); continue; }
                            if (!placement.isStructureChunk(state, cx, cz)) { pw.println(cx + " " + cz + " excl"); continue; }
                            StructureStart start = structure.generate(structHolder, Level.OVERWORLD, reg, gen, gen.getBiomeSource(),
                                    rs, server.getStructureManager(), ws, cp, 0, level, structure.biomes()::contains);
                            if (!start.isValid()) {
                                int rot0 = 0; // report biome at the stub the C code would use
                                var c0 = gen.getBiomeSource();
                                String why = "";
                                for (int rr = 0; rr < 4; rr++) {}
                                pw.println(cx + " " + cz + " biome " + biomeDiag(gen, rs, level, ws, cp));
                                continue;
                            }
                            var pieces = start.getPieces();
                            var base = pieces.get(0).getBoundingBox();
                            int ctrX = (base.maxX() + base.minX()) / 2, ctrZ = (base.maxZ() + base.minZ()) / 2;
                            int h = gen.getBaseHeight(ctrX, ctrZ, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                            var biome = gen.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(ctrX), QuartPos.fromBlock(h), QuartPos.fromBlock(ctrZ), rs.sampler());
                            int pieceMin = pieces.stream().mapToInt(p -> p.getBoundingBox().minY()).min().orElseThrow();
                            StringBuilder sb = new StringBuilder();
                            sb.append(cx).append(' ').append(cz).append(" OK ").append(h).append(' ').append(ctrX).append(',').append(ctrZ)
                              .append(' ').append(biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("?"))
                              .append(' ').append(pieceMin).append(' ').append(start.getBoundingBox().minY()).append(' ').append(pieces.size());
                            for (StructurePiece p : pieces) {
                                var b = p.getBoundingBox();
                                sb.append(" [").append(b.minX()).append(',').append(b.minY()).append(',').append(b.minZ()).append("..")
                                  .append(b.maxX()).append(',').append(b.maxY()).append(',').append(b.maxZ()).append(']');
                            }
                            pw.println(sb);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                System.out.println("[outpost-oracle] wrote " + out);
            server.halt(false);
        });
    }
}
