// Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0
// An ephemeral test key exercises the verifier. It is NEVER release evidence.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { artifactNames, command, digest, inspectArtifacts, readJson, root, sign, verifySigned, writeJson } from './candidate.mjs';
import { selectCandidate } from './release.mjs';

test('actual packaged distribution: four signatures, independent fingerprint, and tamper rejection', t => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'magika-signature-test-'));
  t.after(() => { command('gpgconf', ['--homedir', path.join(temp, 'keys'), '--kill', 'all']); fs.rmSync(temp, { recursive: true, force: true }); });
  const home = path.join(temp, 'keys'); fs.mkdirSync(home, { mode: 0o700 });
  const gpg = args => command('gpg', ['--homedir', home, '--batch', '--pinentry-mode', 'loopback', '--passphrase', '', ...args]);
  gpg(['--quick-generate-key', 'Magika verifier test <fixture@invalid>', 'ed25519', 'sign', '1d']);
  const fingerprint = gpg(['--with-colons', '--list-secret-keys']).toString().split('\n').find(line => line.startsWith('fpr:')).split(':')[9];
  const key = gpg(['--armor', '--export-secret-keys', fingerprint]).toString();
  const dir = path.join(temp, 'candidate'); fs.mkdirSync(dir);
  const v = fs.readFileSync(path.join(root, 'pom.xml'), 'utf8').match(/<version>([^<]+)<\/version>/)[1];
  for (const name of artifactNames(v)) fs.copyFileSync(path.join(root, name.endsWith('.pom') ? 'pom.xml' : `target/${name}`), path.join(dir, name));
  const checked = inspectArtifacts(dir, v);
  const candidate = { schema: 1, version: v, coordinate: `net.zerocloud:magika:${v}`, sourceSha: command('git', ['rev-parse', 'HEAD'], { cwd: root }).toString().trim(),
    ...checked, contentSha256: digest(JSON.stringify(checked.artifacts)) };
  writeJson(path.join(dir, 'candidate.json'), candidate);
  sign(dir, fingerprint, key, '');
  const signed = verifySigned(dir, fingerprint);
  assert.equal(Object.keys(signed.signatures).length, 4);
  assert.throws(() => verifySigned(dir, 'F'.repeat(40)), /signingFingerprint|Expected values/);
  // Update the sidecar's hash to defeat a checksum-only verifier; a valid signature
  // of a DIFFERENT artifact must still fail the real OpenPGP check.
  const signature = path.join(dir, `magika-${v}.jar.asc`);
  fs.copyFileSync(path.join(dir, `magika-${v}.pom.asc`), signature);
  const tampered = readJson(path.join(dir, 'candidate.json'));
  tampered.signatures[`magika-${v}.jar.asc`] = { bytes: fs.statSync(signature).size, sha256: digest(fs.readFileSync(signature)) };
  writeJson(path.join(dir, 'candidate.json'), tampered);
  assert.throws(() => verifySigned(dir, fingerprint), /gpg failed/);
});

test('matrix selection refuses missing, divergent or substituted candidate artifacts', t => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'magika-matrix-test-'));
  t.after(() => fs.rmSync(temp, { recursive: true, force: true }));
  const candidate = { sourceSha: 'a'.repeat(40), artifacts: { 'sdk.jar': { sha256: digest('jar') } } };
  for (const runtime of ['8', '17', '21']) {
    const dir = path.join(temp, `candidate-java-${runtime}`); fs.mkdirSync(dir);
    fs.writeFileSync(path.join(dir, 'sdk.jar'), 'jar'); writeJson(path.join(dir, 'candidate.json'), candidate);
  }
  selectCandidate(temp, path.join(temp, 'pass'));
  fs.writeFileSync(path.join(temp, 'candidate-java-8/sdk.jar'), 'substitute');
  assert.throws(() => selectCandidate(temp, path.join(temp, 'bad-bytes')));
  fs.writeFileSync(path.join(temp, 'candidate-java-8/sdk.jar'), 'jar');
  writeJson(path.join(temp, 'candidate-java-17/candidate.json'), { ...candidate, sourceSha: 'b'.repeat(40) });
  assert.throws(() => selectCandidate(temp, path.join(temp, 'bad-source')), /different candidate bytes/);
  fs.rmSync(path.join(temp, 'candidate-java-8'), { recursive: true });
  assert.throws(() => selectCandidate(temp, path.join(temp, 'missing-runtime')), /ENOENT/);
});
