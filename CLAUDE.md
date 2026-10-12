# LowYPillagerOutpost: notes for Claude

Lowest pillager outpost finder, **Minecraft 26.2 only** (no other versions need work).
C core in `lysh-c/` (outpost logic in `src/outpost.[ch]`, the rest is the LowYSwampHut terrain
engine, kept so this can merge back into the swamp hut finder). Java GUI in `app/`.

## Rules
- The C core is verified bit-exact against Minecraft. After **any** C change, re-run the check
  below; the numbers must not change.
- Keep code minimal. User-facing steps should be copy-paste commands. The owner does the
  in-game testing. Minecraft source: mcsrc.dev (or Vineflower locally).
- Commit as `friedsalad21 <76220605+friedsalad21@users.noreply.github.com>`; no real names
  or personal emails in the repo (it is public).

## Build
```bash
./build-dist.sh   # -> dist/LowYPillagerOutpost-<ver>.jar (Windows DLL via mingw-w64 + Linux .so)
cmake -S lysh-c -B lysh-c/build -G Ninja -DJAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cmake --build lysh-c/build
```
Version: `app/src/main/java/project/AppVersion.java`. The README's top download link points at
the current release; update it when releasing.

## Verify
```bash
./lysh-c/build/lysh outpost --seed -3767261087998042360 --regions 32 --dump | awk '{print $3}' | sort | uniq -c
#   -> 134 OK / 385 biome / 306 excl / 3271 freq
./lysh-c/build/lysh outpost --seed -3767261087998042360 --regions 32 --max-y 50
#   -> 26 hits, lowest /tp 5304 46 -8456
```
In-game ground truth: `tools/oracle-26.2` (Fabric dev server, needs Java 25):
`(cd tools/oracle-26.2 && ./gradlew runServer --args=nogui)` writes `run/oracle.txt` (every
candidate in regions -32..31, or the chunks in `run/oracle-list.txt`), `run/biome-tree.txt`, and
`run/biome-oracle.txt` if `run/biome-points.txt` exists. Diff columns cx cz status h ctr
pieceMin bboxMin against the `--dump` output. `run/eula.txt` is committed (EULA accepted).

## Possible later work (not requested)
Several-x speed-up: replace the pre-check `lysh_phase2_density_at` (builds whole column caches)
with a single-cell solid test, and check more heights before the full height scan.
