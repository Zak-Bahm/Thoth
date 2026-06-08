#!/usr/bin/env bash
#
# Builds the desktop (glibc x86_64) native libraries needed to read ZIM archives in-process
# from the JVM, and stages them into :desktop's resources. No sidecar processes.
#
# It produces two files in desktop/src/main/resources/native/linux-x86_64/:
#   libzim.so.9          — prebuilt desktop libzim (from openzim.org; deps statically bundled)
#   libzim_wrapper.so    — the org.kiwix.libzim JNI wrapper, compiled here from the
#                          third_party/java-libkiwix submodule against the desktop libzim
#
# We only build the libzim half of java-libkiwix: ZimRepository/DesktopZimRepository use
# org.kiwix.libzim.* exclusively (libkiwix is Android-only and unused here).
#
# Requirements: a C++ toolchain (g++), JDK (for javac -h + jni.h), curl, tar.
set -euo pipefail

LIBZIM_VER="${LIBZIM_VER:-9.7.0}"      # keep in sync with third_party/java-libkiwix lib/build.gradle
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
JLK="$ROOT/third_party/java-libkiwix"
CPP="$JLK/lib/src/main/cpp"
JAVA_SRC="$JLK/lib/src/main/java"
OUT="$HERE/src/main/resources/native/linux-x86_64"
WORK="$HERE/build/zim-native"

JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
echo ">> JDK: $JDK_HOME"
[ -f "$JDK_HOME/include/jni.h" ] || { echo "ERROR: jni.h not found under $JDK_HOME/include"; exit 1; }
[ -d "$JLK/lib" ] || { echo "ERROR: submodule missing — run: git submodule update --init"; exit 1; }

rm -rf "$WORK"; mkdir -p "$WORK" "$OUT"

echo ">> Downloading desktop libzim $LIBZIM_VER"
curl -fsSL --retry 3 -o "$WORK/libzim.tar.gz" \
  "https://download.openzim.org/release/libzim/libzim_linux-x86_64-${LIBZIM_VER}.tar.gz"
tar -xzf "$WORK/libzim.tar.gz" -C "$WORK"
ZDIR="$(find "$WORK" -maxdepth 1 -type d -name 'libzim_linux-x86_64-*' | head -1)"
ZIM_INC="$ZDIR/include"
ZIM_LIB="$ZDIR/lib/x86_64-linux-gnu"
echo ">> libzim headers: $ZIM_INC"

echo ">> Generating JNI headers (javac -h) for org.kiwix.libzim"
mkdir -p "$WORK/classes" "$WORK/javah"
"$JDK_HOME/bin/javac" -h "$WORK/javah" -d "$WORK/classes" "$JAVA_SRC"/org/kiwix/libzim/*.java

echo ">> Compiling libzim_wrapper.so"
g++ -shared -fPIC -std=c++17 -w \
  -I"$JDK_HOME/include" -I"$JDK_HOME/include/linux" \
  -I"$CPP" -I"$WORK/javah" -I"$ZIM_INC" \
  "$CPP"/libzim/*.cpp \
  -L"$ZIM_LIB" -lzim -Wl,-rpath,'$ORIGIN' \
  -o "$WORK/libzim_wrapper.so"

echo ">> Staging into $OUT"
# Bundle the real libzim under its SONAME (libzim.so.9) — what the wrapper NEEDs at runtime —
# plus the wrapper. Both get extracted side-by-side at startup so $ORIGIN rpath resolves.
REAL_SO="$(readlink -f "$ZIM_LIB/libzim.so")"
cp -f "$REAL_SO" "$OUT/libzim.so.9"
cp -f "$WORK/libzim_wrapper.so" "$OUT/libzim_wrapper.so"
chmod +x "$OUT"/*.so*

echo ">> Done. Staged:"
ls -l "$OUT"
echo ">> Dependency check (should report no 'not found'):"
( cd "$OUT" && ldd ./libzim_wrapper.so | grep -i "not found" && echo "  !! missing deps above" || echo "  OK — all resolve" )
