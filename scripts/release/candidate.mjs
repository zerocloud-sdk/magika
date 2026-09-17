// Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0
// Release tooling only. No npm dependencies; never included in the SDK.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import * as fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const digest = (bytes, algorithm = 'sha256') => createHash(algorithm).update(bytes).digest('hex');
export const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'));
export const writeJson = (file, value) => fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n');
export function command(program, args, options = {}) {
  const result = spawnSync(program, args, { maxBuffer: 32 * 1024 * 1024, ...options });
  // Child diagnostics may include key identities or credential-bearing inputs.
  if (result.status !== 0) throw new Error(`${program} failed (exit ${result.status}); sensitive diagnostics suppressed`);
  return result.stdout;
}
export function version(value) {
  assert.match(value, /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/, 'Expected a release version x.y.z');
  return value;
}
export function artifactNames(v) {
  version(v);
  return ['.jar', '-sources.jar', '-javadoc.jar', '.pom'].map(suffix => `magika-${v}${suffix}`).sort();
}
export function bundleFileNames(v) {
  return artifactNames(v).flatMap(name => ['', '.asc', '.md5', '.sha1', '.sha256', '.sha512'].map(suffix => name + suffix)).sort();
}
export function repositoryPath(v) { return `net/zerocloud/magika/${version(v)}`; }
const inventory = (dir, names) => Object.fromEntries(names.sort().map(name => {
  const bytes = fs.readFileSync(path.join(dir, name));
  return [name, { bytes: bytes.length, sha256: digest(bytes) }];
}));
const jarEntries = jar => command('unzip', ['-Z1', jar]).toString().trim().split('\n').filter(name => !name.endsWith('/'));
const jarRead = (jar, name) => command('unzip', ['-p', jar, name]);

export function inspectArtifacts(dir, v) {
  const names = artifactNames(v);
  const main = path.join(dir, `magika-${v}.jar`);
  const sources = path.join(dir, `magika-${v}-sources.jar`);
  const docs = path.join(dir, `magika-${v}-javadoc.jar`);
  const pom = fs.readFileSync(path.join(dir, `magika-${v}.pom`));
  assert.deepEqual(pom, fs.readFileSync(path.join(root, 'pom.xml')), 'Candidate POM differs from checkout');
  const xml = pom.toString();
  assert.match(xml, new RegExp(`<groupId>net.zerocloud</groupId>\\s*<artifactId>magika</artifactId>\\s*<version>${v.replaceAll('.', '\\.')}</version>`));
  for (const metadata of ['name', 'description', 'url', 'licenses', 'developers', 'scm']) {
    assert.match(xml, new RegExp(`<${metadata}>[\\s\\S]+?</${metadata}>`), `Missing POM ${metadata}`);
  }
  const dependencies = [...xml.match(/<dependencies>([\s\S]*?)<\/dependencies>/)[1].matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)]
    .map(match => Object.fromEntries(['groupId', 'artifactId', 'version', 'scope'].map(tag => [tag,
      match[1].match(new RegExp(`<${tag}>([^<]+)</${tag}>`))?.[1] || (tag === 'scope' ? 'compile' : '')])));
  assert.deepEqual(dependencies, [
    { groupId: 'com.microsoft.onnxruntime', artifactId: 'onnxruntime', version: '1.30.0', scope: 'compile' },
    { groupId: 'com.google.code.gson', artifactId: 'gson', version: '2.13.2', scope: 'compile' },
    { groupId: 'junit', artifactId: 'junit', version: '4.13.2', scope: 'test' },
    { groupId: 'org.ow2.asm', artifactId: 'asm', version: '9.10.1', scope: 'test' }
  ], 'Unexpected production/test dependency boundary');
  const modelRoot = 'net/zerocloud/magika/model/';
  const manifest = readJson(path.join(root, 'src/main/resources', modelRoot, 'asset-manifest.json'));
  assert.deepEqual(jarRead(main, modelRoot + 'asset-manifest.json'),
    fs.readFileSync(path.join(root, 'src/main/resources', modelRoot, 'asset-manifest.json')));
  assert.equal(manifest.upstreamCommit, '9f225aa480e675af44343b9160f077073ed1b752');
  assert.equal(manifest.modelVersion, 'standard_v3_3');
  for (const asset of manifest.assets) {
    const bytes = jarRead(main, modelRoot + asset.file);
    assert.equal(bytes.length, asset.bytes, `Asset size: ${asset.file}`);
    assert.equal(digest(bytes), asset.sha256, `Asset digest: ${asset.file}`);
    assert.ok(asset.source.includes(manifest.upstreamCommit));
  }
  const javaFiles = fs.readdirSync(path.join(root, 'src/main/java/net/zerocloud/magika')).filter(name => name.endsWith('.java'));
  const classRoots = new Set(javaFiles.map(name => name.slice(0, -5)));
  const allowedResources = new Set(['META-INF/MANIFEST.MF', 'META-INF/LICENSE', 'META-INF/NOTICE',
    'META-INF/maven/net.zerocloud/magika/pom.xml', 'META-INF/maven/net.zerocloud/magika/pom.properties',
    'net/zerocloud/magika/sdk.properties', modelRoot + 'asset-manifest.json',
    ...manifest.assets.map(asset => modelRoot + asset.file)]);
  let classes = 0;
  for (const name of jarEntries(main)) {
    if (name.endsWith('.class')) {
      assert.match(name, /^net\/zerocloud\/magika\/[^/]+\.class$/);
      assert.ok(classRoots.has(path.basename(name, '.class').split('$')[0]), `Unexpected class: ${name}`);
      const bytes = jarRead(main, name);
      assert.equal(bytes.readUInt32BE(0), 0xcafebabe);
      assert.equal(bytes.readUInt16BE(6), 52, `Not Java 8: ${name}`);
      classes++;
    } else assert.ok(allowedResources.has(name), `Unexpected production resource: ${name}`);
  }
  assert.ok(classes > 0);
  assert.match(jarRead(main, 'META-INF/MANIFEST.MF').toString(), new RegExp(`Implementation-Version: ${v.replaceAll('.', '\\.')}(?:\\r?\\n)`));
  assert.match(jarRead(main, 'net/zerocloud/magika/sdk.properties').toString(), new RegExp(`sdk.version=${v.replaceAll('.', '\\.')}(?:\\r?\\n|$)`));
  assert.deepEqual(jarRead(main, 'META-INF/maven/net.zerocloud/magika/pom.xml'), pom);
  for (const jar of [main, sources, docs]) {
    for (const name of ['LICENSE', 'NOTICE']) {
      assert.deepEqual(jarRead(jar, `META-INF/${name}`), fs.readFileSync(path.join(root, name)));
    }
    for (const name of jarEntries(jar)) {
      assert.ok(!/(?:^|\/)(?:reference|tests_data|scripts|benchmarks)\/|\.py$|(?:Test|Probe)\.(?:java|class)$/.test(name), `Test/verification material in ${jar}: ${name}`);
    }
  }
  for (const name of javaFiles) {
    assert.deepEqual(jarRead(sources, `net/zerocloud/magika/${name}`),
      fs.readFileSync(path.join(root, 'src/main/java/net/zerocloud/magika', name)));
    const text = fs.readFileSync(path.join(root, 'src/main/java/net/zerocloud/magika', name), 'utf8');
    if (/^public (?:final )?(?:class|enum)/m.test(text)) {
      assert.ok(jarEntries(docs).includes(`net/zerocloud/magika/${name.replace('.java', '.html')}`), `Missing public Javadoc: ${name}`);
    }
  }
  for (const name of jarEntries(sources)) {
    assert.ok(allowedResources.has(name) || javaFiles.some(file => name === `net/zerocloud/magika/${file}`), `Unexpected source distribution entry: ${name}`);
  }
  for (const name of jarEntries(docs)) assert.ok(!/\.(?:class|java|sh|py|onnx|gz)$/.test(name), `Unexpected Javadoc distribution entry: ${name}`);
  return { artifacts: inventory(dir, names), model: manifest.modelVersion, upstreamCommit: manifest.upstreamCommit,
    assets: manifest.assets, javaClassVersion: 52, classes };
}

export function stage(v, sourceSha, dir) {
  version(v);
  assert.match(sourceSha, /^[a-f0-9]{40}$/);
  assert.equal(command('git', ['rev-parse', 'HEAD'], { cwd: root }).toString().trim(), sourceSha);
  assert.equal(command('git', ['status', '--porcelain', '--untracked-files=no'], { cwd: root }).length, 0,
    'Stage a candidate only from a clean tracked checkout');
  fs.mkdirSync(dir, { recursive: false });
  for (const name of artifactNames(v)) {
    fs.copyFileSync(path.join(root, name.endsWith('.pom') ? 'pom.xml' : `target/${name}`), path.join(dir, name));
  }
  const checked = inspectArtifacts(dir, v);
  const candidate = { schema: 1, coordinate: `net.zerocloud:magika:${v}`, version: v, sourceSha,
    ...checked, contentSha256: digest(JSON.stringify(checked.artifacts)) };
  writeJson(path.join(dir, 'candidate.json'), candidate);
  return candidate;
}

function fingerprint(value) {
  assert.match(value || '', /^[A-F0-9]{40}$/, 'MAVEN_GPG_FINGERPRINT must be a full primary-key fingerprint');
  return value;
}
function gpgHome(operation) {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'magika-gpg-'));
  fs.chmodSync(home, 0o700);
  const gpg = (args, input) => command('gpg', ['--batch', '--no-tty', '--homedir', home, ...args], {
    input, env: { PATH: process.env.PATH, LANG: 'C', GNUPGHOME: home }
  });
  try { return operation(gpg); }
  finally {
    spawnSync('gpgconf', ['--homedir', home, '--kill', 'all'], { stdio: 'ignore' });
    fs.rmSync(home, { recursive: true, force: true });
  }
}
export function sign(dir, expectedFingerprint, privateKey, passphrase) {
  assert.equal(command('gpg', ['--version']).toString().split('\n')[0], 'gpg (GnuPG) 2.4.4', 'Release signing is pinned to Ubuntu 24.04 GnuPG 2.4.4');
  fingerprint(expectedFingerprint);
  assert.ok(privateKey?.includes('-----BEGIN PGP PRIVATE KEY BLOCK-----'), 'Missing armored signing key');
  assert.equal(typeof passphrase, 'string', 'Missing signing passphrase');
  const candidate = readJson(path.join(dir, 'candidate.json'));
  assert.deepEqual(inspectArtifacts(dir, candidate.version).artifacts, candidate.artifacts);
  gpgHome(gpg => {
    gpg(['--import'], privateKey);
    const keys = gpg(['--with-colons', '--list-secret-keys', expectedFingerprint]).toString();
    assert.ok(keys.split('\n').some(line => line.startsWith('fpr:') && line.split(':')[9] === expectedFingerprint));
    for (const name of artifactNames(candidate.version)) {
      gpg(['--yes', '--pinentry-mode', 'loopback', '--passphrase-fd', '0', '--local-user', expectedFingerprint,
        '--digest-algo', 'SHA256', '--armor', '--detach-sign', '--output', path.join(dir, name + '.asc'), path.join(dir, name)], passphrase + '\n');
    }
    fs.writeFileSync(path.join(dir, 'signer.asc'), gpg(['--armor', '--export-options', 'export-minimal', '--export', expectedFingerprint]));
  });
  candidate.signingFingerprint = expectedFingerprint;
  candidate.signatures = inventory(dir, artifactNames(candidate.version).map(name => name + '.asc'));
  for (const name of artifactNames(candidate.version)) {
    for (const algorithm of ['md5', 'sha1', 'sha256', 'sha512']) {
      fs.writeFileSync(path.join(dir, `${name}.${algorithm}`), digest(fs.readFileSync(path.join(dir, name)), algorithm) + '\n');
    }
  }
  const bundleRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'magika-bundle-'));
  try {
    const base = path.join(bundleRoot, repositoryPath(candidate.version));
    fs.mkdirSync(base, { recursive: true });
    const files = bundleFileNames(candidate.version);
    for (const name of files) fs.copyFileSync(path.join(dir, name), path.join(base, name));
    const bundle = path.join(dir, 'central-bundle.zip');
    fs.rmSync(bundle, { force: true });
    command('zip', ['-q', '-X', bundle, ...files.sort().map(name => `${repositoryPath(candidate.version)}/${name}`)], { cwd: bundleRoot });
    candidate.bundleSha256 = digest(fs.readFileSync(bundle));
  } finally { fs.rmSync(bundleRoot, { recursive: true, force: true }); }
  writeJson(path.join(dir, 'candidate.json'), candidate);
  verifySigned(dir, expectedFingerprint);
  writeChecksums(dir, candidate);
  return candidate;
}

export function writeChecksums(dir, candidate) {
  fs.writeFileSync(path.join(dir, 'SHA256SUMS'), Object.entries({ ...candidate.artifacts, ...candidate.signatures })
    .map(([name, value]) => `${value.sha256}  ${name}`).join('\n') + '\n');
}

export function verifySigned(dir, expectedFingerprint) {
  fingerprint(expectedFingerprint);
  const candidate = readJson(path.join(dir, 'candidate.json'));
  assert.equal(candidate.schema, 1);
  assert.match(candidate.sourceSha, /^[a-f0-9]{40}$/);
  assert.equal(candidate.sourceSha, command('git', ['rev-parse', 'HEAD'], { cwd: root }).toString().trim(), 'Candidate source differs from checkout');
  assert.equal(candidate.coordinate, `net.zerocloud:magika:${version(candidate.version)}`);
  const artifacts = inspectArtifacts(dir, candidate.version).artifacts;
  assert.deepEqual(artifacts, candidate.artifacts);
  assert.equal(digest(JSON.stringify(artifacts)), candidate.contentSha256);
  assert.equal(candidate.signingFingerprint, expectedFingerprint);
  assert.deepEqual(inventory(dir, artifactNames(candidate.version).map(name => name + '.asc')), candidate.signatures);
  gpgHome(gpg => {
    gpg(['--import'], fs.readFileSync(path.join(dir, 'signer.asc')));
    for (const name of artifactNames(candidate.version)) {
      const status = gpg(['--status-fd', '1', '--verify', path.join(dir, name + '.asc'), path.join(dir, name)]).toString();
      const valid = status.split('\n').filter(line => line.startsWith('[GNUPG:] VALIDSIG '));
      assert.equal(valid.length, 1, `No unique cryptographic signature for ${name}`);
      const fields = valid[0].split(' ');
      assert.ok(fields[2] === expectedFingerprint || fields.at(-1) === expectedFingerprint, `Wrong signer: ${name}`);
    }
  });
  const bundle = path.join(dir, 'central-bundle.zip');
  assert.equal(digest(fs.readFileSync(bundle)), candidate.bundleSha256);
  const expectedEntries = bundleFileNames(candidate.version).map(name => `${repositoryPath(candidate.version)}/${name}`);
  for (const name of bundleFileNames(candidate.version)) {
    const entry = `${repositoryPath(candidate.version)}/${name}`;
    assert.deepEqual(jarRead(bundle, entry), fs.readFileSync(path.join(dir, name)), `Bundle mismatch: ${entry}`);
  }
  for (const name of artifactNames(candidate.version)) {
    for (const algorithm of ['md5', 'sha1', 'sha256', 'sha512']) {
      assert.equal(fs.readFileSync(path.join(dir, `${name}.${algorithm}`), 'utf8').trim(), digest(fs.readFileSync(path.join(dir, name)), algorithm));
    }
  }
  assert.deepEqual(jarEntries(bundle).sort(), expectedEntries.sort());
  return candidate;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [operation, ...args] = process.argv.slice(2);
    let candidate;
    if (operation === 'stage') candidate = stage(args[0], args[1], path.resolve(args[2]));
    else if (operation === 'sign') candidate = sign(path.resolve(args[0]), process.env.MAVEN_GPG_FINGERPRINT,
      process.env.MAVEN_GPG_PRIVATE_KEY, process.env.MAVEN_GPG_PASSPHRASE);
    else if (operation === 'verify') candidate = verifySigned(path.resolve(args[0]), process.env.MAVEN_GPG_FINGERPRINT);
    else throw new Error('Usage: candidate.mjs stage VERSION SOURCE_SHA OUT | sign DIR | verify DIR');
    console.log(JSON.stringify({ coordinate: candidate.coordinate, sourceSha: candidate.sourceSha,
      contentSha256: candidate.contentSha256, bundleSha256: candidate.bundleSha256,
      signaturesVerified: operation !== 'stage' ? 4 : 0 }));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
