// Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { digest, repositoryPath, version } from './candidate.mjs';

const ledgerBranch = 'release-ledger';
const sameRelease = (left, right) => {
  for (const field of ['version', 'sourceSha', 'contentSha256', 'bundleSha256', 'signingFingerprint']) {
    assert.equal(left[field], right[field], `Release conflict: ${field}`);
  }
};
export function releaseBody(candidate) {
  const source = `https://github.com/zerocloud-sdk/magika/blob/${candidate.sourceSha}`;
  const assets = candidate.assets.map(asset => `| [${asset.file}](${asset.source}) | ${asset.bytes} | \`${asset.sha256}\` |`).join('\n');
  return `Magika Java SDK **${candidate.version}**, independently maintained by ZeroCloud SDK.\n\n`
    + `Maven: \`${candidate.coordinate}\`\nSource: \`${candidate.sourceSha}\`\nModel: \`${candidate.model}\`\n`
    + `Fixed upstream: [google/magika at ${candidate.upstreamCommit}](https://github.com/google/magika/tree/${candidate.upstreamCommit}).\n\n`
    + `The following Apache-2.0 model assets are bundled together; no runtime model download or Python is required.\n\n`
    + `| Asset / fixed source | Bytes | SHA-256 |\n| --- | ---: | --- |\n${assets}\n\n`
    + `Verified platform: Ubuntu 24.04 x64 / CPU, Java 8, 17 and 21, ONNX Runtime 1.30.0. `
    + `Other platforms are not part of this release's verified support claim.\n\n`
    + `Add the Maven dependency:\n\n\`\`\`xml\n<dependency>\n  <groupId>net.zerocloud</groupId>\n`
    + `  <artifactId>magika</artifactId>\n  <version>${candidate.version}</version>\n</dependency>\n\`\`\`\n\n`
    + `Identify a saved file through the public API:\n\n\`\`\`java\nimport java.nio.file.Paths;\n`
    + `import net.zerocloud.magika.DetectionResult;\nimport net.zerocloud.magika.Magika;\n\n`
    + `try (Magika magika = Magika.create()) {\n  DetectionResult result = magika.identify(Paths.get("upload.pdf"));\n`
    + `  System.out.println(result.getLabel() + " " + result.getMimeType());\n}\n\`\`\`\n\n`
    + `After Maven resolves the dependencies, identification works offline. `
    + `See the [usage and lifecycle contracts](${source}/README.md), `
    + `[standalone examples](https://github.com/zerocloud-sdk/magika/tree/${candidate.sourceSha}/examples/offline), `
    + `and [performance report](${source}/docs/performance/issue-8-v1.md).\n\n`
    + `Candidate SHA-256: \`${candidate.contentSha256}\`\nBundle SHA-256: \`${candidate.bundleSha256}\`\n`
    + `Signing fingerprint: \`${candidate.signingFingerprint}\`\n\n`
    + `Published to Maven Central. Durable deployment record and signed bundle: `
    + `https://github.com/zerocloud-sdk/magika/tree/${ledgerBranch}/releases/${candidate.version}\n`;
}

// All production URLs are fixed by the CLI. Tests use actual loopback HTTP servers.
// No HTTP response bodies/headers or authentication values are included in errors.
export class Services {
  constructor({ github = 'https://api.github.com', central = 'https://central.sonatype.com',
    repository = 'https://repo.maven.apache.org/maven2', githubToken, centralUsername, centralPassword,
    pollMs = 10000, polls = 180 } = {}) {
    this.github = github;
    this.central = central;
    this.repository = repository;
    this.githubToken = githubToken;
    this.centralToken = Buffer.from(`${centralUsername}:${centralPassword}`).toString('base64');
    this.pollMs = pollMs;
    this.polls = polls;
    this.repoPath = '/repos/zerocloud-sdk/magika';
  }
  async request(base, endpoint, { method = 'GET', body, allow404 = false, binary = false, headers = {} } = {}) {
    let response;
    try {
      response = await fetch(base + endpoint, { method, body, headers,
        signal: AbortSignal.timeout(60000), redirect: 'error' });
    } catch { throw new Error(`Request outcome unknown: ${method} ${endpoint.split('?')[0]}; inspect durable release record before retry`); }
    if (response.status === 404 && allow404) return null;
    if (!response.ok) throw new Error(`Remote HTTP ${response.status}: ${method} ${endpoint.split('?')[0]}`);
    if (binary) return Buffer.from(await response.arrayBuffer());
    const text = await response.text();
    if (!text) return null;
    try { return JSON.parse(text); } catch { return text; }
  }
  gh(endpoint, options = {}) {
    return this.request(this.github, this.repoPath + endpoint, { ...options,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      headers: { Authorization: `Bearer ${this.githubToken}`, Accept: 'application/vnd.github+json',
        'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28' } });
  }
  portal(endpoint, options = {}) {
    return this.request(this.central, '/api/v1/publisher' + endpoint, { ...options,
      headers: { Authorization: `Bearer ${this.centralToken}` } });
  }
  async record(v) {
    const data = await this.gh(`/contents/releases/${version(v)}/state.json?ref=${ledgerBranch}`, { allow404: true });
    if (!data) return null;
    assert.equal(data.encoding, 'base64');
    return { state: JSON.parse(Buffer.from(data.content, 'base64').toString()), sha: data.sha };
  }
  async storedBlob(v, name) {
    assert.ok(['candidate.json', 'central-bundle.zip', 'signer.asc'].includes(name));
    const entry = await this.gh(`/contents/releases/${version(v)}/${name}?ref=${ledgerBranch}`);
    // The Contents API omits content for files over 1 MiB; Git Blobs handles the bundle.
    const blob = await this.gh(`/git/blobs/${entry.sha}`);
    assert.equal(blob.encoding, 'base64');
    return Buffer.from(blob.content, 'base64');
  }
  async prepare(candidate, bundle, publicKey) {
    const old = await this.record(candidate.version);
    if (old) { sameRelease(old.state.candidate, candidate); return old; }
    const ref = await this.gh(`/git/ref/heads/${ledgerBranch}`, { allow404: true });
    const parent = ref ? await this.gh(`/git/commits/${ref.object.sha}`) : null;
    const state = { schema: 1, candidate, phase: 'prepared', deploymentId: null,
      deploymentName: `magika-${candidate.version}-${candidate.sourceSha}-${candidate.bundleSha256}` };
    const files = { 'state.json': Buffer.from(JSON.stringify(state, null, 2) + '\n'),
      'candidate.json': Buffer.from(JSON.stringify(candidate, null, 2) + '\n'),
      'central-bundle.zip': bundle, 'signer.asc': publicKey };
    const tree = [];
    for (const [name, bytes] of Object.entries(files)) {
      const blob = await this.gh('/git/blobs', { method: 'POST', body: { content: bytes.toString('base64'), encoding: 'base64' } });
      tree.push({ path: `releases/${candidate.version}/${name}`, mode: '100644', type: 'blob', sha: blob.sha });
    }
    const newTree = await this.gh('/git/trees', { method: 'POST', body: { ...(parent ? { base_tree: parent.tree.sha } : {}), tree } });
    const commit = await this.gh('/git/commits', { method: 'POST', body: {
      message: `Preserve signed candidate ${candidate.coordinate}`, tree: newTree.sha, parents: ref ? [ref.object.sha] : [] } });
    if (ref) await this.gh(`/git/refs/heads/${ledgerBranch}`, { method: 'PATCH', body: { sha: commit.sha, force: false } });
    else await this.gh('/git/refs', { method: 'POST', body: { ref: `refs/heads/${ledgerBranch}`, sha: commit.sha } });
    return await this.record(candidate.version);
  }
  async save(record, patch) {
    const state = { ...record.state, ...patch };
    const result = await this.gh(`/contents/releases/${version(state.candidate.version)}/state.json`, {
      method: 'PUT', body: { message: `Record ${state.candidate.coordinate}: ${state.phase}`,
        branch: ledgerBranch, sha: record.sha, content: Buffer.from(JSON.stringify(state, null, 2) + '\n').toString('base64') } });
    return { state, sha: result.content.sha };
  }
  async githubState(candidate) {
    const tag = `v${version(candidate.version)}`;
    const ref = await this.gh(`/git/ref/tags/${tag}`, { allow404: true });
    let object = ref?.object;
    for (let i = 0; object?.type === 'tag' && i < 8; i++) {
      object = (await this.gh(`/git/tags/${object.sha}`)).object;
    }
    if (object) {
      assert.equal(object.type, 'commit', 'Release tag does not resolve to a commit');
      assert.equal(object.sha, candidate.sourceSha, 'Release conflict: GitHub tag source');
    }
    const release = await this.gh(`/releases/tags/${tag}`, { allow404: true });
    if (release) {
      assert.ok(ref, 'Release exists without its tag');
      assert.equal(release.tag_name, tag);
      assert.equal(release.body, releaseBody(candidate), 'Release conflict: GitHub Release identity');
      assert.equal(release.draft, false, 'Existing Release is still a draft');
      assert.equal(release.prerelease, false, 'Existing Release is a prerelease');
    }
    return { tag: !!ref, release: !!release };
  }
  async centralFiles(candidate) {
    let found = 0, total = 0;
    for (const [name, expected] of Object.entries({ ...candidate.artifacts, ...candidate.signatures })) {
      total++;
      const bytes = await this.request(this.repository, `/${repositoryPath(candidate.version)}/${name}`, { allow404: true, binary: true });
      if (bytes === null) continue;
      found++;
      assert.equal(digest(bytes), expected.sha256, `Release conflict: Central file ${name}`);
      assert.equal(bytes.length, expected.bytes, `Release conflict: Central size ${name}`);
    }
    return found === total ? 'complete' : found === 0 ? 'absent' : 'partial';
  }
  async isPublished(v) {
    const data = await this.portal(`/published?namespace=net.zerocloud&name=magika&version=${version(v)}`);
    assert.equal(typeof data.published, 'boolean', 'Invalid Central published response');
    return data.published;
  }
  async status(id) {
    assert.match(id, /^[a-fA-F0-9-]{36}$/, 'Invalid deployment ID');
    return this.portal(`/status?id=${id}`, { method: 'POST' });
  }
  async findDeployment(name, v) {
    const matches = [];
    for (let page = 0; ; page++) {
      const data = await this.portal(`/deployments?namespace=net.zerocloud&page=${page}&size=100`);
      assert.ok(Array.isArray(data.deployments) && Number.isInteger(data.pageCount), 'Invalid Central deployment list');
      for (const item of data.deployments) {
        if (item.deploymentName === name) continue;
        const sameCoordinate = item.deploymentComponents?.some(component => component.purl === `pkg:maven/net.zerocloud/magika@${v}`);
        assert.ok(!sameCoordinate && !item.deploymentName.startsWith(`magika-${v}-`), 'Release conflict: another Central deployment uses this version');
      }
      matches.push(...data.deployments.filter(item => item.deploymentName === name));
      if (page + 1 >= data.pageCount) break;
      assert.ok(page < 1000, 'Deployment listing did not terminate');
    }
    assert.ok(matches.length <= 1, 'Multiple Central deployments match the exact candidate; investigate before proceeding');
    return matches[0]?.deploymentId;
  }
  async upload(candidate, bundle, deploymentName) {
    const form = new FormData();
    form.append('bundle', new Blob([bundle], { type: 'application/octet-stream' }), 'central-bundle.zip');
    // USER_MANAGED permits checking the validated deployment bytes before the
    // workflow automatically calls the documented publish endpoint.
    const id = await this.portal(`/upload?name=${encodeURIComponent(deploymentName)}&publishingType=USER_MANAGED`, { method: 'POST', body: form });
    assert.match(id, /^[a-fA-F0-9-]{36}$/, 'Upload returned no valid deployment ID; inspect Portal');
    return id;
  }
  async deploymentFiles(candidate, id) {
    for (const [name, expected] of Object.entries({ ...candidate.artifacts, ...candidate.signatures })) {
      const bytes = await this.portal(`/deployment/${id}/download/${repositoryPath(candidate.version)}/${name}`, { binary: true });
      assert.equal(digest(bytes), expected.sha256, `Release conflict: validated deployment ${name}`);
    }
  }
  async completeGithub(candidate) {
    const existing = await this.githubState(candidate);
    const tag = `v${candidate.version}`;
    if (!existing.tag) await this.gh('/git/refs', { method: 'POST', body: { ref: `refs/tags/${tag}`, sha: candidate.sourceSha } });
    if (!existing.release) await this.gh('/releases', { method: 'POST', body: { tag_name: tag,
      target_commitish: candidate.sourceSha, name: tag, body: releaseBody(candidate), draft: false, prerelease: false, make_latest: 'true' } });
    await this.githubState(candidate);
  }
  wait() { return delay(this.pollMs); }
}

function validateStatus(status, record) {
  assert.equal(status.deploymentId, record.state.deploymentId, 'Release conflict: deployment ID');
  assert.equal(status.deploymentName, record.state.deploymentName, 'Release conflict: deployment name');
  assert.ok(['PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED', 'FAILED'].includes(status.deploymentState), 'Unknown Central deployment state');
  if (['VALIDATED', 'PUBLISHING', 'PUBLISHED'].includes(status.deploymentState)) {
    assert.deepEqual(status.purls, [`pkg:maven/net.zerocloud/magika@${record.state.candidate.version}`], 'Release conflict: deployment coordinates');
  }
}

export async function publish(candidate, bundle, publicKey, services, recoveryId) {
  assert.equal(digest(bundle), candidate.bundleSha256);
  await services.githubState(candidate);
  const alreadyPublished = await services.isPublished(candidate.version);
  const central = await services.centralFiles(candidate);
  let record = await services.record(candidate.version);
  if (record) sameRelease(record.state.candidate, candidate);
  // A published coordinate without this workflow's durable deployment identity
  // must be investigated, never adopted on the strength of matching filenames.
  if (!record && (alreadyPublished || central !== 'absent')) throw new Error('Central coordinate already exists without a release ledger; refusing upload');
  if (!record) record = await services.prepare(candidate, bundle, publicKey);
  // Portal deployment history is time-limited. Its authenticated published flag,
  // all eight exact public files and our durable deployment identity remain
  // sufficient evidence when the old status/list entry has been purged.
  if (alreadyPublished && central === 'complete' && record.state.deploymentId) {
    await services.completeGithub(candidate);
    return await services.save(record, { phase: 'complete', centralState: 'PUBLISHED' });
  }
  // The Portal's paginated deployment listing also recovers a lost upload
  // response. An empty listing is not proof that an ambiguous upload failed.
  if (!record.state.deploymentId && !recoveryId) recoveryId = await services.findDeployment(record.state.deploymentName, candidate.version);
  if (recoveryId) {
    assert.match(recoveryId, /^[a-fA-F0-9-]{36}$/);
    assert.ok(!record.state.deploymentId || record.state.deploymentId === recoveryId, 'Release conflict: recovery deployment ID');
    const recovered = { ...record, state: { ...record.state, deploymentId: recoveryId } };
    validateStatus(await services.status(recoveryId), recovered);
    record = await services.save(record, { deploymentId: recoveryId, phase: 'uploaded' });
  }
  if (!record.state.deploymentId) {
    assert.equal(alreadyPublished, false, 'Central reports published without a known deployment; do not re-upload');
    assert.equal(central, 'absent', 'Central has partial or existing content; do not re-upload');
    assert.equal(record.state.phase, 'prepared', 'Previous upload outcome unknown: inspect Portal and supply recovery_deployment_id; do not re-upload');
    // Persist intent BEFORE contacting Central, closing the duplicate-upload
    // window if the response or the following durable write is lost.
    record = await services.save(record, { phase: 'uploading' });
    const id = await services.upload(candidate, bundle, record.state.deploymentName);
    // Public deployment IDs are safe in logs and survive a subsequent save failure.
    console.log(`Central deployment ID: ${id}`);
    record = await services.save(record, { deploymentId: id, phase: 'uploaded' });
  }
  for (let attempt = 0; attempt < services.polls; attempt++) {
    const status = await services.status(record.state.deploymentId);
    validateStatus(status, record);
    console.log(`Central deployment ${record.state.deploymentId}: ${status.deploymentState}`);
    if (status.deploymentState === 'FAILED') {
      await services.save(record, { phase: 'failed', centralState: 'FAILED' });
      throw new Error('Central validation failed; deployment preserved for inspection; this version is not automatically re-uploaded');
    }
    if (status.deploymentState === 'VALIDATED') {
      await services.deploymentFiles(candidate, record.state.deploymentId);
      // Re-read GitHub before a resumed irreversible publication.
      await services.githubState(candidate);
      record = await services.save(record, { phase: 'publishing', centralState: 'VALIDATED' });
      await services.portal(`/deployment/${record.state.deploymentId}`, { method: 'POST' });
    } else if (status.deploymentState === 'PUBLISHED') {
      record = await services.save(record, { phase: 'published', centralState: 'PUBLISHED' });
      const files = await services.centralFiles(candidate);
      if (files === 'complete') {
        await services.completeGithub(candidate);
        return await services.save(record, { phase: 'complete', centralState: 'PUBLISHED' });
      }
      // Central propagation may lag the Portal's PUBLISHED status. Wait for all
      // eight exact files; never publish GitHub records from a partial mirror.
    }
    await services.wait();
  }
  throw new Error('Observation deadline reached; durable deployment retained. Re-run to observe the same deployment');
}
