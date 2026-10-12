#!/bin/sh
# Build dist/LowYPillagerOutpost-<version>.jar on Linux (Windows: build.bat).
# The jar embeds the Windows core (native/lysh.dll, cross-compiled with mingw-w64)
# and the Linux core (native/lysh.so), so the same jar runs on both.
# Needs: cmake ninja gcc mingw-w64 and a JDK (17+).
set -eu
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
VERSION=$(sed -n 's/.*String VERSION = "\([^"]*\)".*/\1/p' app/src/main/java/project/AppVersion.java)
JAR=dist/LowYPillagerOutpost-$VERSION.jar
B=_scratch/dist-build

echo "==> C core (Linux)"
cmake -S lysh-c -B lysh-c/build -G Ninja -DJAVA_HOME="$JAVA_HOME" >/dev/null
cmake --build lysh-c/build >/dev/null
echo "==> C core (Windows, mingw-w64)"
cmake -S lysh-c -B lysh-c/build-win -G Ninja -DJAVA_HOME="$JAVA_HOME" \
      -DCMAKE_TOOLCHAIN_FILE="$PWD/lysh-c/mingw-w64.cmake" >/dev/null
cmake --build lysh-c/build-win >/dev/null 2>&1 || cmake --build lysh-c/build-win

echo "==> Java ($VERSION)"
rm -rf "$B" && mkdir -p "$B/classes/native" dist
javac --release 17 -encoding UTF-8 -nowarn -d "$B/classes" $(find app/src/main/java -name '*.java')
cp -r app/src/main/resources/. "$B/classes/"
cp LICENSE "$B/classes/LICENSE.txt"
cp lysh-c/build-win/lysh.dll lysh-c/build/lysh.so "$B/classes/native/"
printf 'Manifest-Version: 1.0\nMain-Class: project.Launcher\nEnable-Native-Access: ALL-UNNAMED\n' > "$B/MANIFEST.MF"
rm -f dist/LowYPillagerOutpost-*.jar
jar --create --file "$JAR" --manifest "$B/MANIFEST.MF" -C "$B/classes" .
cp lysh-c/build-win/lysh.exe dist/
printf '@echo off\r\nREM Opens the GUI; arguments are passed on (e.g. --help for command line mode).\r\njava -jar "%%~dp0%s" %%*\r\nif errorlevel 1 pause\r\n' \
    "$(basename "$JAR")" > dist/run.bat
echo "==> $JAR ($(du -h "$JAR" | cut -f1))"
