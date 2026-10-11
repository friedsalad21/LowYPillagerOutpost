# Pillager outpost search (branch `pillager-outpost`) — status

Separate tool for now, built so it can merge into the swamp hut finder later: all new code
lives in new files (`lysh-c/src/outpost.[ch]`, `biome_tree_*.h`), plus small additive
changes (`lysh_biome_at()` in biome.c, exported thread helpers in search.c, new CLI subcommands).

## Done (C core) — verified against Minecraft itself
- `lysh outpost --seed S --regions N [--max-y M] [--version V] [--threads T] [--dump]`
- Result Y = bottom of the outpost's full bounding box (lowest piece − 12, the box
  pillager spawns use). Lowest piece = min(base surface − 1, each side-plate's surface).
- Versions: 1.19~1.19.1, 1.19.2~1.19.3, 1.19.4~1.20.5, 1.20.6~1.21.3, 1.21.4,
  1.21.5~26.1 (cubiomes trees), 26.2, 26.3 (trees dumped from the game; 26.3 adds dappled_forest).
- 26.3 and 26.2 oracle (C:\CODE\outpost-oracle, outpost-oracle-262): 4096 candidates and
  200k biome samples identical; 61 lowest hits identical on 26.3.
- Speed: ~57k regions/s on 12 threads.

## Not done yet
- 1.21.11 oracle (C:\CODE\outpost-oracle-12111) was mid-setup: older versions are unverified.
- Java GUI / JNI bridge / dist jar for the outpost tool.
- Side finding: the swamp tool's 26.2~26.3 mode uses cubiomes btree262, which misses 26.3's
  dappled_forest — it could switch to the dumped trees here.

Regenerate a dumped tree: run the oracle (writes run/biome-tree.txt), then
`py -I lysh-c/tree2c.py biome-tree.txt cubiomes/biomes.h rtreeNNN out.h <ver>`.
