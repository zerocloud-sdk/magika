#!/usr/bin/env bash
# Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
candidate_dir=$(realpath -- "${1:?Usage: consume-candidate.sh CANDIDATE JDK EMPTY_WORK_DIR}")
runtime_jdk=$(realpath -- "${2:?Missing runtime JDK}")
work_dir=$(realpath -m -- "${3:?Missing empty work directory}")
test ! -e "$work_dir"
node "$repo_dir/scripts/release/candidate.mjs" verify "$candidate_dir"
sdk_version=$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1])).version)' "$candidate_dir/candidate.json")
mkdir -p "$work_dir"/{consumer,repository}
cp -R -- "$repo_dir/examples/offline/pom.xml" "$repo_dir/examples/offline/src" "$work_dir/consumer/"
# Override both user and global Maven settings: the existing user cache and mirrors
# cannot supply the SDK or alter the independent consumer's dependency resolution.
cat > "$work_dir/settings.xml" <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>
XML
maven=(mvn -B -ntp -s "$work_dir/settings.xml" -gs "$work_dir/settings.xml" "-Dmaven.repo.local=$work_dir/repository")
cd -- "$work_dir/consumer"
"${maven[@]}" org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  "-Dfile=$candidate_dir/magika-$sdk_version.jar" "-DpomFile=$candidate_dir/magika-$sdk_version.pom" \
  "-Dsources=$candidate_dir/magika-$sdk_version-sources.jar" "-Djavadoc=$candidate_dir/magika-$sdk_version-javadoc.jar"
installed="$work_dir/repository/net/zerocloud/magika/$sdk_version"
for name in "magika-$sdk_version.jar" "magika-$sdk_version-sources.jar" "magika-$sdk_version-javadoc.jar" "magika-$sdk_version.pom"; do
  cmp -- "$candidate_dir/$name" "$installed/$name"
  cp -- "$candidate_dir/$name.asc" "$installed/$name.asc"
done
"${maven[@]}" "-Dmagika.version=$sdk_version" clean package
cmp -- "$candidate_dir/magika-$sdk_version.jar" "$work_dir/consumer/target/dependency/magika-$sdk_version.jar"
# An offline Maven rebuild proves that the fresh repository is now self-contained.
"${maven[@]}" -o "-Dmagika.version=$sdk_version" package
"$runtime_jdk/bin/java" -version
bash "$repo_dir/scripts/verify-offline.sh" "$runtime_jdk" "$work_dir/consumer/target" "$sdk_version"
sha256sum "$candidate_dir/magika-$sdk_version.jar" "$installed/magika-$sdk_version.jar" \
  "$work_dir/consumer/target/dependency/magika-$sdk_version.jar"
