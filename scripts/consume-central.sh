#!/usr/bin/env bash
# Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
candidate_dir=$(realpath -- "${1:?Usage: consume-central.sh SIGNED_CANDIDATE JDK NEW_WORK_DIR}")
runtime_jdk=$(realpath -- "${2:?Missing runtime JDK}")
work_dir=$(realpath -m -- "${3:?Missing new work directory}")
test ! -e "$work_dir"
case "$work_dir/" in "$repo_dir/"*) echo 'Consumer must be outside the SDK checkout' >&2; exit 1 ;; esac
test -x "$runtime_jdk/bin/java"
# Candidate files are read only as authenticated expectations, never installed.
node "$repo_dir/scripts/release/candidate.mjs" verify "$candidate_dir"
sdk_version=$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1])).version)' "$candidate_dir/candidate.json")
mkdir -p "$work_dir"/{consumer,repository,central-files}
cp -R -- "$repo_dir/examples/offline/pom.xml" "$repo_dir/examples/offline/src" "$work_dir/consumer/"
cat > "$work_dir/settings.xml" <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors><mirror><id>central-only</id><mirrorOf>*</mirrorOf>
    <url>https://repo.maven.apache.org/maven2</url>
  </mirror></mirrors>
</settings>
XML
maven=(mvn -B -C -s "$work_dir/settings.xml" -gs "$work_dir/settings.xml" "-Dmaven.repo.local=$work_dir/repository")
cd -- "$work_dir/consumer"
echo "Central consumer: independent project=$PWD; initially empty cache=$work_dir/repository"
# Keep transfer messages: they prove the POM and JAR were downloaded from Central.
"${maven[@]}" "-Dmagika.version=$sdk_version" clean package 2>&1 | tee "$work_dir/maven-online.log"
node --input-type=module - "$candidate_dir/candidate.json" "$work_dir" <<'JS'
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
const [manifest, work] = process.argv.slice(2);
const candidate = JSON.parse(fs.readFileSync(manifest));
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const base = `https://repo.maven.apache.org/maven2/net/zerocloud/magika/${candidate.version}`;
const downloads = [];
for (const [name, expected] of Object.entries({ ...candidate.artifacts, ...candidate.signatures })) {
  const url = `${base}/${name}`;
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(60000) });
  assert.equal(response.status, 200, `Central download failed: ${name}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  assert.equal(bytes.length, expected.bytes, `Central size differs: ${name}`);
  assert.equal(digest(bytes), expected.sha256, `Central bytes differ: ${name}`);
  fs.writeFileSync(path.join(work, 'central-files', name), bytes);
  downloads.push({ url, status: response.status, bytes: bytes.length, sha256: digest(bytes) });
}
const cache = path.join(work, 'repository/net/zerocloud/magika', candidate.version);
const log = fs.readFileSync(path.join(work, 'maven-online.log'), 'utf8');
const origins = fs.readFileSync(path.join(cache, '_remote.repositories'), 'utf8');
for (const suffix of ['.pom', '.jar']) {
  const name = `magika-${candidate.version}${suffix}`;
  assert.equal(digest(fs.readFileSync(path.join(cache, name))), candidate.artifacts[name].sha256);
  assert.ok(log.includes(`Downloaded from central-only: ${base}/${name}`), `Missing Maven download receipt: ${name}`);
  assert.ok(origins.includes(`${name}>central-only=`), `Missing cache origin: ${name}`);
}
const loadedJar = path.join(work, 'consumer/target/dependency', `magika-${candidate.version}.jar`);
const sdkSha256 = digest(fs.readFileSync(loadedJar));
assert.equal(sdkSha256, candidate.artifacts[path.basename(loadedJar)].sha256);
const receipt = { checkedAt: new Date().toISOString(), coordinate: candidate.coordinate,
  sourceSha: candidate.sourceSha, sdkSha256, candidateInstalled: false, downloads };
fs.writeFileSync(path.join(work, 'central-downloads.json'), JSON.stringify(receipt, null, 2) + '\n');
console.log(JSON.stringify(receipt, null, 2));
JS
"$runtime_jdk/bin/java" -version 2>&1 | tee "$work_dir/runtime.log"
bash "$repo_dir/scripts/verify-offline.sh" "$runtime_jdk" "$work_dir/consumer/target" "$sdk_version" online \
  2>&1 | tee "$work_dir/java-only-online.log"
# The same project/cache also builds offline and then runs with no external network.
"${maven[@]}" -o "-Dmagika.version=$sdk_version" package 2>&1 | tee "$work_dir/maven-offline.log"
bash "$repo_dir/scripts/verify-offline.sh" "$runtime_jdk" "$work_dir/consumer/target" "$sdk_version" \
  2>&1 | tee "$work_dir/java-only-offline.log"
