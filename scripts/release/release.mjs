// Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { bundleFileNames, command, digest, readJson, repositoryPath, sign, verifySigned, writeChecksums, writeJson } from './candidate.mjs';
import { publish, Services } from './publication.mjs';

export function selectCandidate(matrix, output) {
  let selected;
  for (const runtime of ['8', '17', '21']) {
    const dir = path.join(matrix, `candidate-java-${runtime}`);
    const candidate = readJson(path.join(dir, 'candidate.json'));
    if (selected) assert.deepEqual(candidate, selected, `Java ${runtime} built different candidate bytes/source`);
    selected = candidate;
    for (const [name, value] of Object.entries(candidate.artifacts)) {
      assert.equal(digest(fs.readFileSync(path.join(dir, name))), value.sha256);
    }
  }
  fs.cpSync(path.join(matrix, 'candidate-java-21'), output, { recursive: true, errorOnExist: true, force: false });
  return selected;
}

export async function restore(dir, services, fingerprint) {
  const candidate = readJson(path.join(dir, 'candidate.json'));
  const record = await services.record(candidate.version);
  if (!record) return false;
  const previous = record.state.candidate;
  for (const field of ['version', 'sourceSha', 'contentSha256']) {
    assert.equal(previous[field], candidate[field], `Release conflict: restored ${field}`);
  }
  assert.equal(previous.signingFingerprint, fingerprint, 'Release conflict: signing fingerprint');
  const stored = await services.storedBlob(candidate.version, 'candidate.json');
  assert.deepEqual(JSON.parse(stored), previous);
  const bundle = await services.storedBlob(candidate.version, 'central-bundle.zip');
  assert.equal(digest(bundle), previous.bundleSha256, 'Stored bundle digest mismatch');
  fs.writeFileSync(path.join(dir, 'central-bundle.zip'), bundle);
  fs.writeFileSync(path.join(dir, 'signer.asc'), await services.storedBlob(candidate.version, 'signer.asc'));
  for (const name of bundleFileNames(candidate.version)) {
    fs.writeFileSync(path.join(dir, name), command('unzip', ['-p', path.join(dir, 'central-bundle.zip'), `${repositoryPath(candidate.version)}/${name}`]));
  }
  writeJson(path.join(dir, 'candidate.json'), previous);
  verifySigned(dir, fingerprint);
  writeChecksums(dir, previous);
  return true;
}

export async function main([operation, ...args]) {
  if (operation === 'select') { selectCandidate(path.resolve(args[0]), path.resolve(args[1])); return; }
  const dir = path.resolve(args[0]);
  const fingerprint = process.env.MAVEN_GPG_FINGERPRINT;
  const services = new Services({ githubToken: process.env.GH_TOKEN,
    centralUsername: process.env.CENTRAL_USERNAME, centralPassword: process.env.CENTRAL_PASSWORD });
  assert.ok(process.env.GH_TOKEN, 'Missing GH_TOKEN');
  if (operation === 'prepare') {
    if (await restore(dir, services, fingerprint)) console.log('Reusing the exact signed candidate from the durable release ledger');
    else sign(dir, fingerprint, process.env.MAVEN_GPG_PRIVATE_KEY, process.env.MAVEN_GPG_PASSPHRASE);
  } else if (operation === 'publish') {
    assert.ok(process.env.CENTRAL_USERNAME && process.env.CENTRAL_PASSWORD, 'Missing Central token');
    const candidate = verifySigned(dir, fingerprint);
    await publish(candidate, fs.readFileSync(path.join(dir, 'central-bundle.zip')),
      fs.readFileSync(path.join(dir, 'signer.asc')), services, process.env.RECOVERY_DEPLOYMENT_ID || undefined);
  } else throw new Error('Usage: release.mjs select MATRIX OUT | prepare CANDIDATE | publish CANDIDATE');
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
