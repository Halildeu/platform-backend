import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { publishVerifiedImage, validatePublishContext, repository } from './publish-image.mjs';

const source = 'a'.repeat(40);
const imageId = `sha256:${'b'.repeat(64)}`;
const digest = `sha256:${'c'.repeat(64)}`;
const env = { GITHUB_ACTIONS: 'true', GITHUB_REPOSITORY: 'Halildeu/platform-backend',
  GITHUB_REF: 'refs/heads/main', GITHUB_SHA: source, GITHUB_EVENT_NAME: 'push' };
function fixture() {
  const directory = mkdtempSync(join(tmpdir(), 'teams-publish-'));
  const smoke = { schemaVersion: 1, sourceCommit: source, imageId, disabledContainerSmokePassed: true,
    liveMeetingAcceptance: false, liveAudioAcceptance: false, checks: [
      'nonroot-disabled-image-with-matching-source', 'health-explicitly-disabled-no-live-audio',
      'anonymous-control-and-callback-rejected', 'disabled-join-refused',
      'state-volume-writable-and-retained-on-container-restart',
    ] };
  const save = () => {
    writeFileSync(join(directory, 'SOURCE_COMMIT'), source + '\n');
    writeFileSync(join(directory, 'smoke.json'), JSON.stringify(smoke));
    writeFileSync(join(directory, 'teams-worker-image.tar.gz'), 'synthetic-image-archive');
    writeFileSync(join(directory, 'SHA256SUMS'), ['SOURCE_COMMIT', 'smoke.json', 'teams-worker-image.tar.gz']
      .map(name => `${createHash('sha256').update(readFileSync(join(directory, name))).digest('hex')}  ${name}\n`).join(''));
  };
  save();
  return { directory, smoke, save, close: () => rmSync(directory, { recursive: true, force: true }) };
}
function transport({ wrongLoaded = false, wrongRemote = false } = {}) {
  const commands = [];
  return { commands, docker: async (...args) => {
    commands.push(args);
    if (args[0] === 'image') return JSON.stringify([{ Id: wrongLoaded ? `sha256:${'d'.repeat(64)}` : imageId,
      Config: { User: '1654:1654', Labels: { 'org.opencontainers.image.revision': source }, Env: ['TeamsCapture__Enabled=false'] },
      RepoDigests: [`${repository}@${digest}`] }]);
    if (args[0] === 'manifest') return JSON.stringify({ schemaVersion: 2,
      mediaType: 'application/vnd.docker.distribution.manifest.v2+json',
      config: { digest: wrongRemote ? `sha256:${'d'.repeat(64)}` : imageId } });
    return '';
  } };
}
test('publishes only the tested archive and verifies registry config digest', async () => {
  const f = fixture(); const t = transport();
  try {
    const report = await publishVerifiedImage(source, f.directory, t.docker, env);
    assert.equal(report.image, `${repository}@${digest}`);
    assert.equal(report.imageId, imageId);
    assert.equal(report.runtimeDeployed, false);
    assert.equal(report.liveMeetingAcceptance, false);
    assert.deepEqual(t.commands.map(command => command[0]), ['load', 'image', 'tag', 'push', 'image', 'manifest']);
    assert.ok(!t.commands.some(command => command.includes('build')));
    assert.equal(t.commands.find(command => command[0] === 'push')[1], `${repository}:sha-${source}`);
  } finally { f.close(); }
});
for (const patch of [{ GITHUB_EVENT_NAME: 'pull_request' }, { GITHUB_REF: 'refs/heads/feature' },
  { GITHUB_REPOSITORY: 'untrusted/fork' }, { GITHUB_SHA: 'd'.repeat(40) },
  { GITHUB_EVENT_NAME: 'workflow_dispatch', EXPECTED_SOURCE_SHA: 'd'.repeat(40) }]) {
  test(`rejects unauthorized publication context: ${Object.keys(patch).join(', ')}`, () => {
    assert.throws(() => validatePublishContext(source, { ...env, ...patch }));
  });
}
test('manual main publication requires the exact expected commit', () => {
  validatePublishContext(source, { ...env, GITHUB_EVENT_NAME: 'workflow_dispatch', EXPECTED_SOURCE_SHA: source });
});
for (const failure of ['checksum', 'missing-smoke-check', 'wrong-source', 'not-disabled', 'wrong-loaded', 'wrong-remote']) {
  test(`does not certify ${failure}`, async () => {
    const f = fixture(); const t = transport({ wrongLoaded: failure === 'wrong-loaded', wrongRemote: failure === 'wrong-remote' });
    try {
      if (failure === 'missing-smoke-check') f.smoke.checks.pop();
      if (failure === 'wrong-source') f.smoke.sourceCommit = 'd'.repeat(40);
      if (failure === 'not-disabled') f.smoke.disabledContainerSmokePassed = false;
      f.save();
      if (failure === 'checksum') writeFileSync(join(f.directory, 'teams-worker-image.tar.gz'), 'tampered');
      await assert.rejects(publishVerifiedImage(source, f.directory, t.docker, env));
      if (failure !== 'wrong-remote') assert.ok(!t.commands.some(command => command[0] === 'push'));
      if (!failure.startsWith('wrong-l') && failure !== 'wrong-remote') assert.equal(t.commands.length, 0);
    } finally { f.close(); }
  });
}
