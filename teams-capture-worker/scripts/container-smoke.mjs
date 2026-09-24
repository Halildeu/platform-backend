// Runs only a disposable, disabled container. No tenant credentials or Graph calls.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const exec = promisify(execFile);
const docker = async (...args) => (await exec('docker', args, { timeout: 30000, maxBuffer: 1048576 })).stdout.trim();
const [image, source, reportPath] = process.argv.slice(2);
if (process.argv.length !== 5 || !/^teams-worker-ci:[0-9a-f]{40}$/.test(image ?? '') || !/^[0-9a-f]{40}$/.test(source ?? '')) {
  console.error('Usage: container-smoke.mjs teams-worker-ci:SOURCE_SHA SOURCE_SHA NEW_REPORT_PATH');
  process.exit(2);
}
const name = `teams-disabled-${randomUUID()}`;
const volume = `${name}-state`;
const key = 'synthetic-disabled-container-check-key';
const checks = [];
let containerCreated = false;
let volumeCreated = false;
let report;
try {
  const metadata = JSON.parse(await docker('image', 'inspect', image))[0];
  assert.equal(metadata.Config.User, '1654:1654');
  assert.equal(metadata.Config.Labels['org.opencontainers.image.revision'], source);
  assert.ok(metadata.Config.Env.includes('TeamsCapture__Enabled=false'));
  checks.push('nonroot-disabled-image-with-matching-source');
  await docker('volume', 'create', volume);
  volumeCreated = true;
  await docker('create', '--name', name, '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
    '--tmpfs', '/tmp:rw,nosuid,nodev,size=32m', '--mount', `type=volume,source=${volume},target=/var/lib/teams-capture`,
    '--publish', '127.0.0.1::8080', '--env', `TeamsCapture__ControlApiKey=${key}`, image);
  containerCreated = true;
  await docker('start', name);
  const binding = await docker('port', name, '8080/tcp');
  assert.match(binding, /^127\.0\.0\.1:\d+$/);
  const base = `http://${binding}`;
  async function health() {
    for (let i = 0; i < 40; i++) {
      try {
        const response = await fetch(`${base}/health`, { signal: AbortSignal.timeout(1000) });
        if (response.status === 200) {
          const body = await response.json();
          assert.equal(body.capture, 'disabled-until-tenant-registration');
          assert.equal(body.liveAudio, false);
          return;
        }
      } catch { /* Bounded cold-start retry on this disposable container only. */ }
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error('health-not-ready');
  }
  await health();
  checks.push('health-explicitly-disabled-no-live-audio');
  const response = (path, init = {}) => fetch(`${base}${path}`, { ...init, redirect: 'error', signal: AbortSignal.timeout(5000) });
  assert.equal((await response('/api/teams/readiness')).status, 401);
  assert.equal((await response('/api/teams/callback', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"value":[]}' })).status, 401);
  const privateReadiness = await response('/api/teams/readiness', { headers: { 'X-Teams-Control-Key': key } });
  assert.equal(privateReadiness.status, 503);
  const readiness = await privateReadiness.json();
  assert.equal(readiness.controlPlaneConfigured, false);
  assert.equal(readiness.configuredIdentity.authentication, 'application-client-credentials');
  assert.equal(readiness.configuredIdentity.applicationId, null);
  assert.equal(readiness.teamsSidePanel, false);
  assert.equal((await response('/api/teams/meetings/22222222-2222-4222-8222-222222222222/join', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Teams-Control-Key': key },
    body: JSON.stringify({ calendarEventId: 'synthetic', threadId: 'synthetic', messageId: '0',
      organizerUserId: '11111111-1111-4111-8111-111111111111', correlationId: 'disabled-container-check' })
  })).status, 503);
  checks.push('anonymous-control-and-callback-rejected', 'disabled-join-refused');
  // Test the image's real user/volume permissions, not a real meeting state claim.
  await docker('exec', name, 'sh', '-c', 'printf disabled-smoke > /var/lib/teams-capture/.smoke-marker');
  await docker('restart', '--time', '5', name);
  await health();
  assert.equal(await docker('exec', name, 'cat', '/var/lib/teams-capture/.smoke-marker'), 'disabled-smoke');
  checks.push('state-volume-writable-and-retained-on-container-restart');
  report = { schemaVersion: 1, sourceCommit: source, imageId: metadata.Id,
    disabledContainerSmokePassed: true, checks, liveMeetingAcceptance: false, liveAudioAcceptance: false };
} catch {
  // Container logs, inspect output and exception bodies are deliberately not copied into evidence.
  console.error('Disabled container smoke failed. No live Teams acceptance claimed.');
  process.exitCode = 1;
} finally {
  try {
    if (containerCreated) await docker('rm', '--force', name);
    if (volumeCreated) await docker('volume', 'rm', volume);
  } catch {
    console.error('Disposable container cleanup failed. Inspect this CI job before retrying.');
    process.exitCode = 1;
  }
}
if (report && !process.exitCode) {
  writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  console.log('PASS: disabled container, private endpoints, writable persistent volume and restart. No live Teams/audio acceptance.');
}
