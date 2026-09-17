#!/usr/bin/env bash
# Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0
set -euo pipefail
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
output=${1:?Usage: bash benchmarks/run.sh NEW_OUTPUT_DIRECTORY [all|path|stream|batch|shared|resources]}
scenario=${2:-all}
case "$scenario" in all|path|stream|batch|shared|resources) ;; *) echo "Unknown scenario: $scenario" >&2; exit 2;; esac
test ! -e "$output" || { echo "Output already exists: $output" >&2; exit 2; }
mkdir -p -- "$output"
output=$(cd -- "$output" && pwd)
runtime=${BENCH_JAVA_HOME:-${JAVA_HOME:?Set JAVA_HOME or BENCH_JAVA_HOME}}
classpath="$repository/benchmarks/target/identification-1.0.0.jar:$repository/benchmarks/target/dependency/*"
# A fresh JVM per scenario; run without concurrent builds/tests or other benchmark runs.
vm=(-Xms128m -Xmx128m -XX:+AlwaysPreTouch -XX:+UseSerialGC -XX:ActiveProcessorCount=4)
warmup=${WARMUP_ROUNDS:-5}
rounds=${MEASUREMENT_ROUNDS:-10}
repeats=${CORPUS_REPEATS:-10}
batch=${BATCH_SIZE:-32}
threads=${INTRA_OP_THREADS:-1}
callers=${CALLERS:-4}
cd -- "$repository"
test -f benchmarks/target/identification-1.0.0.jar
# Refuse a stale installed dependency relative to the current packaged SDK.
cmp target/magika-0.1.0.jar benchmarks/target/dependency/magika-0.1.0.jar
{
  date --utc --iso-8601=seconds
  id
  cat /etc/os-release
  uname -a
  lscpu
  free -b
  df -T "$repository"
  "$runtime/bin/java" -version
  git rev-parse HEAD
  git status --short
  for file in /sys/fs/cgroup/cpu.max /sys/fs/cgroup/memory.max /sys/fs/cgroup/cpuset.cpus.effective; do
    if test -r "$file"; then echo "$file"; cat "$file"; fi
  done
} > "$output/environment.txt" 2>&1
sha256sum target/magika-0.1.0.jar benchmarks/target/identification-1.0.0.jar benchmarks/target/dependency/*.jar > "$output/artifacts.sha256"
{
  git ls-files -z -- src/main pom.xml
  find benchmarks/src -type f -print0
  printf '%s\0' benchmarks/pom.xml benchmarks/run.sh
} | sort -zu | xargs -0 sha256sum > "$output/sources.sha256"
record_command() {
  printf '%q' "$1"
  shift
  printf ' %q' "$@"
  printf '\n'
}
record_command bash benchmarks/run.sh "$output" "$scenario" > "$output/commands.txt"
for selected in path stream batch shared resources; do
  if test "$scenario" != all && test "$scenario" != "$selected"; then continue; fi
  if test "$selected" = resources; then
    main=net.zerocloud.benchmarks.ResourceMeasurement
    args=("$repository" "$output/$selected" "${RESOURCE_WARMUP:-30}" "${RESOURCE_PHASES:-8}" "${RESOURCE_CYCLES:-25}")
  else
    main=net.zerocloud.benchmarks.IdentificationBenchmark
    args=("$repository" "$output/$selected" "$selected" "$warmup" "$rounds" "$repeats" "$batch" "$threads" "$callers")
  fi
  command=("$runtime/bin/java" "${vm[@]}" -cp "$classpath" "$main" "${args[@]}")
  record_command "${command[@]}" >> "$output/commands.txt"
  "${command[@]}" > "$output/$selected.log" 2>&1
  echo "Completed $selected"
done
echo "Evidence: $output"
