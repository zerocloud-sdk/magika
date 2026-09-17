// Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { test } from 'node:test';
import { artifactNames, digest } from './candidate.mjs';
import { publish, releaseBody, Services } from './publication.mjs';

const deploymentId = '12345678-1234-1234-1234-123456789abc';
const bundle = Buffer.from('fixture bundle; cryptographic verification is tested separately');
const publicKey = Buffer.from('fixture public key');
function candidateFixture() {
  const files = Object.fromEntries(artifactNames('0.1.0').flatMap(name => [name, name + '.asc']).map(name => [name, Buffer.from(name)]));
  const values = names => Object.fromEntries(names.map(name => [name, { bytes: files[name].length, sha256: digest(files[name]) }]));
  const candidate = { schema: 1, version: '0.1.0', coordinate: 'net.zerocloud:magika:0.1.0', sourceSha: 'a'.repeat(40),
    model: 'standard_v3_3', contentSha256: 'b'.repeat(64), bundleSha256: digest(bundle), signingFingerprint: 'C'.repeat(40),
    upstreamCommit: 'd'.repeat(40),
    assets: [{ file: 'model.onnx', bytes: 123, sha256: 'e'.repeat(64), source: `https://example.org/${'d'.repeat(40)}/model.onnx` }],
    artifacts: values(artifactNames('0.1.0')), signatures: values(artifactNames('0.1.0').map(name => name + '.asc')) };
  return { candidate, files };
}

async function fixture(t, options = {}) {
  const { candidate, files } = candidateFixture();
  const state = { candidate, files, calls: [], blobs: new Map(), trees: new Map(), commits: new Map(),
    refs: new Map(), releases: new Map(), statuses: options.statuses || ['PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED'],
    centralState: 'absent', deploymentName: null, deploymentExists: false, sequence: 0, ...options };
  const prefix = '/repos/zerocloud-sdk/magika';
  const id = () => String(++state.sequence).padStart(40, '0');
  const currentTree = () => {
    const commit = state.refs.get('heads/release-ledger');
    return commit ? state.trees.get(state.commits.get(commit).tree.sha) : {};
  };
  state.readRecord = () => {
    const sha = currentTree()['releases/0.1.0/state.json'];
    return sha ? JSON.parse(state.blobs.get(sha)) : null;
  };
  state.changeRecord = patch => {
    const tree = currentTree(), name = 'releases/0.1.0/state.json', sha = id();
    state.blobs.set(sha, Buffer.from(JSON.stringify({ ...state.readRecord(), ...patch })));
    tree[name] = sha;
  };
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      const route = url.pathname;
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const bytes = Buffer.concat(chunks);
      const body = req.headers['content-type']?.includes('application/json') && bytes.length ? JSON.parse(bytes) : undefined;
      state.calls.push({ method: req.method, route, query: url.search, body });
      const send = (value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(value === undefined ? '' : JSON.stringify(value)); };
      const raw = value => { res.writeHead(200); res.end(value); };
      if (state.failRequest?.(req, body)) return send({ message: 'response deliberately contains private-test-token' }, 503);
      if (route.startsWith(prefix)) {
        assert.equal(req.headers.authorization, 'Bearer github-fixture-token');
        const endpoint = route.slice(prefix.length);
        if (endpoint.startsWith('/git/ref/')) {
          const ref = endpoint.slice('/git/ref/'.length), sha = state.refs.get(ref);
          return sha ? send({ object: { type: 'commit', sha } }) : send({}, 404);
        }
        if (endpoint === '/git/refs' && req.method === 'POST') {
          const ref = body.ref.slice(5);
          if (state.refs.has(ref)) return send({}, 422);
          state.refs.set(ref, body.sha); return send({ ref: body.ref, object: { sha: body.sha } }, 201);
        }
        if (endpoint.startsWith('/git/refs/') && req.method === 'PATCH') {
          assert.equal(body.force, false);
          state.refs.set(endpoint.slice('/git/refs/'.length), body.sha); return send({});
        }
        if (endpoint === '/git/blobs' && req.method === 'POST') {
          const sha = id(); state.blobs.set(sha, Buffer.from(body.content, 'base64')); return send({ sha }, 201);
        }
        if (endpoint.startsWith('/git/blobs/')) {
          const data = state.blobs.get(endpoint.split('/').at(-1)); return data ? send({ encoding: 'base64', content: data.toString('base64') }) : send({}, 404);
        }
        if (endpoint === '/git/trees') {
          const sha = id(), tree = { ...(state.trees.get(body.base_tree) || {}) };
          for (const entry of body.tree) tree[entry.path] = entry.sha;
          state.trees.set(sha, tree); return send({ sha }, 201);
        }
        if (endpoint === '/git/commits') {
          const sha = id(); state.commits.set(sha, { tree: { sha: body.tree }, parents: body.parents }); return send({ sha }, 201);
        }
        if (endpoint.startsWith('/git/commits/')) return send(state.commits.get(endpoint.split('/').at(-1)));
        if (endpoint.startsWith('/contents/')) {
          const name = endpoint.slice('/contents/'.length), tree = currentTree(), sha = tree[name];
          if (req.method === 'GET') return sha ? send({ sha, encoding: 'base64', content: state.blobs.get(sha).toString('base64') }) : send({}, 404);
          if (body.sha !== sha) return send({}, 409);
          const next = id(); state.blobs.set(next, Buffer.from(body.content, 'base64')); tree[name] = next;
          return send({ content: { sha: next } });
        }
        if (endpoint.startsWith('/releases/tags/')) {
          const release = state.releases.get(endpoint.split('/').at(-1)); return release ? send(release) : send({}, 404);
        }
        if (endpoint === '/releases' && req.method === 'POST') {
          assert.equal(state.centralState, 'PUBLISHED', 'GitHub Release created before Central published');
          state.releases.set(body.tag_name, body); return send(body, 201);
        }
      }
      if (route.startsWith('/api/v1/publisher/')) {
        assert.equal(req.headers.authorization, 'Bearer ' + Buffer.from('central-user:central-password').toString('base64'));
        const endpoint = route.slice('/api/v1/publisher'.length);
        if (endpoint === '/published') return send({ published: state.portalPublished ?? state.centralState === 'PUBLISHED' });
        if (endpoint === '/deployments') {
          const deployments = state.deploymentExists ? [{ deploymentId, deploymentName: state.deploymentName }] : [];
          if (state.deploymentPages) {
            const page = Number(url.searchParams.get('page'));
            return send({ deployments: state.deploymentPages[page], page, pageCount: state.deploymentPages.length });
          }
          return send({ deployments: state.duplicateDeployments ? [...deployments, ...deployments] : deployments,
            page: 0, pageCount: 1, totalResultCount: deployments.length });
        }
        if (endpoint === '/upload') {
          assert.equal(url.searchParams.get('publishingType'), 'USER_MANAGED');
          assert.ok(bytes.includes(bundle));
          assert.match(req.headers['content-type'], /^multipart\/form-data/);
          assert.equal(state.readRecord().phase, 'uploading', 'Upload intent not durable');
          state.deploymentExists = true;
          state.deploymentName = url.searchParams.get('name');
          if (state.loseUploadResponse) { req.socket.destroy(); return; }
          return raw(deploymentId);
        }
        if (endpoint === '/status') {
          assert.equal(url.searchParams.get('id'), deploymentId);
          if (state.statuses.length) state.centralState = state.statuses.shift();
          return send({ deploymentId, deploymentName: state.wrongName || state.deploymentName,
            deploymentState: state.centralState, purls: state.wrongPurls || ['pkg:maven/net.zerocloud/magika@0.1.0'] });
        }
        if (endpoint === `/deployment/${deploymentId}` && req.method === 'POST') {
          assert.equal(state.centralState, 'VALIDATED'); state.centralState = 'PUBLISHING';
          if (state.losePublishResponse) { req.socket.destroy(); return; }
          return send(undefined, 204);
        }
        if (endpoint.includes('/download/')) return raw(state.corruptDeployment ? Buffer.from('wrong') : state.files[route.split('/').at(-1)]);
      }
      if (route.startsWith('/maven2/')) {
        const name = route.split('/').at(-1);
        if (state.centralState !== 'PUBLISHED' || state.missingCentralFile === name) return send({}, 404);
        return raw(state.corruptCentral ? Buffer.from('wrong') : state.files[name]);
      }
      return send({ unknown: route }, 404);
    } catch (error) { state.serverError = error; res.writeHead(500); res.end('fixture failure'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  state.services = new Services({ github: base, central: base, repository: base + '/maven2',
    githubToken: 'github-fixture-token', centralUsername: 'central-user', centralPassword: 'central-password', pollMs: 1, polls: 8 });
  state.run = recoveryId => publish(state.candidate, bundle, publicKey, state.services, recoveryId);
  state.uploads = () => state.calls.filter(call => call.route === '/api/v1/publisher/upload').length;
  return state;
}

test('validates exact deployment bytes, publishes, waits for PUBLISHED, then creates matching tag and Release', async t => {
  const f = await fixture(t);
  const result = await f.run();
  assert.equal(result.state.phase, 'complete');
  assert.equal(f.uploads(), 1);
  assert.equal(f.refs.get('tags/v0.1.0'), f.candidate.sourceSha);
  assert.equal(f.releases.get('v0.1.0').body, releaseBody(f.candidate));
  assert.equal(f.calls.filter(call => call.route.includes('/download/')).length, 8);
  assert.equal(f.readRecord().candidate.bundleSha256, digest(bundle));
  assert.deepEqual(await f.services.storedBlob('0.1.0', 'central-bundle.zip'), bundle);
  assert.equal(f.serverError, undefined);
});

test('complete retry reads external state and makes no duplicate upload, tag, or Release', async t => {
  const f = await fixture(t); await f.run();
  const before = f.calls.length; await f.run();
  assert.equal(f.uploads(), 1);
  assert.equal(f.calls.slice(before).filter(call => call.method === 'POST' && /(?:upload|git\/refs|\/releases)$/.test(call.route)).length, 0);
});

test('recovers Central PUBLISHED when GitHub tag and Release are both missing', async t => {
  const f = await fixture(t); await f.run();
  f.refs.delete('tags/v0.1.0'); f.releases.clear(); f.changeRecord({ phase: 'uploaded' });
  await f.run();
  assert.equal(f.uploads(), 1); assert.ok(f.releases.has('v0.1.0')); assert.equal(f.readRecord().phase, 'complete');
});

test('published recovery survives expiry of Central deployment history', async t => {
  const f = await fixture(t); await f.run();
  f.refs.delete('tags/v0.1.0'); f.releases.clear(); f.changeRecord({ phase: 'published' });
  f.deploymentExists = false;
  f.failRequest = req => req.url.includes('/status?') || req.url.includes('/deployments?');
  await f.run(); assert.equal(f.uploads(), 1); assert.ok(f.releases.has('v0.1.0'));
});

test('recovers failure between GitHub tag creation and Release creation', async t => {
  const f = await fixture(t, { failRequest: req => req.method === 'POST' && req.url.endsWith('/releases') });
  await assert.rejects(f.run(), /Remote HTTP 503/);
  assert.equal(f.refs.get('tags/v0.1.0'), f.candidate.sourceSha);
  f.failRequest = null; await f.run(); assert.equal(f.uploads(), 1); assert.ok(f.releases.has('v0.1.0'));
});

test('lost upload response is recovered through exact-name Central listing', async t => {
  const f = await fixture(t, { loseUploadResponse: true });
  await assert.rejects(f.run(), /Request outcome unknown/);
  assert.equal(f.readRecord().phase, 'uploading'); assert.equal(f.readRecord().deploymentId, null);
  f.loseUploadResponse = false; await f.run(); assert.equal(f.uploads(), 1);
});

test('unknown upload with no visible deployment refuses another upload', async t => {
  const f = await fixture(t, { loseUploadResponse: true });
  await assert.rejects(f.run()); f.deploymentExists = false;
  await assert.rejects(f.run(), /Previous upload outcome unknown/); assert.equal(f.uploads(), 1);
});

test('failure persisting deployment ID is recoverable without a second upload', async t => {
  const f = await fixture(t, { failRequest: (req, body) => req.method === 'PUT' && body && JSON.parse(Buffer.from(body.content, 'base64')).phase === 'uploaded' });
  await assert.rejects(f.run(), /Remote HTTP 503/); f.failRequest = null;
  await f.run(); assert.equal(f.uploads(), 1);
});

test('poll timeout is an observation limit; retry uses the same deployment', async t => {
  const f = await fixture(t, { statuses: Array(8).fill('VALIDATING') });
  await assert.rejects(f.run(), /Observation deadline/);
  assert.equal(f.readRecord().deploymentId, deploymentId);
  f.statuses = ['VALIDATED', 'PUBLISHING', 'PUBLISHED']; await f.run(); assert.equal(f.uploads(), 1);
});

test('transient status failure preserves the live deployment for retry', async t => {
  const f = await fixture(t, { failRequest: req => req.url.includes('/status?') });
  await assert.rejects(f.run(), /Remote HTTP 503/); f.failRequest = null;
  await f.run(); assert.equal(f.uploads(), 1);
});

test('FAILED validation remains inspectable and never creates GitHub records or replaces the deployment', async t => {
  const f = await fixture(t, { statuses: ['FAILED'] });
  await assert.rejects(f.run(), /Central validation failed/);
  assert.equal(f.readRecord().phase, 'failed'); await assert.rejects(f.run(), /Central validation failed/);
  assert.equal(f.uploads(), 1); assert.equal(f.refs.has('tags/v0.1.0'), false);
});

for (const field of ['sourceSha', 'contentSha256', 'bundleSha256', 'signingFingerprint']) {
  test(`rejects a conflicting ${field} at the same version`, async t => {
    const f = await fixture(t); await f.run();
    f.candidate = { ...f.candidate, [field]: 'd'.repeat(field === 'sourceSha' || field === 'signingFingerprint' ? 40 : 64) };
    await assert.rejects(f.run()); assert.equal(f.uploads(), 1);
  });
}

test('conflicting tag or Release is rejected before any Central upload', async t => {
  const f = await fixture(t); f.refs.set('tags/v0.1.0', 'd'.repeat(40));
  await assert.rejects(f.run(), /GitHub tag source/); assert.equal(f.uploads(), 0);
  f.refs.set('tags/v0.1.0', f.candidate.sourceSha); f.releases.set('v0.1.0', { tag_name: 'v0.1.0', body: 'foreign release' });
  await assert.rejects(f.run(), /GitHub Release identity/); assert.equal(f.uploads(), 0);
});

test('published coordinates without a ledger are refused even before public mirror propagation', async t => {
  const f = await fixture(t, { portalPublished: true });
  await assert.rejects(f.run(), /already exists without a release ledger/); assert.equal(f.uploads(), 0);
});

test('conflicting validated deployment is never published', async t => {
  const f = await fixture(t, { statuses: ['VALIDATED'], corruptDeployment: true });
  await assert.rejects(f.run(), /validated deployment/);
  assert.equal(f.calls.filter(call => call.route === `/api/v1/publisher/deployment/${deploymentId}` && call.method === 'POST').length, 0);
});

test('conflicting public Central bytes cannot produce a GitHub Release', async t => {
  const f = await fixture(t, { statuses: ['PUBLISHED'], corruptCentral: true });
  await assert.rejects(f.run(), /Central file/); assert.equal(f.releases.size, 0);
});

test('PUBLISHED with a missing public file waits; retry completes once the mirror catches up', async t => {
  const f = await fixture(t, { statuses: ['PUBLISHED'], missingCentralFile: 'magika-0.1.0.pom.asc' });
  await assert.rejects(f.run(), /Observation deadline/); assert.equal(f.releases.size, 0);
  f.missingCentralFile = null; await f.run(); assert.equal(f.uploads(), 1);
});

test('wrong deployment name, coordinates and ambiguous matches are refused', async t => {
  const f = await fixture(t, { loseUploadResponse: true }); await assert.rejects(f.run());
  f.duplicateDeployments = true; await assert.rejects(f.run(), /Multiple Central deployments/);
  f.duplicateDeployments = false; f.wrongName = 'another candidate';
  await assert.rejects(f.run(), /deployment name/);
  f.wrongName = null; f.statuses = ['VALIDATED']; f.wrongPurls = ['pkg:maven/other/artifact@1.0.0'];
  await assert.rejects(f.run(), /deployment coordinates/); assert.equal(f.uploads(), 1);
});

test('remote errors never expose credentials or response bodies', async t => {
  const f = await fixture(t, { failRequest: () => true });
  await assert.rejects(f.run(), error => {
    for (const secret of ['private-test-token', 'github-fixture-token', 'central-user', 'central-password', Buffer.from('central-user:central-password').toString('base64')]) {
      assert.ok(!String(error).includes(secret));
    }
    return true;
  });
});

test('failure to persist upload intent prevents contacting the upload endpoint', async t => {
  const f = await fixture(t, { failRequest: req => req.method === 'PUT' });
  await assert.rejects(f.run(), /Remote HTTP 503/); assert.equal(f.uploads(), 0);
  f.failRequest = null; await f.run(); assert.equal(f.uploads(), 1);
});

test('unpublished ledger refuses a different source before upload', async t => {
  const f = await fixture(t); await f.services.prepare(f.candidate, bundle, publicKey);
  f.candidate = { ...f.candidate, sourceSha: 'd'.repeat(40) };
  await assert.rejects(f.run(), /Release conflict: sourceSha/); assert.equal(f.uploads(), 0);
});

test('lost publish response resumes from the same live deployment', async t => {
  const f = await fixture(t, { statuses: ['VALIDATED', 'PUBLISHING', 'PUBLISHED'], losePublishResponse: true });
  await assert.rejects(f.run(), /Request outcome unknown/); assert.equal(f.readRecord().phase, 'publishing');
  f.losePublishResponse = false; await f.run(); assert.equal(f.uploads(), 1);
});

test('deployment recovery searches later pages and rejects other content at the same coordinate', async t => {
  const f = await fixture(t, { loseUploadResponse: true }); await assert.rejects(f.run());
  f.deploymentPages = [[{ deploymentId: 'another', deploymentName: 'unrelated' }], [{ deploymentId, deploymentName: f.deploymentName }]];
  f.loseUploadResponse = false; await f.run(); assert.equal(f.uploads(), 1);
  const g = await fixture(t, { deploymentPages: [[{ deploymentId: 'another', deploymentName: 'manual-bundle',
    deploymentComponents: [{ purl: 'pkg:maven/net.zerocloud/magika@0.1.0' }] }]] });
  await assert.rejects(g.run(), /another Central deployment uses this version/); assert.equal(g.uploads(), 0);
});
