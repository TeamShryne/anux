#!/usr/bin/env bash
# Fetch Termux proot + runtime libs for all Android ABIs into
# app/src/main/assets/proot/<abi>/. Idempotent: skips ABIs already present.
# Runs automatically via Gradle (fetchProotBinaries) and in CI (cached).
set -euo pipefail

PROOT_VER="5.1.107.92"
TALLOC_VER="2.4.3"
SHMEM_VER="0.7"
BASE="https://packages.termux.dev/apt/termux-main/pool/main"
OUT="app/src/main/assets/proot"

declare -A MAP=( ["arm64-v8a"]="aarch64" ["armeabi-v7a"]="arm" ["x86"]="i686" ["x86_64"]="x86_64" )

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for ABI in "${!MAP[@]}"; do
  T="${MAP[$ABI]}"
  D="$OUT/$ABI"
  if [[ -f "$D/proot" && -f "$D/lib/libtalloc.so.2" && -f "$D/lib/libandroid-shmem.so" ]]; then
    echo "proot $ABI: cached"
    continue
  fi
  echo "proot $ABI: downloading"
  mkdir -p "$D/lib" "$WORK/$ABI"
  curl -sSFL -o "$WORK/$ABI/proot.deb" "$BASE/p/proot/proot_${PROOT_VER}_${T}.deb"
  curl -sSFL -o "$WORK/$ABI/talloc.deb" "$BASE/libt/libtalloc/libtalloc_${TALLOC_VER}_${T}.deb"
  curl -sSFL -o "$WORK/$ABI/shmem.deb" "$BASE/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VER}_${T}.deb"
  for pkg in proot talloc shmem; do
    ar p "$WORK/$ABI/$pkg.deb" data.tar.xz | tar -xJ -C "$WORK/$ABI"
  done
  PREFIX="$WORK/$ABI/data/data/com.termux/files/usr"
  cp -f "$PREFIX/bin/proot" "$D/proot"
  cp -fL "$PREFIX/lib/libtalloc.so.2" "$D/lib/libtalloc.so.2"
  cp -fL "$PREFIX/lib/libandroid-shmem.so" "$D/lib/libandroid-shmem.so"
  chmod 755 "$D/proot"
  echo "proot $ABI: ok"
done
