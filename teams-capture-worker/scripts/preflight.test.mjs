import test from 'node:test';
import assert from 'node:assert/strict';
import { runPreflight } from './preflight.mjs';

const config = {
  controlBaseUrl: 'http://127.0.0.1:38181', publicCallbackUrl: 'https://callback.example/api/teams/callback',
  expectedTenantId: '11111111-1111-4111-8111-111111111111',
  expectedApplicationId: '22222222-2222-4222-8222-222222222222'
};
const secret = 'synthetic-control-key-used-only-in-tests';
function readiness(changes = {}) {
  return { controlPlaneConfigured: true, configuredIdentity: {
    authentication: 'application-client-credentials', tenantId: config.expectedTenantId,
    applicationId: config.expectedApplicationId
  }, tenantAcceptance: 'not-verified-by-configuration', mediaMode: 'service-hosted-presence-only',
  liveAudio: false, liveSpeakerAttribution: false, teamsSidePanel: false, automaticCalendarScan: false, ...changes };
}
function fake(changes = {}, observe = () => {}) {
  return async (url, init) => {
    observe(url, init);
    if (url.endsWith('/health')) return Response.json({ liveAudio: false });
    if (init.headers?.['X-Teams-Control-Key']) return Response.json(readiness(changes));
    return new Response(null, { status: 401 });
  };
}
test('correct application configuration passes only preparation, never live acceptance', async () => {
  const requests = [];
  const result = await runPreflight(config, secret, fake({}, (url, init) => requests.push({ url, init })));
  assert.equal(result.controlPlanePreflightPassed, true);
  assert.equal(result.liveMeetingAcceptance, false);
  assert.equal(result.liveAudioAcceptance, false);
  assert.equal(result.checks.length, 4);
  assert.ok(!JSON.stringify(result).includes(secret));
  assert.ok(requests.every(({ init }) => init.redirect === 'error' && init.signal));
  assert.ok(requests.filter(({ url }) => url.startsWith('https://callback')).every(({ init }) => !init.headers?.['X-Teams-Control-Key']));
  assert.ok(requests.every(({ url }) => !url.endsWith('/join') && !url.endsWith('/leave')));
});
for (const field of ['tenantId', 'applicationId', 'authentication']) test(`wrong ${field} fails identity gate`, async () => {
  const identity = { ...readiness().configuredIdentity, [field]: 'unexpected' };
  assert.equal((await runPreflight(config, secret, fake({ configuredIdentity: identity }))).controlPlanePreflightPassed, false);
});
for (const change of [{ controlPlaneConfigured: false }, { liveAudio: true }, { configuredIdentity: null }]) {
  test(`partial or incompatible readiness cannot pass: ${JSON.stringify(change)}`, async () => {
    assert.equal((await runPreflight(config, secret, fake(change))).controlPlanePreflightPassed, false);
  });
}
test('health 200 alone and anonymously accessible control endpoint cannot pass', async () => {
  const result = await runPreflight(config, secret, async () => Response.json(readiness()));
  assert.equal(result.controlPlanePreflightPassed, false);
  assert.equal(result.checks[1].passed, false);
});
test('network errors and response bodies are not echoed into evidence', async () => {
  const result = await runPreflight(config, secret, async () => { throw new Error(secret); });
  assert.equal(result.controlPlanePreflightPassed, false);
  assert.ok(!JSON.stringify(result).includes(secret));
});
test('redirects cannot be mistaken for valid endpoint responses', async () => {
  const result = await runPreflight(config, secret, async () => new Response(null, { status: 302 }));
  assert.equal(result.controlPlanePreflightPassed, false);
});
for (const changes of [
  { controlBaseUrl: 'http://control.example' }, { controlBaseUrl: 'https://user:secret@control.example' },
  { publicCallbackUrl: 'http://callback.example/callback' }, { publicCallbackUrl: 'https://callback.example/callback?secret=x' },
  { publicCallbackUrl: 'https://callback.example/teams-bot/callback' },
  { expectedApplicationId: '' }, { clientSecret: 'must-not-be-in-config' }
]) test('invalid transport, identity or secret config is rejected before requests: ' + Object.keys(changes).join(), async () => {
  let called = false;
  await assert.rejects(runPreflight({ ...config, ...changes }, secret, async () => { called = true; }));
  assert.equal(called, false);
});
