#!/usr/bin/env bash
# Builds libsvac3.so (AC-3 encoder JNI wrapper + a minimal static FFmpeg) for arm64-v8a and armeabi-v7a
# into ../src/main/jniLibs. Runs in Linux/WSL with the distro's clang + lld, using the Android NDK's sysroot
# (works on any host CPU, including ARM64 WSL where Google ships no NDK).
#   NDK=/mnt/c/Users/<you>/AppData/Local/Android/Sdk/ndk/27.1.12297006 ./build.sh
set -euo pipefail

FFMPEG_VERSION=7.1.1
# SHA-256 of https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz, whose .asc signature verifies against the FFmpeg
# release key FCF9 86EA 15E6 E293 A564 4F10 B432 2F04 D676 58D8. Change both lines together.
FFMPEG_SHA256=733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1
API=25  # = minSdk
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HOME/svac3-build}"
: "${NDK:?set NDK to the Android NDK root}"
prebuilts=("$NDK"/toolchains/llvm/prebuilt/*)
PREBUILT=${prebuilts[0]}
SYSROOT="$PREBUILT/sysroot"
builtins_dirs=("$PREBUILT"/lib/clang/*/lib/linux)
BUILTINS_DIR=${builtins_dirs[0]}

mkdir -p "$WORK"
cd "$WORK"
# FFMPEG_TARBALL may point at an already downloaded ffmpeg-$FFMPEG_VERSION.tar.xz.
TARBALL="${FFMPEG_TARBALL:-$WORK/ffmpeg-$FFMPEG_VERSION.tar.xz}"
[ -f "$TARBALL" ] || curl -fsSL -o "$TARBALL" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
# Never build from unverified sources. The tarball is checked on every run, even when an earlier run already
# unpacked it, and the source tree is always re-extracted from it, so a tree left on disk is never trusted.
if ! echo "$FFMPEG_SHA256  $TARBALL" | sha256sum -c --status -; then
  echo "SHA-256 mismatch for $TARBALL (expected $FFMPEG_SHA256); refusing to build" >&2
  if [ -z "${FFMPEG_TARBALL:-}" ]; then
    # This script's own download: delete it so the next run fetches a fresh copy instead of failing forever.
    rm -f "$TARBALL"
  fi
  exit 1
fi
rm -rf "ffmpeg-$FFMPEG_VERSION"
tar xf "$TARBALL"

build_abi() {
  local abi=$1 triple=$2 arch=$3 cpu=$4 builtins=$5 extra_cflags=$6
  # The distro clang links "-lgcc" for Android targets; like the NDK, stand the compiler-rt builtins in for it.
  local rtdir="$WORK/rt/$abi"
  mkdir -p "$rtdir"
  cp "$BUILTINS_DIR/libclang_rt.builtins-$builtins-android.a" "$rtdir/libgcc.a"
  local cc="clang --target=$triple$API --sysroot=$SYSROOT -fPIC -L$rtdir $extra_cflags"
  local prefix="$WORK/out/$abi"
  rm -rf "$WORK/obj/$abi" && mkdir -p "$WORK/obj/$abi" && cd "$WORK/obj/$abi"
  # Only what the AC-3 (float) encoder needs: no programs, no other codecs, formats or filters.
  "$WORK/ffmpeg-$FFMPEG_VERSION/configure" \
    --prefix="$prefix" --target-os=android --arch="$arch" --cpu="$cpu" --enable-cross-compile \
    --cc="$cc" --ld="$cc" --ar=llvm-ar --nm=llvm-nm --ranlib=llvm-ranlib --strip=llvm-strip \
    --extra-ldflags="-fuse-ld=lld" \
    --disable-everything --disable-programs --disable-doc --disable-network --disable-autodetect \
    --disable-avdevice --disable-avformat --disable-swscale --disable-swresample --disable-avfilter \
    --disable-postproc --enable-static --disable-shared --enable-pic \
    --enable-encoder=ac3 > configure.log
  make -j"$(nproc)" > make.log
  make install > /dev/null
  # The JNI wrapper, linked with -Bsymbolic and only JNI symbols exported.
  local out="$HERE/../src/main/jniLibs/$abi"
  mkdir -p "$out"
  $cc -O2 -shared -fuse-ld=lld -nostdlib++ -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL \
    -I"$prefix/include" "$HERE/svac3.c" -o "$out/libsvac3.so" \
    "$prefix/lib/libavcodec.a" "$prefix/lib/libavutil.a" \
    "$BUILTINS_DIR/libclang_rt.builtins-$builtins-android.a" -lm -llog
  llvm-strip --strip-unneeded "$out/libsvac3.so"
  echo "built $out/libsvac3.so ($(stat -c %s "$out/libsvac3.so") bytes)"
  cd "$WORK"
}

build_abi arm64-v8a aarch64-linux-android aarch64 armv8-a aarch64 ""
build_abi armeabi-v7a armv7a-linux-androideabi arm armv7-a arm "-mfloat-abi=softfp -mfpu=neon"
