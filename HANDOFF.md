# HANDOFF — LowYPillagerOutpost

For the next Claude session (on the owner's headless Ubuntu home server). Read this first.

## Goal

Tool that finds the lowest-spawning pillager outposts for a seed, **Minecraft 26.2 only**.
Lowest = bottom of the outpost's full bounding box (lowest piece - 12). Separate tool for
now; keep outpost code in its own files so it can later merge into the swamp hut finder
(upstream https://github.com/M33Galaxy/LowYSwampHut, history kept; this repo: github.com/friedsalad21/LowYPillagerOutpost, private).

## State: C core DONE and verified — do not redo

- `lysh-c/src/outpost.[ch]`, CLI `lysh outpost ...` (see README). Default version 26.2.
- Verified bit-exact against Minecraft (26.2 and 26.3): 4096 candidates, 61 deep outposts,
  200k biome samples. **No other versions need checking** (owner: only 26.2 matters).
- Biome lookup for 26.2/26.3 uses trees dumped from the game (`biome_tree_262mc.h`,
  `biome_tree_263.h`) + a port of `Climate.RTree.search` in `biome.c`.
- Real run: seed -3767261087998042360, 2M x 2M blocks, max-y -40 -> 8 hits in 310 s
  (`results/`).

## GUI: built and merged (PR #1); test release v1.0.0-test, Windows test pending

- C: `jni_bridge.c` exports `project.NativeOutpost.open/band/close/coreVersion` (26.2 fixed).
- Java (`app/`): new `OutpostGui` (Single Seed + Seed List tabs), `OutpostSearch` (band loop,
  shared with the jar CLI), `NativeOutpost`, compact `CmdLineRunner`. The swamp-hut GUI/CLI
  classes were removed (they live on upstream). `NativePhase1` is just the library loader now.
- Build on Linux: `./build-dist.sh` -> `dist/LowYPillagerOutpost-<ver>.jar` with both
  `native/lysh.dll` (mingw-w64 cross build, `lysh-c/mingw-w64.cmake`) and `native/lysh.so`,
  plus `dist/lysh.exe` and `dist/run.bat`. `app/build-dist.ps1` / `build.bat` still work on Windows.
- Tested on the server: jar results identical to the C CLI (regions 32, max-y 50: 26 hits);
  GUI driven under `xvfb-run` (both tabs, pause/resume/stop). The Windows DLL can't be run here
  (no Wine), so the Windows test is the owner's.
- **The home server CPU is weak (i3, 4 threads): test with small areas only** (e.g. square side
  64 regions, under a second). Big searches are for the owner's PC.

Optional later (not requested): ~several-x speed-up by replacing the pre-check
`lysh_phase2_density_at` (builds whole column caches) with a single-cell solid test, plus
checking more heights before the full height scan. Was started once and reverted.

## Verify after any C change

```bash
./lysh-c/build/lysh outpost --seed -3767261087998042360 --regions 32 --version 26.2 --dump > c.txt
# oracle: (cd tools/oracle-26.2 && ./gradlew runServer --args=nogui) -> run/oracle.txt, then diff
```
The oracle writes `run/oracle.txt` (candidates in regions -32..31; or the chunks listed in
`run/oracle-list.txt`), `run/biome-tree.txt`, and `run/biome-oracle.txt` if
`run/biome-points.txt` exists. Compare columns cx cz status h ctr pieceMin bboxMin.
`run/eula.txt` (eula=true) is committed — the owner approved the Minecraft EULA.

## Home server setup (Ubuntu, headless)

```bash
sudo apt update
sudo apt install -y git gh build-essential cmake ninja-build python3 openjdk-21-jdk mingw-w64 xvfb
# Java 25 is needed only for the oracle (Minecraft 26.2 dev server):
sudo apt install -y openjdk-25-jdk   # if missing: use Adoptium Temurin 25
```

Get the project (private GitHub repo; needs `gh auth login` first):

```bash
gh repo clone friedsalad21/LowYPillagerOutpost
cd LowYPillagerOutpost
cmake -S lysh-c -B lysh-c/build -G Ninja -DJAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
cmake --build lysh-c/build
./lysh-c/build/lysh outpost --seed -3767261087998042360 --regions 32 --max-y 50
```

Expected (26.2): 26 `/tp` lines, lowest `/tp 5304 46 -8456` (max-y 40 gives none in this small
area). The regions-32 `--dump` must still give 134 OK / 385 biome / 306 excl / 3271 freq.

## Notes

- The owner's prefs: lazy/minimal code, keep Windows user steps to copy-paste commands; the owner does
  the in-game testing; use mcsrc.dev (or Vineflower locally) for Minecraft source.
- Windows paths from the old PC (not on the server): `C:\CODE\LowYPillagerOutpost` (this repo),
  `C:\CODE\_mcdata` (scratch: decompiled 26.3 source, cubiomes tables), MSYS2 at `C:\msys64`.
