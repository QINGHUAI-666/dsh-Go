#!/usr/bin/env bash
# Refreshes the Droidspaces binaries and boot-module scripts bundled by dsh-go.
#
# The APK ships the unmodified upstream static binaries as jniLibs (so Android
# extracts them executable, the same trick runtime/build-runtime.sh uses for
# proot) and the upstream boot-module scripts as Android assets. The module id
# and the app_data_file SELinux rules are dsh-go's; the rest is upstream.
#
#   tooling/droidspaces/fetch.sh            # download, verify, replace
#   tooling/droidspaces/fetch.sh --check    # verify the files already in the tree
#
# Updating VERSION requires updating APK_SHA256, the per-ABI hashes and the
# version/notice in THIRD_PARTY_NOTICES.md.
set -euo pipefail

VERSION="7.2.0"
APK_NAME="Droidspaces-universal-v7.2.0-cdadb9eb.apk"
APK_URL="https://github.com/ravindu644/Droidspaces-OSS/releases/download/v$VERSION/$APK_NAME"
APK_SHA256="b1499be2448641ffd6fc7305fd2f60fb5266fef4845c39263714f6e74568d1d2"

ROOT=$(CDPATH= cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
JNI="$ROOT/app/android/app/src/main/jniLibs"
ASSETS="$ROOT/app/android/app/src/main/assets/droidspaces"
declare -A SHA=(
  [arm64-v8a/libdroidspaces.so]="54dac6cc9cdc18a9cc61eeaaf7587c25dadd65e20e544a4f470e4b77c9e5db07"
  [x86_64/libdroidspaces.so]="824392aaa34a86eaa740693ebbbe13ba2ff6d4d308eeb72e5e07529d4effd521"
  [arm64-v8a/libbusybox.so]="cf48c0cffec3dcfba6298ca14a8b41ff6e18eb7419e76c882168ea08f03c5026"
  [x86_64/libbusybox.so]="16bd2c480dc09dfaa51f2c1d0129bbc889e595266987ae55efafe6191aeedae1"
)

die() { printf 'droidspaces: %s\n' "$*" >&2; exit 1; }

check() {
  for rel in "${!SHA[@]}"; do
    local file="$JNI/$rel"
    [[ -f "$file" ]] || die "missing $file (run tooling/droidspaces/fetch.sh)"
    local got
    got=$(sha256sum "$file" | cut -d' ' -f1)
    [[ "$got" == "${SHA[$rel]}" ]] || die "sha256 mismatch for $file: $got"
    printf 'ok  %s  %s\n' "${SHA[$rel]:0:12}" "$file"
  done
  for file in droidspaces_autoboot.sh post-fs-data.sh service.sh; do
    [[ -f "$ASSETS/$file" ]] || die "missing $ASSETS/$file"
  done
}

if [[ "${1:-}" == "--check" ]]; then
  check
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fL --retry 3 -o "$tmp/$APK_NAME" "$APK_URL"
echo "$APK_SHA256  $tmp/$APK_NAME" | sha256sum -c -
unzip -q "$tmp/$APK_NAME" 'assets/binaries/*' 'assets/boot-module/*' -d "$tmp/apk"

for abi in arm64-v8a x86_64; do
  case "$abi" in
    arm64-v8a) upstream=aarch64 ;;
    x86_64) upstream=x86_64 ;;
  esac
  mkdir -p "$JNI/$abi"
  cp "$tmp/apk/assets/binaries/droidspaces-$upstream" "$JNI/$abi/libdroidspaces.so"
  cp "$tmp/apk/assets/binaries/busybox-$upstream" "$JNI/$abi/libbusybox.so"
  chmod 755 "$JNI/$abi/libdroidspaces.so" "$JNI/$abi/libbusybox.so"
  for rel in "libdroidspaces.so" "libbusybox.so"; do
    got=$(sha256sum "$JNI/$abi/$rel" | cut -d' ' -f1)
    [[ "$got" == "${SHA[$abi/$rel]}" ]] || die "sha256 mismatch for $abi/$rel: $got"
    printf 'installed %s/%s (%s)\n' "$abi" "$rel" "$VERSION"
  done
done

mkdir -p "$ASSETS"
for file in droidspaces_autoboot.sh post-fs-data.sh service.sh; do
  cp "$tmp/apk/assets/boot-module/$file" "$ASSETS/$file"
  chmod 644 "$ASSETS/$file"
done
printf 'updated boot-module scripts in %s (module.prop and sepolicy.rule are dsh-go files)\n' "$ASSETS"
