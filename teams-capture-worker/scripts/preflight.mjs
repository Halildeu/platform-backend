// Read-only control-plane preparation check. Never joins a meeting or claims live acceptance.
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const fields = ['controlBaseUrl', 'publicCallbackUrl', 'expectedTenantId', 'expectedApplicationId'];
function validate(config, key) {
  if (!config || typeof config !== 'object' || Array.isArray(config)
      || Object.keys(config).length !== fields.length || fields.some(field => typeof config[field] !== 'string')
      || typeof key !== 'string' || key.length < 32 || /[\r\n]/.test(key)) throw new Error('invalid-configuration');
  for (const field of ['expectedTenantId', 'expectedApplicationId']) {
    if (!uuid.test(config[field]) || /^0{8}-0{4}-0{4}-0{4}-0{12}$/.test(config[field])) throw new Error('invalid-configuration');
  }
  const control = new URL(config.controlBaseUrl);
  const callback = new URL(config.publicCallbackUrl);
  const loopback = ['127.0.0.1', '[::1]'].includes(control.hostname);
  if ((control.protocol !== 'https:' && !(control.protocol === 'http:' && loopback))
      || control.pathname !== '/' || control.username || control.password || control.search || control.hash
      || callback.protocol !== 'https:' || callback.pathname !== '/api/teams/callback'
      || callback.username || callback.password || callback.search || callback.hash)
    throw new Error('invalid-configuration');
  return { control: control.origin, callback: callback.href };
}

export async function runPreflight(config, key, fetcher = fetch) {
  const urls = validate(config, key);
  const checks = [];
  async function check(name, url, init, expectedStatus, verify = () => true) {
    try {
      const response = await fetcher(url, { ...init, redirect: 'error', signal: AbortSignal.timeout(10000) });
      const passed = response.status === expectedStatus && await verify(response);
      checks.push({ name, passed, httpStatus: response.status });
    } catch {
      // Fetch errors, response bodies and supplied values may contain secrets/PII.
      checks.push({ name, passed: false, error: 'request-or-validation-failed' });
    }
  }
  await check('process-health', `${urls.control}/health`, {}, 200,
    async response => (await response.json()).liveAudio === false);
  await check('private-readiness-rejects-anonymous', `${urls.control}/api/teams/readiness`, {}, 401);
  await check('public-callback-rejects-anonymous', urls.callback,
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"value":[]}' }, 401);
  await check('configured-application-identity', `${urls.control}/api/teams/readiness`,
    { headers: { 'X-Teams-Control-Key': key } }, 200, async response => {
      const body = await response.json();
      const identity = body.configuredIdentity;
      return body.controlPlaneConfigured === true
        && identity?.authentication === 'application-client-credentials'
        && identity.tenantId?.toLowerCase() === config.expectedTenantId.toLowerCase()
        && identity.applicationId?.toLowerCase() === config.expectedApplicationId.toLowerCase()
        && body.tenantAcceptance === 'not-verified-by-configuration'
        && body.mediaMode === 'service-hosted-presence-only'
        && ['liveAudio', 'liveSpeakerAttribution', 'teamsSidePanel', 'automaticCalendarScan']
          .every(field => body[field] === false);
    });
  return {
    schemaVersion: 1, checkedAt: new Date().toISOString(),
    controlPlanePreflightPassed: checks.every(check => check.passed), checks,
    identityEvidence: 'configured-application-only-not-an-authenticated-Graph-call',
    liveMeetingAcceptance: false, liveAudioAcceptance: false,
    remaining: ['signed-microsoft-callback', 'real-meeting-join-restart-leave',
      'approved-live-media-adapter', 'timed-participant-audio-attribution',
      'authorized-side-panel-and-calendar-integration', 'two-person-live-analysis-acceptance']
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (process.argv.length !== 4) throw new Error('arguments');
    const config = JSON.parse(readFileSync(process.argv[2], 'utf8'));
    const report = await runPreflight(config, process.env.TEAMS_CONTROL_API_KEY);
    writeFileSync(process.argv[3], JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    console.log(report.controlPlanePreflightPassed
      ? 'Control-plane preflight passed. Live Teams/audio acceptance is NOT verified.'
      : 'Control-plane preflight failed. See the redacted report; do not activate.');
    process.exitCode = report.controlPlanePreflightPassed ? 0 : 1;
  } catch {
    console.error('Preflight not completed. Check non-secret config, secret-store environment and a NEW report path.');
    process.exitCode = 2;
  }
}
