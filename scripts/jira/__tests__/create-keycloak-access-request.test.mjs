import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseArgs,
  applyDefaults,
  validateRequest,
  buildPayload,
} from '../create-keycloak-access-request.mjs';

test('parseArgs returns expected structure', () => {
  const result = parseArgs([
    '--env',
    'production',
    '--role',
    'kc-admin',
    '--type',
    'break-glass',
    '--start',
    '2025-11-05T08:00:00Z',
    '--end',
    '2025-11-05T10:00:00Z',
    '--reason',
    'Planned maintenance',
    '--assignee',
    '@bob',
    '--dry-run',
  ]);

  assert.deepEqual(result, {
    env: 'production',
    role: 'kc-admin',
    type: 'break-glass',
    start: '2025-11-05T08:00:00Z',
    end: '2025-11-05T10:00:00Z',
    reason: 'Planned maintenance',
    change: undefined,
    assignee: '@bob',
    dryRun: true,
  });
});

test('applyDefaults merges missing values', () => {
  const args = {
    env: 'stage',
    role: 'kc-support',
    type: 'permanent',
    start: '2025-11-05T08:00:00Z',
    end: '2025-11-05T12:00:00Z',
    reason: 'Support onboarding',
    change: undefined,
    assignee: undefined,
    dryRun: false,
  };

  const defaults = {
    defaultAssignee: '@alice',
    defaultChange: 'SEC-999',
  };

  const result = applyDefaults(args, defaults);
  assert.equal(result.assignee, '@alice');
  assert.equal(result.change, 'SEC-999');
});

test('validateRequest throws on invalid break-glass duration', () => {
  const params = {
    env: 'production',
    role: 'kc-admin',
    type: 'break-glass',
    start: '2025-11-05T08:00:00Z',
    end: '2025-11-05T16:30:00Z',
    reason: 'Long maintenance window',
    change: 'SEC-123',
    assignee: '@alice',
    dryRun: false,
  };

  assert.throws(
    () => validateRequest(params),
    { name: 'ValidationError' },
    'Expected validation error for long break-glass window',
  );
});

test('buildPayload constructs Jira payload', () => {
  const params = {
    env: 'stage',
    role: 'kc-support',
    type: 'permanent',
    start: '2025-11-05T08:00:00Z',
    end: '2025-11-06T08:00:00Z',
    reason: 'Support shift handover',
    change: 'SEC-123',
    assignee: '@alice',
    dryRun: false,
  };

  const payload = buildPayload(params, { projectKey: 'SECURITY', issueType: 'Task' });
  assert.equal(payload.fields.project.key, 'SECURITY');
  assert.equal(payload.fields.summary, 'Keycloak Access - stage - kc-support');
  assert.equal(payload.fields.customfield_access_type, 'permanent');
  assert.equal(payload.fields.assignee.name, 'alice');
  assert.match(payload.fields.description, /Support shift handover/);
});
