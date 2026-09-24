import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { createManifest, writePackage } from './package.mjs';

const config = {
  teamsAppId: '11111111-1111-1111-1111-111111111111',
  botAppId: '22222222-2222-2222-2222-222222222222',
  callbackBaseUrl: 'https://callback.example.com',
  privacyUrl: 'https://policy.example.com/privacy',
  termsUrl: 'https://policy.example.com/terms',
};
test('package contains only install files, resolved IDs and matching hashes', () => {
  const root = mkdtempSync(join(tmpdir(), 'teams-package-'));
  try {
    const output = join(root, 'package');
    const hashes = writePackage(config, output);
    assert.deepEqual(readdirSync(output).sort(), ['color.png', 'manifest.json', 'outline.png']);
    const manifest = JSON.parse(readFileSync(join(output, 'manifest.json')));
    assert.equal(manifest.bots[0].botId, config.botAppId);
    assert.equal(manifest.id, config.teamsAppId);
    assert.deepEqual(manifest.validDomains, ['callback.example.com']);
    assert.equal(manifest.configurableTabs, undefined); // This package cannot claim a live side panel.
    assert.ok(!JSON.stringify(manifest).includes('{{'));
    for (const row of hashes.split('\n')) {
      const [hash, name] = row.split('  ');
      assert.equal(createHash('sha256').update(readFileSync(join(output, name))).digest('hex'), hash);
    }
    assert.throws(() => writePackage(config, output), { code: 'EEXIST' });
  } finally { rmSync(root, { recursive: true, force: true }); }
});

for (const [field, value] of [
  ['teamsAppId', '{{TEAMS_APP_ID}}'], ['botAppId', '00000000-0000-0000-0000-000000000000'],
  ['callbackBaseUrl', 'http://callback.example.com'], ['callbackBaseUrl', 'https://localhost'],
  ['callbackBaseUrl', 'https://127.0.0.1'], ['callbackBaseUrl', 'https://callback.example.com/path'],
  ['callbackBaseUrl', 'https://callback.example.com:8443'], ['privacyUrl', 'https://user:secret@example.com/privacy'],
  ['privacyUrl', 'https://example.invalid/privacy'], ['termsUrl', 'https://example.com/terms?token=secret'],
]) test(`rejects unsuitable configuration: ${field} ${value.split(':')[0]}`, () => {
  assert.throws(() => createManifest({ ...config, [field]: value }));
});
test('template, unknown fields and accidentally supplied credentials are rejected', () => {
  assert.throws(() => createManifest(JSON.parse(readFileSync(new URL('config.example.json', import.meta.url)))));
  assert.throws(() => createManifest({ ...config, clientSecret: 'synthetic-value' }));
  assert.throws(() => createManifest({ ...config, privacyUrl: undefined }));
});
test('CLI reports failure without reflecting unsafe operator input', () => {
  const run = spawnSync(process.execPath, [fileURLToPath(new URL('package.mjs', import.meta.url)),
    'synthetic-secret-path-do-not-echo', 'unused-output'], { encoding: 'utf8' });
  assert.equal(run.status, 1);
  assert.ok(!run.stderr.includes('synthetic-secret-path-do-not-echo'));
});
