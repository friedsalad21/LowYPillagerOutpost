# LowYPillagerOutpost

Finds the **lowest-spawning pillager outposts** in a Minecraft Java world seed (target
version **26.2**). Forked from [M33Galaxy/LowYSwampHut](https://github.com/M33Galaxy/LowYSwampHut)
(MIT); the swamp-hut terrain engine (`lysh-c`) is reused, the outpost logic is new and kept in
its own files so it can be merged back into the swamp hut finder later.

Results are exact: every rule was taken from the 26.2/26.3 game code and the C results were
compared with Minecraft itself (see "Verification").

## What "lowest" means

The reported Y is the **bottom of the outpost's full bounding box** — the box Minecraft uses
for the pillager spawn override (`bounding_box: full`). That is 12 blocks below the lowest
structure piece. The lowest piece is either the base plate (one block below the surface at the
outpost centre) or one of the three terrain-following side plates (surface height where each
plate attaches), whichever is lower.

## Run a search (command line)

Build once (see `HANDOFF.md` for Linux / Windows build commands), then:

```bash
lysh outpost --seed <seed> --regions <N> --max-y <Y> > results.txt
```

| Option | Meaning |
|---|---|
| `--seed` | world seed |
| `--regions N` | search N regions (512 blocks each) in every direction from 0,0 |
| `--rx0 --rx1 --rz0 --rz1` | explicit region rectangle instead of `--regions` (whole world: -58594 .. 58594) |
| `--max-y Y` | keep outposts whose bounding-box bottom is at or below Y |
| `--threads T` | default = all CPU threads |
| `--version` | `26.2` (default); also `26.3`, `1.21.5~26.1` and older (older ones unverified) |
| `--dump` | debug: every candidate, one per line |

Output, lowest first: `/tp x y z   (lowest piece P, base B)` — x/z is the outpost centre,
y the bounding-box bottom.

Speed: ~50k regions/s on 12 threads. 2,000,000 x 2,000,000 blocks (`--regions 1954`) takes
about 5 minutes; the whole world about 3.5 days.

Example (seed -3767261087998042360, 2M x 2M blocks, max-y -40): `results/`.

## How it works

`lysh-c/src/outpost.c` (header comment has the full derivation):

1. Outpost placement: random_spread spacing 32 / separation 8 / salt 165745296.
2. 1-in-5 roll (`legacy_type_1` frequency reducer).
3. No village placement within 10 chunks.
4. Quick exact pre-check: if the ground is solid at the target Y at all four columns that
   matter, the outpost cannot be low enough.
5. Exact surface heights (pre-carver `WORLD_SURFACE_WG`, water counts as surface) at the
   base centre and the three plate attachment points; biome at the start position must be
   in `#has_structure/pillager_outpost`.

## Verification

`tools/oracle-26.2` is a Fabric server mod that runs Minecraft's own structure generation on
a seed and writes every candidate (or a chosen list of chunks, or biome samples) to a file.
C output was identical to it on 4096 candidates, 61 deep outposts and 200,000 biome samples
(26.2 and 26.3).

## Layout

| Path | What |
|---|---|
| `lysh-c/` | C core (CMake). `src/outpost.*` = outpost search; rest = terrain engine from LowYSwampHut |
| `app/` | Java GUI — still the swamp-hut GUI, **to be converted** (see `HANDOFF.md`) |
| `tools/oracle-26.2/` | in-game ground truth (Fabric dev server mod) |
| `tools/biome-tree/` | turns an oracle biome-tree dump into a C header |
| `tools/jni-win32/` | `jni_md.h` for cross-compiling the Windows DLL from Linux |
| `results/` | saved search results |

## Credits

Terrain engine and original tool: M33Galaxy, SunnySlopes (MIT). Biome trees for older
versions: cubiomes (MIT). Outpost search: this fork.
