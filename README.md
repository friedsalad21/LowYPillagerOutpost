# LowYPillagerOutpost

## ⬇️ [Download LowYPillagerOutpost-1.0.0.jar](https://github.com/friedsalad21/LowYPillagerOutpost/releases/download/v1.0.0-test/LowYPillagerOutpost-1.0.0.jar)

Needs Java 17+ ([how to install](#2-install-java)). Then double-click the jar.
All versions: [Releases page](https://github.com/friedsalad21/LowYPillagerOutpost/releases).

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

## Requirements

### To run it

#### 1. Get the jar

 On this repo's
[Releases page](https://github.com/friedsalad21/LowYPillagerOutpost/releases), open the newest
release and download `LowYPillagerOutpost-<version>.jar` under **Assets**.

#### 2. Install Java

**Java 17 or newer** (free). If you already have it, skip this: type `java -version`
in Command Prompt / a terminal, and any number 17 or higher is fine. The Java that comes with
the Minecraft launcher does **not** count.

*Windows:*
1. Go to [adoptium.net/temurin/releases](https://adoptium.net/temurin/releases/?os=windows&arch=x64&package=jre&version=21)
   (Windows, x64, JRE, version 21 are pre-selected).
2. Download the **.msi** file and run it.
3. On the "Custom Setup" screen, make sure **Add to PATH** and **Associate .jar** are both set
   to install, so double-clicking the jar works. Then click Next / Install.

Or in Command Prompt: `winget install EclipseAdoptium.Temurin.21.JRE`

*Linux:*
- Ubuntu / Debian / Mint: `sudo apt install openjdk-21-jre`
- Fedora: `sudo dnf install java-21-openjdk`
- Arch: `sudo pacman -S jre21-openjdk`

#### 3. Run it

 Double-click the jar. If that opens it like a zip file or nothing happens, run
`java -jar LowYPillagerOutpost-<version>.jar` in the folder where the jar is.

**System requirements:** 64-bit Windows or Linux (no macOS, no ARM), a CPU with AVX2 (any
Intel/AMD from about 2015 on), and on Linux glibc 2.34+ (Ubuntu 22.04+, Debian 12+, Fedora 35+).
Nothing else: the search engine is inside the jar.

### To build it from source

**On Linux** (builds both the Windows and the Linux version):

```bash
sudo apt install build-essential cmake ninja-build mingw-w64 openjdk-21-jdk
./build-dist.sh        # -> dist/LowYPillagerOutpost-<version>.jar
```

**On Windows**: a JDK 17+ (e.g. Temurin), [CMake](https://cmake.org), and
[MSYS2](https://www.msys2.org) with `pacman -S mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-ninja`.
Then double-click `build.bat` (output in `dist\`).

**Optional, only to re-verify against Minecraft**: Java 25 for the Fabric oracle in
`tools/oracle-26.2` (Gradle downloads the rest itself).

## Run a search (GUI)

Double-click `LowYPillagerOutpost-<version>.jar` (or
`java -jar LowYPillagerOutpost-<version>.jar`). The C core is inside the jar (Windows x64 and
Linux x64).

- **Single Seed**: seed (number or text), threads, max Y (bounding-box bottom), and the area as
  a square side or a MinX/MaxX/MinZ/MaxZ rectangle in regions (512 blocks; bounds inclusive).
  Default area is 3908 x 3908 regions = 2M x 2M blocks.
- **Seed List**: same, for every seed in a text file (one per line, `#` = comment).
- Results: sort by Y or distance (or click a column header), double-click a row to copy the
  `/tp`, export to a text file. Pause / Stop work between bands.

The jar also has a command-line mode: `java -jar LowYPillagerOutpost-<version>.jar --help`.

## Run a search (C command line)

Build once (needs the build tools above), then:

```bash
cmake -S lysh-c -B lysh-c/build -G Ninja && cmake --build lysh-c/build   # -> lysh-c/build/lysh
```


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
| `lysh-c/` | C core (CMake). `src/outpost.*` = outpost search; rest = terrain engine from LowYSwampHut (engine notes, in Chinese: `lysh-c/README.md`) |
| `app/` | Java GUI + jar command line (`OutpostGui`, `OutpostSearch`, `NativeOutpost`) |
| `tools/oracle-26.2/` | in-game ground truth (Fabric dev server mod) |
| `tools/biome-tree/` | turns an oracle biome-tree dump into a C header |
| `tools/jni-win32/` | `jni_md.h` for cross-compiling the Windows DLL from Linux |
| `results/` | saved search results |

## Credits

Terrain engine and original tool: M33Galaxy, SunnySlopes (MIT). Biome trees for older
versions: cubiomes (MIT). Outpost search: this fork.
