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

## The ONLY remaining task: the GUI

Convert `app/` (the old swamp-hut Swing GUI, Java 17, built by `app/build-dist.ps1` with plain
javac + jar, C core embedded as `/native/lysh.dll`) into an outpost GUI:

1. JNI: add a `project.NativeOutpost` class + exports in `lysh-c/src/jni_bridge.c` wrapping
   `lysh_outpost_session_open / _band / _free` (outpost.h). Return hits as a flat int array
   (ctr_x, bbox_min_y, ctr_z, piece_min_y, base_y). Follow the existing `NativePhase2.scanOpen/
   scanBand/scanClose` pattern (same file) — bands give the progress bar.
2. GUI (`app/src/main/java/project/LowYSwampHutForFixedSeed.java`, 3143 lines, + `CmdLineRunner`,
   `GameVersion`, `messages_*.properties`): replace swamp wording/logic with outposts.
   Inputs: seed, threads, max Y (bbox bottom; e.g. -40), area (regions or square side),
   **no version picker (26.2 fixed)**. Output rows `/tp x y z` + lowest piece + base, sort by
   Y / distance, export. Keep seed-list tab if cheap. Drop swamp-only bits.
3. Rename product: jar `LowYPillagerOutpost-<ver>.jar`, window title, `AppVersion`.
   `build-dist.ps1` still says LowYSwampHut in names — update. Native lib loader
   (`NativePhase1`) only knows `lysh.dll`; on Linux it needs `liblysh.so`/`lysh.so` for testing.
4. Build a Windows deliverable from Linux: cross-compile the DLL with mingw-w64
   (`tools/jni-win32/jni_md.h` + the Linux JDK's `include/jni.h`), build the jar with javac,
   embed the DLL. A Linux .so build lets you smoke-test the GUI under `xvfb-run`.
5. The owner tests the GUI on Windows. Give them the jar path / how to copy it.

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
./lysh-c/build/lysh outpost --seed -3767261087998042360 --regions 32 --max-y 40
```

Expected first lines of that last command (26.2): a handful of `/tp` lines; the regions-32
dump must still give 134 OK / 385 biome / 306 excl / 3271 freq.

## Notes

- the owner's prefs: lazy/minimal code, keep Windows user steps to copy-paste commands; the owner does
  the in-game testing; use mcsrc.dev (or Vineflower locally) for Minecraft source.
- Windows paths from the old PC (not on the server): `C:\CODE\LowYPillagerOutpost` (this repo),
  `C:\CODE\_mcdata` (scratch: decompiled 26.3 source, cubiomes tables), MSYS2 at `C:\msys64`.
