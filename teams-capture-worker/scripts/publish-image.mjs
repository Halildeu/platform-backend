// Publish the exact disabled container tested in this workflow run; never rebuild it here.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createReadStream, readFileSync, writeFileSync, appendFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

export const repository = 'ghcr.io/halildeu/platform-backend-teams-capture-worker';
const shaPattern = /^[a-f0-9]{40}$/;
const digestPattern = /^sha256:[a-f0-9]{64}$/;
const files = ['SOURCE_COMMIT', 'smoke.json', 'teams-worker-image.tar.gz'];
const requiredChecks = [
  'nonroot-disabled-image-with-matching-source', 'health-explicitly-disabled-no-live-audio',
  'anonymous-control-and-callback-rejected', 'disabled-join-refused',
  'state-volume-writable-and-retained-on-container-restart',
];

export function validatePublishContext(source, env) {
  assert.match(source, shaPattern);
  assert.equal(env.GITHUB_ACTIONS, 'true');
  assert.equal(env.GITHUB_REPOSITORY, 'Halildeu/platform-backend');
  assert.equal(env.GITHUB_REF, 'refs/heads/main');
  assert.equal(env.GITHUB_SHA, source);
  assert.ok(['push', 'workflow_dispatch'].includes(env.GITHUB_EVENT_NAME));
  if (env.GITHUB_EVENT_NAME === 'workflow_dispatch') assert.equal(env.EXPECTED_SOURCE_SHA, source);
}

function smallText(path) {
  assert.ok(statSync(path).size <= 1048576, 'oversized metadata');
  return readFileSync(path, 'utf8');
}

export async function verifyArchive(source, directory) {
  assert.match(source, shaPattern);
  const lines = smallText(resolve(directory, 'SHA256SUMS')).trim().split(/\r?\n/);
  assert.equal(lines.length, files.length);
  const hashes = new Map();
  for (const line of lines) {
    const entry = /^([a-f0-9]{64})  (SOURCE_COMMIT|smoke\.json|teams-worker-image\.tar\.gz)$/.exec(line);
    assert.ok(entry, 'invalid archive manifest');
    assert.ok(!hashes.has(entry[2]), 'duplicate archive entry');
    hashes.set(entry[2], entry[1]);
  }
  for (const file of files) {
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(resolve(directory, file))) hash.update(chunk);
    assert.equal(hash.digest('hex'), hashes.get(file), 'archive checksum mismatch');
  }
  assert.equal(smallText(resolve(directory, 'SOURCE_COMMIT')).trim(), source);
  const smoke = JSON.parse(smallText(resolve(directory, 'smoke.json')));
  assert.equal(smoke.schemaVersion, 1);
  assert.equal(smoke.sourceCommit, source);
  assert.match(smoke.imageId, digestPattern);
  assert.equal(smoke.disabledContainerSmokePassed, true);
  assert.equal(smoke.liveMeetingAcceptance, false);
  assert.equal(smoke.liveAudioAcceptance, false);
  assert.ok(Array.isArray(smoke.checks) && requiredChecks.every(check => smoke.checks.includes(check)));
  return smoke;
}

export async function publishVerifiedImage(source, directory, docker, env) {
  validatePublishContext(source, env);
  const smoke = await verifyArchive(source, directory);
  const testedTag = `teams-worker-ci:${source}`;
  const tag = `${repository}:sha-${source}`;
  await docker('load', '--input', resolve(directory, 'teams-worker-image.tar.gz'));
  const [metadata] = JSON.parse(await docker('image', 'inspect', testedTag));
  assert.equal(metadata.Id, smoke.imageId);
  assert.equal(metadata.Config.User, '1654:1654');
  assert.equal(metadata.Config.Labels['org.opencontainers.image.revision'], source);
  assert.ok(metadata.Config.Env.includes('TeamsCapture__Enabled=false'));
  await docker('tag', testedTag, tag);
  await docker('push', tag);
  const [published] = JSON.parse(await docker('image', 'inspect', tag));
  const refs = published.RepoDigests.filter(value => value.startsWith(`${repository}@`));
  assert.equal(refs.length, 1);
  const digest = refs[0].slice(repository.length + 1);
  assert.match(digest, digestPattern);
  const remote = JSON.parse(await docker('manifest', 'inspect', refs[0]));
  assert.equal(remote.schemaVersion, 2);
  assert.ok(['application/vnd.docker.distribution.manifest.v2+json',
    'application/vnd.oci.image.manifest.v1+json'].includes(remote.mediaType));
  assert.equal(remote.config?.digest, smoke.imageId, 'registry must contain the tested image');
  return { schemaVersion: 1, sourceCommit: source, imageId: smoke.imageId,
    repository, tag, digest, image: refs[0], registryImageVerified: true,
    runtimeDeployed: false, liveMeetingAcceptance: false, liveAudioAcceptance: false };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    assert.equal(process.argv.length, 5);
    const [source, directory, reportPath] = process.argv.slice(2);
    const exec = promisify(execFile);
    const docker = async (...args) => (await exec('docker', args, {
      timeout: 180000, maxBuffer: 16777216,
    })).stdout.trim();
    const report = await publishVerifiedImage(source, directory, docker, process.env);
    writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    if (process.env.GITHUB_OUTPUT)
      appendFileSync(process.env.GITHUB_OUTPUT, `digest=${report.digest}\nimage=${report.image}\n`);
    console.log(`Verified registry image: ${report.image}. No runtime deployment or Teams acceptance claimed.`);
  } catch {
    // Docker responses and environment values are not copied into failure reports.
    console.error('Teams image publication failed verification. No runtime deployment or Teams acceptance claimed.');
    process.exitCode = 1;
  }
}
