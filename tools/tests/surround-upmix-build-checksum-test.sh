#!/usr/bin/env sh
# The surround-upmix native build must refuse an FFmpeg tarball whose SHA-256 does not match the pinned value,
# and must do so before extracting anything. Runs offline: FFMPEG_TARBALL points at a local bogus file.

set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH='' cd -- "$script_dir/../.." && pwd)
build_script="$repo_root/surround-upmix/native/build.sh"

grep -E '^FFMPEG_SHA256=[0-9a-f]{64}$' "$build_script" >/dev/null

check_line=$(grep -n 'sha256sum -c' "$build_script" | head -n 1 | cut -d: -f1)
extract_line=$(grep -n 'tar xf' "$build_script" | head -n 1 | cut -d: -f1)
if [ -z "$check_line" ] || [ -z "$extract_line" ] || [ "$check_line" -ge "$extract_line" ]; then
  printf 'build.sh must verify the FFmpeg tarball hash before extracting it\n' >&2
  exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/ndk" "$tmp/work"
printf 'not the ffmpeg release\n' > "$tmp/bogus.tar.xz"

if NDK="$tmp/ndk" WORK="$tmp/work" FFMPEG_TARBALL="$tmp/bogus.tar.xz" \
  bash "$build_script" > "$tmp/out.log" 2>&1; then
  printf 'build.sh accepted a tarball with the wrong SHA-256\n' >&2
  exit 1
fi

grep -F 'SHA-256 mismatch' "$tmp/out.log" >/dev/null
if [ -e "$tmp/work/ffmpeg-7.1.1" ]; then
  printf 'build.sh extracted an unverified tarball\n' >&2
  exit 1
fi

notices="$repo_root/app/src/main/assets/licenses/THIRD-PARTY-NOTICES.txt"
licenses_doc="$repo_root/docs/THIRD-PARTY-LICENSES.md"
grep -F 'libsvac3.so' "$notices" >/dev/null
grep -F 'FFmpeg 7.1.1' "$notices" >/dev/null
grep -F 'libsvac3.so' "$licenses_doc" >/dev/null
