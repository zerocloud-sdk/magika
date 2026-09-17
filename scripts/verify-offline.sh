#!/usr/bin/env bash
# Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0
# Ubuntu 24.04 x64 acceptance: a fresh network namespace and a Java-only chroot.
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
runtime_jdk=${1:?Usage: scripts/verify-offline.sh /absolute/path/to/runtime-jdk}
runtime_jdk=$(readlink -f -- "$runtime_jdk")
consumer_dir=${2:-"$repo_dir/examples/offline/target"}
sdk_version=${3:-0.1.0}
network_mode=${4:-offline}
case "$network_mode" in online|offline) ;; *) echo 'Expected online or offline network mode' >&2; exit 1 ;; esac
test -x "$runtime_jdk/bin/java"
test -f "$consumer_dir/offline-byte-array-1.0.0.jar"
test -f "$consumer_dir/dependency/magika-$sdk_version.jar"
sdk_digest=$(sha256sum "$consumer_dir/dependency/magika-$sdk_version.jar" | cut -d ' ' -f 1)
for required in rsync unzip ldd unshare chroot ip; do
  command -v "$required" >/dev/null
done
privilege=()
if [[ $(id -u) != 0 ]]; then
  privilege=(sudo -n)
fi
isolation_dir=$(mktemp -d "${TMPDIR:-/tmp}/magika-offline.XXXXXXXX")
cleanup() { "${privilege[@]}" rm -rf -- "$isolation_dir"; }
trap cleanup EXIT
isolation_root="$isolation_dir/root"
mkdir -p "$isolation_root"/{jdk/bin,app,dev,tmp} "$isolation_dir/native"
chmod 1777 "$isolation_root/tmp"
cp -L -- "$runtime_jdk/bin/java" "$isolation_root/jdk/bin/java"
# A Java runtime filesystem: no Maven, Python, shell, or host filesystem bind.
for directory in lib jre conf; do
  if [[ -d "$runtime_jdk/$directory" ]]; then
    rsync -rL --exclude=src.zip --exclude=*.jmod -- "$runtime_jdk/$directory" "$isolation_root/jdk/"
  fi
done
cp -- "$consumer_dir/offline-byte-array-1.0.0.jar" "$consumer_dir"/dependency/*.jar "$isolation_root/app/"
unzip -q "$consumer_dir/dependency/onnxruntime-1.30.0.jar" 'ai/onnxruntime/native/linux-x64/*' -d "$isolation_dir/native"
# Include dynamic loader and native dependencies, at their absolute loader paths.
while IFS= read -r -d '' binary; do
  while IFS= read -r library; do
    [[ "$library" == "$isolation_dir/"* ]] && continue
    cp -L --parents -- "$library" "$isolation_root"
  done < <(ldd "$binary" 2>/dev/null | awk '$1 ~ /^\// {print $1} $2 == "=>" && $3 ~ /^\// {print $3}')
done < <(find "$isolation_root/jdk" "$isolation_dir/native" -type f \( -name '*.so' -o -name '*.so.*' -o -name java \) -print0)
"${privilege[@]}" mknod -m 666 "$isolation_root/dev/null" c 1 3
"${privilege[@]}" mknod -m 666 "$isolation_root/dev/random" c 1 8
"${privilege[@]}" mknod -m 666 "$isolation_root/dev/urandom" c 1 9
echo "Packaged Java-only consumer: runtime=$runtime_jdk; network=$network_mode"
namespace=()
if [[ "$network_mode" == offline ]]; then
  namespace=(unshare --net --)
fi
"${privilege[@]}" "${namespace[@]}" bash -c '
  set -euo pipefail
  check=--check-java-only
  if [[ "$3" == offline ]]; then
    ip link set lo up
    check=--check-isolated
  fi
  exec env -i PATH=/jdk/bin LANG=C \
    LD_LIBRARY_PATH=/jdk/lib:/jdk/lib/jli:/jdk/lib/amd64/jli:/jdk/jre/lib/amd64/jli:/jdk/lib/server:/jdk/jre/lib/amd64/server \
    /usr/sbin/chroot "$1" /jdk/bin/java -Xmx128m -Dexpected.sdk.sha256="$2" -cp "/app/*" example.OfflineExample "$check"
' bash "$isolation_root" "$sdk_digest" "$network_mode"
